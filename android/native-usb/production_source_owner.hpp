#pragma once
#include <mutex>
#include <memory>
#include <thread>

namespace productionSource {
#if defined(LOCALMUSIC_EXPERIMENTAL_DIRECT) && LOCALMUSIC_EXPERIMENTAL_DIRECT
// Explicit personal-build exception; this does not certify hardware behavior.
constexpr bool enabled=true;
#else
constexpr bool enabled=false;
#endif
inline void releaseFd(int& fd) {if(fd>=0)::close(fd);fd=-1;}
inline int copySource(int fd) {
#ifdef _WIN32
    struct _stat64 info{};
    return fd>=0 && !_fstat64(fd,&info) && (info.st_mode&_S_IFMT)==_S_IFREG?::dup(fd):-1;
#else
    struct stat info{};
    return fd>=0 && !fstat(fd,&info) && S_ISREG(info.st_mode)?fcntl(fd,F_DUPFD_CLOEXEC,0):-1;
#endif
}
// Registry locks protect identity only; worker and callbacks never acquire them.
// Source commands use try_lock and retain at most one incoming fd. Stop copies
// the shared owner then signals atomically; stale owners never reuse new state.
class Registry {
    struct Owner {
        int usb=-1,source=-1;
        std::mutex command;
        std::thread worker;
        StreamState state;
        std::atomic<bool> shutdown{false},finished{true},closed{false},changing{false},restored{true},whole{false};
        std::atomic<uint64_t> generation{0},first{0};
        unsigned rate=0,bits=0;
        void stop() {shutdown=true;state.stop=true;}
        void join() {state.stop=true;if(worker.joinable())worker.join();}
        ~Owner(){stop();join();releaseFd(source);releaseFd(usb);}
        uint64_t begin(int fd,unsigned hz,unsigned depth,uint64_t frame,bool play) {
            if(shutdown || generation>=INT64_MAX || frame>INT64_MAX || !StreamPacketBridge::supports(hz,depth,{4,5,1,582,true}))return 0;
            int retained=copySource(fd);if(retained<0)return 0;
            changing=true;join();
            if(shutdown || !restored){releaseFd(retained);changing=false;return 0;}
            releaseFd(source);source=retained;rate=hz;bits=depth;first=frame;
            state.reset();state.pause=!play;whole=false;finished=false;
            const auto token=++generation;
            if(shutdown){state.stop=true;finished=true;changing=false;return 0;}
            try {
                worker=std::thread([this] {
                    std::string result;
                    try {result=ordinarySourceBackend(usb,source,rate,bits,state,first.load());}
                    catch(...) {state.error=LIBUSB_ERROR_OTHER;state.phase=6;}
                    whole=result.find("\"wholeStreamMd5Verified\":true")!=std::string::npos;
                    const bool noWrites=result.find("\"volumeWriteAttempted\":false")!=std::string::npos &&
                        result.find("\"sampleRateWriteAttempted\":false")!=std::string::npos && result.find("\"alternateWriteAttempted\":false")!=std::string::npos;
                    restored=result.find("\"settingsRestored\":true")!=std::string::npos ||
                        result.find("\"usbOwnershipAttempted\":false")!=std::string::npos || noWrites;
                    // No growing report history; result dies with this job.
                    finished=true;
                });
            } catch(...) {state.error=LIBUSB_ERROR_NO_MEM;state.phase=6;finished=true;changing=false;return 0;}
            // A racing Stop cannot be erased by state.reset or start another job.
            if(shutdown)state.stop=true;
            changing=false;return token;
        }
    };
    std::mutex mutex;
    std::shared_ptr<Owner> owner;
    uint64_t serial=0,lease=0;
    static void diagnostics(std::ostream& out,const Owner& value) {
        out<<",\"packetErrors\":"<<value.state.packetErrors<<",\"transferErrors\":"<<value.state.transferErrors
           <<",\"queueEmptyDuringActive\":"<<value.state.queueEmpty<<",\"maxEventServiceGapUs\":"<<value.state.maxEventServiceGapUs
           <<",\"callbacks\":"<<value.state.callbacks<<",\"cancelledTransfers\":"<<value.state.cancelled
           <<",\"maxTransferSlots\":"<<StreamPacketBridge::SLOT_COUNT;
    }
    std::shared_ptr<Owner> get(uint64_t token) {
        std::lock_guard<std::mutex> lock(mutex);
        return token && token==lease?owner:nullptr;
    }
public:
    bool busy() {std::lock_guard<std::mutex> lock(mutex);return bool(owner);}
    uint64_t open(int fd) {
        std::lock_guard<std::mutex> lock(mutex);
        if(owner || fd<0 || serial>=INT64_MAX)return 0;
        bool available=false;if(!liveStream.reserved.compare_exchange_strong(available,true))return 0;
        try {
            auto created=std::make_shared<Owner>();created->usb=::dup(fd);
            if(created->usb<0){liveStream.reserved=false;return 0;}
            owner=std::move(created);lease=++serial;return lease;
        } catch(...) {liveStream.reserved=false;return 0;}
    }
    uint64_t start(uint64_t token,int fd,unsigned rate,unsigned bits,uint64_t first,bool play) {
        auto value=get(token);if(!value)return 0;
        std::unique_lock<std::mutex> command(value->command,std::try_to_lock);
        return command.owns_lock()?value->begin(fd,rate,bits,first,play):0;
    }
    uint64_t seek(uint64_t token,uint64_t generation,uint64_t first) {
        auto value=get(token);if(!value)return 0;
        std::unique_lock<std::mutex> command(value->command,std::try_to_lock);
        if(!command.owns_lock() || value->generation!=generation || !generation)return 0;
        return value->begin(value->source,value->rate,value->bits,first,!value->state.pause.load());
    }
    bool paused(uint64_t token,uint64_t generation,bool pause) {
        auto value=get(token);if(!value)return false;
        std::unique_lock<std::mutex> command(value->command,std::try_to_lock);
        if(!command.owns_lock() || value->shutdown || value->generation!=generation || !generation || value->finished)return false;
        value->state.pause=pause;return true;
    }
    void stop(uint64_t token) {auto value=get(token);if(value)value->stop();}
    std::string snapshot(uint64_t token) {
        auto value=get(token);if(!value)return "{\"stale\":true}";
        const bool done=value->finished.load();
        const auto phase=value->state.phase.load();
        const char* name=value->changing?"changing":value->closed?"closed":(value->state.error || (done && !value->restored))?"failed":
            value->shutdown?"stopping":!value->generation?"idle":done?"end":phase<=1?"opening":phase==3?"paused":phase>=4?"draining":"running";
        const char* integrity=done && !value->shutdown && !value->changing && !value->state.error && value->generation?(value->first?"partial":value->whole?"whole":"unverified"):"unverified";
        std::ostringstream out;out<<"{\"phase\":\""<<name<<"\",\"generation\":"<<value->generation
            <<",\"firstFrame\":"<<value->first<<",\"completedFrames\":"<<value->state.positionFrames
            <<",\"submittedFrames\":"<<value->state.submittedFrames<<",\"pending\":"<<value->state.pending
            <<",\"finished\":"<<(done?"true":"false")<<",\"restored\":"<<((done && value->restored)?"true":"false")
            <<",\"integrity\":\""<<integrity<<"\",\"error\":"<<value->state.error;
        diagnostics(out,*value);out<<"}";
        return out.str();
    }
    std::string close(uint64_t token) {
        auto value=get(token);if(!value)return "{\"stale\":true}";
        value->stop();
        std::lock_guard<std::mutex> command(value->command);value->join();
        releaseFd(value->source);releaseFd(value->usb);value->closed=true;
        const bool safe=value->restored.load();
        if(safe) {
            std::lock_guard<std::mutex> lock(mutex);
            if(owner==value){owner.reset();lease=0;liveStream.reserved=false;}
        }
        // Unknown restoration quarantines the owner/reservation until restart.
        std::ostringstream out;out<<"{\"closed\":true,\"retired\":true,\"pending\":0,\"restored\":"<<(safe?"true":"false")
            <<",\"generation\":"<<value->generation<<",\"firstFrame\":"<<value->first<<",\"completedFrames\":"<<value->state.positionFrames
            <<",\"submittedFrames\":"<<value->state.submittedFrames<<",\"error\":"<<value->state.error;
        diagnostics(out,*value);out<<"}";
        return out.str();
    }
};
Registry registry;
}

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_localmusic_usb_NativeDirect_available(JNIEnv*,jclass) {return productionSource::enabled?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jlong JNICALL Java_com_localmusic_usb_NativeDirect_open(JNIEnv*,jclass,jint usb) {
    if(!productionSource::enabled)return 0;
    try {return productionSource::registry.open(usb);} catch(...) {return 0;}
}
JNIEXPORT jlong JNICALL Java_com_localmusic_usb_NativeDirect_start(JNIEnv*,jclass,jlong lease,jint fd,jint rate,jint bits,jlong first,jboolean play) {
    if(lease<=0 || first<0)return 0;
    try {return productionSource::registry.start(lease,fd,rate,bits,first,play==JNI_TRUE);} catch(...) {return 0;}
}
JNIEXPORT jlong JNICALL Java_com_localmusic_usb_NativeDirect_seek(JNIEnv*,jclass,jlong lease,jlong generation,jlong frame) {
    if(lease<=0 || generation<=0 || frame<0)return 0;
    try {return productionSource::registry.seek(lease,generation,frame);} catch(...) {return 0;}
}
JNIEXPORT jboolean JNICALL Java_com_localmusic_usb_NativeDirect_paused(JNIEnv*,jclass,jlong lease,jlong generation,jboolean value) {
    if(lease<=0 || generation<=0)return JNI_FALSE;
    try {return productionSource::registry.paused(lease,generation,value==JNI_TRUE)?JNI_TRUE:JNI_FALSE;} catch(...) {return JNI_FALSE;}
}
JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirect_stop(JNIEnv*,jclass,jlong lease) {
    if(lease>0)productionSource::registry.stop(lease);
}
JNIEXPORT jstring JNICALL Java_com_localmusic_usb_NativeDirect_snapshot(JNIEnv* env,jclass,jlong lease) {
    try {return env->NewStringUTF(productionSource::registry.snapshot(lease).c_str());}
    catch(...) {return env->NewStringUTF("{\"phase\":\"failed\",\"error\":-99}");}
}
JNIEXPORT jstring JNICALL Java_com_localmusic_usb_NativeDirect_close(JNIEnv* env,jclass,jlong lease) {
    try {return env->NewStringUTF(productionSource::registry.close(lease).c_str());}
    catch(...) {return env->NewStringUTF("{\"closed\":false,\"restored\":false}");}
}
}
