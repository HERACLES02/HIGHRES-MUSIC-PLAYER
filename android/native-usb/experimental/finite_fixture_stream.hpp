#pragma once
#include "libusb_packet_adapter.hpp"
#include "quiet_flac_fixture.hpp"
#include "../stream_transport.hpp"

struct FiniteFixtureResult {
    enum class Terminal { End, Stopped, Failed };
    Terminal terminal=Terminal::Failed;
    bool md5Verified=false;
};
// ONE worker owns this entire scope, including dispatch and retirement. Caller
// owns the verified, unchanged private fd and configured USB context/claims.
// Returning (including exceptions) must precede control restoration/context exit.
inline FiniteFixtureResult runFiniteFixtureStream(libusb_context* ctx,libusb_device_handle* dev,int fd,unsigned rate,unsigned bits,
        StreamPacketBridge::Endpoint endpoint,const QuietFlacFixture& verified,StreamState& state,const std::function<bool()>& configure) {
    using T=FiniteFixtureResult::Terminal;
    using S=LibusbPacketAdapter::State;
    state.finiteSource=true;
    if(state.stop)return {T::Stopped,false};
    if(verified.status!=QuietFlacFixture::Status::Valid || verified.frames!=6ULL*rate) {
        state.error=LIBUSB_ERROR_INVALID_PARAM;state.phase=6;return {};
    }
    try {
        LibusbPacketAdapter adapter(ctx,dev,&state.stop);
        auto publish=[&] {
            const auto s=adapter.stats();
            state.pending=s.pending;state.submittedBytes=s.submittedBytes;state.submittedFrames=s.submittedFrames;
            state.completedBytes=s.completedBytes;state.positionFrames=s.completedFrames;state.completedIntervals=s.completedPackets;
            state.callbacks=s.callbacks;state.cancelled=s.cancelled;state.transferErrors=s.errors;state.packetErrors=s.packetErrors;state.hash=s.hash;
            if(adapter.error())state.error=adapter.error();
        };
        using Publish=decltype(publish);
        struct Drain {
            LibusbPacketAdapter& adapter;StreamState& state;Publish& publish;
            ~Drain() {
                state.phase=4;adapter.requestStop();
                const auto start=std::chrono::steady_clock::now();
                do {
                    adapter.pump();adapter.events();
                    if(std::chrono::steady_clock::now()-start>std::chrono::seconds(2))state.cleanupDelayed=true;
                } while(!adapter.drained());
                publish();
                state.pending=0;
                if(adapter.error())state.error=adapter.error();
            }
        } drain{adapter,state,publish};
        if(state.stop)return {T::Stopped,false};
        const bool configured=configure();
        if(state.stop)return {T::Stopped,false};
        if(!configured) {state.error=LIBUSB_ERROR_IO;return {};}
        if(!adapter.startFd(fd,rate,bits,endpoint)) {state.error=LIBUSB_ERROR_NOT_SUPPORTED;return {};}
        state.phase=2;
        do {
            if(state.stop){state.phase=4;adapter.requestStop();}
            adapter.pump();adapter.events();adapter.pump();publish();
            std::this_thread::yield();
        } while(adapter.state()==S::Running || !adapter.drained());
        const auto status=adapter.state();
        if(status==S::Failed || state.error)return {};
        if(state.stop || status==S::Stopped)return {T::Stopped,false};
        const auto s=adapter.stats();
        const uint64_t expected=verified.frames*bits/8*2;
        const bool complete=status==S::End && adapter.md5Verified() && s.submittedBytes==expected &&
            s.completedBytes==expected && s.hash==verified.fnv1a64;
        if(!complete){state.error=LIBUSB_ERROR_IO;return {};}
        return {T::End,true};
    } catch(...) {
        // Stack unwinding drains adapter-owned borrows BEFORE this handler.
        state.error=LIBUSB_ERROR_OTHER;state.phase=6;return {};
    }
}
