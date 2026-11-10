#ifndef USB_HOST_TEST
#include <jni.h>
#endif
#include <libusb.h>
#include <unistd.h>
#include <cerrno>
#include <sstream>
#include <string>
#include <functional>
#ifndef USB_HOST_TEST
#include "pcm_decode.hpp"
#include "stream_transport.hpp"
#include "experimental/finite_fixture_stream.hpp"
#include "experimental/ordinary_source_stream.hpp"
#endif

namespace {
// Close libusb before closing our dup. The Java connection retains its own fd.
struct Resources {
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    int fd = -1;
    ~Resources() {
        if (handle) libusb_close(handle);
        if (context) libusb_exit(context);
        if (fd >= 0) close(fd);
    }
};
struct Report {
    std::ostringstream rows;
    bool first = true;
    void result(const char* operation, int result, int observedErrno = 0,
                const unsigned char* bytes = nullptr, int size = 0) {
        if (!first) rows << ',';
        first = false;
        rows << "{\"operation\":\"" << operation << "\",\"result\":" << result
             << ",\"error\":\"" << (result < 0 ? libusb_error_name(result) : "SUCCESS")
             << "\",\"errnoAfterCall\":" << observedErrno;
        if (bytes && result == size) {
            static const char hex[] = "0123456789abcdef";
            rows << ",\"dataHex\":\"";
            for (int i = 0; i < size; ++i) rows << hex[bytes[i] >> 4] << hex[bytes[i] & 15];
            rows << '"';
            if (size == 2) {
                const auto value = static_cast<int16_t>(bytes[0] | (bytes[1] << 8));
                rows << ",\"rawSigned256thDb\":" << value;
            }
            if (size == 3) rows << ",\"rateHz\":" << (bytes[0] | bytes[1] << 8 | bytes[2] << 16);
        }
        rows << '}';
    }
};
void volumeReads(libusb_device_handle* handle, Report& report, const char* phase) {
    for (int channel = 1; channel <= 2; ++channel) {
        for (int attribute = 1; attribute <= 4; ++attribute) {
            unsigned char bytes[2] = {};
            const std::string operation = std::string(phase) + "_volume_ch" + std::to_string(channel) +
                                          "_get_" + std::to_string(attribute);
            errno = 0;
            const int result = libusb_control_transfer(handle, 0xa1, 0x80 | attribute,
                                                       0x0200 | channel, 0x0700, bytes, 2, 500);
            report.result(operation.c_str(), result, errno, bytes, 2);
        }
    }
}
#include "control_test.hpp"
struct PlaybackResult { std::string json; bool submitted=false; };
using PlaybackHook=std::function<PlaybackResult(libusb_context*,libusb_device_handle*,ControlTest&)>;
std::string inspect(int javaFd, bool allowDetach, bool testControls = false, const PlaybackHook* playbackHook=nullptr) {
    Report report;
    Resources resources;
    int speed = 0;
    bool acClaimed = false, playbackClaimed = false;
    bool volumeAttempted = false, rateAttempted = false, alternateAttempted = false;
    bool lowVerified = false, matrixPassed = false, settingsRestored = false;
    PlaybackResult playbackResult{"null",false};
    // RAII releases any claims even if a C++ exception interrupts report generation.
    struct Claims {
        Resources& resources;
        bool& ac;
        bool& playback;
        ~Claims() {
            if (playback) libusb_release_interface(resources.handle, 2);
            if (ac) libusb_release_interface(resources.handle, 0);
        }
    } claims{resources, acClaimed, playbackClaimed};
    do {
        errno = 0;
        resources.fd = dup(javaFd);
        if (resources.fd < 0) { report.result("dup_fd", LIBUSB_ERROR_IO, errno); break; }
        const libusb_init_option option{LIBUSB_OPTION_NO_DEVICE_DISCOVERY, {0}};
        const int init = libusb_init_context(&resources.context, &option, 1);
        report.result("init_no_discovery", init);
        if (init < 0) break;
        const int wrap = libusb_wrap_sys_device(resources.context, resources.fd, &resources.handle);
        report.result("wrap_android_fd", wrap);
        if (wrap < 0) break;
        libusb_device* device = libusb_get_device(resources.handle);
        libusb_device_descriptor descriptor{};
        const int read = libusb_get_device_descriptor(device, &descriptor);
        report.result("device_descriptor", read);
        if (read < 0 || descriptor.idVendor != 0x31b2 || descriptor.idProduct != 0x0311) {
            report.result("m2_identity_guard", LIBUSB_ERROR_NOT_SUPPORTED); break;
        }
        speed = libusb_get_device_speed(device);
        int configuration = -1;
        const int config = libusb_get_configuration(resources.handle, &configuration);
        report.result("get_configuration", config);
        if (config < 0 || configuration != 1) {
            report.result("configuration_1_guard", LIBUSB_ERROR_NOT_SUPPORTED); break;
        }
        report.result("kernel_driver_control_before", libusb_kernel_driver_active(resources.handle, 0));
        report.result("kernel_driver_playback_before", libusb_kernel_driver_active(resources.handle, 2));
        volumeReads(resources.handle, report, "unclaimed");
        // Scoped auto-detach reattaches on release. No direct detach calls, capture or HID claims.
        const int autoDetach = libusb_set_auto_detach_kernel_driver(resources.handle, allowDetach ? 1 : 0);
        report.result("configure_auto_detach", autoDetach);
        if (autoDetach < 0) break;
        const int control = libusb_claim_interface(resources.handle, 0);
        report.result("claim_control_0", control);
        acClaimed = control == 0;
        if (acClaimed) volumeReads(resources.handle, report, "claimed");
        const int playback = libusb_claim_interface(resources.handle, 2);
        report.result("claim_playback_2", playback);
        playbackClaimed = playback == 0;
        if (playbackClaimed) {
            unsigned char alternate = 0;
            errno = 0;
            const int alt = libusb_control_transfer(resources.handle, 0x81, 10, 0, 2, &alternate, 1, 500);
            report.result("get_playback_alternate", alt, errno, &alternate, 1);
            unsigned char rate[3] = {};
            errno = 0;
            const int readRate = libusb_control_transfer(resources.handle, 0xa2, 0x81, 0x0100, 4, rate, 3, 500);
            report.result("get_endpoint_sample_rate", readRate, errno, rate, 3);
            if ((testControls || playbackHook) && acClaimed && speed == LIBUSB_SPEED_FULL) {
                ControlTest controls{resources.handle, report};
                controls.run(playbackHook==nullptr);
                if(playbackHook && controls.lowVerified) playbackResult=(*playbackHook)(resources.context,resources.handle,controls);
                controls.finish();
                volumeAttempted = controls.volumeTouched[0] || controls.volumeTouched[1];
                rateAttempted = controls.rateTouched; alternateAttempted = controls.altTouched;
                lowVerified = controls.lowVerified; matrixPassed = controls.matrixPassed;
                settingsRestored = controls.restored;
            } else if (testControls || playbackHook) report.result("control_test_ownership_speed_guard", LIBUSB_ERROR_NOT_SUPPORTED);
        }
    } while (false);
    if (playbackClaimed) {
        const int released = libusb_release_interface(resources.handle, 2);
        if (released == 0) playbackClaimed = false;
        report.result("release_playback_2", released);
    }
    if (acClaimed) {
        const int released = libusb_release_interface(resources.handle, 0);
        if (released == 0) acClaimed = false;
        report.result("release_control_0", released);
    }
    if (resources.handle) {
        report.result("kernel_driver_control_after", libusb_kernel_driver_active(resources.handle, 0));
        report.result("kernel_driver_playback_after", libusb_kernel_driver_active(resources.handle, 2));
    }
    std::ostringstream output;
    output << "{\"libusbVersion\":\"1.0.29\",\"negotiatedSpeedEnum\":" << speed
           << ",\"allowTemporaryDriverDetach\":" << (allowDetach ? "true" : "false")
           << ",\"controlTestRequested\":" << (testControls ? "true" : "false")
           << ",\"volumeWriteAttempted\":" << (volumeAttempted ? "true" : "false")
           << ",\"sampleRateWriteAttempted\":" << (rateAttempted ? "true" : "false")
           << ",\"alternateWriteAttempted\":" << (alternateAttempted ? "true" : "false")
           << ",\"lowVolumeControlVerified\":" << (lowVerified ? "true" : "false")
           << ",\"configurationMatrixPassed\":" << (matrixPassed ? "true" : "false")
           << ",\"settingsRestored\":" << (settingsRestored ? "true" : "false")
           << ",\"streamExperiment\":" << playbackResult.json
           << ",\"operations\":[" << report.rows.str() << "],\"audioSubmitted\":" << (playbackResult.submitted?"true":"false") << ",\"captureOrHidClaimed\":false,"
           << "\"clockingValidated\":false,\"safeHardwareVolumeEstablished\":false,"
           << "\"errnoNote\":\"errnoAfterCall is supplementary; libusb return codes are authoritative. Positive kernel-driver results mean active, not a transfer byte count.\"}";
    return output.str();
}
}
#ifndef USB_HOST_TEST
namespace { StreamState liveStream; }
namespace {
// No setter: opening the physical entry requires a later reviewed hardware gate.
const std::atomic<bool> ordinarySourceHardwareReady{false};
StreamPacketBridge::Endpoint ordinaryEndpoint(libusb_device_handle* dev,unsigned rate,unsigned bits) {
    if((bits!=16 && bits!=24) || libusb_get_device_speed(libusb_get_device(dev))!=LIBUSB_SPEED_FULL)
        throw std::runtime_error("Unsupported source/device speed");
    libusb_config_descriptor* raw=nullptr;
    if(libusb_get_active_config_descriptor(libusb_get_device(dev),&raw)<0 || !raw)
        throw std::runtime_error("No active playback descriptors");
    std::unique_ptr<libusb_config_descriptor,decltype(&libusb_free_config_descriptor)> config(raw,&libusb_free_config_descriptor);
    StreamPacketBridge::Endpoint result{};
    for(int i=0;i<config->bNumInterfaces;++i)for(int a=0;a<config->interface[i].num_altsetting;++a) {
        const auto& alt=config->interface[i].altsetting[a];
        if(alt.bInterfaceNumber!=2 || alt.bAlternateSetting!=(bits==16?1:2))continue;
        if(result.address || alt.bInterfaceClass!=1 || alt.bInterfaceSubClass!=2 || alt.bNumEndpoints!=1 ||
                !alt.endpoint || alt.extra_length<0 || alt.extra_length>512 || !alt.extra)
            throw std::runtime_error("Unexpected playback alternate");
        bool pcm=false,format=false;
        for(int offset=0;offset<alt.extra_length;) {
            const auto* p=alt.extra+offset;const int length=p[0];
            if(length<3 || length>alt.extra_length-offset)throw std::runtime_error("Malformed playback descriptor");
            if(p[1]==0x24 && p[2]==1) {
                if(pcm || length!=7 || p[5]!=1 || p[6]!=0)throw std::runtime_error("Playback is not PCM");
                pcm=true;
            }
            if(p[1]==0x24 && p[2]==2) {
                if(format || length<8 || p[3]!=1 || p[4]!=2 || p[5]!=bits/8 || p[6]!=bits ||
                        !p[7] || length!=8+3*p[7])throw std::runtime_error("Unsupported playback format");
                for(int n=0;n<p[7];++n)if(unsigned(p[8+3*n]|p[9+3*n]<<8|p[10+3*n]<<16)==rate)format=true;
                if(!format)throw std::runtime_error("Source rate absent from descriptor");
            }
            offset+=length;
        }
        const auto& ep=alt.endpoint[0];
        result={ep.bEndpointAddress,ep.bmAttributes,ep.bInterval,ep.wMaxPacketSize,true};
        if(!pcm || !format || ep.bEndpointAddress!=4 || ep.bmAttributes!=5 || ep.bSynchAddress!=0 ||
                !StreamPacketBridge::supports(rate,bits,result))throw std::runtime_error("Unsupported playback endpoint");
    }
    if(!result.address)throw std::runtime_error("Playback alternate absent");
    return result;
}
std::string ordinarySourceBackend(int usbFd,int sourceFd,unsigned rate,unsigned bits,StreamState& state,uint64_t first=0) {
    if(state.stop){state.phase=5;return "{\"terminal\":\"cancelled\",\"usbOwnershipAttempted\":false,\"audioSubmitted\":false}";}
#ifdef _WIN32
    struct _stat64 sourceInfo{};
    const bool wholeFile=sourceFd>=0 && !_fstat64(sourceFd,&sourceInfo) && (sourceInfo.st_mode&_S_IFMT)==_S_IFREG;
#else
    struct stat sourceInfo{};
    const bool wholeFile=sourceFd>=0 && !fstat(sourceFd,&sourceInfo) && S_ISREG(sourceInfo.st_mode);
#endif
    if(!wholeFile || !StreamPacketBridge::supports(rate,bits,{4,5,1,582,true})) {
        state.error=LIBUSB_ERROR_INVALID_PARAM;state.phase=6;
        return "{\"terminal\":\"failed\",\"usbOwnershipAttempted\":false,\"audioSubmitted\":false}";
    }
    bool entered=false;
    PlaybackHook hook=[&](libusb_context* ctx,libusb_device_handle* dev,ControlTest& controls) {
        entered=true;
        const auto endpoint=ordinaryEndpoint(dev,rate,bits);
        bool configured=false;
        const auto outcome=runOrdinarySourceStream(ctx,dev,sourceFd,rate,bits,endpoint,state,[&](const StreamSourceSession::Source&) {
            if(state.stop)return false;
            if(configured)return true;
            configured=controls.alternateSet("source_format",bits==16?1:2) && controls.rateSet("source_rate",rate) && controls.lowStillSet("source_before_audio");
            state.levelRaw1=controls.target[0];state.levelRaw2=controls.target[1];return configured;
        },first);
        const char* terminal=state.error?"failed":outcome.terminal==OrdinarySourceResult::Terminal::End?"eof":
            outcome.terminal==OrdinarySourceResult::Terminal::Stopped?"cancelled":"failed";
        state.phase=state.error?6:5;
        std::ostringstream out;
        out<<"{\"route\":\"ordinary_streaming_flac\",\"terminal\":\""<<terminal<<"\",\"wholeStreamMd5Verified\":"<<(outcome.wholeMd5?"true":"false")
           <<",\"sourceGeneration\":"<<outcome.generation<<",\"sampleRateHz\":"<<rate<<",\"bits\":"<<bits<<",\"stats\":"<<state.json()
           <<",\"clockingValidated\":false,\"positionMeaning\":\"Completed USB frames, not measured DAC render time\"}";
        return PlaybackResult{out.str(),state.submittedBytes>0};
    };
    try {
        auto report=inspect(usbFd,true,false,&hook);
        if(!entered) {
            state.error=LIBUSB_ERROR_NOT_SUPPORTED;state.phase=6;
            return "{\"terminal\":\"failed\",\"usbReport\":"+report+",\"stats\":"+state.json()+"}";
        }
        return report;
    }
    catch(...) {
        state.error=LIBUSB_ERROR_OTHER;state.phase=6;
        return std::string("{\"terminal\":\"failed\",\"nativeException\":true,\"restorationUnknown\":true,\"audioSubmitted\":")+
            (state.submittedBytes>0?"true":"false")+",\"stats\":"+state.json()+"}";
    }
}
}
#include "production_source_owner.hpp"
extern "C" JNIEXPORT jstring JNICALL
Java_com_localmusic_diagnostics_NativeUsb_runOrdinarySource(JNIEnv* env,jclass,jint usbFd,jint sourceFd,jint rate,jint bits) {
    if(!ordinarySourceHardwareReady.load())return env->NewStringUTF("{\"hardwareGateClosed\":true,\"usbOwnershipAttempted\":false,\"audioSubmitted\":false}");
    // A generational production JNI/service owner must replace this closed entry
    // before activation. Do not reuse the finite fixture command namespace.
    if(!liveStream.reserved || !liveStream.executing)return env->NewStringUTF("{\"ownerRequired\":true,\"audioSubmitted\":false}");
    try {return env->NewStringUTF(ordinarySourceBackend(usbFd,sourceFd,rate,bits,liveStream).c_str());}
    catch(...) {return env->NewStringUTF("{\"nativeException\":true,\"restorationUnknown\":true}");}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localmusic_diagnostics_NativeUsb_inspect(JNIEnv* env, jclass, jint fd, jboolean detach, jboolean controls) {
    if(productionSource::registry.busy())return env->NewStringUTF("{\"productionOwnerActive\":true,\"audioSubmitted\":false}");
    try { return env->NewStringUTF(inspect(fd, detach == JNI_TRUE, controls == JNI_TRUE).c_str()); }
    catch (...) { return env->NewStringUTF("{\"nativeException\":true,\"audioSubmitted\":false}"); }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_localmusic_diagnostics_NativeUsb_beginStream(JNIEnv*,jclass) {
    bool expected=false;
    if(!liveStream.reserved.compare_exchange_strong(expected,true)) return JNI_FALSE;
    liveStream.reset(); return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_localmusic_diagnostics_NativeUsb_streamCommand(JNIEnv*,jclass,jint command,jint value) {
    if(productionSource::registry.busy())return;
    if(liveStream.finiteSource && command!=0 && command!=4)return;
    if(command==0) liveStream.stop=true;
    else if(command==1) liveStream.pause=value!=0;
    else if(command==2 && value>=0 && value<=576000) liveStream.seek=value;
    else if(command==3 && (value==-40 || value==-30 || value==-20)) { liveStream.volumeDb=value; ++liveStream.volumeCommand; }
    else if(command==4) { liveStream.stop=true; if(!liveStream.executing) liveStream.reserved=false; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localmusic_diagnostics_NativeUsb_runStreamingFixture(JNIEnv* env,jclass,jint usbFd,jint sourceFd,jint rate,jint bits) {
    if(productionSource::registry.busy())return env->NewStringUTF("{\"productionOwnerActive\":true,\"audioSubmitted\":false}");
    if(!liveStream.reserved)return env->NewStringUTF("{\"notReserved\":true}");
    bool expected=false;
    if(!liveStream.executing.compare_exchange_strong(expected,true))return env->NewStringUTF("{\"alreadyExecuting\":true}");
    struct Reservation {
        ~Reservation(){liveStream.executing=false;liveStream.reserved=false;liveStream.phase=liveStream.error?6:5;}
    } reservation;
    liveStream.finiteSource=true;
    try {
        // Java keeps a verified, private, unchanged whole-file fd alive until
        // this call returns. No decoder/FILE work runs on a USB callback.
        const auto verified=validateQuietFlacFixture(sourceFd,rate,bits,liveStream.stop);
        const char* names[]={"valid","cancelled","invalid_input","invalid_format","source_failed","wrong_length","too_loud","unverified"};
        std::ostringstream preflight;
        preflight<<"{\"status\":\""<<names[int(verified.status)]<<"\",\"frames\":"<<verified.frames
                 <<",\"wholeStreamMd5Verified\":"<<(verified.md5Verified?"true":"false")<<"}";
        if(verified.status!=QuietFlacFixture::Status::Valid || liveStream.stop) {
            if(verified.status!=QuietFlacFixture::Status::Cancelled && !liveStream.stop)liveStream.error=LIBUSB_ERROR_INVALID_PARAM;
            return env->NewStringUTF(("{\"audioSubmitted\":false,\"usbOwnershipAttempted\":false,\"sourcePreflight\":"+preflight.str()+"}").c_str());
        }
        PlaybackHook hook=[&](libusb_context* ctx,libusb_device_handle* dev,ControlTest& controls) {
            // Decode the selected alternate's ACTUAL endpoint descriptor. Java
            // also pins the complete recorded raw layout before entering JNI.
            libusb_config_descriptor* raw=nullptr;
            if(libusb_get_active_config_descriptor(libusb_get_device(dev),&raw)<0)throw std::runtime_error("No active endpoint descriptors");
            std::unique_ptr<libusb_config_descriptor,decltype(&libusb_free_config_descriptor)> config(raw,&libusb_free_config_descriptor);
            StreamPacketBridge::Endpoint endpoint{};
            for(int i=0;i<config->bNumInterfaces;++i)for(int a=0;a<config->interface[i].num_altsetting;++a) {
                const auto& alt=config->interface[i].altsetting[a];
                if(alt.bInterfaceNumber!=2 || alt.bAlternateSetting!=(bits==16?1:2))continue;
                if(alt.bNumEndpoints!=1)throw std::runtime_error("Unexpected playback endpoints");
                const auto& ep=alt.endpoint[0];
                if(ep.bEndpointAddress!=4 || ep.bmAttributes!=5 || ep.bInterval!=1 || ep.wMaxPacketSize!=(bits==16?388:582))
                    throw std::runtime_error("Playback descriptor differs from recorded M2");
                endpoint={ep.bEndpointAddress,ep.bmAttributes,ep.bInterval,ep.wMaxPacketSize,true};
            }
            if(!endpoint.address)throw std::runtime_error("Playback alternate absent");
            const auto outcome=runFiniteFixtureStream(ctx,dev,sourceFd,rate,bits,endpoint,verified,liveStream,[&] {
                if(liveStream.stop)return false;
                if(!controls.alternateSet("finite_stream_format",bits==16?1:2) || !controls.rateSet("finite_stream_rate",rate))return false;
                if(!controls.lowStillSet("finite_before_audio") || liveStream.stop)return false;
                liveStream.levelRaw1=controls.target[0];liveStream.levelRaw2=controls.target[1];return true;
            });
            // Adapter transfers are drained/destroyed BEFORE inspect() restores
            // controls, releases claims and closes the borrowed context.
            const char* terminal=liveStream.error?"failed":outcome.terminal==FiniteFixtureResult::Terminal::End?"eof":
                outcome.terminal==FiniteFixtureResult::Terminal::Stopped?"cancelled":"failed";
            liveStream.phase=liveStream.error?6:5;
            std::ostringstream report;
            report<<"{\"route\":\"finite_streaming_flac\",\"terminal\":\""<<terminal<<"\",\"sourcePreflight\":"<<preflight.str()
                  <<",\"wholeStreamMd5Verified\":"<<(outcome.md5Verified?"true":"false")<<",\"sampleRateHz\":"<<rate<<",\"bits\":"<<bits
                  <<",\"durationSecondsRequested\":6,\"endpointInterval\":"<<endpoint.interval<<",\"maximumPacketBytes\":"<<endpoint.maxPacketBytes
                  <<",\"positionMeaning\":\"Fully completed frame count, not DAC render time\",\"volumeVerification\":\"Startup readback only; no in-flight control transfers\","
                  <<"\"clocking\":\"Experimental nominal 1000 intervals/s, no feedback or shared capture clock assumed\",\"stats\":"<<liveStream.json()<<"}";
            return PlaybackResult{report.str(),liveStream.submittedBytes>0};
        };
        if(liveStream.stop)return env->NewStringUTF("{\"cancelledBeforeUsb\":true,\"usbOwnershipAttempted\":false,\"audioSubmitted\":false}");
        return env->NewStringUTF(inspect(usbFd,true,false,&hook).c_str());
    } catch(...) {
        liveStream.error=LIBUSB_ERROR_OTHER;liveStream.phase=6;
        return env->NewStringUTF((std::string("{\"nativeException\":true,\"restorationUnknown\":true,\"audioSubmitted\":")+
            (liveStream.submittedBytes>0?"true":"false")+",\"stats\":"+liveStream.json()+"}").c_str());
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localmusic_diagnostics_NativeUsb_streamStats(JNIEnv* env,jclass) { return env->NewStringUTF(liveStream.json().c_str()); }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_localmusic_diagnostics_NativeUsb_decodePcm(JNIEnv* env,jclass,jbyteArray input,jint rate,jint bits) {
    try {
        const auto size=env->GetArrayLength(input);
        if(size<12 || size>8*1024*1024) throw std::runtime_error("Fixture size rejected");
        std::vector<unsigned char> encoded(size); env->GetByteArrayRegion(input,0,size,reinterpret_cast<jbyte*>(encoded.data()));
        auto pcm=decodeFixture(encoded);
        if(pcm.rate!=rate || pcm.bits!=bits) throw std::runtime_error("Fixture metadata mismatch");
        auto output=env->NewByteArray(pcm.bytes.size());
        if(output) env->SetByteArrayRegion(output,0,pcm.bytes.size(),reinterpret_cast<const jbyte*>(pcm.bytes.data()));
        return output;
    } catch(const std::exception& error) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),error.what()); return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_localmusic_diagnostics_NativeUsb_runStream(JNIEnv* env,jclass,jint fd,jbyteArray pcm,jint rate,jint bits,jint seconds) {
    if(productionSource::registry.busy())return env->NewStringUTF("{\"productionOwnerActive\":true,\"audioSubmitted\":false}");
    if(!liveStream.reserved) return env->NewStringUTF("{\"notReserved\":true}");
    bool expected=false;
    if(!liveStream.executing.compare_exchange_strong(expected,true)) return env->NewStringUTF("{\"alreadyExecuting\":true}");
    struct Reservation { ~Reservation() { liveStream.executing=false; liveStream.reserved=false; liveStream.phase=liveStream.error?6:5; } } reservation;
    try {
        if(!liveStream.reserved) throw std::runtime_error("Stream not reserved");
        const int size=env->GetArrayLength(pcm);
        if(size<=0 || size>8*1024*1024) throw std::runtime_error("PCM size rejected");
        std::vector<unsigned char> bytes(size); env->GetByteArrayRegion(pcm,0,size,reinterpret_cast<jbyte*>(bytes.data()));
        PacketPcm validator(bytes,rate,bits);
        for(size_t p=0;p<bytes.size();p+=bits/8) {
            int32_t value=0; for(int b=0;b<bits/8;++b) value|=int32_t(bytes[p+b])<<(8*b);
            if(value&(1<<(bits-1))) value-=1<<bits;
            if(std::abs(int64_t(value))>((int64_t(1)<<(bits-1))-1)*8/1000) throw std::runtime_error("PCM exceeds quiet-fixture ceiling");
        }
        PlaybackHook hook=[&](libusb_context* ctx,libusb_device_handle* dev,ControlTest& controls) {
            const auto start=std::chrono::steady_clock::now();
            auto configure=[&](bool idle) {
                if(liveStream.stop) return false;
                if(idle) return controls.alternateSet("stream_idle",0);
                if(!controls.alternateSet("stream_format",bits==16?1:2) || !controls.rateSet("stream_rate",rate)) return false;
                if(liveStream.volumeCommand>0) {
                    const int target=liveStream.volumeDb*256;
                    for(int ch=1;ch<=2;++ch) {
                        int minimum,maximum,resolution;
                        if(!controls.volumeGet("stream_volume_min",ch,2,minimum) || !controls.volumeGet("stream_volume_max",ch,3,maximum) ||
                           !controls.volumeGet("stream_volume_res",ch,4,resolution) || resolution<=0 || target<minimum || target>maximum ||
                           (target-minimum)%resolution!=0) return false;
                        controls.target[ch-1]=target;
                    }
                }
                for(int ch=1;ch<=2;++ch) if(!controls.volumeSet("stream_hardware_ch"+std::to_string(ch),ch,controls.target[ch-1])) return false;
                if(!controls.lowStillSet("stream_before_audio") || liveStream.stop) return false;
                liveStream.levelRaw1=controls.target[0]; liveStream.levelRaw2=controls.target[1];
                return true;
            };
            IsoExperiment experiment(ctx,dev,liveStream,bytes,rate,bits,seconds,configure,[&]{return controls.lowStillSet("stream_periodic");});
            experiment.run();
            const auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
            std::ostringstream report;
            report<<"{\"clocking\":\"Experimental nominal 1000-interval/s packet cadence; no feedback or capture timing. Device clocking remains unvalidated.\","
                  <<"\"sampleRateHz\":"<<rate<<",\"bits\":"<<bits<<",\"durationSecondsRequested\":"<<seconds
                  <<",\"wallElapsedMs\":"<<elapsed<<",\"endpointInterval\":1,\"maximumPacketBytes\":"<<(bits==16?388:582)
                  <<",\"stoppedByRequest\":"<<(liveStream.stop?"true":"false")<<",\"stats\":"<<liveStream.json()<<"}";
            return PlaybackResult{report.str(),liveStream.submittedBytes>0};
        };
        if(liveStream.stop) return env->NewStringUTF("{\"cancelledBeforeUsb\":true,\"audioSubmitted\":false}");
        auto result=inspect(fd,true,false,&hook);
        return env->NewStringUTF(result.c_str());
    } catch(...) {
        liveStream.error=LIBUSB_ERROR_OTHER; liveStream.phase=6;
        return env->NewStringUTF("{\"nativeException\":true,\"restorationUnknown\":true}");
    }
}
#endif
