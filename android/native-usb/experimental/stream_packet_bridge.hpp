#pragma once
#include "flac_source_owner.hpp"
#include "../packet_pcm.hpp"
#include <array>
#include <exception>
#include <limits>

// Offline coordinator. All methods except complete() have ONE serialized
// worker caller. Transport borrows buffers until completion, even if cancel
// reports NOT_FOUND. No owner/queue method runs on a transport callback.
class StreamPacketBridge {
public:
    enum class State { Stopped, Running, End, Failed, Stale };
    enum class Step { Ready, Full, NeedData, Terminal };
    enum class Completion { Pending, Done, Cancelled, Error };
    // Decoded descriptor fields. Only full-speed 1-ms isochronous data OUT.
    struct Endpoint { unsigned address,attributes,interval,maxPacketBytes; bool fullSpeed; };
    struct Packet { size_t slot=0; uint64_t ticket=0,source=0,firstFrame=0; const unsigned char* data=nullptr; size_t bytes=0,frames=0; };
    // Full-speed one-millisecond packets: fixed 64ms scheduling capacity.
    static constexpr size_t SLOT_COUNT=64,PACKET_BYTES=582;
private:
    enum class Life { Free, Ready, Pending };
    struct Slot {
        std::array<unsigned char,PACKET_BYTES> pcm{};
        Packet packet;
        Life life=Life::Free;
        bool cancelSent=false;
        std::atomic<uint64_t> ticket{0};
        std::atomic<Completion> completion{Completion::Pending};
    };
    static_assert(std::atomic<uint64_t>::is_always_lock_free,"Callback tickets must be lock-free");
    static_assert(std::atomic<Completion>::is_always_lock_free,"Callback results must be lock-free");
    FlacSourceOwner owner;
    FlacSourceOwner::Block block;
    std::array<Slot,SLOT_COUNT> slots{};
    State phase=State::Stopped;
    unsigned rate=0;
    uint64_t source=0,schedule=0,nextTicket=0;
    bool retiring=true,verified=false;
    void discard(State why) {
        phase=why;retiring=true;verified=false;
        for(auto& slot:slots)if(slot.life==Life::Ready)slot.life=Life::Free;
        owner.stop();
    }
    void reap() {
        for(auto& slot:slots)if(slot.life==Life::Pending) {
            const auto result=slot.completion.load(std::memory_order_acquire);
            if(result==Completion::Pending)continue;
            slot.life=Life::Free;
            if(!retiring && result!=Completion::Done)discard(State::Failed);
        }
    }
    bool current() {
        const auto snapshot=owner.snapshot();
        if(snapshot.source!=source){discard(State::Stale);return false;}
        if(snapshot.status==FlacSourceOwner::Status::Failed){discard(State::Failed);return false;}
        if(snapshot.status==FlacSourceOwner::Status::Stopped){discard(State::Stopped);return false;}
        return true;
    }
    template<class Replace> bool startInput(unsigned hz,unsigned depth,Endpoint endpoint,Replace&& replace) {
        reap();
        if(!drained() || (phase==State::Running && !retiring))return false;
        if(!supports(hz,depth,endpoint))return false;
        owner.stop();phase=State::Stopped;retiring=true;verified=false;
        source=replace();rate=hz;schedule=0;phase=State::Running;retiring=false;return true;
    }
public:
    static bool supports(unsigned hz,unsigned depth,Endpoint endpoint) {
        const bool format=(hz==44100 || hz==48000 || hz==88200 || hz==96000) && (depth==16 || depth==24);
        const bool out=endpoint.address && endpoint.address<=15;
        return format && out && (endpoint.attributes&3)==1 && (endpoint.attributes&0x30)==0 &&
            endpoint.fullSpeed && endpoint.interval==1 && endpoint.maxPacketBytes<=PACKET_BYTES &&
            endpoint.maxPacketBytes>=((hz+999)/1000)*2*(depth/8);
    }
    StreamPacketBridge()=default;
    StreamPacketBridge(const StreamPacketBridge&)=delete;
    StreamPacketBridge& operator=(const StreamPacketBridge&)=delete;
    ~StreamPacketBridge() {
        // Caller owns event dispatch: stop/cancel/drain before destruction.
        // Silently freeing borrowed storage would cause a use-after-free.
        if(!drained())std::terminate();
    }
    bool drained()const {
        for(const auto& slot:slots)if(slot.life!=Life::Free)return false;
        return true;
    }
    bool start(std::string path,unsigned hz,unsigned depth,Endpoint endpoint,uint64_t first=0) {
        // Validate before source allocation. No format change while a callback
        // owns a buffer. No feedback endpoint or shared capture clock assumed.
        return startInput(hz,depth,endpoint,[&]{return owner.replace(std::move(path),hz,depth,first);});
    }
    // Control worker only: joins source, potentially waiting for storage IO.
    // Retrieve cancellation requests separately, then drain every completion.
    void stop(){discard(State::Stopped);}
    // Whole regular file only. Existing owner duplicates synchronously after
    // acceptance; caller can close immediately. Retained copies share position
    // and must not read/seek concurrently. Rejection does not touch the fd.
    bool startFd(int fd,unsigned hz,unsigned depth,Endpoint endpoint,uint64_t first=0) {
        return startInput(hz,depth,endpoint,[&]{return owner.replaceFd(fd,hz,depth,first);});
    }
    Step prepare() {
        reap();
        if(retiring || phase!=State::Running)return Step::Terminal;
        if(!current())return Step::Terminal;
        Slot* free=nullptr;
        for(auto& slot:slots)if(slot.life==Life::Free){free=&slot;break;}
        if(!free)return Step::Full;
        uint64_t nextPhase=schedule;const auto wanted=nominalPacketFrames(rate,nextPhase);
        const auto pulled=owner.pull(source,wanted,block);
        using Read=PcmFrameQueue::Read;
        if(pulled.status==Read::NeedData)return Step::NeedData;
        if(pulled.status==Read::End) {
            phase=State::End;verified=owner.snapshot().md5Verified;return Step::Terminal;
        }
        if(pulled.status!=Read::Data) {
            discard(pulled.status==Read::Stale?State::Stale:pulled.status==Read::Stopped?State::Stopped:State::Failed);
            return Step::Terminal;
        }
        if(nextTicket==std::numeric_limits<uint64_t>::max()){discard(State::Failed);return Step::Terminal;}
        if(!owner.deliver(block,[&](const unsigned char* data,size_t bytes){std::memcpy(free->pcm.data(),data,bytes);})) {
            // Queue failure can invalidate the pulled block before the source
            // worker has closed FILE/published Failed. Never call that gap EOF
            // or Terminal while the bridge still reports Running.
            return current()?Step::NeedData:Step::Terminal;
        }
        schedule=nextPhase;
        free->packet={size_t(free-slots.data()),++nextTicket,source,block.firstFrame(),free->pcm.data(),block.bytes(),block.frames()};
        free->life=Life::Ready;return Step::Ready;
    }
    // Oldest prepared packet becomes in-flight. A synchronous submit failure
    // must be returned via complete(Error); its PCM is never silently retried.
    bool submit(Packet& packet) {
        reap();
        if(retiring || (phase!=State::Running && phase!=State::End) || !current())return false;
        Slot* oldest=nullptr;
        for(auto& slot:slots)if(slot.life==Life::Ready && (!oldest || slot.packet.ticket<oldest->packet.ticket))oldest=&slot;
        if(!oldest)return false;
        oldest->cancelSent=false;oldest->completion.store(Completion::Pending,std::memory_order_relaxed);
        oldest->ticket.store(oldest->packet.ticket,std::memory_order_release);
        oldest->life=Life::Pending;packet=oldest->packet;return true;
    }
    bool cancel(Packet& packet) {
        reap();if(!retiring)return false;
        for(auto& slot:slots)if(slot.life==Life::Pending && !slot.cancelSent) {
            slot.cancelSent=true;packet=slot.packet;return true;
        }
        return false;
    }
    // Only callback entry. Bounded lock-free atomics: no allocation, IO,
    // owner calls, logging, Java, locks or resubmission. Reject stale tickets.
    bool complete(size_t index,uint64_t ticket,Completion result) noexcept {
        if(index>=slots.size() || !ticket || result==Completion::Pending)return false;
        auto& slot=slots[index];auto expected=ticket;
        if(!slot.ticket.compare_exchange_strong(expected,0,std::memory_order_acq_rel))return false;
        slot.completion.store(result,std::memory_order_release);return true;
    }
    State state()const{return phase;}
    bool md5Verified()const{return phase==State::End && verified;}
};
