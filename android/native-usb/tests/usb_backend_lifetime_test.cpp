#include "../experimental/libusb_packet_adapter.hpp"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <malloc.h>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>

// Real pinned Android backend, no fake transfer allocator or event function.
// These executable-side wrappers delegate allowed operations and reject device
// calls before dispatch. NO_DEVICE_DISCOVERY also disables backend scanning.
void check(bool value,const char* message){if(!value){std::fprintf(stderr,"FAIL: %s\n",message);std::exit(1);}}
std::atomic<FILE*> sourceFile{nullptr};
std::atomic<unsigned> sourceReads{0},sourceOpens{0},sourceCloses{0};
unsigned contexts=0,contextOpens=0,contextCloses=0,transfers=0,allocations=0,frees=0;
FILE* trackSource(FILE* file) {
    if(file){check(!sourceFile.exchange(file),"only one real source file");++sourceOpens;}return file;
}
extern "C" {
int LIBUSB_CALL __real_libusb_init_context(libusb_context**,const libusb_init_option*,int);
void LIBUSB_CALL __real_libusb_exit(libusb_context*);
libusb_transfer* LIBUSB_CALL __real_libusb_alloc_transfer(int);
void LIBUSB_CALL __real_libusb_free_transfer(libusb_transfer*);
int LIBUSB_CALL __real_libusb_submit_transfer(libusb_transfer*);
int LIBUSB_CALL __real_libusb_cancel_transfer(libusb_transfer*);
int LIBUSB_CALL __wrap_libusb_init_context(libusb_context** ctx,const libusb_init_option* options,int count) {
    bool disabled=false;for(int i=0;i<count;++i)disabled=disabled || options[i].option==LIBUSB_OPTION_NO_DEVICE_DISCOVERY;
    check(ctx && disabled,"NO_DEVICE_DISCOVERY required before real backend initialization");
    check(!contexts && !transfers && !sourceFile.load(),"previous context and adapter resources retired");
    const int result=__real_libusb_init_context(ctx,options,count);
    if(result==0){++contexts;++contextOpens;}return result;
}
void LIBUSB_CALL __wrap_libusb_exit(libusb_context* ctx) {
    check(contexts==1 && transfers==0 && !sourceFile.load(),"adapter transfers and source must retire before context exit");
    __real_libusb_exit(ctx);--contexts;++contextCloses;
}
libusb_transfer* LIBUSB_CALL __wrap_libusb_alloc_transfer(int count) {
    check(contexts==1 && count==1 && transfers<8,"bounded real transfer pool inside live context");
    auto* transfer=__real_libusb_alloc_transfer(count);
    if(transfer){++transfers;++allocations;}return transfer;
}
void LIBUSB_CALL __wrap_libusb_free_transfer(libusb_transfer* transfer) {
    check(contexts==1 && transfers>0 && transfer,"real transfer free requires live context");
    __real_libusb_free_transfer(transfer);--transfers;++frees;
}
int LIBUSB_CALL __wrap_libusb_submit_transfer(libusb_transfer*){check(false,"device-free check must never submit a transfer");return LIBUSB_ERROR_OTHER;}
int LIBUSB_CALL __wrap_libusb_cancel_transfer(libusb_transfer*){check(false,"device-free check has no submitted transfers to cancel");return LIBUSB_ERROR_OTHER;}
int LIBUSB_CALL __wrap_libusb_open(libusb_device*,libusb_device_handle**){check(false,"device-free check must never open a device");return LIBUSB_ERROR_OTHER;}
int LIBUSB_CALL __wrap_libusb_wrap_sys_device(libusb_context*,intptr_t,libusb_device_handle**){check(false,"device-free check must never wrap a device fd");return LIBUSB_ERROR_OTHER;}
int LIBUSB_CALL __wrap_libusb_claim_interface(libusb_device_handle*,int){check(false,"device-free check must never claim an interface");return LIBUSB_ERROR_OTHER;}
FILE* __real_fopen(const char*,const char*);
FILE* __real_fdopen(int,const char*);
int __real_fclose(FILE*);
size_t __real_fread(void*,size_t,size_t,FILE*);
FILE* __wrap_fopen(const char* path,const char* mode) {
    FILE* file=__real_fopen(path,mode);const auto length=std::strlen(path);
    if(file && length>=5 && std::strcmp(path+length-5,".flac")==0) {
        trackSource(file);
    }
    return file;
}
FILE* __wrap_fdopen(int fd,const char* mode){return trackSource(__real_fdopen(fd,mode));}
int __wrap_fclose(FILE* file) {
    const bool source=file==sourceFile.load();const int result=__real_fclose(file);
    if(source){check(result==0,"source file closes successfully");sourceFile=nullptr;++sourceCloses;}return result;
}
size_t __wrap_fread(void* data,size_t size,size_t count,FILE* file) {
    const auto result=__real_fread(data,size,count,file);
    if(file==sourceFile.load())++sourceReads;
    return result;
}
}
unsigned entries(const char* path) {
    DIR* directory=opendir(path);check(directory,"open process resource directory");unsigned count=0;
    while(auto* entry=readdir(directory))if(std::strcmp(entry->d_name,".") && std::strcmp(entry->d_name,".."))++count;
    check(closedir(directory)==0,"close process resource directory");return count;
}
struct Sample {size_t heap,resident;unsigned fds,threads;};
Sample sample() {
    rusage usage{};check(getrusage(RUSAGE_SELF,&usage)==0,"resident high-water sample");
    return {mallinfo().uordblks,size_t(usage.ru_maxrss)*1024,entries("/proc/self/fd"),entries("/proc/self/task")};
}
void actualLibrary(void* address) {
    Dl_info info{};check(dladdr(address,&info)!=0 && std::strstr(info.dli_fname,"libusb-1.0.so"),"symbol resolves to real libusb shared backend");
}
int main(int argc,char** argv) {
    check(argc==4,"two generated sources and test mode");
    const auto* version=libusb_get_version();
    check(version->major==1 && version->minor==0 && version->micro==29,"actual pinned libusb version");
    actualLibrary(reinterpret_cast<void*>(&__real_libusb_init_context));
    actualLibrary(reinterpret_cast<void*>(&__real_libusb_alloc_transfer));
    actualLibrary(reinterpret_cast<void*>(&__real_libusb_free_transfer));
    actualLibrary(reinterpret_cast<void*>(&__real_libusb_submit_transfer));
    actualLibrary(reinterpret_cast<void*>(&__real_libusb_cancel_transfer));
    actualLibrary(reinterpret_cast<void*>(&libusb_handle_events_timeout_completed));
    const bool omit=std::strcmp(argv[3],"omit-discovery")==0,early=std::strcmp(argv[3],"early-exit")==0;
    check(omit || early || std::strcmp(argv[3],"normal")==0,"known test mode");
    Sample warm{},last{};const auto before=sample();unsigned fdSources=0;
    for(unsigned cycle=0;cycle<110;++cycle) {
        libusb_context* context=nullptr;
        const libusb_init_option option{LIBUSB_OPTION_NO_DEVICE_DISCOVERY,{0}};
        check(libusb_init_context(&context,omit?nullptr:&option,omit?0:1)==0 && context,"initialize real context without discovery");
        {
            LibusbPacketAdapter adapter(context,nullptr);
            check(transfers==8 && adapter.drained(),"real adapter transfer pool is ready");
            if(early)libusb_exit(context); // Guard fails BEFORE the invalid exit.
            const bool high=cycle%2;const unsigned rate=high?96000:44100,bits=high?24:16;
            const auto readBefore=sourceReads.load();
            if((cycle/2)%2) {
                const int fd=open(argv[high?2:1],O_RDONLY|O_CLOEXEC);check(fd>=0,"open generated descriptor source");
                check(adapter.startFd(fd,rate,bits,{4,5,1,high?582u:388u,true},cycle%3?5001:0),"start descriptor source with real backend");
                check(close(fd)==0,"caller descriptor closes immediately after acceptance");++fdSources;
            } else check(adapter.start(argv[high?2:1],rate,bits,{4,5,1,high?582u:388u,true},cycle%3?5001:0),"start generated source with real transfer pool");
            auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(10);
            while(sourceReads.load()==readBefore){check(std::chrono::steady_clock::now()<deadline,"source worker reaches real file read");std::this_thread::yield();}
            // Stop BEFORE first pump: no device handle or submit is needed.
            adapter.requestStop();adapter.pump();adapter.events();
            check(adapter.state()==LibusbPacketAdapter::State::Stopped && adapter.drained() && !adapter.error(),"source stop/join drains device-free adapter");
            check(!sourceFile.load() && sourceOpens==sourceCloses,"source worker closed before adapter/context teardown");
            timeval timeout{0,0};
            const int event=libusb_handle_events_timeout_completed(context,&timeout,nullptr);
            check(event==0 || event==LIBUSB_ERROR_INTERRUPTED,"real empty-context event dispatch");
        }
        check(transfers==0 && allocations==frees,"real transfer pool freed before context exit");
        libusb_exit(context);check(!contexts,"real context released");last=sample();
        check(last.fds==before.fds && last.threads==before.threads,"no retained file descriptors or threads after cycle");
        if(cycle==9)warm=last;
    }
    check(contextOpens==110 && contextCloses==110 && allocations==880 && frees==880 && sourceOpens==110 && sourceCloses==110,"exact backend/adapter/source lifetime counts");
    check(last.heap<64*1024*1024 && last.resident<64*1024*1024 && last.heap<=warm.heap+8*1024*1024 && last.resident<=warm.resident+8*1024*1024,"finite backend lifetime memory budget");
    std::printf("{\"status\":\"PASS\",\"contexts\":%u,\"transfersAllocated\":%u,\"transfersFreed\":%u,\"sourceOpens\":%u,\"sourceCloses\":%u,\"descriptorSources\":%u,\"heap10\":%zu,\"heap110\":%zu,\"resident10\":%zu,\"resident110\":%zu,\"fdsBefore\":%u,\"fdsAfter\":%u,\"threadsBefore\":%u,\"threadsAfter\":%u,\"deviceSubmissions\":0}\n",contextOpens,allocations,frees,sourceOpens.load(),sourceCloses.load(),fdSources,warm.heap,last.heap,warm.resident,last.resident,before.fds,last.fds,before.threads,last.threads);
}
