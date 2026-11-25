#pragma once
#include "../experimental/stream_source_session.hpp"
#include <mutex>

// Test-backend JNI only: actual session implementation, independent fake PCM
// references. These exports are absent from the physical USB library.
namespace sourceJni {
using Session=StreamSourceSession;
struct Owner {
    int reference=-1,pendingReference=-1;
    uint64_t offset=0,pendingOffset=0,issued=0;
    std::unique_ptr<Session> session;
    static void release(int& fd){if(fd>=0)closeFd(fd);fd=-1;}
    static int duplicate(int fd) {
        if(fd<0)return -1;
#ifdef _WIN32
        struct _stat64 info{};
        return !_fstat64(fd,&info) && (info.st_mode&_S_IFMT)==_S_IFREG?_dup(fd):-1;
#else
        struct stat info{};
        return !fstat(fd,&info) && S_ISREG(info.st_mode)?fcntl(fd,F_DUPFD_CLOEXEC,0):-1;
#endif
    }
    Owner() {
        session=std::make_unique<Session>(nullptr,nullptr,[this](const Session::Source& source) {
            check(!fake::count && !inCallback,"JNI format/reference replacement only after callbacks retire");
            if(pendingReference>=0){release(reference);reference=pendingReference;pendingReference=-1;offset=pendingOffset;}
            const uint64_t frameBytes=source.bits/8*2;
            if(reference<0 || source.first>(INT64_MAX-offset)/frameBytes)return false;
            fake::reset(source.rate,source.bits,"-");fake::pipe=false;
            fake::reference=fdopen(duplicate(reference),"rb");
            const uint64_t position=offset+source.first*frameBytes;
#ifdef _WIN32
            const bool positioned=fake::reference && !_fseeki64(fake::reference,position,SEEK_SET);
#else
            const bool positioned=fake::reference && !fseeko(fake::reference,position,SEEK_SET);
#endif
            fake::cancelMode=1; // NOT_FOUND followed by queued successful callback.
            return positioned;
        });
    }
    ~Owner() {
        session.reset(); // Stop/join/drain before any reference/file reuse.
        if(fake::reference)std::fclose(fake::reference);
        fake::reference=nullptr;release(reference);release(pendingReference);
    }
};
std::mutex mutex;
std::unique_ptr<Owner> owner;
uint64_t nextHandle=0,handle=0;
bool valid(jlong token){return token>0 && uint64_t(token)==handle && owner;}
std::string snapshot() {
    const auto& session=*owner->session;const auto stats=session.stats();
    static const char* phases[]={"idle","changing","running","end","stopped","failed","pausing","paused"};
    static const char* integrity[]={"unverified","partial","whole"};
    std::ostringstream out;
    out<<"{\"phase\":\""<<phases[int(session.state())]<<"\",\"integrity\":\""<<integrity[int(session.integrity())]
       <<"\",\"generation\":"<<session.generation()<<",\"submittedFrames\":"<<stats.submittedFrames
       <<",\"firstFrame\":"<<session.firstFrame()<<",\"completedFrames\":"<<stats.completedFrames<<",\"pending\":"<<stats.pending
       <<",\"starved\":"<<(session.starved()?"true":"false")<<"}";
    return out.str();
}
}
extern "C" {
JNIEXPORT jboolean JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_setPaused(JNIEnv*,jclass,jlong token,jlong generation,jboolean value) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(!sourceJni::valid(token) || generation<=0 || uint64_t(generation)!=sourceJni::owner->session->generation())return JNI_FALSE;
    return sourceJni::owner->session->setPaused(value==JNI_TRUE)?JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jlong JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_open(JNIEnv*,jclass) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(sourceJni::owner || liveStream.reserved.load() || liveStream.executing.load() || fake::count || sourceJni::nextHandle>=INT64_MAX)return 0;
    try {
        fake::allocations=fake::frees=0;serviceFake::mode=0;serviceFake::descriptors();
        sourceJni::owner=std::make_unique<sourceJni::Owner>();
        sourceJni::handle=++sourceJni::nextHandle;return sourceJni::handle;
    } catch(...) {return 0;}
}
JNIEXPORT jlong JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_replace(JNIEnv*,jclass,jlong token,jint fd,jint reference,jint rate,jint bits,jlong offset) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(!sourceJni::valid(token) || (offset!=0 && offset!=44) || (bits!=16 && bits!=24))return 0;
    auto& owner=*sourceJni::owner;
    if(owner.pendingReference>=0 || owner.issued>=INT64_MAX)return 0;
    const int copy=sourceJni::Owner::duplicate(reference);if(copy<0)return 0;
    const auto& ep=serviceFake::endpoints[bits==24];
    const auto accepted=owner.session->replaceFd(fd,rate,bits,{ep.bEndpointAddress,ep.bmAttributes,ep.bInterval,ep.wMaxPacketSize,true});
    if(!accepted){int rejected=copy;sourceJni::Owner::release(rejected);return 0;}
    owner.pendingReference=copy;owner.pendingOffset=offset;owner.issued=accepted;return accepted;
}
JNIEXPORT jlong JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_seek(JNIEnv*,jclass,jlong token,jlong generation,jlong frame) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(!sourceJni::valid(token) || generation<=0 || frame<0 || sourceJni::owner->issued>=INT64_MAX ||
            uint64_t(generation)!=sourceJni::owner->session->generation())return 0;
    const auto accepted=sourceJni::owner->session->seek(frame);
    if(accepted)sourceJni::owner->issued=accepted;
    return accepted;
}
JNIEXPORT void JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_stop(JNIEnv*,jclass,jlong token) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(sourceJni::valid(token))sourceJni::owner->session->requestStop();
}
JNIEXPORT jstring JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_step(JNIEnv* env,jclass,jlong token) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(!sourceJni::valid(token))return env->NewStringUTF("{\"stale\":true}");
    try {
        // Bounded batch on the IO worker; callback code remains unchanged.
        for(int i=0;i<8;++i)sourceJni::owner->session->step();
        return env->NewStringUTF(sourceJni::snapshot().c_str());
    } catch(...) {
        sourceJni::owner->session->requestStop();
        return env->NewStringUTF("{\"phase\":\"failed\",\"nativeException\":true}");
    }
}
JNIEXPORT jstring JNICALL Java_com_localmusic_diagnostics_SourceSessionNative_close(JNIEnv* env,jclass,jlong token) {
    std::lock_guard<std::mutex> lock(sourceJni::mutex);
    if(!sourceJni::valid(token))return env->NewStringUTF("{\"stale\":true}");
    sourceJni::owner.reset();sourceJni::handle=0;
    std::ostringstream out;
    out<<"{\"closed\":true,\"pending\":"<<fake::count<<",\"allocations\":"<<fake::allocations<<",\"frees\":"<<fake::frees<<"}";
    return env->NewStringUTF(out.str().c_str());
}
}
