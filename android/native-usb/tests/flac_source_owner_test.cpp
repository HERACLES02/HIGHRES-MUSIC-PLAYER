#include "../experimental/flac_source_owner.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <chrono>
#include <future>
#include <fcntl.h>
#ifdef _WIN32
#include <io.h>
#else
#include <unistd.h>
#endif
#ifdef __ANDROID__
#include <sys/resource.h>
#endif

using Owner=FlacSourceOwner;
using Read=PcmFrameQueue::Read;
void check(bool value,const char* text){if(!value){std::cerr << "FAIL: " << text << '\n';std::exit(1);}}
extern "C" FILE* __real_fopen(const char*,const char*);
extern "C" int __real_fclose(FILE*);
extern "C" size_t __real_fread(void*,size_t,size_t,FILE*);
struct ReadGate {
    std::mutex mutex;std::condition_variable cv;
    bool armed=false,entered=false,released=false;
    void arm(){std::lock_guard<std::mutex> lock(mutex);armed=true;entered=released=false;}
    void pause(){std::unique_lock<std::mutex> lock(mutex);if(!armed)return;armed=false;entered=true;cv.notify_all();
        check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return released;}),"read gate completion missing");}
    void wait(){std::unique_lock<std::mutex> lock(mutex);check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return entered;}),"read gate entry missing");}
    void release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
} gate,closeGate;
std::atomic<FILE*> tracked{nullptr};
std::atomic<int> active{0},opens{0},closes{0};
FILE* track(FILE* file){if(file){check(++active==1,"old source must close before replacement opens");tracked=file;++opens;}return file;}
#ifdef _WIN32
extern "C" FILE* __real__fdopen(int,const char*);
extern "C" FILE* __wrap__fdopen(int fd,const char* mode){return track(__real__fdopen(fd,mode));}
#else
extern "C" FILE* __real_fdopen(int,const char*);
extern "C" FILE* __wrap_fdopen(int fd,const char* mode){
    check((fcntl(fd,F_GETFD)&FD_CLOEXEC)!=0,"owned descriptor must be close-on-exec");return track(__real_fdopen(fd,mode));
}
#endif
extern "C" FILE* __wrap_fopen(const char* path,const char* mode) {
    FILE* file=__real_fopen(path,mode);size_t size=std::strlen(path);
    if(file && size>=5 && std::strcmp(path+size-5,".flac")==0) {
        check(++active==1,"old source must close before replacement opens");tracked=file;++opens;
    }
    return file;
}
extern "C" int __wrap_fclose(FILE* file) {
    bool source=file==tracked.load();if(source)closeGate.pause();int result=__real_fclose(file);
    if(source){tracked=nullptr;--active;++closes;}
    return result;
}
extern "C" size_t __wrap_fread(void* out,size_t size,size_t count,FILE* file) {
    if(file==tracked.load()){check(active==1,"source must stay open through pending read");gate.pause();}
    return __real_fread(out,size,count,file);
}

void drain(Owner& owner,uint64_t id,const char* reference,uint64_t first,bool md5) {
    FILE* pcm=std::fopen(reference,"rb");check(pcm!=nullptr,"open independent PCM");
    auto format=owner.snapshot();const size_t width=format.bits/8*2;
    check(std::fseek(pcm,long(first*width),SEEK_SET)==0,"seek reference");
    Owner::Block block;std::array<unsigned char,Owner::MAX_PULL_FRAMES*6> expected{};
    uint64_t position=first;
    for(;;) {
        auto result=owner.pull(id,73,block);
        if(result.status==Read::NeedData){owner.wait(result.event);continue;}
        if(result.status==Read::End)break;
        check(result.status==Read::Data,"source owner must produce requested PCM");
        check(block.source()==id && block.firstFrame()==position && block.rate()==format.rate && block.bits()==format.bits,"source block identity/position/format");
        check(std::fread(expected.data(),1,block.bytes(),pcm)==block.bytes(),"read bounded reference block");
        check(owner.deliver(block,[&](const unsigned char* data,size_t bytes){
            check(bytes==block.bytes() && std::memcmp(data,expected.data(),bytes)==0,"exact source-owner PCM");
        }),"current block must be deliverable");
        position+=block.frames();
    }
    check(std::fgetc(pcm)==EOF,"complete reference suffix without padding");std::fclose(pcm);
    auto end=owner.snapshot();check(end.source==id && end.status==Owner::Status::End && end.md5Verified==md5,"owner terminal state/integrity");
}
Owner::Block hold(Owner& owner,uint64_t id) {
    Owner::Block block;
    for(;;) {
        auto result=owner.pull(id,73,block);
        if(result.status==Read::NeedData){owner.wait(result.event);continue;}
        check(result.status==Read::Data,"hold a decoded consumer block");return block;
    }
}
void rejected(Owner& owner,const Owner::Block& block,bool unsafe=false) {
    bool called=false;auto copy=[&](const unsigned char*,size_t){called=true;};
    bool accepted=owner.deliver(block,copy);if(unsafe){copy(block.data(),block.bytes());accepted=true;}
    check(!accepted && !called,"stale consumer block must never reach copy callback");
}
void failed(Owner& owner,uint64_t id,const char* reference) {
    FILE* pcm=std::fopen(reference,"rb");check(pcm!=nullptr,"open error-prefix reference");
    Owner::Block block,last;std::array<unsigned char,Owner::MAX_PULL_FRAMES*6> expected{};
    for(;;) {
        auto result=owner.pull(id,73,block);
        if(result.status==Read::NeedData){owner.wait(result.event);continue;}
        if(result.status==Read::Failed)break;
        check(result.status==Read::Data,"bad source must fail without End/automatic restart");
        check(std::fread(expected.data(),1,block.bytes(),pcm)==block.bytes() &&
              std::memcmp(block.data(),expected.data(),block.bytes())==0,"valid prefix before source failure");
        last=block;
    }
    std::fclose(pcm);auto status=owner.snapshot();
    check(status.source==id && status.status==Owner::Status::Failed && !status.md5Verified,"source failure metadata");
    rejected(owner,last);
}
long peakMemory() {
#ifdef __ANDROID__
    rusage usage{};check(getrusage(RUSAGE_SELF,&usage)==0,"process memory sample");return usage.ru_maxrss;
#else
    return 0;
#endif
}
int openFd(const char* path) {
#ifdef _WIN32
    return _open(path,_O_RDONLY|_O_BINARY);
#else
    return ::open(path,O_RDONLY|O_CLOEXEC);
#endif
}
void closeFd(int fd) {
#ifdef _WIN32
    check(_close(fd)==0,"close caller descriptor");
#else
    check(::close(fd)==0,"close caller descriptor");
#endif
}
int main(int argc,char** argv) {
    if(argc<9)return 2;
    const unsigned rateA=unsigned(std::atoi(argv[3])),bitsA=unsigned(std::atoi(argv[4]));
    const unsigned rateB=unsigned(std::atoi(argv[7])),bitsB=unsigned(std::atoi(argv[8]));
    const char* mode=argc==10?argv[9]:"valid";
    unsigned cases=0;
    // Test watchdog only; this is not an IO cancellation deadline in the owner.
    std::mutex watchMutex;std::condition_variable watchCv;bool finished=false;
    std::thread watchdog([&]{std::unique_lock<std::mutex> lock(watchMutex);
        if(!watchCv.wait_for(lock,std::chrono::seconds(25),[&]{return finished;})){
            std::fputs("FAIL: source-owner test watchdog\n",stderr);std::_Exit(1);}});
    {
        Owner owner;
        if(std::strcmp(mode,"fd")==0) {
            int fd=openFd(argv[1]);check(fd>=0,"open borrowed descriptor");
            auto id=owner.replaceFd(fd,rateA,bitsA);closeFd(fd);
            drain(owner,id,argv[2],0,true);++cases;
            fd=openFd(argv[1]);id=owner.replaceFd(fd,rateA,bitsA,5001);closeFd(fd);
            drain(owner,id,argv[2],5001,false);++cases;
            fd=openFd(argv[1]);id=owner.replaceFd(fd,rateA,bitsA);auto held=hold(owner,id);owner.stop();rejected(owner,held);
#ifdef _WIN32
            check(_lseek(fd,0,SEEK_SET)==0,"borrowed descriptor survives owner stop");
#else
            check(lseek(fd,0,SEEK_SET)==0,"borrowed descriptor survives owner stop");
#endif
            closeFd(fd);++cases;
            id=owner.replaceFd(-1,rateA,bitsA);failed(owner,id,argv[2]);++cases;
            int pipeFds[2];
#ifdef _WIN32
            check(_pipe(pipeFds,4096,_O_BINARY)==0,"create withheld-data pipe");
#else
            check(pipe(pipeFds)==0,"create withheld-data pipe");
#endif
            id=owner.replaceFd(pipeFds[0],rateA,bitsA);closeFd(pipeFds[0]);
            failed(owner,id,argv[2]);closeFd(pipeFds[1]);++cases;
            fd=openFd(argv[1]);id=owner.replaceFd(fd,rateA,bitsA);closeFd(fd);
#ifdef _WIN32
            int unrelated=openFd("NUL");
            if(unrelated!=fd){check(_dup2(unrelated,fd)==0,"reuse caller fd number");closeFd(unrelated);}
#else
            int unrelated=openFd("/dev/null");
            if(unrelated!=fd){check(dup2(unrelated,fd)==fd,"reuse caller fd number");closeFd(unrelated);}
#endif
            drain(owner,id,argv[2],0,true);owner.stop();closeFd(fd);++cases;
            fd=openFd(argv[1]);id=owner.replaceFd(fd,rateA,bitsA);closeFd(fd);held=hold(owner,id);
            int second=openFd(argv[5]);id=owner.replaceFd(second,rateB,bitsB);closeFd(second);rejected(owner,held);
            drain(owner,id,argv[6],0,true);++cases;
            fd=openFd(argv[1]);bool invalid=false;
            try{owner.replaceFd(fd,123,16);}catch(const std::invalid_argument&){invalid=true;}
            check(invalid && owner.snapshot().status==Owner::Status::Stopped,"invalid fd job fails closed");closeFd(fd);++cases;
        } else if(std::strcmp(mode,"stress")==0) {
            Owner::Block old;long warm=0;
            for(int cycle=0;cycle<100;++cycle) {
                const bool alternate=cycle%2;uint64_t first=cycle%3?5001:0;
                auto id=owner.replace(alternate?argv[5]:argv[1],alternate?rateB:rateA,alternate?bitsB:bitsA,first);
                rejected(owner,old);old=hold(owner,id);check(old.firstFrame()==first,"repeated seek position");
                FILE* reference=std::fopen(alternate?argv[6]:argv[2],"rb");check(reference!=nullptr,"stress prefix reference");
                std::array<unsigned char,Owner::MAX_PULL_FRAMES*6> expected{};
                check(std::fseek(reference,long(first*(old.bits()/8*2)),SEEK_SET)==0,"stress reference offset");
                check(std::fread(expected.data(),1,old.bytes(),reference)==old.bytes(),"bounded stress reference block");
                check(owner.deliver(old,[&](const unsigned char* data,size_t bytes){
                    check(std::memcmp(data,expected.data(),bytes)==0,"stress seek/source PCM");
                }),"repeated current block delivery");std::fclose(reference);
                owner.stop();rejected(owner,old);check(active==0,"each repeated owner stop closes source");
                if(cycle==9)warm=peakMemory();
            }
            auto final=peakMemory();check(!final || (final<64*1024 && final<=warm+4096),"repeated source replacement memory plateau");
            std::cout << "MEMORY: " << warm << ' ' << final << " KiB\n";cases=100;
        } else if(std::strcmp(mode,"error")==0) {
            auto id=owner.replace(argv[1],rateA,bitsA);failed(owner,id,argv[2]);++cases;
            id=owner.replace(argv[5],rateB,bitsB);drain(owner,id,argv[6],0,true);++cases;
        } else if(std::strcmp(mode,"unverified")==0) {
            auto id=owner.replace(argv[1],rateA,bitsA);drain(owner,id,argv[2],0,false);++cases;
        } else if(std::strcmp(mode,"unsafe-deliver")==0) {
            auto id=owner.replace(argv[1],rateA,bitsA);auto old=hold(owner,id);
            owner.replace(argv[5],rateB,bitsB);rejected(owner,old,true);
        } else {
            Owner::Block blank;
            check(owner.snapshot().status==Owner::Status::Stopped && owner.pull(0,73,blank).status==Read::Stopped,"initial stopped owner");
            rejected(owner,blank);++cases;
            auto id=owner.replace(argv[1],rateA,bitsA);drain(owner,id,argv[2],0,true);++cases;
            FILE* ref=std::fopen(argv[2],"rb");check(ref!=nullptr,"reference length");
            check(std::fseek(ref,0,SEEK_END)==0,"reference end");const auto total=uint64_t(std::ftell(ref))/(bitsA/8*2);std::fclose(ref);
            for(uint64_t first:{uint64_t(1),uint64_t(5001),total-1,total,uint64_t(0)}) {
                id=owner.replace(argv[1],rateA,bitsA,first);drain(owner,id,argv[2],first,first==0);++cases;
            }
            id=owner.replace(argv[1],rateA,bitsA);auto old=hold(owner,id);auto oldId=id;
            id=owner.replace(argv[5],rateB,bitsB);rejected(owner,old);
            check(owner.pull(oldId,73,blank).status==Read::Stale,"old pull handle after format/source switch");
            drain(owner,id,argv[6],0,true);++cases;
            id=owner.replace(argv[5],rateB,bitsB);old=hold(owner,id);owner.stop();rejected(owner,old);
            check(owner.pull(id,73,blank).status==Read::Stale && active==0,"stop invalidates consumer and joins/closes");++cases;
            auto missing=std::string(argv[1])+".missing.flac";
            id=owner.replace(missing,rateA,bitsA);failed(owner,id,argv[2]);++cases;
            id=owner.replace(argv[1],rateA,bitsA,total+1);failed(owner,id,argv[2]);++cases;
            id=owner.replace(argv[1],rateA,bitsA==16?24:16);failed(owner,id,argv[2]);++cases;
            closeGate.arm();id=owner.replace(argv[1],rateA,bitsA==16?24:16);closeGate.wait();
            check(owner.pull(id,73,blank).status==Read::NeedData && active==1,"terminal failure waits for source file closure");
            closeGate.release();failed(owner,id,argv[2]);++cases;
            id=owner.replace(argv[1],rateA==44100?48000:44100,bitsA);failed(owner,id,argv[2]);++cases;
            id=owner.replace(argv[1],rateA,bitsA);old=hold(owner,id);bool invalid=false;
            try{owner.replace(argv[1],123,16);}catch(const std::invalid_argument&){invalid=true;}
            check(invalid && owner.snapshot().status==Owner::Status::Stopped && active==0,"invalid replacement fails closed after joining old source");
            rejected(owner,old);++cases;
            // Retire while construction's actual fread call is pending. New
            // source cannot open until the old read is released and joined.
            auto opened=opens.load(),closed=closes.load();gate.arm();id=owner.replace(argv[1],rateA,bitsA);gate.wait();
            auto pending=owner.pull(id,73,blank);check(pending.status==Read::NeedData,"blocked constructor has no PCM");
            std::atomic<bool> replaced{false};uint64_t next=0;
            std::thread control([&]{next=owner.replace(argv[5],rateB,bitsB);replaced=true;});
            owner.wait(pending.event);
            check(owner.pull(id,73,blank).status==Read::Stale && owner.snapshot().status==Owner::Status::Stopped,"old source retired before join completes");
            check(!replaced && opens==opened+1 && closes==closed && active==1,"replacement waits without premature close or new open");
            gate.release();control.join();drain(owner,next,argv[6],0,true);++cases;
            // A gate models a delayed bounded copy. Replacement must serialize
            // with it; this is not permission for blocking production callbacks.
            id=owner.replace(argv[1],rateA,bitsA);old=hold(owner,id);
            std::promise<void> enteredCopy,releaseCopy,controlStarted;
            auto copyEntered=enteredCopy.get_future(),copyRelease=releaseCopy.get_future(),started=controlStarted.get_future();
            std::atomic<bool> controlDone{false};bool delivered=false;
            std::thread consumer([&]{delivered=owner.deliver(old,[&](const unsigned char*,size_t){
                enteredCopy.set_value();copyRelease.wait();check(!controlDone,"replacement cannot finish during accepted copy");
            });});copyEntered.wait();
            std::thread replacer([&]{controlStarted.set_value();next=owner.replace(argv[5],rateB,bitsB);controlDone=true;});
            started.wait();check(!controlDone,"source replacement waits for accepted copy");
            releaseCopy.set_value();consumer.join();replacer.join();check(delivered,"copy accepted before replacement");
            rejected(owner,old);drain(owner,next,argv[6],0,true);++cases;
            // Keep a block after its owner object dies, then create a new owner
            // whose per-source counter also begins at 1.
            owner.stop();Owner::Block orphan;
            {Owner previous;auto oldSource=previous.replace(argv[1],rateA,bitsA);orphan=hold(previous,oldSource);}
            {Owner replacement;replacement.replace(argv[1],rateA,bitsA);
             check(!replacement.deliver(orphan,[](const unsigned char*,size_t){}),"block from destroyed owner must be rejected by a new owner");}
            check(active==0,"owner destruction joins and closes");++cases;
            id=owner.replace(argv[1],rateA,bitsA);old=hold(owner,id);
            check(owner.pull(id,Owner::MAX_PULL_FRAMES+1,old).status==Read::Invalid,"bounded consumer block size");
            rejected(owner,old);++cases;
            owner.stop();
        }
    }
    check(active==0 && opens==closes,"all worker source handles close before owner destruction returns");
    {std::lock_guard<std::mutex> lock(watchMutex);finished=true;watchCv.notify_all();}watchdog.join();
    std::cout << "PASS: " << cases << " source-owner cases (" << mode << ")\n";
}
