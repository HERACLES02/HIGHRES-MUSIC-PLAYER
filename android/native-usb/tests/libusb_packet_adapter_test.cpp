#include "../experimental/libusb_packet_adapter.hpp"
#include "../experimental/quiet_flac_fixture.hpp"
#include "../experimental/finite_fixture_stream.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <chrono>
#include <thread>
#include <condition_variable>
#include <new>
#ifndef _WIN32
#include <fcntl.h>
#include <unistd.h>
#endif
#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#include <fcntl.h>
#include <io.h>
#elif defined(__ANDROID__)
#include <malloc.h>
#include <sys/resource.h>
#endif
using Adapter=LibusbPacketAdapter;
using Bridge=StreamPacketBridge;
int inputFd(const char* path) {
#ifdef _WIN32
    return _open(path,_O_RDONLY|_O_BINARY);
#else
    return open(path,O_RDONLY|O_CLOEXEC);
#endif
}
void closeFd(int fd) {
#ifdef _WIN32
    _close(fd);
#else
    close(fd);
#endif
}
void check(bool ok,const char* why){if(!ok){std::cerr<<"FAIL: "<<why<<'\n';std::exit(1);}}
thread_local bool inCallback=false;
thread_local size_t callbackAllocations=0;
std::atomic<size_t> largestAllocation{0};
std::atomic<bool> failNextNew{false};
void allocation(size_t bytes) {
    if(inCallback)++callbackAllocations;
    auto old=largestAllocation.load();while(old<bytes && !largestAllocation.compare_exchange_weak(old,bytes)){}
}
extern "C" void* __real_malloc(size_t);
extern "C" void* __real_calloc(size_t,size_t);
extern "C" void* __real_realloc(void*,size_t);
extern "C" void* __wrap_malloc(size_t n){allocation(n);return __real_malloc(n);}
extern "C" void* __wrap_calloc(size_t n,size_t s){allocation(n*s);return __real_calloc(n,s);}
extern "C" void* __wrap_realloc(void* p,size_t n){allocation(n);return __real_realloc(p,n);}
void* operator new(size_t n){if(failNextNew.exchange(false))throw std::bad_alloc();allocation(n);auto* p=std::malloc(n?n:1);if(!p)throw std::bad_alloc();return p;}
void* operator new[](size_t n){return ::operator new(n);}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete[](void* p) noexcept{std::free(p);}
void operator delete(void* p,size_t) noexcept{std::free(p);}
void operator delete[](void* p,size_t) noexcept{std::free(p);}
struct Gate {
    std::mutex mutex;std::condition_variable cv;bool armed=false,entered=false,released=false;
    void arm(){std::lock_guard<std::mutex> lock(mutex);armed=true;entered=released=false;}
    void pause(){std::unique_lock<std::mutex> lock(mutex);if(!armed)return;armed=false;entered=true;cv.notify_all();check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return released;}),"read gate timeout");}
    void wait(){std::unique_lock<std::mutex> lock(mutex);check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return entered;}),"read gate entry");}
    void release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
} gate;
extern "C" size_t __real_fread(void*,size_t,size_t,FILE*);
extern "C" size_t __wrap_fread(void* p,size_t s,size_t n,FILE* f){check(!inCallback,"no callback FILE IO");gate.pause();return __real_fread(p,s,n,f);}
size_t peakHeap=0,peakResident=0;
void memory() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS_EX info{};check(GetProcessMemoryInfo(GetCurrentProcess(),reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&info),sizeof(info)),"process memory sample");
    peakHeap=std::max(peakHeap,size_t(info.PrivateUsage));peakResident=std::max(peakResident,size_t(info.PeakWorkingSetSize));
#elif defined(__ANDROID__)
    const auto heap=mallinfo();rusage info{};check(!getrusage(RUSAGE_SELF,&info),"Android resident sample");
    peakHeap=std::max(peakHeap,size_t(heap.uordblks));peakResident=std::max(peakResident,size_t(info.ru_maxrss)*1024);
#endif
}
namespace fake {
struct Pending {libusb_transfer* transfer=nullptr;std::array<unsigned char,582> bytes{};unsigned length=0;bool cancelled=false;};
std::array<Pending,Bridge::SLOT_COUNT> pending{};
FILE* reference=nullptr;
Adapter* adapter=nullptr;
std::atomic<bool>* externalStop=nullptr;
size_t count=0,frames=0,packets=0,attempts=0,events=0,allocations=0,frees=0,cancels=0,maxPending=0;
unsigned rate=0,bits=0,endpoint=4;
int failAllocation=0,failSubmit=0,fault=0,cancelMode=0,delay=0;
bool pipe=false,shortSeen=false,stopOnSubmit=false,stopOnEvent=false;
void reset(unsigned hz,unsigned depth,const char* raw,uint64_t first=0) {
    check(!count,"old transfers drained before source/format reset");
    if(reference)std::fclose(reference);
    pipe=std::strcmp(raw,"-")==0;reference=pipe?nullptr:std::fopen(raw,"rb");
    check(pipe || reference,"open independent PCM");
    if(reference)check(!std::fseek(reference,long(first*depth/8*2),SEEK_SET),"reference seek");
    rate=hz;bits=depth;frames=packets=attempts=events=cancels=maxPending=0;
    failSubmit=fault=cancelMode=delay=0;shortSeen=stopOnSubmit=stopOnEvent=false;endpoint=4;
    externalStop=nullptr;
}
void checkPending() {
    for(size_t i=0;i<count;++i)check(pending[i].transfer->length==int(pending[i].length) && std::memcmp(pending[i].transfer->buffer,pending[i].bytes.data(),pending[i].length)==0,"in-flight descriptor and PCM immutable until callback");
}
}
extern "C" {
libusb_transfer* LIBUSB_CALL libusb_alloc_transfer(int packets) {
    check(!inCallback && packets==1,"one packet per preallocated transfer, outside callback");
    if(int(++fake::allocations)==fake::failAllocation)return nullptr;
    return static_cast<libusb_transfer*>(std::calloc(1,sizeof(libusb_transfer)+sizeof(libusb_iso_packet_descriptor)));
}
void LIBUSB_CALL libusb_free_transfer(libusb_transfer* p){check(!inCallback && !fake::count,"free only outside callbacks after all transfers drain");++fake::frees;std::free(p);}
int LIBUSB_CALL libusb_submit_transfer(libusb_transfer* p) {
    check(!inCallback,"no callback resubmission");check(fake::count<Bridge::SLOT_COUNT,"bounded outstanding transfer pool");
    check(!fake::externalStop || !fake::externalStop->load(),"Probe never resubmits after Stop during event dispatch");
    if(int(++fake::attempts)==fake::failSubmit)return LIBUSB_ERROR_IO;
    check(p->endpoint==fake::endpoint && p->type==LIBUSB_TRANSFER_TYPE_ISOCHRONOUS && p->num_iso_packets==1 && p->timeout==1000 && p->flags==0,"correct endpoint/ISO/timeout/manual buffer ownership");
    check(!fake::shortSeen,"short packet only at finite end");
    const unsigned bytes=p->iso_packet_desc[0].length;
    const auto nominal=((fake::packets+1)*uint64_t(fake::rate)/1000)-(fake::packets*uint64_t(fake::rate)/1000);
    check(bytes>0 && bytes<=nominal*fake::bits/8*2 && bytes<=582 && bytes%(fake::bits/8*2)==0 && p->length==int(bytes),"exact fractional boundaries and whole frames");
    fake::shortSeen=bytes<nominal*fake::bits/8*2;
    std::array<unsigned char,582> expected{};
    if(fake::pipe)check(std::fwrite(p->buffer,1,bytes,stdout)==bytes,"bounded PCM pipe sink");
    else check(std::fread(expected.data(),1,bytes,fake::reference)==bytes && std::memcmp(expected.data(),p->buffer,bytes)==0,"exact independent submitted PCM");
    fake::frames+=bytes/(fake::bits/8*2);++fake::packets;
    auto& queued=fake::pending[fake::count++];queued.transfer=p;queued.length=bytes;queued.cancelled=false;
    std::memcpy(queued.bytes.data(),p->buffer,bytes);fake::maxPending=std::max(fake::maxPending,fake::count);
    if(fake::stopOnSubmit){if(fake::externalStop)*fake::externalStop=true;else fake::adapter->requestStop();}
    return 0;
}
int LIBUSB_CALL libusb_cancel_transfer(libusb_transfer* p) {
    check(!inCallback,"no callback cancel/owner work");fake::checkPending();
    for(size_t i=0;i<fake::count;++i)if(fake::pending[i].transfer==p) {
        check(!fake::pending[i].cancelled,"one cancellation request per pending borrow");
        fake::pending[i].cancelled=true;++fake::cancels;
        return fake::cancelMode==1?LIBUSB_ERROR_NOT_FOUND:fake::cancelMode==2?LIBUSB_ERROR_IO:0;
    }
    check(false,"only pending transfers can be cancelled");return LIBUSB_ERROR_NOT_FOUND;
}
int LIBUSB_CALL libusb_handle_events_timeout_completed(libusb_context*,timeval* timeout,int*) {
    check(!inCallback && timeout->tv_sec==0 && timeout->tv_usec==20000,"bounded worker event slice");
    fake::checkPending();++fake::events;
    if(fake::events==1 && fake::fault==20)throw std::runtime_error("injected worker event exception");
    if(fake::events==1 && fake::fault==1)return LIBUSB_ERROR_IO;
    if(fake::events==1 && fake::fault==2)return LIBUSB_ERROR_INTERRUPTED;
    if(fake::delay>0){--fake::delay;return 0;}
    if(!fake::count)return 0;
    const auto queued=fake::pending[--fake::count];auto* p=queued.transfer;
    p->status=queued.cancelled && fake::cancelMode==0?LIBUSB_TRANSFER_CANCELLED:LIBUSB_TRANSFER_COMPLETED;
    p->actual_length=0; // ISO aggregate is undefined: adapter must ignore it.
    auto& packet=p->iso_packet_desc[0];packet.status=LIBUSB_TRANSFER_COMPLETED;packet.actual_length=packet.length;
    if(p->status==LIBUSB_TRANSFER_CANCELLED)packet.actual_length=packet.length/2;
    if(fake::events==1) {
        if(fake::fault>=10 && fake::fault<=15)p->status=static_cast<libusb_transfer_status>(fake::fault-9);
        if(fake::fault==3)--packet.actual_length;
        if(fake::fault==4)++packet.actual_length;
        if(fake::fault==5)packet.status=LIBUSB_TRANSFER_ERROR;
        if(fake::fault==6)++packet.length;
        if(fake::fault==7)p->num_iso_packets=0;
        if(fake::fault==8)--p->length;
        if(fake::stopOnEvent){if(fake::externalStop)*fake::externalStop=true;else fake::adapter->requestStop();}
    }
    inCallback=true;callbackAllocations=0;p->callback(p);
    check(!callbackAllocations,"zero instrumented callback allocations");inCallback=false;return 0;
}
}
Bridge::Endpoint descriptor(unsigned bits){return {fake::endpoint,5,1,bits==16?388u:582u,true};}
void drive(Adapter& adapter,Adapter::State expected,bool md5=false) {
    auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
    auto previousFrames=fake::frames,previousPending=fake::count;
    do {
        if(std::chrono::steady_clock::now()>=deadline)std::cerr<<"TIMEOUT progress: "<<fake::frames<<" frames, "<<fake::packets<<" packets, "<<fake::events<<" events, "<<fake::count<<" pending\n";
        check(std::chrono::steady_clock::now()<deadline,"bounded adapter progress timeout");
        adapter.pump();adapter.events();adapter.pump();
        if(fake::frames!=previousFrames || fake::count!=previousPending) {
            deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
            previousFrames=fake::frames;previousPending=fake::count;
        }
        if(fake::packets%256==0)memory();
        std::this_thread::yield();
    } while(adapter.state()==Adapter::State::Running || !adapter.drained());
    check(adapter.state()==expected && adapter.md5Verified()==md5,"explicit adapter terminal state/integrity");
    check((expected==Adapter::State::Failed)==bool(adapter.error()),"error code retained separately from EOF/Stop");
    check(fake::count==0,"all libusb callbacks retired");
    if(expected==Adapter::State::End && fake::reference)check(std::fgetc(fake::reference)==EOF,"complete PCM suffix without padding or loop");
}
void waitPending(Adapter& adapter) {
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(10);
    while(fake::count<Bridge::SLOT_COUNT){check(std::chrono::steady_clock::now()<deadline,"source fills all fixed transfer slots");adapter.pump();std::this_thread::yield();}
}
int main(int argc,char** argv) {
    check(argc==6 || argc==10,"generated source/reference/rate/bits/mode plus replacement");
    const unsigned rate=std::stoul(argv[3]),bits=std::stoul(argv[4]);const std::string mode=argv[5];
    fake::reset(rate,bits,argv[2]);
    if(mode.rfind("probe-",0)==0) {
        int fd=inputFd(argv[1]);check(fd>=0,"open private simulated Probe source");
        if(mode=="probe-unlinked") {
#ifndef _WIN32
            check(unlink(argv[1])==0 && access(argv[1],F_OK)!=0,"private file unlinked before preflight/playback");
#else
            check(false,"unlinked descriptor test requires POSIX file semantics");
#endif
        }
        StreamState state;state.reset();
        const auto verified=validateQuietFlacFixture(fd,rate,bits,state.stop);
        check(verified.status==QuietFlacFixture::Status::Valid,"Probe preflight accepts generated quiet reference");
        const int cycles=mode=="probe-stress"?110:1;
        size_t heap10=0,resident10=0;
        for(int cycle=0;cycle<cycles;++cycle) {
        state.reset();
        if(cycle)fake::reset(rate,bits,argv[2]);
        fake::allocations=fake::frees=0;
        fake::externalStop=&state.stop;
        if(mode=="probe-prestop")state.stop=true;
        if(mode=="probe-stop" || mode=="probe-late-cancel")fake::stopOnEvent=true;
        if(mode=="probe-late-cancel")fake::cancelMode=1;
        if(mode=="probe-submit-stop")fake::stopOnSubmit=true;
        if(mode=="probe-submit-error")fake::failSubmit=5;
        if(mode=="probe-packet-error")fake::fault=3;
        if(mode=="probe-event-throw")fake::fault=20;
        int configured=0;
        const auto result=runFiniteFixtureStream(nullptr,nullptr,fd,rate,bits,descriptor(bits),verified,state,[&] {
            check(!fake::count,"initial configuration precedes transfers");++configured;
            if(mode=="probe-config-stop" || mode=="probe-config-stop-false")state.stop=true;
            return mode!="probe-config-error" && mode!="probe-config-stop-false";
        });
        using T=FiniteFixtureResult::Terminal;
        const auto expected=mode=="probe-prestop" || mode=="probe-stop" || mode=="probe-late-cancel" || mode=="probe-config-stop" || mode=="probe-config-stop-false" || mode=="probe-submit-stop"?T::Stopped:
            mode=="probe-submit-error" || mode=="probe-packet-error" || mode=="probe-config-error" || mode=="probe-event-throw"?T::Failed:T::End;
        check(result.terminal==expected,"Probe reports explicit EOF/Stop/error");
        check(!fake::count && fake::allocations==fake::frees,"Probe drains and frees before caller/device lifetime ends");
        check(state.pending==0,"Probe publishes drained progress");
        check(state.submittedBytes==fake::frames*bits/8*2,"Probe preserves submission evidence even on worker exceptions");
        if(mode=="probe-packet-error")check(state.packetErrors>0,"Probe reports packet failures in the packet-error counter");
        if(expected==T::End) {
            check(configured==1 && result.md5Verified && state.error==0,"Probe configures once and reports complete MD5 EOF");
            check(state.submittedFrames==6ULL*rate && state.positionFrames==6ULL*rate && state.completedIntervals==6000,"finite frame/interval progress never loops");
            check(state.submittedBytes==6ULL*rate*bits/8*2 && state.completedBytes==state.submittedBytes,"exact finite submitted/completed bytes");
            check(state.hash==verified.fnv1a64 && std::fgetc(fake::reference)==EOF,"Probe bytes match independent reference completely");
        }
        if(mode=="probe-prestop")check(!configured && !fake::attempts,"pre-start Stop prevents configuration/submission");
        if(mode=="probe-config-stop" || mode=="probe-config-stop-false" || mode=="probe-config-error")check(!fake::attempts,"configuration boundary prevents submission");
        if(mode=="probe-submit-stop")check(fake::attempts==1,"Stop inside submission prevents all later attempts");
        if(mode=="probe-late-cancel")check(fake::cancels>0,"completion-after-cancel actually exercised");
        check(largestAllocation<=1024*1024,"Probe individual allocation cap");memory();
        if(cycle==9){heap10=peakHeap;resident10=peakResident;}
        }
        closeFd(fd);
        std::cout<<"PASS: "<<mode<<" MEMORY "<<heap10<<' '<<resident10<<' '<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';
        std::fclose(fake::reference);return 0;
    }
    const bool quiet=mode.rfind("quiet-",0)==0;
    if(quiet) {
        const int fd=inputFd(argv[1]);check(fd>=0,"open disposable quiet fixture");
        std::atomic<bool> stop{mode=="quiet-prestop"};
        if(mode=="quiet-valid") {
            using Q=QuietFlacFixture::Status;
            check(validateQuietFlacFixture(-1,rate,bits,stop).status==Q::InvalidInput,"invalid descriptor rejected before decode");
            check(validateQuietFlacFixture(fd,192000,bits,stop).status==Q::InvalidFormat,"unsupported rate rejected");
            check(validateQuietFlacFixture(fd,rate,32,stop).status==Q::InvalidFormat,"unsupported width rejected");
            int pipes[2];
#ifdef _WIN32
            check(_pipe(pipes,256,_O_BINARY)==0,"create withheld preflight pipe");
#else
            check(pipe(pipes)==0,"create withheld preflight pipe");
#endif
            check(validateQuietFlacFixture(pipes[0],rate,bits,stop).status==Q::InvalidInput,"pipe rejected without reading withheld data");
            closeFd(pipes[0]);closeFd(pipes[1]);
        }
        std::thread stopper;
        if(mode=="quiet-stop") {
            gate.arm();stopper=std::thread([&]{gate.wait();stop=true;gate.release();});
        }
        const int cycles=mode=="quiet-stress"?110:1;
        size_t heap10=0,resident10=0;
        for(int cycle=0;cycle<cycles;++cycle) {
            const auto result=validateQuietFlacFixture(fd,rate,bits,stop);
            if(stopper.joinable())stopper.join();
            using Q=QuietFlacFixture::Status;
            const auto expected=mode=="quiet-loud"?Q::TooLoud:mode=="quiet-length"?Q::WrongLength:
                mode=="quiet-corrupt"?Q::SourceFailed:mode=="quiet-input"?Q::InvalidInput:mode=="quiet-unverified"?Q::Unverified:
                mode=="quiet-stop" || mode=="quiet-prestop"?Q::Cancelled:Q::Valid;
            if(result.status!=expected)std::cerr<<"Expected quiet status "<<int(expected)<<", observed "<<int(result.status)<<'\n';
            check(result.status==expected,"quiet preflight explicit terminal reason");
            check(!fake::allocations && !fake::attempts,"preflight makes no libusb allocations or submissions");
            if(expected==Q::Valid) {
                check(result.frames==6ULL*rate && result.md5Verified,"complete six-second source and whole-stream MD5 verified");
                FILE* reference=std::fopen(argv[2],"rb");check(reference,"open preflight independent PCM");
                uint64_t hash=14695981039346656037ULL;std::array<unsigned char,4096> bytes{};size_t size;
                while((size=std::fread(bytes.data(),1,bytes.size(),reference)))for(size_t i=0;i<size;++i){hash^=bytes[i];hash*=1099511628211ULL;}
                std::fclose(reference);check(hash==result.fnv1a64,"preflight reads exact independent PCM bytes in order");
            }
            memory();if(cycle==9){heap10=peakHeap;resident10=peakResident;}
        }
        closeFd(fd);
        check(largestAllocation<=1024*1024,"preflight allocation request cap");
        if(mode!="quiet-valid") {
            std::cout<<"PASS: "<<mode<<" MEMORY "<<heap10<<' '<<resident10<<' '<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';
            std::fclose(fake::reference);return 0;
        }
    }
#ifdef _WIN32
    if(mode=="pipe")_setmode(_fileno(stdout),_O_BINARY);
#endif
    if(mode=="starve")gate.arm();
    {
        Adapter adapter(nullptr,nullptr);fake::adapter=&adapter;
        if(quiet) {
            const int fd=inputFd(argv[1]);check(fd>=0,"open preflighted source for actual bridge");
            check(adapter.startFd(fd,rate,bits,descriptor(bits)),"preflighted descriptor enters actual streaming bridge");closeFd(fd);
        } else check(adapter.start(argv[1],rate,bits,descriptor(bits)),"start supported native source");
        if(mode=="service-gap") {
            waitPending(adapter);
            // Advance the simulated endpoint while the worker cannot run.
            // Completed transfers remain borrowed until callbacks retire them.
            // Recorded gaps: 22.625ms in Direct43, 59.145ms in older diagnostics.
            uint64_t scheduledUs=0;
            for(size_t i=0;i<fake::count;++i)scheduledUs+=uint64_t(fake::pending[i].transfer->num_iso_packets)*1000;
            const auto missing=[&](uint64_t gapUs){return gapUs>scheduledUs?gapUs-scheduledUs:0;};
            check(!missing(22625) && !missing(59145),"queued USB intervals cover measured worker-service gaps without an endpoint underrun");
            fake::checkPending();
        }
        if(mode=="starve") {
            gate.wait();for(int i=0;i<20;++i){adapter.pump();adapter.events();check(!fake::count && !fake::attempts && adapter.state()==Adapter::State::Running,"starvation is not silence/EOF/error");}gate.release();
        }
        if(mode=="fd") {
            check(argc==10,"descriptor replacement fixtures");
            adapter.requestStop();drive(adapter,Adapter::State::Stopped);
            for(uint64_t first:{uint64_t(0),uint64_t(5001)}) {
                fake::reset(rate,bits,argv[2],first);const int fd=inputFd(argv[1]);check(fd>=0,"open disposable input descriptor");
                check(adapter.startFd(fd,rate,bits,descriptor(bits),first),"accept borrowed descriptor source");closeFd(fd);
                drive(adapter,Adapter::State::End,first==0);
            }
            fake::reset(rate,bits,argv[2]);
            check(adapter.startFd(-1,rate,bits,descriptor(bits)),"invalid descriptor job accepted for explicit asynchronous failure");
            drive(adapter,Adapter::State::Failed);check(!fake::attempts,"invalid descriptor emits no USB data");
            int pipeFds[2];
#ifdef _WIN32
            check(_pipe(pipeFds,256,_O_BINARY)==0,"create disposable pipe");
#else
            check(pipe(pipeFds)==0,"create disposable pipe");
#endif
            fake::reset(rate,bits,argv[2]);check(adapter.startFd(pipeFds[0],rate,bits,descriptor(bits)),"unsupported pipe job accepted for explicit failure");closeFd(pipeFds[0]);
            drive(adapter,Adapter::State::Failed);closeFd(pipeFds[1]);check(!fake::attempts,"pipe rejected without reading withheld data");
            fake::reset(rate,bits,argv[2]);int fd=inputFd(argv[1]);check(fd>=0,"open descriptor for pending source");
            check(adapter.startFd(fd,rate,bits,descriptor(bits)),"descriptor source before pending transfers");closeFd(fd);waitPending(adapter);
            const unsigned b=std::stoul(argv[9]),r=std::stoul(argv[8]);fd=inputFd(argv[6]);check(fd>=0,"open replacement descriptor");
            check(!adapter.startFd(fd,r,b,descriptor(b)),"descriptor replacement waits for transfer drain");
#ifdef _WIN32
            check(_lseeki64(fd,0,SEEK_CUR)==0,"rejected source does not consume caller descriptor");
#else
            check(lseek(fd,0,SEEK_CUR)==0,"rejected source does not consume caller descriptor");
#endif
            adapter.requestStop();drive(adapter,Adapter::State::Stopped);
            fake::reset(r,b,argv[7]);check(adapter.startFd(fd,r,b,descriptor(b)),"same caller descriptor accepted after drain");closeFd(fd);
            drive(adapter,Adapter::State::End,true);
            std::cout<<"PASS: 5 descriptor handoff cases\n";
        } else if(mode=="stress") {
            check(argc==10,"stress replacement fixtures");adapter.requestStop();drive(adapter,Adapter::State::Stopped);
            size_t heap10=0,resident10=0;
            for(int cycle=0;cycle<110;++cycle) {
                const bool b=cycle%2;const unsigned hz=b?std::stoul(argv[8]):rate,depth=b?std::stoul(argv[9]):bits;const auto first=cycle%3?5001u:0u;
                fake::reset(hz,depth,b?argv[7]:argv[2],first);
                check(adapter.start(b?argv[6]:argv[1],hz,depth,descriptor(depth),first),"repeated native source/format/seek");waitPending(adapter);
                std::thread stop([&]{adapter.requestStop();});adapter.events();stop.join();drive(adapter,Adapter::State::Stopped);
                memory();if(cycle==9){heap10=peakHeap;resident10=peakResident;}
            }
            std::cout<<"PASS: 110 adapter cycles MEMORY "<<heap10<<' '<<resident10<<' '<<peakHeap<<' '<<peakResident<<'\n';
        } else if(mode=="lifecycle") {
            check(argc==10,"lifecycle replacement fixtures");adapter.requestStop();drive(adapter,Adapter::State::Stopped);check(!fake::attempts,"Stop before first pump submits nothing");
            for(int cancelMode:{0,1,2}) {
                fake::reset(rate,bits,argv[2]);check(adapter.start(argv[1],rate,bits,descriptor(bits)),"restart explicitly");waitPending(adapter);
                for(int i=0;i<20;++i){adapter.pump();fake::checkPending();check(fake::count==Bridge::SLOT_COUNT && fake::attempts==Bridge::SLOT_COUNT,"backpressure leaves source and pending buffers bounded");}
                fake::cancelMode=cancelMode;adapter.requestStop();adapter.pump();
                check(fake::count==Bridge::SLOT_COUNT && fake::cancels==Bridge::SLOT_COUNT && !adapter.drained(),"cancel/NOT_FOUND/error is not transfer retirement");
                check(!adapter.start(argv[6],std::stoul(argv[8]),std::stoul(argv[9]),descriptor(std::stoul(argv[9]))),"format cannot change with callbacks queued");
                fake::delay=5;for(int i=0;i<5;++i){adapter.events();adapter.pump();check(fake::count==Bridge::SLOT_COUNT && !adapter.drained(),"slow cancellation preserves all borrowed storage");}
                drive(adapter,cancelMode==2?Adapter::State::Failed:Adapter::State::Stopped);
            }
            for(int fault:{1,3,4,5,6,7,8,10,11,12,13,14,15}) {
                fake::reset(rate,bits,argv[2]);fake::fault=fault;check(adapter.start(argv[1],rate,bits,descriptor(bits)),"failure source start");drive(adapter,Adapter::State::Failed);
            }
            for(int failedSubmit:{1,5}) {
                fake::reset(rate,bits,argv[2]);fake::failSubmit=failedSubmit;check(adapter.start(argv[1],rate,bits,descriptor(bits)),"submit failure source");drive(adapter,Adapter::State::Failed);check(fake::attempts==size_t(failedSubmit),"no later submissions after failed submit");
                const int failure=adapter.error();
                check(!adapter.start(argv[1],192000,24,descriptor(24)) && adapter.error()==failure && adapter.state()==Adapter::State::Failed,"rejected restart preserves failed terminal result");
            }
            for(bool duringSubmit:{false,true}) {
                fake::reset(rate,bits,argv[2]);fake::stopOnSubmit=duringSubmit;fake::stopOnEvent=!duringSubmit;
                check(adapter.start(argv[1],rate,bits,descriptor(bits)),"Stop boundary source");drive(adapter,Adapter::State::Stopped);
                if(duringSubmit)check(fake::attempts==1,"Stop during submission blocks all subsequent submits");
            }
            const unsigned b=std::stoul(argv[9]),r=std::stoul(argv[8]);
            fake::reset(r,b,argv[7]);fake::endpoint=5;check(adapter.start(argv[6],r,b,descriptor(b)),"different format and endpoint after drain");drive(adapter,Adapter::State::End,true);
            fake::reset(rate,bits,argv[2],5001);fake::fault=2;
            check(adapter.start(argv[1],rate,bits,descriptor(bits),5001),"exact seek plus interrupted event");drive(adapter,Adapter::State::End,false);
            check(!adapter.start(argv[1],192000,24,descriptor(24)),"no unsupported rate");
            auto bad=descriptor(bits);bad.address=0x84;check(!adapter.start(argv[1],rate,bits,bad),"no capture endpoint used as playback");
            std::cout<<"PASS: adapter lifecycle/failures/drain/seek/format/endpoint\n";
        } else {
            drive(adapter,mode=="error"?Adapter::State::Failed:Adapter::State::End,mode!="error" && mode!="unverified");
            if(mode!="error")check(fake::frames>0,"adapter must submit source PCM through libusb");
            memory();
            if(mode=="pipe") {
                check(largestAllocation<=1024*1024,"allocation request cap");
                std::cerr<<"PASS: pipe "<<fake::frames<<" MEMORY "<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';
            } else std::cout<<"PASS: adapter "<<mode<<' '<<fake::frames<<" exact frames\n";
        }
    }
    check(fake::allocations==Bridge::SLOT_COUNT && fake::frees==Bridge::SLOT_COUNT,"fixed transfer pool freed exactly once after drain");
    if(fake::reference)std::fclose(fake::reference);
    fake::reference=nullptr;
    // Partial pool allocation failure unwinds only allocated, never active data.
    for(int at:{1,4,int(Bridge::SLOT_COUNT)}) {
        fake::allocations=fake::frees=0;fake::failAllocation=at;bool threw=false;
        try{Adapter failed(nullptr,nullptr);}catch(const std::runtime_error&){threw=true;}
        check(threw && fake::allocations==size_t(at) && fake::frees==size_t(at-1),"partial transfer allocation cleanup");
    }
    return 0;
}
