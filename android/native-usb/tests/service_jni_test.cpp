#define USB_SERVICE_HOST_TEST
#include "service_fake_backend.cpp"
#include <fstream>

namespace {
std::string returnedJson;
jstring JNICALL captureString(JNIEnv*,const char* text){returnedJson=text;return nullptr;}
}
int main(int argc,char** argv) {
    if(argc==2 && (std::string(argv[1])=="direct-enabled" || std::string(argv[1])=="direct-disabled")) {
        const bool enabled=std::string(argv[1])=="direct-enabled";
        check(bool(Java_com_localmusic_usb_NativeDirect_available(nullptr,nullptr))==enabled,"personal Direct availability matches explicit build choice");
        check(!Java_com_localmusic_usb_NativeDirect_open(nullptr,nullptr,-1),"invalid device cannot acquire Direct ownership");
        check(!ordinarySourceHardwareReady && !productionSource::registry.busy() && !serviceFake::contexts,"legacy hardware gate stays closed and availability performs no USB work");
        std::puts("PASS: explicit Direct build gate; invalid fd rejected; legacy gate closed; no USB access");
        return 0;
    }
    check(argc==6,"source/reference/rate/bits/mode");
    const unsigned rate=std::stoul(argv[3]),bits=std::stoul(argv[4]);
    const std::string mode=argv[5];
    const int input=inputFd(argv[1]),reference=inputFd(argv[2]);
    check(input>=0 && reference>=0,"open disposable JNI fixture descriptors");
    JNIEnv environment{};
    std::remove_const_t<std::remove_pointer_t<decltype(environment.functions)>> functions{};
    functions.NewStringUTF=captureString;environment.functions=&functions;
    serviceFake::pace=false;
    if(mode.rfind("direct-owner",0)==0) {
        auto& registry=productionSource::registry;
        const auto lease=registry.open(input);check(lease>0,"production owner retains USB descriptor with a generational lease");
        check(!registry.open(input),"one production owner only");
        Java_com_localmusic_diagnostics_NativeUsb_streamCommand(&environment,nullptr,4,0);
        check(Java_com_localmusic_diagnostics_NativeUsb_beginStream(&environment,nullptr)==JNI_FALSE,"legacy route cannot take production reservation");
        Java_com_localmusic_diagnostics_NativeUsb_inspect(&environment,nullptr,-1,JNI_TRUE,JNI_TRUE);
        check(returnedJson.find("productionOwnerActive")!=std::string::npos,"legacy inspect cannot claim during production lease");
        if(mode=="direct-owner-packet-failure") {
            serviceFake::prepare(rate,bits,reference,false,3);
            check(registry.start(lease,input,rate,bits,0,true)>0,"packet-failure source accepted");
            const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(15);
            std::string snapshot;
            do {check(std::chrono::steady_clock::now()<end,"packet failure retires");snapshot=registry.snapshot(lease);std::this_thread::yield();} while(snapshot.find("\"finished\":true")==std::string::npos);
            check(snapshot.find("\"packetErrors\":1")!=std::string::npos && snapshot.find("\"transferErrors\":0")!=std::string::npos,"native report distinguishes actual packet fault from transfer errors");
            const auto closed=registry.close(lease);
            check(closed.find("\"packetErrors\":1")!=std::string::npos && closed.find("\"restored\":true")!=std::string::npos,"final diagnostics survive retirement and control restore");
            check(!fake::count && !serviceFake::claims && fake::allocations==fake::frees,"packet-failure report follows complete retirement");
            if(fake::reference)std::fclose(fake::reference);
            fake::reference=nullptr;
            ::close(input);::close(reference);std::puts("PASS: production packet-error diagnostics retained after close");return 0;
        }
        if(mode=="direct-owner-race" || mode=="direct-owner-quarantine") {
            serviceFake::prepare(rate,bits,reference,false,mode=="direct-owner-quarantine"?7:0);
            if(mode=="direct-owner-race")gate.arm();
            check(registry.start(lease,input,rate,bits,0,mode!="direct-owner-race")>0,"race/failure source accepted");
            if(mode=="direct-owner-race") {
                gate.wait();memory();std::atomic<uint64_t> replacement{UINT64_MAX};
                std::thread replace([&]{replacement=registry.start(lease,input,rate,bits,0,true);});
                const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(3);
                while(registry.snapshot(lease).find("changing")==std::string::npos){check(std::chrono::steady_clock::now()<end,"replacement enters drain");std::this_thread::yield();}
                check(!registry.start(lease,input,rate,bits,0,true),"only one outstanding native source command");
                registry.stop(lease);gate.release();replace.join();
                check(replacement==0 && !fake::attempts,"Stop during blocked old-source join cannot start replacement");
                check(registry.close(lease).find("\"restored\":true")!=std::string::npos,"race closes after old source/transfer retirement");
            } else {
                const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(15);
                while(registry.snapshot(lease).find("\"finished\":true")==std::string::npos){check(std::chrono::steady_clock::now()<end,"failed job finishes");std::this_thread::yield();}
                check(registry.close(lease).find("\"restored\":false")!=std::string::npos,"unknown restoration is explicit");
                check(!registry.open(input),"unknown restoration quarantines native ownership");
            }
            check(!fake::count && !serviceFake::claims && serviceFake::contexts==serviceFake::closed && fake::allocations==fake::frees,"race/failure native resources retired");
            if(fake::reference)std::fclose(fake::reference);
            fake::reference=nullptr;closeFd(input);closeFd(reference);memory();
            std::cout<<"PASS: production owner "<<mode<<" MEMORY "<<peakHeap<<' '<<peakResident<<'\n';return 0;
        }
        serviceFake::prepare(rate,bits,reference,false,0);
        const auto first=registry.start(lease,input,rate,bits,0,true);check(first>0,"production owner starts ordinary source without reference API");
        auto wait=[&](const char* phase) {
            const auto end=std::chrono::steady_clock::now()+std::chrono::seconds(15);
            std::string value;
            do {check(std::chrono::steady_clock::now()<end,"production owner makes progress");value=registry.snapshot(lease);std::this_thread::yield();} while(value.find(phase)==std::string::npos);
            return value;
        };
        const auto complete=wait("\"finished\":true");
        check(complete.find("\"phase\":\"end\"")!=std::string::npos,"production EOF explicit");
        check(complete.find("\"integrity\":\"whole\"")!=std::string::npos && fake::frames>0,"production exact PCM and whole MD5");
        check(!registry.paused(lease,first+1,false) && !registry.seek(lease,first+1,5001),"stale source controls rejected");
        serviceFake::prepare(rate,bits,reference,false,0);
        check(!std::fseek(fake::reference,5001*(bits/8*2),SEEK_SET),"independent expected seek suffix");
        const auto second=registry.seek(lease,first,5001);check(second>first,"seek changes source generation after prior job retirement");
        const auto partial=wait("\"finished\":true");
        check(partial.find("\"integrity\":\"partial\"")!=std::string::npos,"post-seek decode never claims whole MD5");
        registry.stop(lease);
        check(registry.snapshot(lease).find("\"integrity\":\"unverified\"")!=std::string::npos,"Stop does not report a partial decode as verified EOF");
        check(registry.close(lease).find("\"restored\":true")!=std::string::npos,"close confirms join, retirement and controls restoration");
        check(!fake::count && fake::allocations==fake::frees && !serviceFake::claims,"production close drains actual transfers");
        const auto next=registry.open(input);check(next>lease,"owner lease never reused");registry.stop(lease);registry.close(lease);
        check(registry.snapshot(next).find("stale")==std::string::npos,"old Stop/close cannot touch new owner");registry.close(next);
        if(fake::reference)std::fclose(fake::reference);
        fake::reference=nullptr;
        closeFd(input);closeFd(reference);std::cout<<"PASS: production generational owner\n";return 0;
    }
    if(mode.rfind("ordinary-",0)==0) {
        Java_com_localmusic_diagnostics_NativeUsb_runOrdinarySource(&environment,nullptr,-1,-1,rate,bits);
        check(returnedJson.find("\"hardwareGateClosed\":true")!=std::string::npos && !serviceFake::contexts && !fake::allocations,"public production JNI is gated before any ownership/transfer work");
        const int fault=mode=="ordinary-claim"?4:mode=="ordinary-descriptor"?5:mode=="ordinary-packet"?3:mode=="ordinary-config"?7:0;
        serviceFake::prepare(rate,bits,reference,false,fault);liveStream.reset();
        if(mode=="ordinary-stop")fake::stopOnEvent=true;
        if(mode=="ordinary-submit-stop")fake::stopOnSubmit=true;
        if(mode=="ordinary-prestop")liveStream.stop=true;
        if(mode=="ordinary-desc-length")serviceFake::formatExtra[bits==24][7]=255;
        if(mode=="ordinary-desc-width")serviceFake::formatExtra[bits==24][12]=4;
        if(mode=="ordinary-desc-rate")std::memset(serviceFake::formatExtra[bits==24]+15,0,12);
        if(mode=="ordinary-desc-feedback")serviceFake::endpoints[bits==24].bSynchAddress=0x81;
        std::thread controller;
        if(mode=="ordinary-starve") {
            gate.arm();
            controller=std::thread([&] {
                gate.wait();std::this_thread::sleep_for(std::chrono::milliseconds(10));
                check(liveStream.queueEmpty>0 && !liveStream.submittedFrames,"ordinary backend reports starvation without fake EOF or silence");
                liveStream.stop=true;gate.release();
            });
        }
        if(mode=="ordinary-pause") {
            liveStream.pause=true;
            controller=std::thread([&] {
                const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
                while(liveStream.phase!=3){check(std::chrono::steady_clock::now()<deadline,"ordinary backend reaches paused state");std::this_thread::yield();}
                const auto frames=liveStream.submittedFrames.load();std::this_thread::sleep_for(std::chrono::milliseconds(10));memory();
                check(liveStream.pending==0 && liveStream.submittedFrames==frames,"ordinary paused backend holds buffers without new PCM");
                liveStream.pause=false;
            });
        }
        const auto result=ordinarySourceBackend(input,mode=="ordinary-invalid"?-1:input,mode=="ordinary-rate"?192000:rate,
            mode=="ordinary-metadata"?(bits==16?24:16):bits,liveStream);
        if(controller.joinable())controller.join();
        const bool whole=mode=="ordinary-backend" || mode=="ordinary-pause";
        if(whole) {
            std::ifstream file(argv[2],std::ios::binary|std::ios::ate);
            const auto expected=static_cast<uint64_t>(file.tellg())/(bits/8*2);
            check(fake::frames==expected && fake::frames>0,"ordinary production backend emits exact independent PCM");
            check(result.find("\"wholeStreamMd5Verified\":true")!=std::string::npos,"ordinary backend reports whole-stream integrity");
        } else if(mode=="ordinary-stop" || mode=="ordinary-submit-stop" || mode=="ordinary-prestop" || mode=="ordinary-starve")
            check(result.find("cancelled")!=std::string::npos,"ordinary Stop remains cancellation");
        else if(mode=="ordinary-unverified")check(result.find("\"terminal\":\"eof\"")!=std::string::npos && result.find("\"wholeStreamMd5Verified\":false")!=std::string::npos && !liveStream.error,"missing MD5 is unverified clean EOF, not corruption");
        else check(result.find("\"terminal\":\"failed\"")!=std::string::npos && liveStream.error && result.find("\"wholeStreamMd5Verified\":true")==std::string::npos,"rejected/corrupt source reports explicit failure without whole integrity");
        if(mode=="ordinary-submit-stop")check(fake::attempts==1,"external Stop during submit prevents every subsequent submission");
        if(mode=="ordinary-prestop" || mode=="ordinary-invalid" || mode=="ordinary-rate")check(!serviceFake::contexts && !fake::attempts,"precheck rejects before USB ownership");
        if(mode=="ordinary-descriptor" || mode.rfind("ordinary-desc-",0)==0 || mode=="ordinary-claim" || mode=="ordinary-metadata" || mode=="ordinary-config")check(!fake::attempts,"descriptor/claim/format failure emits no PCM");
        check(serviceFake::contexts==serviceFake::closed && !serviceFake::claims && !fake::count && fake::allocations==fake::frees,"ordinary backend drains before claim/context retirement");
        if(serviceFake::contexts && fault!=7)check(serviceFake::alternate==0 && serviceFake::frequency==44100 && serviceFake::volumes[0]==-5120 && serviceFake::volumes[1]==-5120,"ordinary backend restores controls after final transfer");
        if(fake::reference)std::fclose(fake::reference);
        fake::reference=nullptr;
        closeFd(input);closeFd(reference);memory();
        std::cout<<"PASS: ordinary production backend "<<mode<<" MEMORY "<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';return 0;
    }
    if(mode=="probe-session") {
        const auto handle=Java_com_localmusic_diagnostics_SourceSessionNative_open(&environment,nullptr);
        check(handle>0,"JNI owns one ordinary-source session");
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_open(&environment,nullptr),"second JNI owner rejected");
        auto replace=[&] {
            const int fd=inputFd(argv[1]),ref=inputFd(argv[2]);
            const auto token=Java_com_localmusic_diagnostics_SourceSessionNative_replace(&environment,nullptr,handle,fd,ref,rate,bits,0);
            closeFd(fd);closeFd(ref);check(token>0,"JNI duplicates provider fds before caller close");return token;
        };
        auto until=[&](const char* phase) {
            const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
            do {check(std::chrono::steady_clock::now()<deadline,"JNI session progress");
                Java_com_localmusic_diagnostics_SourceSessionNative_step(&environment,nullptr,handle);
                std::this_thread::yield();
            } while(returnedJson.find(phase)==std::string::npos);
        };
        const auto first=replace();
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_replace(&environment,nullptr,handle,input,reference,rate,bits,0),"JNI pending work is bounded to one source");
        until("\"phase\":\"running\"");
        check(Java_com_localmusic_diagnostics_SourceSessionNative_setPaused(&environment,nullptr,handle,first,JNI_TRUE),"JNI forwards Pause to actual source");
        until("\"phase\":\"paused\"");
        const auto pausedFrames=fake::frames;
        for(int i=0;i<20;++i)Java_com_localmusic_diagnostics_SourceSessionNative_step(&environment,nullptr,handle);
        check(fake::frames==pausedFrames && !fake::count,"JNI Pause stops PCM submissions");
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_setPaused(&environment,nullptr,handle,first+1,JNI_FALSE),"stale generation cannot Resume");
        check(Java_com_localmusic_diagnostics_SourceSessionNative_setPaused(&environment,nullptr,handle,first,JNI_FALSE),"JNI forwards Resume");
        until("\"phase\":\"end\"");
        check(fake::frames==6ULL*rate && returnedJson.find("whole")!=std::string::npos,"JNI exact ordinary EOF and whole MD5");
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_seek(&environment,nullptr,handle,first,-1),"negative seek rejected without unsigned wrap");
        const auto second=Java_com_localmusic_diagnostics_SourceSessionNative_seek(&environment,nullptr,handle,first,5001);
        check(second>first,"JNI seek has a new generation");until("\"phase\":\"end\"");
        check(fake::frames==6ULL*rate-5001 && returnedJson.find("partial")!=std::string::npos,"JNI exact partial suffix, no whole MD5 claim");
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_seek(&environment,nullptr,handle,first,0),"stale-generation seek rejected");
        replace();until("\"phase\":\"running\"");
        const auto submitted=fake::frames;replace();
        Java_com_localmusic_diagnostics_SourceSessionNative_stop(&environment,nullptr,handle);until("\"phase\":\"stopped\"");
        Java_com_localmusic_diagnostics_SourceSessionNative_close(&environment,nullptr,handle);
        check(!fake::count && fake::allocations==8 && fake::frees==8,"JNI closes after late callback/buffer retirement");
        check(fake::frames==submitted,"Stop discards queued replacement without new submissions");
        check(!Java_com_localmusic_diagnostics_SourceSessionNative_seek(&environment,nullptr,handle,second,0),"closed JNI handle rejected");
        const auto next=Java_com_localmusic_diagnostics_SourceSessionNative_open(&environment,nullptr);
        check(next>handle,"native handle generations never reuse old identity");
        Java_com_localmusic_diagnostics_SourceSessionNative_close(&environment,nullptr,handle);
        Java_com_localmusic_diagnostics_SourceSessionNative_step(&environment,nullptr,next);
        check(returnedJson.find("idle")!=std::string::npos,"stale close cannot destroy a newer native owner");
        check(Java_com_localmusic_diagnostics_SourceSessionNative_replace(&environment,nullptr,next,input,reference,rate,bits,0)>0,"error/Stop source accepted");
        const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
        while(!fake::count){check(std::chrono::steady_clock::now()<deadline,"error/Stop starts");Java_com_localmusic_diagnostics_SourceSessionNative_step(&environment,nullptr,next);}
        fake::fault=3;fake::events=0;fake::delay=0;
        Java_com_localmusic_diagnostics_SourceSessionNative_stop(&environment,nullptr,next);
        do {check(std::chrono::steady_clock::now()<deadline,"error/Stop drains");Java_com_localmusic_diagnostics_SourceSessionNative_step(&environment,nullptr,next);} while(fake::count);
        check(returnedJson.find("\"phase\":\"failed\"")!=std::string::npos,"packet failure during Stop stays Failed, never Stopped");
        Java_com_localmusic_diagnostics_SourceSessionNative_close(&environment,nullptr,next);
        closeFd(input);closeFd(reference);std::cout<<"PASS: ordinary-source JNI lifecycle\n";return 0;
    }
    const int cycles=mode=="probe-stress"?110:1;
    size_t heap10=0,resident10=0;
    for(int cycle=0;cycle<cycles;++cycle) {
        const int fault=mode=="probe-submit-error"?2:mode=="probe-packet-error"?3:
            mode=="probe-config-stop" || mode=="probe-config-stop-false"?6:mode=="probe-config-error"?7:
            mode=="probe-claim-denied"?4:mode=="probe-bad-descriptor"?5:0;
        serviceFake::prepare(rate,bits,reference,false,fault);
        if(mode=="probe-stop" || mode=="probe-late-cancel")fake::stopOnEvent=true;
        if(mode=="probe-submit-stop")fake::stopOnSubmit=true;
        if(mode=="probe-event-throw")fake::fault=20;
        check(Java_com_localmusic_diagnostics_NativeUsb_beginStream(&environment,nullptr)==JNI_TRUE,"reserve actual JNI state");
        if(mode=="probe-prestop")Java_com_localmusic_diagnostics_NativeUsb_streamCommand(&environment,nullptr,0,0);
        Java_com_localmusic_diagnostics_NativeUsb_runStreamingFixture(&environment,nullptr,input,input,rate,bits);
        check(!liveStream.executing && !liveStream.reserved && !fake::count,"JNI returns after reservation/transfer retirement");
        check(serviceFake::contexts==serviceFake::closed && !serviceFake::claims && fake::allocations==fake::frees,"JNI context/claim/transfer lifetime");
        if(fault==4 || fault==5) {
            check(!fake::attempts,"ownership/descriptor rejection submits nothing");
            if(fault==5)check(returnedJson.find("\"nativeException\":true")!=std::string::npos && serviceFake::restored>0,"descriptor rejection unwinds/restores before context exit");
            else check(returnedJson.find("\"audioSubmitted\":false")!=std::string::npos,"denied playback claim cannot start");
            Java_com_localmusic_diagnostics_SelfTestNative_release(&environment,nullptr);
            std::cout<<"PASS: service JNI ownership/descriptor gate\n";continue;
        }
        check(returnedJson.find("\"nativeException\"")==std::string::npos,"no unhandled JNI exception");
        const bool fail=fault==2 || fault==3 || fault==7 || mode=="probe-event-throw";
        const bool stop=mode=="probe-prestop" || mode=="probe-stop" || mode=="probe-late-cancel" ||
            mode=="probe-submit-stop" || fault==6;
        if(fail)check(liveStream.error!=0 && returnedJson.find("\"terminal\":\"failed\"")!=std::string::npos,"JNI reports transfer/config failure");
        else if(stop)check(liveStream.error==0 && (returnedJson.find("\"cancelled\"")!=std::string::npos ||
            returnedJson.find("\"cancelledBeforeUsb\":true")!=std::string::npos),"JNI reports cancellation separately");
        else {
            check(returnedJson.find("\"terminal\":\"eof\"")!=std::string::npos && returnedJson.find("\"wholeStreamMd5Verified\":true")!=std::string::npos,"actual JNI completes verified EOF");
            check(fake::frames==6ULL*rate && std::fgetc(fake::reference)==EOF,"actual JNI exact independent reference payload");
        }
        if(serviceFake::contexts && fault!=7)check(returnedJson.find("\"settingsRestored\":true")!=std::string::npos,"actual JNI restores controls after drain");
        Java_com_localmusic_diagnostics_SelfTestNative_release(&environment,nullptr);
        memory();if(cycle==9){heap10=peakHeap;resident10=peakResident;}
    }
    closeFd(input);closeFd(reference);
    std::cout<<"PASS: service JNI "<<mode<<" MEMORY "<<heap10<<' '<<resident10<<' '<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';
}
