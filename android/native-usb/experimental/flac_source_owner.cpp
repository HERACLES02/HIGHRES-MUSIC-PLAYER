#include "flac_source_owner.hpp"
#include "flac_queue_producer.hpp"
#include <cstdio>
#include <limits>
#include <utility>
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

namespace {
void closeInput(int fd) {
    if(fd<0)return;
#ifdef _WIN32
    _close(fd);
#else
    ::close(fd);
#endif
}
bool regularInput(int fd) {
#ifdef _WIN32
    struct _stat64 info{};
    return _fstat64(fd,&info)==0 && (info.st_mode&_S_IFMT)==_S_IFREG;
#else
    struct stat info{};
    return fstat(fd,&info)==0 && S_ISREG(info.st_mode);
#endif
}
std::atomic<uint64_t> identities{0};
uint64_t nextIdentity() {
    auto id=identities.load();
    do {
        if(id==std::numeric_limits<uint64_t>::max())throw std::overflow_error("Owner identity exhausted");
    } while(!identities.compare_exchange_weak(id,id+1));
    return id+1;
}
}
FlacSourceOwner::FlacSourceOwner():identity(nextIdentity()){}
FlacSourceOwner::InputFd::~InputFd(){closeInput(value);}
uint64_t FlacSourceOwner::replaceFd(int fd,unsigned sampleRate,unsigned sampleBits,uint64_t first) {
#ifdef _WIN32
    int duplicate=fd<0?-1:_dup(fd);
#else
    int duplicate=fd<0?-1:fcntl(fd,F_DUPFD_CLOEXEC,0);
#endif
    return replaceInput("",InputFd(duplicate),sampleRate,sampleBits,first);
}
FlacSourceOwner::~FlacSourceOwner(){stop();}
void FlacSourceOwner::stop() {
    {
        std::lock_guard<std::mutex> lock(mutex);
        if(queue)queue->stop(queueToken);
        if(source<std::numeric_limits<uint64_t>::max())++source;
        cancel=true;phase=Status::Stopped;verified=false;++event;cv.notify_all();
    }
    // Never hold the owner lock while joining: the worker needs it to leave
    // backpressure or publish completion. Queue/flag live until join returns.
    if(worker.joinable())worker.join();
}
uint64_t FlacSourceOwner::replace(std::string path,unsigned sampleRate,unsigned sampleBits,uint64_t first) {
    return replaceInput(std::move(path),InputFd(),sampleRate,sampleBits,first);
}
uint64_t FlacSourceOwner::replaceInput(std::string path,InputFd input,unsigned sampleRate,unsigned sampleBits,uint64_t first) {
    stop();
    auto next=std::make_unique<PcmFrameQueue>(sampleRate,sampleBits,4096);
    std::lock_guard<std::mutex> lock(mutex);
    if(source==std::numeric_limits<uint64_t>::max())throw std::overflow_error("Source generation exhausted");
    queue=std::move(next);queueToken=queue->generation();
    rate=sampleRate;bits=sampleBits;cursor=first;cancel=false;phase=Status::Starting;
    auto* destination=queue.get();const auto token=queueToken,id=source;
    try {
        worker=std::thread([this,destination,token,id,path=std::move(path),input=std::move(input),first]() mutable {
            run(destination,token,id,std::move(path),std::move(input),first);
        });
    } catch(...) {
        queue->fail(token);phase=Status::Failed;++event;cv.notify_all();throw;
    }
    return id;
}
void FlacSourceOwner::run(PcmFrameQueue* destination,uint64_t token,uint64_t id,std::string path,InputFd input,uint64_t first) {
    bool md5=false;
    try {
        // Reader and FILE are worker-local. Reverse destruction closes the
        // reader before its borrowed FILE; completion is published afterward.
        FILE* opened=nullptr;
        if(input.value>=0) {
            // Some Windows CRT seeks falsely succeed on pipes. Reject special
            // descriptors before any read; Android proxy regular files remain
            // eligible and still go through the reader's seek validation.
            if(!regularInput(input.value))closeInput(input.release());
        }
        if(input.value>=0) {
#ifdef _WIN32
            opened=_fdopen(input.value,"rb");
#else
            opened=fdopen(input.value,"rb");
#endif
            if(opened)input.release();else closeInput(input.release());
        } else if(!path.empty())opened=std::fopen(path.c_str(),"rb");
        std::unique_ptr<FILE,decltype(&std::fclose)> file(opened,&std::fclose);
        if(!file)destination->fail(token);
        else {
            FlacStreamReader reader(file.get(),cancel);const auto info=reader.info();
            if(!destination->matchesFormat(info.rate,info.bits) || (first && !reader.seek(first))) {
                if(cancel.load())destination->stop(token);else destination->fail(token);
            } else {
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    if(id==source)phase=Status::Running;
                }
                FlacQueueProducer producer(reader,*destination,token,cancel);
                for(;;) {
                    std::unique_lock<std::mutex> lock(mutex);const auto before=event;lock.unlock();
                    auto result=producer.step();lock.lock();
                    if(result.status==FlacQueueProducer::Status::Progress){++event;cv.notify_all();continue;}
                    if(result.status==FlacQueueProducer::Status::Full) {
                        cv.wait(lock,[&]{return event!=before || cancel.load() || source!=id;});continue;
                    }
                    md5=result.md5Verified;break;
                }
            }
        }
    } catch(...) {destination->fail(token);}
    std::lock_guard<std::mutex> lock(mutex);
    if(id==source) {
        switch(destination->state(token)) {
            case PcmFrameQueue::State::Finished:phase=Status::End;verified=md5;break;
            case PcmFrameQueue::State::Stopped:phase=Status::Stopped;verified=false;break;
            default:phase=Status::Failed;verified=false;break;
        }
        ++event;cv.notify_all();
    }
}
FlacSourceOwner::Snapshot FlacSourceOwner::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex);return {source,phase,rate,bits,verified};
}
FlacSourceOwner::Pull FlacSourceOwner::pull(uint64_t id,size_t wanted,Block& block) {
    std::lock_guard<std::mutex> lock(mutex);block.job=0;block.count=0;
    auto result=PcmFrameQueue::Read::NeedData;
    if(id!=source)result=PcmFrameQueue::Read::Stale;
    else if(phase==Status::Stopped)result=PcmFrameQueue::Read::Stopped;
    else if(phase==Status::Failed)result=PcmFrameQueue::Read::Failed;
    else if(!wanted || wanted>MAX_PULL_FRAMES)result=PcmFrameQueue::Read::Invalid;
    else {
        auto read=queue->pull(queueToken,wanted,block.pcm.data(),block.pcm.size());result=read.status;
        if(result==PcmFrameQueue::Read::Data) {
            block.owner=identity;block.job=id;block.count=read.frames;block.first=cursor;block.sampleRate=rate;block.sampleBits=bits;
            cursor+=read.frames;++event;cv.notify_all();
        }
        // Queue termination can precede final metadata/FILE cleanup. Publish
        // consumer End/Failed only after the worker's terminal result is ready.
        if(result==PcmFrameQueue::Read::End && phase!=Status::End)result=PcmFrameQueue::Read::NeedData;
        if(result==PcmFrameQueue::Read::Failed && phase!=Status::Failed)result=PcmFrameQueue::Read::NeedData;
    }
    return {result,event};
}
void FlacSourceOwner::wait(uint64_t observed) {
    std::unique_lock<std::mutex> lock(mutex);cv.wait(lock,[&]{return event!=observed;});
}
