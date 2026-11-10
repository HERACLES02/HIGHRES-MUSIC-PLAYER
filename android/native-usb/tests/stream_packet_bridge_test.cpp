#include "../experimental/stream_packet_bridge.hpp"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <thread>
#include <chrono>
#include <iostream>
#include <condition_variable>
#include <new>
#ifdef _WIN32
#include <windows.h>
#include <psapi.h>
#include <io.h>
#include <fcntl.h>
#elif defined(__ANDROID__)
#include <malloc.h>
#include <sys/resource.h>
#endif
using Bridge=StreamPacketBridge;
thread_local bool bounded=false;
thread_local size_t allocations=0;
std::atomic<size_t> largestAllocation{0};
void allocation(size_t bytes) {
    if(bounded)++allocations;
    auto previous=largestAllocation.load();
    while(previous<bytes && !largestAllocation.compare_exchange_weak(previous,bytes)){}
}
extern "C" void* __real_malloc(size_t);
extern "C" void* __real_calloc(size_t,size_t);
extern "C" void* __real_realloc(void*,size_t);
extern "C" void* __wrap_malloc(size_t n){allocation(n);return __real_malloc(n);}
extern "C" void* __wrap_calloc(size_t n,size_t s){allocation(n*s);return __real_calloc(n,s);}
extern "C" void* __wrap_realloc(void* p,size_t n){allocation(n);return __real_realloc(p,n);}
size_t peakPrivate=0,peakResident=0;
void check(bool,const char*);
void memory() {
#ifdef _WIN32
    PROCESS_MEMORY_COUNTERS_EX counters{};
    check(GetProcessMemoryInfo(GetCurrentProcess(),reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&counters),sizeof(counters)),"process memory sample");
    peakPrivate=std::max(peakPrivate,size_t(counters.PrivateUsage));
    peakResident=std::max(peakResident,size_t(counters.PeakWorkingSetSize));
#elif defined(__ANDROID__)
    // Android native allocated heap is distinct from Windows private bytes.
    // Keep process resident high-water separate; neither comes from slot size.
    const auto heap=mallinfo();rusage usage{};
    check(getrusage(RUSAGE_SELF,&usage)==0,"Android resident high-water sample");
    peakPrivate=std::max(peakPrivate,size_t(heap.uordblks));
    peakResident=std::max(peakResident,size_t(usage.ru_maxrss)*1024);
#endif
}
void* operator new(size_t bytes){if(bounded)++allocations;auto* p=std::malloc(bytes);if(!p)throw std::bad_alloc();return p;}
void operator delete(void* p) noexcept{std::free(p);}
void operator delete(void* p,size_t) noexcept{std::free(p);}
void* operator new[](size_t bytes){return ::operator new(bytes);}
void operator delete[](void* p) noexcept{std::free(p);}
void operator delete[](void* p,size_t) noexcept{std::free(p);}
void check(bool ok,const char* why){if(!ok){std::cerr<<"FAIL: "<<why<<'\n';std::exit(1);}}
struct Gate {
    std::mutex mutex;std::condition_variable cv;bool armed=false,entered=false,released=false;
    void arm(){std::lock_guard<std::mutex> lock(mutex);armed=true;entered=released=false;}
    void pause(){std::unique_lock<std::mutex> lock(mutex);if(!armed)return;armed=false;entered=true;cv.notify_all();check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return released;}),"read gate timeout");}
    void wait(){std::unique_lock<std::mutex> lock(mutex);check(cv.wait_for(lock,std::chrono::seconds(5),[&]{return entered;}),"read gate entry");}
    void release(){std::lock_guard<std::mutex> lock(mutex);released=true;cv.notify_all();}
} gate;
extern "C" size_t __real_fread(void*,size_t,size_t,FILE*);
extern "C" size_t __wrap_fread(void* p,size_t s,size_t n,FILE* file){gate.pause();return __real_fread(p,s,n,file);}

struct FakeTransport {
    Bridge& bridge;
    std::array<Bridge::Packet,Bridge::SLOT_COUNT> pending{};
    std::array<std::array<unsigned char,Bridge::PACKET_BYTES>,Bridge::SLOT_COUNT> copies{};
    size_t count=0,cancels=0;
    void accept(const Bridge::Packet& packet) {
        check(count<pending.size(),"bounded outstanding transfers");pending[count]=packet;
        std::memcpy(copies[count].data(),packet.data,packet.bytes);++count;
    }
    void cancelRequests() {
        Bridge::Packet packet;
        while(bridge.cancel(packet)) {
            size_t index=0;while(index<count && pending[index].ticket!=packet.ticket)++index;
            check(index<count && std::memcmp(packet.data,copies[index].data(),packet.bytes)==0,"cancel retains in-flight PCM");++cancels;
            // Model NOT_FOUND: completed in OS, callback still pending. Do NOT
            // call complete here or release storage based on cancel's result.
        }
    }
    void finish(bool mixed=false) {
        auto callbacks=[&]{
            for(size_t i=count;i>0;--i) {
                auto packet=pending[i-1];
                check(std::memcmp(packet.data,copies[i-1].data(),packet.bytes)==0,"no in-flight reuse before completion");
                bounded=true;allocations=0;
                check(bridge.complete(packet.slot,packet.ticket,mixed && i%2?Bridge::Completion::Cancelled:Bridge::Completion::Done),"completion after cancel");
                check(!bridge.complete(packet.slot,packet.ticket,Bridge::Completion::Done),"duplicate completion rejected");
                check(allocations==0,"callback allocation count");bounded=false;
            }
        };
        if(mixed){std::thread callback(callbacks);callback.join();}else callbacks();
        count=0;bridge.prepare();
    }
};
Bridge::Step ready(Bridge& bridge) {
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(10);
    for(;;){check(std::chrono::steady_clock::now()<until,"bounded test timeout");auto result=bridge.prepare();if(result!=Bridge::Step::NeedData)return result;std::this_thread::yield();}
}
size_t drain(Bridge& bridge,const char* reference,unsigned rate,unsigned bits,uint64_t first,bool good,bool md5) {
    FILE* pcm=std::fopen(reference,"rb");check(pcm,"open independent reference");
    check(std::fseek(pcm,long(first*bits/8*2),SEEK_SET)==0,"reference seek");
    std::array<unsigned char,582> expected{};uint64_t frames=0,packets=0;bool shortSeen=false;
    FakeTransport fake{bridge};
    for(;;) {
        allocations=0;bounded=true;auto step=ready(bridge);Bridge::Packet packet;
        bool submitted=bridge.submit(packet);bounded=false;check(allocations==0,"worker prepare/submit C++ allocations");
        if(submitted) {
            check(!shortSeen,"short data only at finite end");
            check(packet.firstFrame==first+frames,"exact packet source position");
            const size_t nominal=((packets+1)*uint64_t(rate)/1000)-(packets*uint64_t(rate)/1000);
            check(packet.frames>0 && packet.frames<=nominal && packet.bytes==packet.frames*bits/8*2,"fractional schedule and complete frames");
            shortSeen=packet.frames<nominal;
            check(std::fread(expected.data(),1,packet.bytes,pcm)==packet.bytes && std::memcmp(expected.data(),packet.data,packet.bytes)==0,"exact independent packet PCM");
            frames+=packet.frames;++packets;
            fake.accept(packet);
            if(fake.count==Bridge::SLOT_COUNT)fake.finish();
        } else if(fake.count){fake.cancelRequests();fake.finish();}
        else if(step==Bridge::Step::Terminal && bridge.drained())break;
    }
    if(good) {
        check(frames>0,"bridge must emit streaming PCM packets");
        check(std::fgetc(pcm)==EOF,"all reference frames without padding or repetition");
        check(bridge.state()==Bridge::State::End && bridge.md5Verified()==md5,"explicit EOF and integrity");
    } else {
        if(bridge.state()!=Bridge::State::Failed)std::cerr<<"Unexpected corruption terminal state: "<<int(bridge.state())<<'\n';
        check(bridge.state()==Bridge::State::Failed && !bridge.md5Verified(),"corruption differs from EOF");
    }
    std::fclose(pcm);return size_t(frames);
}
int main(int argc,char** argv) {
    check(argc==6 || argc==10,"source reference rate bits mode and optional replacement arguments");
    const unsigned rate=std::stoul(argv[3]),bits=std::stoul(argv[4]);const std::string mode=argv[5];
    Bridge bridge;Bridge::Endpoint endpoint{4,5,1,bits==16?388u:582u,true};
    if(mode=="starve")gate.arm();
    check(bridge.start(argv[1],rate,bits,endpoint),"start supported source");
    if(mode=="starve") {
        gate.wait();
        for(int i=0;i<20;++i){Bridge::Packet p;check(bridge.prepare()==Bridge::Step::NeedData && !bridge.submit(p),"starvation emits no silence or EOF");}
        gate.release();
    }
    if(mode=="stress") {
        check(argc==10,"replacement source arguments");bridge.stop();
        size_t private10=0,resident10=0;
        Bridge::Packet stale;
        for(int cycle=0;cycle<110;++cycle) {
            const bool alternate=cycle%2;
            const unsigned r=alternate?std::stoul(argv[8]):rate,b=alternate?std::stoul(argv[9]):bits;
            auto ep=endpoint;ep.maxPacketBytes=b==16?388:582;
            const auto first=cycle%3?5001u:0u;
            check(bridge.start(alternate?argv[6]:argv[1],r,b,ep,first),"repeated source/format replacement");
            check(ready(bridge)==Bridge::Step::Ready,"new source prefix");Bridge::Packet p;
            check(bridge.submit(p),"prefix submitted");
            check(!bridge.complete(stale.slot,stale.ticket,Bridge::Completion::Done),"old generation callback rejected");
            FILE* ref=std::fopen(alternate?argv[7]:argv[2],"rb");check(ref,"replacement reference");
            std::array<unsigned char,582> data{};check(std::fseek(ref,long(first*b/8*2),SEEK_SET)==0,"replacement reference position");
            check(std::fread(data.data(),1,p.bytes,ref)==p.bytes && std::memcmp(data.data(),p.data,p.bytes)==0,"format replacement bytes");std::fclose(ref);
            std::atomic<bool> go{false};bool completed=false;
            std::thread callback([&]{while(!go.load())std::this_thread::yield();bounded=true;allocations=0;
                completed=bridge.complete(p.slot,p.ticket,Bridge::Completion::Done);check(!allocations,"racing callback allocations");bounded=false;});
            go=true;bridge.stop();callback.join();check(completed,"completion racing stop accepted for retirement");
            bridge.prepare();check(bridge.drained() && bridge.state()==Bridge::State::Stopped,"no restart after stop race");stale=p;
            memory();if(cycle==9){private10=peakPrivate;resident10=peakResident;}
        }
        std::cout<<"PASS: 110 format/seek/stop races MEMORY "<<private10<<' '<<resident10<<' '<<peakPrivate<<' '<<peakResident<<'\n';return 0;
    }
    if(mode=="pipe") {
#ifdef _WIN32
        _setmode(_fileno(stdout),_O_BINARY);
#endif
        uint64_t frames=0,packets=0;
        for(;;) {
            auto step=ready(bridge);Bridge::Packet p;
            if(bridge.submit(p)) {
                check(std::fwrite(p.data,1,p.bytes,stdout)==p.bytes,"packet pipe output");frames+=p.frames;
                check(bridge.complete(p.slot,p.ticket,Bridge::Completion::Done),"pipe completion");
                if(++packets%256==0)memory();
            } else if(step==Bridge::Step::Terminal && bridge.drained())break;
        }
        memory();check(bridge.state()==Bridge::State::End && bridge.md5Verified(),"long stream verified End");
        check(largestAllocation.load()<=1024*1024,"measured allocation request ceiling");
        std::cerr<<"PASS: pipe "<<frames<<" MEMORY "<<peakPrivate<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';return 0;
    }
    if(mode=="lifecycle") {
        FakeTransport fake{bridge};
        // Half submitted, half ready: stop must discard queued PCM but retain
        // every pending buffer. Source worker can also be Full behind slots.
        for(size_t i=0;i<Bridge::SLOT_COUNT;++i) {
            check(ready(bridge)==Bridge::Step::Ready,"prepare bounded slots");
            if(i<4){Bridge::Packet p;check(bridge.submit(p),"submit old source");fake.accept(p);}
        }
        for(int i=0;i<30;++i)check(bridge.prepare()==Bridge::Step::Full,"backpressure does not advance or allocate");
        const auto old=fake.pending[0];bridge.stop();Bridge::Packet p;
        check(!bridge.submit(p) && bridge.state()==Bridge::State::Stopped,"stop suppresses queued submissions");
        check(!bridge.start(argv[1],rate,bits,endpoint,5001),"seek blocked until drain");
        auto other=endpoint;const unsigned b=bits==16?24:16,r=rate==96000?44100:96000;other.maxPacketBytes=b==16?388:582;
        check(!bridge.start(argv[1],r,b,other),"format replacement blocked until drain");
        fake.cancelRequests();check(fake.cancels==4 && !bridge.drained(),"cancel is not retirement");
        fake.cancelRequests();check(fake.cancels==4,"no duplicate cancellation work");
        fake.finish(true);check(bridge.drained(),"all callbacks drained");
        check(argc==10,"format replacement fixtures");
        const unsigned replacementRate=std::stoul(argv[8]),replacementBits=std::stoul(argv[9]);
        auto replacementEndpoint=endpoint;replacementEndpoint.maxPacketBytes=replacementBits==16?388:582;
        check(bridge.start(argv[6],replacementRate,replacementBits,replacementEndpoint),"different source format after drain");
        drain(bridge,argv[7],replacementRate,replacementBits,0,true,true);
        check(bridge.start(argv[1],rate,bits,endpoint,5001),"explicit seek after drain");
        check(ready(bridge)==Bridge::Step::Ready,"new generation packet ready");
        check(bridge.submit(p) && p.source!=old.source && p.ticket!=old.ticket && p.firstFrame==5001,"new source ticket and seek position");
        check(!bridge.complete(old.slot,old.ticket,Bridge::Completion::Done),"late old callback cannot retire reused slot");
        check(bridge.complete(p.slot,p.ticket,Bridge::Completion::Error),"submission failure callback");bridge.prepare();
        check(bridge.state()==Bridge::State::Failed && !bridge.md5Verified(),"transport failure distinct from clean End");
        bridge.stop();bridge.prepare();
        check(bridge.start(argv[1],rate,bits,endpoint,5001),"recover explicitly after failure");
        drain(bridge,argv[2],rate,bits,5001,true,false);
        for(auto bad:{Bridge::Endpoint{0x84,5,1,582,true},Bridge::Endpoint{4,3,1,582,true},Bridge::Endpoint{4,5,2,582,true},Bridge::Endpoint{4,5,1,1,true},Bridge::Endpoint{4,5,1,2048,true},Bridge::Endpoint{4,5,1,582,false},Bridge::Endpoint{4,0x15,1,582,true}})
            check(!bridge.start(argv[1],rate,bits,bad),"reject incompatible endpoint descriptors");
        check(!bridge.start(argv[1],192000,24,endpoint),"no unsupported source rate");
        std::cout<<"PASS: lifecycle/backpressure/drain/seek/descriptors\n";return 0;
    }
    const auto frames=drain(bridge,argv[2],rate,bits,0,mode!="error",mode!="unverified");
    std::cout<<"PASS: packet bridge "<<mode<<' '<<frames<<" frames\n";
}
