#include "../experimental/flac_queue_producer.hpp"
#include <FLAC/stream_decoder.h>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <mutex>
#include <thread>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#elif defined(__ANDROID__)
#include <sys/resource.h>
#endif

using Queue=PcmFrameQueue;
using Producer=FlacQueueProducer;
using Status=Producer::Status;
void check(bool ok,const char* message) {
    if(!ok){std::cerr << "FAIL: " << message << '\n';std::exit(1);}
}
struct FinishGate {
    std::mutex mutex;
    std::condition_variable cv;
    bool armed=false,entered=false,released=false;
    void arm(){armed=true;entered=released=false;}
    void pause() {
        std::unique_lock<std::mutex> lock(mutex);
        if(!armed)return;
        armed=false;entered=true;cv.notify_all();
        check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return released;}),"finish gate not released");
    }
    void wait(){std::unique_lock<std::mutex> lock(mutex);check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return entered;}),"finish gate not entered");}
    void release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
} gate;
extern "C" FLAC__bool __real_FLAC__stream_decoder_finish(FLAC__StreamDecoder*);
extern "C" FLAC__bool __wrap_FLAC__stream_decoder_finish(FLAC__StreamDecoder* decoder) {
    const auto result=__real_FLAC__stream_decoder_finish(decoder);gate.pause();return result;
}
using File=std::unique_ptr<FILE,decltype(&std::fclose)>;
File open(const char* path){File file(std::fopen(path,"rb"),&std::fclose);check(bool(file),"open generated fixture");return file;}
struct Fixture {
    File source;
    std::atomic<bool> cancel{false};
    FlacStreamReader reader;
    explicit Fixture(const char* path):source(open(path)),reader(source.get(),cancel){}
};
struct Sink {
    File reference;
    uint64_t bytes=0;
    size_t width;
    std::array<unsigned char,4096> output{},expected{};
    explicit Sink(const char* path,unsigned bits):reference(path?open(path):File(nullptr,&std::fclose)),width(bits/8*2){}
    Queue::Read pull(Queue& queue,uint64_t token,size_t wanted=73) {
        output.fill(0xa5);
        auto result=queue.pull(token,wanted,output.data(),output.size());
        if(result.status==Queue::Read::Data) {
            const size_t count=result.frames*width;
            if(reference)check(std::fread(expected.data(),1,count,reference.get())==count &&
                               std::memcmp(output.data(),expected.data(),count)==0,"exact independent PCM through queue");
            else check(std::fwrite(output.data(),1,count,stdout)==count,"write offline PCM pipe");
            bytes+=count;
        } else for(auto value:output)check(value==0xa5,"non-data pull must preserve output");
        return result.status;
    }
    void complete(){if(reference)check(std::fgetc(reference.get())==EOF,"queue must deliver entire reference without padding");}
};

void roundTrip(const char* path,const char* reference,bool md5,bool partial=false) {
    Fixture f(path);auto info=f.reader.info();Queue queue(info.rate,info.bits,257);
    if(partial)check(f.reader.seek(5001),"partial source seek");
    Producer producer(f.reader,queue,queue.generation(),f.cancel);Sink sink(reference,info.bits);
    check(producer.step().status==Status::Progress,"first decoded chunk must enter queue");
    for(int i=0;i<16;++i)check(producer.step().status==Status::Full && queue.bufferedFrames()==257,"full queue retains pending PCM");
    bool ended=false;size_t iterations=0;
    for(;;) {
        check(++iterations<100000,"bounded round-trip progress");
        auto result=producer.step();
        check(result.status==Status::Progress || result.status==Status::Full || result.status==Status::End,"round-trip producer result");
        check(!result.md5Verified || result.status==Status::End,"integrity only at End");
        if(result.status==Status::End){check(result.md5Verified==md5,"explicit EOF MD5 scope");ended=true;}
        auto read=sink.pull(queue,queue.generation());
        if(read==Queue::Read::End){check(ended,"consumer End only after decoder finishes");break;}
        check(read==Queue::Read::Data || read==Queue::Read::NeedData,"round-trip consumer result");
    }
    sink.complete();check(producer.step().status==Status::End,"repeat finite EOF");
}

void retired(const char* path,int action,bool held,bool unsafe=false) {
    Fixture f(path);auto info=f.reader.info();Queue queue(info.rate,info.bits,257);
    const auto old=queue.generation();Producer producer(f.reader,queue,old,f.cancel);
    if(held){check(producer.step().status==Status::Progress,"fill queue");check(producer.step().status==Status::Full,"hold decoded remainder");}
    if(action==0)f.cancel=true;
    if(action==1)check(queue.stop(old),"explicit queue stop");
    if(action==2)check(queue.fail(old),"explicit queue failure");
    auto fresh=old;
    std::array<unsigned char,6> marker{};marker.fill(0x5a);
    if(action>=3) {
        fresh=queue.reset();check(queue.push(fresh,marker.data(),info.bits/8*2)==Queue::Write::Accepted,"seed replacement source");
        if(action==4)f.cancel=true;
    }
    auto result=producer.step();
    if(unsafe) {
        // Deliberately give an old decoder the replacement job's token.
        Producer wrong(f.reader,queue,queue.generation(),f.cancel);result=wrong.step();
    }
    const auto expected=action>=3?Status::Stale:action==2?Status::Failed:Status::Stopped;
    check(result.status==expected && !result.md5Verified,"retired producer must respect terminal state/generation");
    if(action>=3) {
        check(queue.state(fresh)==Queue::State::Open && queue.bufferedFrames()==1,"stale producer must not modify replacement source");
        std::array<unsigned char,6> output{};
        check(queue.pull(fresh,1,output.data(),output.size()).status==Queue::Read::Data &&
              std::memcmp(output.data(),marker.data(),info.bits/8*2)==0,"replacement bytes intact");
    } else check(queue.bufferedFrames()==0,"stop/failure discards unsubmitted queue data");
}

void formatMismatch(const char* path,bool bits) {
    Fixture f(path);auto info=f.reader.info();
    Queue queue(bits?info.rate:(info.rate==44100?48000:44100),bits?(info.bits==16?24:16):info.bits,257);
    Producer producer(f.reader,queue,queue.generation(),f.cancel);
    check(producer.step().status==Status::Failed && queue.bufferedFrames()==0,"format mismatch must fail before any PCM enters queue");
}

void failure(const char* path,const char* reference) {
    Fixture f(path);auto info=f.reader.info();Queue queue(info.rate,info.bits,257);
    Producer producer(f.reader,queue,queue.generation(),f.cancel);Sink sink(reference,info.bits);
    for(size_t iterations=0;;++iterations) {
        check(iterations<100000,"failure path progress");
        auto result=producer.step();
        if(result.status==Status::Failed)break;
        check(result.status==Status::Progress || result.status==Status::Full,"corrupt source must not finish or stop");
        auto read=sink.pull(queue,queue.generation());
        check(read==Queue::Read::Data || read==Queue::Read::NeedData,"valid prefix before integrity failure");
    }
    check(queue.bufferedFrames()==0 && sink.pull(queue,queue.generation())==Queue::Read::Failed &&
          producer.step().status==Status::Failed,"integrity error clears queued data and remains Failed");
}

void terminalRace(const char* path,const char* reference,bool stop) {
    Fixture f(path);auto info=f.reader.info();Queue queue(info.rate,info.bits,257);
    const auto old=queue.generation();Producer producer(f.reader,queue,old,f.cancel);Sink sink(reference,info.bits);
    while(sink.bytes+queue.bufferedFrames()*info.bits/8*2<info.totalFrames*info.bits/8*2) {
        auto result=producer.step();
        check(result.status==Status::Progress || result.status==Status::Full,"reach final integrity check before terminal publication");
        sink.pull(queue,old);
    }
    gate.arm();Producer::Result result{Status::Progress,false};
    std::thread worker([&]{result=producer.step();});gate.wait();
    uint64_t fresh=old;
    if(stop)check(queue.stop(old),"stop while EOF is pending");else fresh=queue.reset();
    gate.release();worker.join();
    check(result.status==(stop?Status::Stopped:Status::Stale) && !result.md5Verified,"old EOF/error must not publish into retired source");
    check(queue.state(fresh)==(stop?Queue::State::Stopped:Queue::State::Open),"old terminal write must not change replacement state");
}

void concurrent(const char* path,const char* reference) {
    Fixture f(path);auto info=f.reader.info();const bool pipe=reference==nullptr;
    Queue queue(info.rate,info.bits,pipe?4096:257);const auto token=queue.generation();
    Producer producer(f.reader,queue,token,f.cancel);Sink sink(reference,info.bits);
    std::mutex mutex;std::condition_variable cv;uint64_t epoch=0;
    std::atomic<bool> full{false},done{false};Producer::Result final{Status::Progress,false};
    auto notify=[&]{std::lock_guard<std::mutex> lock(mutex);++epoch;cv.notify_all();};
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(pipe?120:10);
    std::thread worker([&]{
        for(;;) {
            std::unique_lock<std::mutex> lock(mutex);auto turn=epoch;lock.unlock();
            auto result=producer.step();
            if(result.status==Status::Progress){notify();continue;}
            if(result.status==Status::Full) {
                lock.lock();full=true;cv.notify_all();
                check(cv.wait_until(lock,deadline,[&]{return epoch!=turn;}),"worker must wake after consumer frees space");continue;
            }
            final=result;done=true;notify();break;
        }
    });
    {
        std::unique_lock<std::mutex> lock(mutex);
        check(cv.wait_until(lock,deadline,[&]{return full.load() || done.load();}),"producer must encounter actual backpressure");
    }
    for(;;) {
        std::unique_lock<std::mutex> lock(mutex);auto turn=epoch;lock.unlock();
        auto result=sink.pull(queue,token,pipe?512:73);
        if(result==Queue::Read::End)break;
        if(result==Queue::Read::Data){notify();continue;}
        check(result==Queue::Read::NeedData,"concurrent consumer state");lock.lock();
        check(cv.wait_until(lock,deadline,[&]{return epoch!=turn || done.load();}),"consumer must wake after producer progress");
    }
    worker.join();sink.complete();
    check(full && final.status==Status::End && final.md5Verified && sink.bytes==info.totalFrames*info.bits/8*2,"concurrent finite PCM and integrity");
    if(pipe) {
        long peak=0;
#ifdef __ANDROID__
        rusage usage{};check(getrusage(RUSAGE_SELF,&usage)==0,"process memory high-water");peak=usage.ru_maxrss;
#endif
        std::cerr << "{\"status\":\"End\",\"md5\":1,\"bytes\":" << sink.bytes
                  << ",\"queueBytes\":" << queue.capacityBytes() << ",\"residentHighWaterKiB\":" << peak << "}\n";
    }
}

int main(int argc,char** argv) {
    if(argc<3)return 2;
    const char* mode=argc==4?argv[3]:"valid";
    if(std::strcmp(argv[2],"--pipe")==0) {
#ifdef _WIN32
        _setmode(_fileno(stdout),_O_BINARY);
#endif
        concurrent(argv[1],nullptr);return 0;
    }
    if(std::strcmp(mode,"unsafe-token")==0){retired(argv[1],3,false,true);return 0;}
    if(std::strcmp(mode,"error")==0){failure(argv[1],argv[2]);terminalRace(argv[1],argv[2],false);}
    else if(std::strcmp(mode,"truncated")==0)failure(argv[1],argv[2]);
    else if(std::strcmp(mode,"unverified")==0)roundTrip(argv[1],argv[2],false);
    else if(std::strcmp(mode,"partial")==0)roundTrip(argv[1],argv[2],false,true);
    else {
        roundTrip(argv[1],argv[2],true);
        for(int action=0;action<5;++action)retired(argv[1],action,true);
        retired(argv[1],0,false);
        retired(argv[1],3,false);
        formatMismatch(argv[1],false);formatMismatch(argv[1],true);
        terminalRace(argv[1],argv[2],false);terminalRace(argv[1],argv[2],true);
        concurrent(argv[1],argv[2]);
    }
    std::cout << "PASS: decoder-to-queue " << mode << " cases\n";
}
