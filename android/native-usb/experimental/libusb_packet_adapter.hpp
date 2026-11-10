#pragma once
#include "stream_packet_bridge.hpp"
#include <libusb.h>
#include <array>
#include <atomic>
#include <stdexcept>

// Experimental worker-side transfer adapter, used by the finite Probe route.
// Context/handle are borrowed, already opened/claimed/configured by the caller.
// This adapter does not select a USB interface, rate, feedback or volume.
// ONE worker calls start/pump/events/state/drained and dispatches ALL callbacks
// for this context. No other event dispatcher may run concurrently. Only
// requestStop may run on another thread. Refilling happens after events returns.
class LibusbPacketAdapter {
public:
    using State=StreamPacketBridge::State;
    struct Stats {
        uint64_t submittedBytes=0,submittedFrames=0,completedBytes=0,completedFrames=0,completedPackets=0,
            callbacks=0,cancelled=0,errors=0,packetErrors=0,maxEventServiceGapUs=0,hash=14695981039346656037ULL;
        unsigned pending=0;
    };
private:
    using Bridge=StreamPacketBridge;
    struct Slot {
        libusb_transfer* transfer=nullptr;
        Bridge::Packet packet;
        bool pending=false;
        Bridge::Completion result=Bridge::Completion::Pending;
        int error=0;
    };
    Bridge bridge;
    std::array<Slot,Bridge::SLOT_COUNT> slots{};
    libusb_context* context;
    libusb_device_handle* handle;
    std::atomic<bool> stopRequested{false};
    const std::atomic<bool>* stopSignal;
    const std::atomic<bool>* externalStopSignal;
    bool stopping()const{return stopRequested.load() || (stopSignal && stopSignal->load()) || (externalStopSignal && externalStopSignal->load());}
    unsigned endpoint=0;
    bool active=false,retiring=false,paused=false;
    int failure=0;
    Stats progress;
    uint64_t lastEventServiceUs=0;
    void fail(int code){if(!failure)failure=code;}
    static void LIBUSB_CALL completed(libusb_transfer* transfer) noexcept {
        auto& slot=*static_cast<Slot*>(transfer->user_data);
        // No source access, locks, IO, allocation, logging or resubmission.
        // One packet only. The aggregate actual_length is undefined for ISO;
        // validate the individual packet instead. Cancellation may be partial.
        if(transfer->status==LIBUSB_TRANSFER_CANCELLED){slot.result=Bridge::Completion::Cancelled;return;}
        if(transfer->status!=LIBUSB_TRANSFER_COMPLETED) {
            slot.error=-100-int(transfer->status);slot.result=Bridge::Completion::Error;return;
        }
        if(transfer->num_iso_packets!=1 || transfer->length!=int(slot.packet.bytes) || transfer->buffer!=slot.packet.data) {
            slot.error=-300;slot.result=Bridge::Completion::Error;return;
        }
        const auto& packet=transfer->iso_packet_desc[0];
        if(packet.status!=LIBUSB_TRANSFER_COMPLETED || packet.length!=slot.packet.bytes || packet.actual_length!=packet.length) {
            slot.error=-200-int(packet.status);slot.result=Bridge::Completion::Error;return;
        }
        slot.result=Bridge::Completion::Done;
    }
    void harvest() {
        // Same worker, AFTER callback return. libusb and the bridge can now
        // retire the old borrow before the slot/transfer may be reused.
        for(auto& slot:slots)if(slot.pending && slot.result!=Bridge::Completion::Pending) {
            ++progress.callbacks;
            if(slot.result==Bridge::Completion::Done) {
                progress.completedBytes+=slot.packet.bytes;progress.completedFrames+=slot.packet.frames;++progress.completedPackets;
            }
            if(slot.result==Bridge::Completion::Cancelled)++progress.cancelled;
            if(slot.result==Bridge::Completion::Error) {
                if(slot.error<=-200)++progress.packetErrors;else ++progress.errors;
            }
            if(slot.result==Bridge::Completion::Error)fail(slot.error);
            if(!retiring && slot.result==Bridge::Completion::Cancelled)fail(-301);
            if(!bridge.complete(slot.packet.slot,slot.packet.ticket,slot.result))fail(-302);
            slot.pending=false;
        }
    }
    void retire() {
        if(!retiring){retiring=true;bridge.stop();}
        bridge.prepare(); // Reap reported completions without refilling.
        Bridge::Packet packet;
        while(bridge.cancel(packet)) {
            auto& slot=slots[packet.slot];
            const int result=libusb_cancel_transfer(slot.transfer);
            // NOT_FOUND can mean the completion is already queued. Neither
            // success nor an error retires a borrow; keep dispatching events.
            if(result<0 && result!=LIBUSB_ERROR_NOT_FOUND)fail(result);
        }
    }
    template<class Start> bool startInput(Bridge::Endpoint descriptor,Start&& beginSource) {
        if(!drained() || (active && !retiring && bridge.state()==State::Running))return false;
        // A later concurrent Stop survives startup. Rejected jobs preserve the
        // previous failure, and no incoming fd is touched before drain checks.
        stopRequested=false;
        if(!beginSource())return false;
        failure=0;progress={};lastEventServiceUs=0;endpoint=descriptor.address;active=true;retiring=false;paused=false;return true;
    }
public:
    // Optional shared Stop must outlive this adapter. Only read atomically;
    // this closes command gaps within a multi-submit worker pump.
    LibusbPacketAdapter(libusb_context* ctx,libusb_device_handle* device,const std::atomic<bool>* stop=nullptr,const std::atomic<bool>* externalStop=nullptr):context(ctx),handle(device),stopSignal(stop),externalStopSignal(externalStop) {
        for(auto& slot:slots) {
            slot.transfer=libusb_alloc_transfer(1);
            if(!slot.transfer) {
                for(auto& allocated:slots)if(allocated.transfer)libusb_free_transfer(allocated.transfer);
                throw std::runtime_error("No isochronous transfer memory");
            }
        }
    }
    LibusbPacketAdapter(const LibusbPacketAdapter&)=delete;
    LibusbPacketAdapter& operator=(const LibusbPacketAdapter&)=delete;
    ~LibusbPacketAdapter() {
        // Caller must request Stop and keep pump/events alive until drained.
        // Never free USB-owned resources to satisfy a timeout or cancel result.
        for(const auto& slot:slots)if(slot.pending)std::terminate();
        bridge.stop();
        for(auto& slot:slots)libusb_free_transfer(slot.transfer);
    }
    bool drained()const {
        for(const auto& slot:slots)if(slot.pending)return false;
        return bridge.drained();
    }
    // Worker only, after callbacks return. Never touches source locks or Java.
    Stats stats()const {
        auto result=progress;
        for(const auto& slot:slots)if(slot.pending)++result.pending;
        return result;
    }
    bool start(std::string path,unsigned rate,unsigned bits,Bridge::Endpoint descriptor,uint64_t first=0) {
        return startInput(descriptor,[&]{return bridge.start(std::move(path),rate,bits,descriptor,first);});
    }
    void requestStop() noexcept{stopRequested=true;}
    // Serialized worker only. Already submitted packets finish normally;
    // bounded bridge/source buffers remain owned and unchanged while paused.
    void setPaused(bool value) noexcept{paused=value;}
    // Borrow only for this call; accepted whole-file source owns a synchronous
    // duplicate. Actual metadata/IO failures are reported asynchronously.
    bool startFd(int fd,unsigned rate,unsigned bits,Bridge::Endpoint descriptor,uint64_t first=0) {
        return startInput(descriptor,[&]{return bridge.startFd(fd,rate,bits,descriptor,first);});
    }
    void pump() {
        harvest();
        if(!active)return;
        if(stopping() || failure || retiring){retire();return;}
        if(paused) {
            // Reap completion tickets and observe source EOF/error even though
            // no USB submission is allowed. Preparation remains bounded.
            bridge.prepare();
            if(bridge.state()==State::Failed || bridge.state()==State::Stale || bridge.state()==State::Stopped){fail(-1000);retire();}
            return;
        }
        // At most one fixed pool of submissions, no retry loop or growing work.
        for(size_t i=0;i<slots.size();++i) {
            bridge.prepare();
            const auto status=bridge.state();
            if(status==State::Failed || status==State::Stale || status==State::Stopped)fail(-1000);
            if(stopping() || failure){retire();return;}
            Bridge::Packet packet;
            if(!bridge.submit(packet)) {
                if(bridge.state()==State::Failed || bridge.state()==State::Stale) {fail(-1000);retire();}
                return;
            }
            auto& slot=slots[packet.slot];
            slot.packet=packet;slot.result=Bridge::Completion::Pending;slot.error=0;
            slot.transfer->flags=0;
            libusb_fill_iso_transfer(slot.transfer,handle,static_cast<unsigned char>(endpoint),const_cast<unsigned char*>(packet.data),int(packet.bytes),1,completed,&slot,1000);
            auto& iso=slot.transfer->iso_packet_desc[0];
            iso.length=unsigned(packet.bytes);iso.actual_length=0;iso.status=LIBUSB_TRANSFER_COMPLETED;
            // requestStop is a cooperative command; the final check closes
            // preparation gaps. A submit already racing Stop is drained below.
            if(stopping()) {
                bridge.complete(packet.slot,packet.ticket,Bridge::Completion::Cancelled);retire();return;
            }
            slot.pending=true;
            const int result=libusb_submit_transfer(slot.transfer);
            if(result<0) {
                // Failed submission has no callback. Retire ONLY this unsent
                // borrow locally; previously submitted buffers still drain.
                slot.pending=false;bridge.complete(packet.slot,packet.ticket,Bridge::Completion::Error);
                ++progress.errors;fail(result);retire();return;
            }
            progress.submittedBytes+=packet.bytes;progress.submittedFrames+=packet.frames;
            for(size_t p=0;p<packet.bytes;++p){progress.hash^=packet.data[p];progress.hash*=1099511628211ULL;}
        }
    }
    void events() {
        bool pending=false;for(const auto& slot:slots)pending=pending || slot.pending;
        if(!pending){lastEventServiceUs=0;return;}
        // Worker-side service timing, not DAC timing or a callback measurement.
        if(paused || stopping())lastEventServiceUs=0;
        else {
            const auto now=uint64_t(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count());
            if(lastEventServiceUs)progress.maxEventServiceGapUs=std::max(progress.maxEventServiceGapUs,now-lastEventServiceUs);
            lastEventServiceUs=now;
        }
        timeval timeout{0,20000};
        const int result=libusb_handle_events_timeout_completed(context,&timeout,nullptr);
        if(result<0 && result!=LIBUSB_ERROR_INTERRUPTED)fail(result);
    }
    State state()const {
        if(failure)return State::Failed;
        if(!active || retiring || stopping())return State::Stopped;
        return bridge.state()==State::End && drained()?State::End:State::Running;
    }
    bool md5Verified()const{return state()==State::End && bridge.md5Verified();}
    int error()const{return failure;}
};
