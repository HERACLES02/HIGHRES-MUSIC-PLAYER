// Opt-in .selftest APK only. Compile the ACTUAL JNI implementation against
// the already-verified fake packet transport. No real libusb dependency.
#include "../experimental/finite_fixture_stream.hpp"
#define main adapter_cli_main
#define libusb_handle_events_timeout_completed fixture_fake_events
#include "libusb_packet_adapter_test.cpp"
#undef libusb_handle_events_timeout_completed
#undef main
#ifdef interface
#undef interface
#endif
#include "../diagnostic.cpp"

namespace serviceFake {
int alternate=0,frequency=44100,volumes[2]={-5120,-5120},claims=0,mode=0;
unsigned contexts=0,closed=0,restored=0;
bool pace=true;
libusb_context* context=reinterpret_cast<libusb_context*>(uintptr_t(1));
libusb_device_handle* handle=reinterpret_cast<libusb_device_handle*>(uintptr_t(2));
libusb_device* device=reinterpret_cast<libusb_device*>(uintptr_t(3));
libusb_endpoint_descriptor endpoints[2]{};
libusb_interface_descriptor alternatives[2]{};
libusb_interface playbackInterface{};
libusb_config_descriptor config{};
unsigned char formatExtra[2][27]{};
void descriptors() {
    for(int i=0;i<2;++i) {
        endpoints[i]={};
        endpoints[i].bEndpointAddress=mode==5?5:4;
        endpoints[i].bmAttributes=5;endpoints[i].bInterval=1;
        endpoints[i].wMaxPacketSize=i?582:388;
        alternatives[i]={};alternatives[i].bInterfaceNumber=2;
        alternatives[i].bAlternateSetting=i+1;alternatives[i].bNumEndpoints=1;
        alternatives[i].endpoint=&endpoints[i];
        alternatives[i].bInterfaceClass=1;alternatives[i].bInterfaceSubClass=2;
        unsigned char extra[]={7,0x24,1,1,1,1,0,20,0x24,2,1,2,static_cast<unsigned char>(i?3:2),static_cast<unsigned char>(i?24:16),4,
            0x44,0xac,0,0x80,0xbb,0,0x88,0x58,1,0,0x77,1};
        std::memcpy(formatExtra[i],extra,sizeof(extra));
        alternatives[i].extra=formatExtra[i];alternatives[i].extra_length=sizeof(extra);
    }
    playbackInterface={};playbackInterface.num_altsetting=2;playbackInterface.altsetting=alternatives;
    config={};config.bNumInterfaces=1;config.interface=&playbackInterface;
}
void prepare(unsigned rate,unsigned bits,int referenceFd,bool wav,int selectedMode) {
    check(!liveStream.executing.load() && !fake::count,"previous service native job drained");
    fake::reset(rate,bits,"-");
    fake::pipe=false;fake::reference=fdopen(dup(referenceFd),"rb");
    check(fake::reference && std::fseek(fake::reference,wav?44:0,SEEK_SET)==0,"independent reference descriptor");
    fake::allocations=fake::frees=0;fake::externalStop=&liveStream.stop;
    fake::cancelMode=1; // queued successful completion after cancel/NOT_FOUND
    mode=selectedMode;
    if(mode==2)fake::failSubmit=5;
    if(mode==3)fake::fault=3;
    alternate=0;frequency=44100;volumes[0]=volumes[1]=-5120;claims=0;
    contexts=closed=restored=0;largestAllocation=0;descriptors();
}
}

extern "C" {
int LIBUSB_CALL libusb_handle_events_timeout_completed(libusb_context* ctx,timeval* timeout,int* done) {
    if(serviceFake::pace)std::this_thread::sleep_for(std::chrono::milliseconds(1));
    return fixture_fake_events(ctx,timeout,done);
}
int LIBUSB_CALL libusb_init_context(libusb_context** ctx,const libusb_init_option* options,int count) {
    check(count==1 && options[0].option==LIBUSB_OPTION_NO_DEVICE_DISCOVERY,"JNI never requests discovery");
    *ctx=serviceFake::context;++serviceFake::contexts;return 0;
}
void LIBUSB_CALL libusb_exit(libusb_context*) {
    check(!fake::count && !serviceFake::claims,"context exits after drain and claim release");
    ++serviceFake::closed;
}
int LIBUSB_CALL libusb_wrap_sys_device(libusb_context*,intptr_t fd,libusb_device_handle** out) {
    check(fd>=0,"borrowed fake USB file duplicated");*out=serviceFake::handle;return 0;
}
void LIBUSB_CALL libusb_close(libusb_device_handle*) {
    check(!fake::count && fake::allocations==fake::frees,"device closes after transfer retirement/free");
}
libusb_device* LIBUSB_CALL libusb_get_device(libusb_device_handle*) {return serviceFake::device;}
int LIBUSB_CALL libusb_get_device_descriptor(libusb_device*,libusb_device_descriptor* out) {
    *out={};out->idVendor=0x31b2;out->idProduct=0x0311;return 0;
}
int LIBUSB_CALL libusb_get_device_speed(libusb_device*) {return LIBUSB_SPEED_FULL;}
int LIBUSB_CALL libusb_get_configuration(libusb_device_handle*,int* out) {*out=1;return 0;}
int LIBUSB_CALL libusb_kernel_driver_active(libusb_device_handle*,int) {return 1;}
int LIBUSB_CALL libusb_set_auto_detach_kernel_driver(libusb_device_handle*,int) {return 0;}
int LIBUSB_CALL libusb_claim_interface(libusb_device_handle*,int number) {
    check(number==0 || number==2,"no capture/HID claims");
    if(number==2 && serviceFake::mode==4)return LIBUSB_ERROR_ACCESS;
    serviceFake::claims|=1<<number;return 0;
}
int LIBUSB_CALL libusb_release_interface(libusb_device_handle*,int number) {
    check(!fake::count,"release occurs after final callback");
    serviceFake::claims&=~(1<<number);return 0;
}
int LIBUSB_CALL libusb_set_interface_alt_setting(libusb_device_handle*,int number,int value) {
    check(!fake::count && number==2 && value>=0 && value<=2,"configure/restore only after drain");
    serviceFake::alternate=value;return 0;
}
int LIBUSB_CALL libusb_get_active_config_descriptor(libusb_device*,libusb_config_descriptor** out) {
    *out=&serviceFake::config;return 0;
}
void LIBUSB_CALL libusb_free_config_descriptor(libusb_config_descriptor*) {}
const char* LIBUSB_CALL libusb_error_name(int code) {return code<0?"SELFTEST_ERROR":"SUCCESS";}
int LIBUSB_CALL libusb_control_transfer(libusb_device_handle*,uint8_t type,uint8_t request,uint16_t value,
        uint16_t index,unsigned char* bytes,uint16_t length,unsigned int) {
    check(!inCallback && !fake::count,"control transfer outside callbacks/in-flight window");
    if(type==0x81 && request==10 && index==2 && length==1){bytes[0]=serviceFake::alternate;return 1;}
    if((type==0xa2 || type==0x22) && index==4 && value==0x100 && length==3) {
        if(type==0x22) {
            serviceFake::frequency=bytes[0]|bytes[1]<<8|bytes[2]<<16;
            if(serviceFake::mode==6)liveStream.stop=true;
            if(serviceFake::mode==7)return LIBUSB_ERROR_IO;
        }
        else for(int b=0;b<3;++b)bytes[b]=(serviceFake::frequency>>(8*b))&255;
        return 3;
    }
    const int channel=(value&255)-1;
    if(index==0x700 && (value>>8)==2 && channel>=0 && channel<2 && length==2) {
        int current=0;
        if(type==0x21 && request==1) {
            serviceFake::volumes[channel]=int16_t(bytes[0]|bytes[1]<<8);
            if(serviceFake::volumes[0]==-5120 && serviceFake::volumes[1]==-5120 &&
                    serviceFake::alternate==0 && serviceFake::frequency==44100)++serviceFake::restored;
            return 2;
        }
        if(type!=0xa1)return LIBUSB_ERROR_NOT_SUPPORTED;
        if(request==0x81)current=serviceFake::volumes[channel];
        else if(request==0x82)current=-96*256;
        else if(request==0x83)current=0;
        else if(request==0x84)current=256;
        else return LIBUSB_ERROR_NOT_SUPPORTED;
        bytes[0]=current&255;bytes[1]=(current>>8)&255;return 2;
    }
    return LIBUSB_ERROR_NOT_SUPPORTED;
}

JNIEXPORT void JNICALL Java_com_localmusic_diagnostics_SelfTestNative_prepare(JNIEnv*,jclass,jint fd,jint rate,jint bits,jint mode) {
    serviceFake::prepare(rate,bits,fd,true,mode);
}
JNIEXPORT void JNICALL Java_com_localmusic_diagnostics_SelfTestNative_release(JNIEnv*,jclass) {
    check(!liveStream.executing && !fake::count,"release reference after complete native drain");
    if(fake::reference)std::fclose(fake::reference);
    fake::reference=nullptr;
}
JNIEXPORT jstring JNICALL Java_com_localmusic_diagnostics_SelfTestNative_evidence(JNIEnv* env,jclass) {
    check(!liveStream.executing.load(),"read fake evidence after JNI return");
    std::ostringstream out;
    out<<"{\"simulatedUsb\":true,\"contexts\":"<<serviceFake::contexts<<",\"closed\":"<<serviceFake::closed
       <<",\"restored\":"<<serviceFake::restored<<",\"pending\":"<<fake::count
       <<",\"allocations\":"<<fake::allocations<<",\"frees\":"<<fake::frees<<",\"frames\":"<<fake::frames
       <<",\"largestAllocation\":"<<largestAllocation.load()<<"}";
    return env->NewStringUTF(out.str().c_str());
}
}
#include "source_session_jni.hpp"
namespace { std::atomic<uint64_t> directTestLease{0}; }
extern "C" JNIEXPORT jlong JNICALL Java_com_localmusic_usb_NativeDirectTest_open(JNIEnv*,jclass,jint fd) {
    // Controller checks use explicit pause/read barriers, not wall-clock USB
    // pacing. Scheduling and every emitted packet are still checked below.
    serviceFake::pace=false;
    const auto lease=productionSource::registry.open(fd);directTestLease=lease;return lease;
}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirectTest_armReads(JNIEnv*,jclass) {gate.arm();}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirectTest_waitReads(JNIEnv*,jclass) {gate.wait();}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirectTest_releaseReads(JNIEnv*,jclass) {gate.release();}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirectTest_stop(JNIEnv*,jclass) {productionSource::registry.stop(directTestLease);}
extern "C" JNIEXPORT jstring JNICALL Java_com_localmusic_usb_NativeDirectTest_snapshot(JNIEnv* env,jclass) {return env->NewStringUTF(productionSource::registry.snapshot(directTestLease).c_str());}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_usb_NativeDirectTest_referenceFrame(JNIEnv*,jclass,jlong frame,jint bits) {
    check(!fake::count && frame>=0 && frame<=576000 && (bits==16 || bits==24),"reference reposition after final old callback");
    check(fake::reference && !std::fseek(fake::reference,44+frame*(bits/8*2),SEEK_SET),"independent expected PCM suffix");
}
