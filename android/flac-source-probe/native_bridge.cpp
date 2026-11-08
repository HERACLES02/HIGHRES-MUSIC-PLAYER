#include "../native-usb/experimental/flac_source_owner.hpp"
#include <jni.h>
#include <cstring>
#include <cstdint>
#include <stdexcept>
#include <array>
#include <chrono>
#include <malloc.h>
#include <cstdio>
#include <thread>

namespace {
using Owner=FlacSourceOwner;
struct Context {Owner source;Owner::Block held;std::atomic<uint64_t> frames{0};};
Context& context(jlong handle){return *reinterpret_cast<Context*>(static_cast<uintptr_t>(handle));}
Owner& owner(jlong handle){return context(handle).source;}
void failure(JNIEnv* env,const char* text){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),text);}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_create(JNIEnv*,jclass) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(new Context));
}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_destroy(JNIEnv*,jclass,jlong handle){delete &context(handle);}
extern "C" JNIEXPORT jlong JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_start(JNIEnv* env,jclass,jlong handle,jint fd,jint rate,jint bits,jlong first) {
    try{context(handle).frames=0;return static_cast<jlong>(owner(handle).replaceFd(fd,rate,bits,static_cast<uint64_t>(first)));}
    catch(const std::exception& e){failure(env,e.what());return 0;}
}
extern "C" JNIEXPORT jint JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_take(JNIEnv* env,jclass,jlong handle,jlong source,jbyteArray reference,jint width,jlong first) {
    auto& block=context(handle).held;
    for(;;){auto result=owner(handle).pull(source,73,block);
        if(result.status==PcmFrameQueue::Read::NeedData){owner(handle).wait(result.event);continue;}
        if(result.status!=PcmFrameQueue::Read::Data)return -1;break;}
    const auto length=env->GetArrayLength(reference);size_t offset=static_cast<size_t>(first)*width;
    if(block.firstFrame()!=static_cast<uint64_t>(first) || block.bytes()>Owner::MAX_PULL_FRAMES*6 || offset>static_cast<size_t>(length) || block.bytes()>static_cast<size_t>(length)-offset)return -1;
    std::array<jbyte,Owner::MAX_PULL_FRAMES*6> expected{};
    env->GetByteArrayRegion(reference,static_cast<jsize>(offset),static_cast<jsize>(block.bytes()),expected.data());if(env->ExceptionCheck())return -1;
    bool equal=true;
    if(!owner(handle).deliver(block,[&](const unsigned char* data,size_t bytes){equal=std::memcmp(data,expected.data(),bytes)==0;}) || !equal)return -1;
    return static_cast<jint>(block.frames());
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_heldStale(JNIEnv*,jclass,jlong handle){
    bool called=false;bool accepted=owner(handle).deliver(context(handle).held,[&](const unsigned char*,size_t){called=true;});return !accepted && !called;
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_isStopped(JNIEnv*,jclass,jlong handle){return owner(handle).snapshot().status==Owner::Status::Stopped;}
extern "C" JNIEXPORT jlong JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_consumed(JNIEnv*,jclass,jlong handle){return static_cast<jlong>(context(handle).frames.load());}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_allocator(JNIEnv* env,jclass,jstring path){
    const char* text=env->GetStringUTFChars(path,nullptr);if(!text)return false;
    FILE* file=std::fopen(text,"w");env->ReleaseStringUTFChars(path,text);if(!file)return false;
    int result=malloc_info(0,file);int closed=std::fclose(file);return result==0 && closed==0;
}
extern "C" JNIEXPORT jlongArray JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_stream(JNIEnv* env,jclass,jlong handle,jlong source,jbyteArray pattern,jlong first,jlong wanted,jboolean paced){
    constexpr uint64_t RATE=96000,WIDTH=6,MAX_FRAMES=RATE*1200;
    const auto length=env->GetArrayLength(pattern);if(length<=0 || length>4096 || length%WIDTH || first<0 || wanted<0 || static_cast<uint64_t>(first)>MAX_FRAMES || static_cast<uint64_t>(wanted)>MAX_FRAMES-static_cast<uint64_t>(first)){failure(env,"invalid bounded pattern request");return nullptr;}
    std::array<jbyte,4096> expected{};env->GetByteArrayRegion(pattern,0,length,expected.data());if(env->ExceptionCheck())return nullptr;
    auto started=std::chrono::steady_clock::now();uint64_t frames=0;int terminal=2;bool ok=true;Owner::Block block;context(handle).frames=0;
    for(;;){auto result=owner(handle).pull(source,1024,block);
        if(result.status==PcmFrameQueue::Read::NeedData){owner(handle).wait(result.event);continue;}
        if(result.status==PcmFrameQueue::Read::End){terminal=0;break;}
        if(result.status==PcmFrameQueue::Read::Stale || result.status==PcmFrameQueue::Read::Stopped){terminal=1;break;}
        if(result.status!=PcmFrameQueue::Read::Data || block.firstFrame()!=static_cast<uint64_t>(first)+frames || block.frames()>static_cast<uint64_t>(wanted)-frames){ok=false;break;}
        const uint64_t offset=block.firstFrame()*WIDTH;
        if(!owner(handle).deliver(block,[&](const unsigned char* data,size_t bytes){for(size_t i=0;i<bytes;++i)if(data[i]!=static_cast<unsigned char>(expected[(offset+i)%length]))ok=false;})){terminal=1;break;}
        if(!ok)break;frames+=block.frames();context(handle).frames=frames;
        if(paced)std::this_thread::sleep_until(started+std::chrono::nanoseconds(frames*1000000000ULL/RATE));
    }
    auto state=owner(handle).snapshot();if(terminal==0 && (frames!=static_cast<uint64_t>(wanted) || !state.md5Verified))ok=false;
    jlong values[]{ok?terminal:2,static_cast<jlong>(frames),static_cast<jlong>(frames*WIDTH),static_cast<jlong>(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count())};
    jlongArray out=env->NewLongArray(4);if(out)env->SetLongArrayRegion(out,0,4,values);return out;
}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_stop(JNIEnv*,jclass,jlong handle){owner(handle).stop();}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_retired(JNIEnv*,jclass,jlong handle,jlong source) {
    Owner::Block block;
    for(;;){auto result=owner(handle).pull(source,73,block);
        if(result.status==PcmFrameQueue::Read::Stale)return true;
        if(result.status!=PcmFrameQueue::Read::NeedData)return false;
        owner(handle).wait(result.event);}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_expectFailed(JNIEnv*,jclass,jlong handle,jlong source) {
    Owner::Block block;
    for(;;){auto result=owner(handle).pull(source,73,block);
        if(result.status==PcmFrameQueue::Read::Failed)return owner(handle).snapshot().status==Owner::Status::Failed;
        if(result.status==PcmFrameQueue::Read::NeedData){owner(handle).wait(result.event);continue;}
        if(result.status!=PcmFrameQueue::Read::Data)return false;}
}
extern "C" JNIEXPORT jboolean JNICALL Java_com_localmusic_flacsourceprobe_MainActivity_drain(JNIEnv* env,jclass,jlong handle,jlong source,jbyteArray reference,jint width,jlong first,jboolean md5) {
    const auto length=env->GetArrayLength(reference);
    if(length<=0 || length>1024*1024 || width<4 || width>6)return false;
    jbyte* data=env->GetByteArrayElements(reference,nullptr);if(!data)return false;
    size_t offset=static_cast<size_t>(first)*static_cast<size_t>(width);bool ok=true;
    Owner::Block block;
    for(;;){auto result=owner(handle).pull(source,73,block);
        if(result.status==PcmFrameQueue::Read::NeedData){owner(handle).wait(result.event);continue;}
        if(result.status==PcmFrameQueue::Read::End)break;
        if(result.status!=PcmFrameQueue::Read::Data || offset>static_cast<size_t>(length) || block.bytes()>static_cast<size_t>(length)-offset){ok=false;break;}
        if(block.firstFrame()!=offset/static_cast<size_t>(width) || !owner(handle).deliver(block,[&](const unsigned char* pcm,size_t bytes){if(std::memcmp(pcm,data+offset,bytes)!=0)ok=false;})){ok=false;break;}
        offset+=block.bytes();if(!ok)break;
    }
    auto snapshot=owner(handle).snapshot();ok=ok && offset==static_cast<size_t>(length) && snapshot.status==Owner::Status::End && snapshot.md5Verified==bool(md5);
    env->ReleaseByteArrayElements(reference,data,JNI_ABORT);return ok;
}
