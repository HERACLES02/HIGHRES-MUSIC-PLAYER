#pragma once
#include "stream_source_session.hpp"
#include "../stream_transport.hpp"

struct OrdinarySourceResult {
    enum class Terminal { End, Stopped, Failed };
    Terminal terminal=Terminal::Failed;
    bool wholeMd5=false;
    uint64_t generation=0;
};
// One serialized worker owns this scope. Borrowed USB ownership/configuration
// outlives it. No reference PCM, whole-file staging, duration limit or predecode.
inline OrdinarySourceResult runOrdinarySourceStream(libusb_context* ctx,libusb_device_handle* dev,int fd,unsigned rate,unsigned bits,
        StreamPacketBridge::Endpoint endpoint,StreamState& state,const StreamSourceSession::Configure& configure,uint64_t first=0) {
    using S=StreamSourceSession::State;
    using T=OrdinarySourceResult::Terminal;
    if(state.stop)return {T::Stopped,false};
    try {
        StreamSourceSession session(ctx,dev,configure,&state.stop);
        auto publish=[&] {
            const auto s=session.stats();
            state.pending=s.pending;state.submittedBytes=s.submittedBytes;state.submittedFrames=s.submittedFrames;
            state.completedBytes=s.completedBytes;state.positionFrames=s.completedFrames;state.completedIntervals=s.completedPackets;
            state.callbacks=s.callbacks;state.cancelled=s.cancelled;state.transferErrors=s.errors;state.packetErrors=s.packetErrors;state.hash=s.hash;
            state.maxEventServiceGapUs=s.maxEventServiceGapUs;
            if(session.starved())++state.queueEmpty;
        };
        struct Drain {
            StreamSourceSession& session;StreamState& state;decltype(publish)& report;
            ~Drain() {
                state.phase=4;session.requestStop();
                const auto started=std::chrono::steady_clock::now();
                do {
                    session.step();
                    if(std::chrono::steady_clock::now()-started>std::chrono::seconds(2))state.cleanupDelayed=true;
                } while(!session.drained());
                report();
                if(session.state()==S::Failed)state.error=LIBUSB_ERROR_IO;
            }
        } drain{session,state,publish};
        if(!session.replaceFd(fd,rate,bits,endpoint,first)) {
            if(state.stop)return {T::Stopped,false};
            state.error=LIBUSB_ERROR_INVALID_PARAM;return {};
        }
        do {
            if(state.stop)session.requestStop();
            // Seek/replacement commands await the generational JNI owner. Do
            // not silently accept legacy finite-fixture seek/volume commands.
            if(state.seek.exchange(-1)>=0 || state.volumeCommand.load()!=0) {
                state.error=LIBUSB_ERROR_NOT_SUPPORTED;session.requestStop();
            }
            const auto phase=session.state();
            if(phase==S::Running || phase==S::Pausing || phase==S::Paused)session.setPaused(state.pause.load());
            session.step();publish();
            state.phase=session.state()==S::Paused?3:2;
            if(session.state()==S::Paused)std::this_thread::sleep_for(std::chrono::milliseconds(1));
            else std::this_thread::yield();
        } while(session.state()==S::Changing || session.state()==S::Running || session.state()==S::Pausing || session.state()==S::Paused || !session.drained());
        if(state.error || session.state()==S::Failed){state.error=LIBUSB_ERROR_IO;return {T::Failed,false,session.generation()};}
        if(state.stop || session.state()==S::Stopped)return {T::Stopped,false,session.generation()};
        return {T::End,session.integrity()==StreamSourceSession::Integrity::WholeStreamVerified,session.generation()};
    } catch(...) {state.error=LIBUSB_ERROR_OTHER;return {};}
}
