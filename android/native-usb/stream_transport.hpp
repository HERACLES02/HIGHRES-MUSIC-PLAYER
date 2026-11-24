#pragma once
#include "packet_pcm.hpp"
#include <libusb.h>
#include <array>
#include <atomic>
#include <chrono>
#include <functional>
#include <sstream>
#include <thread>

struct StreamState {
    std::atomic<bool> reserved{false}, executing{false}, stop{false}, pause{false}, cleanupDelayed{false}, finiteSource{false};
    std::atomic<int64_t> seek{-1};
    std::atomic<int> phase{0}, error{0}, pending{0}, pauseCount{0}, seekCount{0}, volumeDb{-40}, volumeCommand{0}, levelRaw1{0}, levelRaw2{0};
    std::atomic<uint64_t> healthChecks{0}, submittedBytes{0}, completedBytes{0}, submittedFrames{0}, positionFrames{0},
        completedIntervals{0}, comparedBytes{0}, mismatches{0}, callbacks{0}, packetErrors{0},
        transferErrors{0}, cancelled{0}, queueEmpty{0}, maxCallbackGapUs{0}, maxEventServiceGapUs{0}, hash{14695981039346656037ULL};
    void reset() {
        stop=false; pause=false; cleanupDelayed=false; finiteSource=false; seek=-1; phase=1; error=0; pending=0;
        pauseCount=0; seekCount=0; submittedBytes=0; completedBytes=0; submittedFrames=0;
        volumeDb=-40; volumeCommand=0; levelRaw1=0; levelRaw2=0;
        positionFrames=0; completedIntervals=0; comparedBytes=0; mismatches=0; callbacks=0;
        packetErrors=0; transferErrors=0; cancelled=0; queueEmpty=0; maxCallbackGapUs=0; maxEventServiceGapUs=0;
        hash=14695981039346656037ULL;
        healthChecks=0;
    }
    std::string json() const {
        std::ostringstream out;
        out<<"{\"finiteSource\":"<<(finiteSource?"true":"false")<<",\"phase\":"<<phase<<",\"error\":"<<error<<",\"pendingTransfers\":"<<pending
           <<",\"submittedBytes\":"<<submittedBytes<<",\"completedBytes\":"<<completedBytes
           <<",\"submittedFrames\":"<<submittedFrames<<",\"positionFrames\":"<<positionFrames
           <<",\"completedIntervals\":"<<completedIntervals<<",\"comparedBytes\":"<<comparedBytes
           <<",\"pcmMismatches\":"<<mismatches<<",\"callbacks\":"<<callbacks
           <<",\"packetErrors\":"<<packetErrors<<",\"transferErrors\":"<<transferErrors
           <<",\"cancelledTransfers\":"<<cancelled<<",\"queueEmptyDuringActive\":"<<queueEmpty
           <<",\"maxCallbackGapUs\":"<<maxCallbackGapUs<<",\"submissionFnv1a64\":\""<<hash
           <<"\",\"pauseCount\":"<<pauseCount<<",\"seekCount\":"<<seekCount
           <<",\"cleanupDelayed\":"<<(cleanupDelayed?"true":"false")<<"}";
        // Target levels below are hardware readback values, never software scaling.
        auto text=out.str(); text.pop_back();
        return text+",\"hardwareLevelRaw1\":"+std::to_string(levelRaw1.load())+",\"hardwareLevelRaw2\":"+std::to_string(levelRaw2.load())+",\"volumeHealthChecks\":"+std::to_string(healthChecks.load())+"}";
    }
};

class IsoExperiment {
    static constexpr int SLOT_COUNT=8, PACKETS=8;
    struct Slot {
        IsoExperiment* owner=nullptr; libusb_transfer* transfer=nullptr;
        std::array<unsigned char,PACKETS*582> buffer{};
        bool pending=false; uint64_t start=0;
    };
    std::array<Slot,SLOT_COUNT> slots{};
    libusb_context* context; libusb_device_handle* handle; StreamState& state;
    PacketPcm source; std::function<bool(bool)> configure; std::function<bool()> health;
    uint64_t target, planned=0, lastCallback=0, streamHash=14695981039346656037ULL;
    int capacity, appliedVolume=0; bool draining=false; uint64_t healthPeriod;
    static uint64_t micros() {
        return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    bool command() const { return state.stop || state.pause || state.seek.load()>=0 || state.volumeCommand!=appliedVolume; }
    void fail(int code) { if(!state.error) state.error=code; }
    void submit(Slot& slot) {
        if(planned>=target || command() || state.error || draining) return;
        int count=static_cast<int>(std::min<uint64_t>(PACKETS,target-planned));
        slot.start=source.cursor; int length=0;
        for(int i=0;i<count;++i) {
            int bytes=source.next(slot.buffer.data()+length,capacity);
            auto& packet=slot.transfer->iso_packet_desc[i]; packet.length=bytes; packet.actual_length=0; packet.status=LIBUSB_TRANSFER_COMPLETED;
            length+=bytes;
        }
        state.comparedBytes=source.compared; state.mismatches=source.mismatches;
        if(source.mismatches) { fail(-301); return; }
        libusb_fill_iso_transfer(slot.transfer,handle,0x04,slot.buffer.data(),length,count,completed,&slot,1000);
        int result=libusb_submit_transfer(slot.transfer);
        if(result<0) { fail(result); return; }
        slot.pending=true; ++state.pending; planned+=count;
        for(int i=0;i<length;++i) { streamHash^=slot.buffer[i]; streamHash*=1099511628211ULL; }
        state.hash=streamHash; state.submittedBytes+=length;
        state.submittedFrames+=length/source.frameBytes;
        state.comparedBytes=source.compared; state.mismatches=source.mismatches;
    }
    static void LIBUSB_CALL completed(libusb_transfer* transfer) noexcept {
        auto& slot=*static_cast<Slot*>(transfer->user_data); auto& engine=*slot.owner;
        try { engine.retire(slot); } catch(...) { engine.fail(LIBUSB_ERROR_OTHER); }
    }
    void retire(Slot& slot) {
        slot.pending=false; --state.pending; ++state.callbacks;
        uint64_t now=micros();
        if(lastCallback && !draining) state.maxCallbackGapUs=std::max(state.maxCallbackGapUs.load(),now-lastCallback);
        lastCallback=now;
        const auto status=slot.transfer->status;
        const bool cancellation=status==LIBUSB_TRANSFER_CANCELLED;
        if(cancellation) ++state.cancelled;
        else if(status!=LIBUSB_TRANSFER_COMPLETED) { ++state.transferErrors; fail(-100-static_cast<int>(status)); }
        uint64_t end=slot.start;
        for(int i=0;i<slot.transfer->num_iso_packets;++i) {
            const auto& packet=slot.transfer->iso_packet_desc[i];
            end+=packet.length/source.frameBytes;
            if(packet.status==LIBUSB_TRANSFER_COMPLETED && packet.actual_length==packet.length) {
                state.completedBytes+=packet.actual_length; ++state.completedIntervals;
                state.positionFrames=std::max(state.positionFrames.load(),end);
            } else if(!cancellation) { ++state.packetErrors; fail(-200-static_cast<int>(packet.status)); }
        }
        if(!draining && !command() && !state.error && planned<target) {
            if(state.pending==0) ++state.queueEmpty;
            submit(slot);
        }
    }
    void events() {
        timeval timeout{0,20000}; int result=libusb_handle_events_timeout_completed(context,&timeout,nullptr);
        if(result<0 && result!=LIBUSB_ERROR_INTERRUPTED) { fail(result); std::this_thread::sleep_for(std::chrono::milliseconds(1)); }
    }
    void cancelAndDrain() {
        draining=true; state.phase=4;
        for(auto& slot:slots) if(slot.pending) {
            int result=libusb_cancel_transfer(slot.transfer);
            if(result<0 && result!=LIBUSB_ERROR_NOT_FOUND) fail(result);
        }
        const auto start=micros();
        // libusb owns in-flight buffers. Never free them before their callbacks,
        // even if cancellation is slow. Transfers also have a 1-second timeout.
        while(state.pending>0) {
            events(); if(micros()-start>2000000) state.cleanupDelayed=true;
        }
        draining=false;
    }
public:
    IsoExperiment(libusb_context* ctx,libusb_device_handle* dev,StreamState& stats,
                  const std::vector<unsigned char>& pcm,int rate,int bits,int seconds,std::function<bool(bool)> config,std::function<bool()> check={},uint64_t healthPeriodUs=10000000)
        :context(ctx),handle(dev),state(stats),source(pcm,rate,bits),configure(std::move(config)),health(std::move(check)),
         target(uint64_t(seconds)*1000),capacity(bits==16?388:582),healthPeriod(healthPeriodUs) {
        if(seconds!=12 && seconds!=60 && seconds!=3660) throw std::runtime_error("Unsupported test duration");
        for(auto& slot:slots) { slot.owner=this; slot.transfer=libusb_alloc_transfer(PACKETS); }
        for(auto& slot:slots) if(!slot.transfer) { for(auto& item:slots) if(item.transfer) libusb_free_transfer(item.transfer); throw std::runtime_error("No USB transfer memory"); }
    }
    ~IsoExperiment() {
        if(state.pending>0) cancelAndDrain();
        for(auto& slot:slots) libusb_free_transfer(slot.transfer);
    }
    void run() {
        appliedVolume=state.volumeCommand;
        if(state.stop) { state.phase=5; return; }
        if(!configure(false)) { if(!state.stop) fail(LIBUSB_ERROR_IO); return; }
        state.phase=2;
        auto lastHealth=micros();
        for(auto& slot:slots) submit(slot);
        while(!state.stop && !state.error) {
            const bool pause=state.pause;
            const int64_t seek=state.seek.exchange(-1);
            if(pause || seek>=0 || state.volumeCommand!=appliedVolume) {
                cancelAndDrain();
                if(!configure(true)) { if(!state.stop) fail(LIBUSB_ERROR_IO); break; }
                if(pause) ++state.pauseCount;
                if(seek>=0) { source.seek(uint64_t(seek)); state.positionFrames=seek; ++state.seekCount; }
                else source.seek(state.positionFrames);
                planned=state.completedIntervals;
                state.phase=3;
                while(state.pause && !state.stop && !state.error) {
                    int64_t next=state.seek.exchange(-1);
                    if(next>=0) { source.seek(next); state.positionFrames=next; ++state.seekCount; }
                    events();
                }
                if(state.stop || state.error) break;
                appliedVolume=state.volumeCommand;
                if(!configure(false)) { if(!state.stop) fail(LIBUSB_ERROR_IO); break; }
                state.phase=2; lastCallback=0;
                for(auto& slot:slots) submit(slot);
            }
            if(command()) continue;
            if(health && micros()-lastHealth>=healthPeriod) {
                ++state.healthChecks;
                if(!health()) { fail(-400); break; }
                lastHealth=micros();
            }
            if(state.pending==0) {
                if(state.completedIntervals<target) fail(-300);
                break;
            }
            events();
        }
        cancelAndDrain();
        state.phase=state.error?6:5;
    }
};
