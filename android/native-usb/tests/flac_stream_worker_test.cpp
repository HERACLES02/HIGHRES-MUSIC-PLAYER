#include "../experimental/flac_stream_reader.hpp"
#include <array>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <mutex>
#include <thread>
#ifdef _WIN32
#include <io.h>
#else
#include <unistd.h>
#endif

// Test-only owner and linker-wrapped IO gates. The real reader, libFLAC and
// libc FILE IO run unchanged. No production worker/queue/USB path is added.
using Reader=FlacStreamReader;
using Status=Reader::Status;
void check(bool ok,const char* message) {
    if(!ok){std::cerr << "FAIL: " << message << '\n';std::exit(1);}
}
const char* name(Status s) {
    switch(s) {
        case Status::Block:return "Block";
        case Status::End:return "End";
        case Status::Cancelled:return "Cancelled";
        default:return "Error";
    }
}
enum class Point { Read, Publish };
struct Gate {
    std::mutex mutex;
    std::condition_variable cv;
    bool armed=false,entered=false,released=false,stopping=false,failRead=false;
    Point wanted=Point::Read;
    std::atomic<FILE*> file{nullptr};
    std::atomic<bool> readerDestroyed{false},joined{false};
    std::atomic<int> closes{0},readErrors{0};
    void reset(FILE* input) {
        armed=entered=released=stopping=failRead=false;
        file=input;readerDestroyed=false;joined=false;closes=0;readErrors=0;
    }
    void arm(Point point) {std::lock_guard<std::mutex> lock(mutex);wanted=point;armed=true;}
    bool pause(Point point) {
        std::unique_lock<std::mutex> lock(mutex);
        if(!armed || wanted!=point)return false;
        armed=false;entered=true;cv.notify_all();
        check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return released;}),"blocked gate was not released");
        return failRead;
    }
    void wait(bool forStop=false) {
        std::unique_lock<std::mutex> lock(mutex);
        check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return forStop?stopping:entered;}),"controller gate timed out");
    }
    void release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
} gate;

extern "C" size_t __real_fread(void*,size_t,size_t,FILE*);
extern "C" int __real_fclose(FILE*);
extern "C" size_t __wrap_fread(void* data,size_t size,size_t count,FILE* file) {
    if(file==gate.file.load()) {
        check(gate.closes==0,"read after file close");
        if(gate.pause(Point::Read)) {
            // Replace only this disposable worker-owned descriptor, on its own
            // thread, with a write-only descriptor. Actual fread sets ferror.
#ifdef _WIN32
            FILE* sink=std::fopen("NUL","wb");
            check(sink && _dup2(_fileno(sink),_fileno(file))==0,"fault descriptor replacement");
#else
            FILE* sink=std::fopen("/dev/null","wb");
            check(sink && dup2(fileno(sink),fileno(file))>=0,"fault descriptor replacement");
#endif
            std::fclose(sink);
            size_t result=__real_fread(data,size,count,file);
            check(result==0 && std::ferror(file),"injected read must produce actual FILE error");
            ++gate.readErrors;return result;
        }
    }
    return __real_fread(data,size,count,file);
}
extern "C" int __wrap_fclose(FILE* file) {
    if(file==gate.file.load()) {
        check(gate.readerDestroyed && gate.joined,"file closed before reader destruction/join");
        ++gate.closes;
    }
    return __real_fclose(file);
}

enum class Mode { Complete, Construction, DecodeRead, SeekRead, Publish };
struct Owner {
    FILE* file;
    const char* reference;
    std::atomic<bool> cancel{false};
    std::atomic<uint64_t> delivered{0};
    std::thread thread;
    Status first=Status::Block,second=Status::Block;
    bool verified=false,empty=false,seekOk=true;
    explicit Owner(const char* path,const char* pcm):file(std::fopen(path,"rb")),reference(pcm) {
        check(file!=nullptr,"open generated source");
        check(std::setvbuf(file,nullptr,_IONBF,0)==0,"disable test FILE read-ahead");
        gate.reset(file);
    }
    void start(Mode mode,bool unsafePublish=false) {
        thread=std::thread([&,mode,unsafePublish] {
            {
                Reader reader(file,cancel);
                if(mode==Mode::DecodeRead || mode==Mode::SeekRead)gate.arm(Point::Read);
                if(mode==Mode::SeekRead)seekOk=reader.seek(5001);
                FILE* pcm=std::fopen(reference,"rb");check(pcm!=nullptr,"open reference PCM");
                std::array<unsigned char,65535*6> expected{};
                uint64_t position=0;
                for(;;) {
                    first=reader.next();
                    if(first!=Status::Block)break;
                    auto block=reader.block();
                    size_t width=reader.info().bits/8*2;
                    check(width && block.bytes && block.bytes<=expected.size() && block.bytes%width==0 &&
                          block.firstFrame==position,"worker block bounds/continuity");
                    check(std::fread(expected.data(),1,block.bytes,pcm)==block.bytes &&
                          std::memcmp(expected.data(),block.data,block.bytes)==0,"independent PCM mismatch");
                    position+=block.bytes/width;
                    if(mode==Mode::Publish)gate.pause(Point::Publish);
                    // A returned borrowed block can outlive a stop request.
                    // This test owner must suppress it at the controlled handoff.
                    if(!cancel.load() || unsafePublish)delivered+=block.bytes;
                }
                second=reader.next();verified=reader.md5Verified();empty=reader.block().bytes==0;
                std::fclose(pcm);
            }
            gate.readerDestroyed=true;
        });
    }
    void joinClose() {
        thread.join();gate.joined=true;
        check(gate.readerDestroyed,"reader must die on worker before join returns");
        check(std::fclose(file)==0,"close owned source");file=nullptr;gate.file=nullptr;
    }
    void stop() {
        cancel=true;
        {std::lock_guard<std::mutex> lock(gate.mutex);gate.stopping=true;gate.cv.notify_all();}
        joinClose();
    }
    void expect(Status status) {
        if(first!=status || second!=status) {
            std::cerr << "expected " << name(status) << ", got " << name(first) << " then " << name(second) << '\n';
            check(false,"terminal status must remain consistent");
        }
        check(empty && verified==(status==Status::End) && gate.closes==1,"terminal bytes/integrity/ownership");
    }
};

int main(int argc,char** argv) {
    if(argc<3)return 2;
    bool unsafe=argc==4 && std::strcmp(argv[3],"--unsafe-publish")==0;
    unsigned tests=0;
    if(!unsafe) {
        {
            Owner owner(argv[1],argv[2]);owner.cancel=true;owner.start(Mode::Complete);
            owner.joinClose();owner.expect(Status::Cancelled);check(owner.delivered==0,"pre-cancel delivery");++tests;
        }
        for(auto mode:{Mode::Construction,Mode::DecodeRead,Mode::SeekRead}) {
            Owner owner(argv[1],argv[2]);
            if(mode==Mode::Construction)gate.arm(Point::Read);
            owner.start(mode);gate.wait();
            auto before=owner.delivered.load();
            std::thread stopper([&]{owner.stop();});gate.wait(true);
            check(!gate.joined && gate.closes==0 && !gate.readerDestroyed,"join must wait for outstanding IO");
            gate.release();stopper.join();owner.expect(Status::Cancelled);
            check(owner.delivered==before,"no delivery after controlled read cancellation");
            if(mode==Mode::SeekRead)check(!owner.seekOk,"cancelled seek must fail");
            ++tests;
        }
        for(bool cancelAlso:{false,true}) {
            Owner owner(argv[1],argv[2]);gate.failRead=true;
            owner.start(Mode::DecodeRead);gate.wait();
            if(cancelAlso)owner.cancel=true;
            gate.release();owner.joinClose();
            check(gate.readErrors==1,"read error injection was not exercised");
            owner.expect(Status::Error);++tests;
        }
    }
    {
        Owner owner(argv[1],argv[2]);gate.arm(Point::Publish);
        owner.start(Mode::Publish,unsafe);gate.wait();owner.cancel=true;
        gate.release();owner.joinClose();owner.expect(Status::Cancelled);
        check(owner.delivered==0,"owner must discard borrowed block after cancel");++tests;
    }
    // Fresh readers can fully verify after cancelled/failed jobs close. Repeated
    // ownership cycles are deliberately separate from in-place seek recovery.
    for(int i=0;i<16;++i) {
        Owner owner(argv[1],argv[2]);owner.start(Mode::Complete);owner.joinClose();owner.expect(Status::End);
        check(owner.delivered>0,"fresh worker must deliver complete PCM");++tests;
    }
    std::cout << "PASS: " << tests << " worker cases: blocked reads/seek, cancellation/error, borrowed handoff, join-before-close and fresh restarts\n";
}
