#pragma once
#include "pcm_frame_queue.hpp"
#include <array>
#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

// Offline only. One serialized control caller for replace/stop/destruction;
// one consumer may run concurrently with that control caller. The object must
// outlive its consumer. No JNI, service, USB or audio callback.
class FlacSourceOwner {
public:
    enum class Status { Starting, Running, End, Stopped, Failed };
    static constexpr size_t MAX_PULL_FRAMES=1024;
    class Block {
        friend class FlacSourceOwner;
        std::array<unsigned char,MAX_PULL_FRAMES*6> pcm{};
        uint64_t owner=0,job=0,first=0;
        size_t count=0;
        unsigned sampleRate=0,sampleBits=0;
    public:
        const unsigned char* data() const{return pcm.data();}
        size_t bytes() const{return count*sampleBits/8*2;}
        size_t frames() const{return count;}
        uint64_t source() const{return job;}
        uint64_t firstFrame() const{return first;}
        unsigned rate() const{return sampleRate;}
        unsigned bits() const{return sampleBits;}
    };
    struct Snapshot {uint64_t source;Status status;unsigned rate,bits;bool md5Verified;};
    struct Pull {PcmFrameQueue::Read status;uint64_t event;};
private:
    struct InputFd {
        int value;
        explicit InputFd(int fd=-1):value(fd){}
        InputFd(InputFd&& other):value(other.release()){}
        ~InputFd();
        int release(){int fd=value;value=-1;return fd;}
    };
    mutable std::mutex mutex;
    std::condition_variable cv;
    std::unique_ptr<PcmFrameQueue> queue;
    std::thread worker;
    std::atomic<bool> cancel{false};
    const uint64_t identity;
    uint64_t source=0,event=0,queueToken=0,cursor=0;
    Status phase=Status::Stopped;
    unsigned rate=0,bits=0;
    bool verified=false;
    uint64_t replaceInput(std::string,InputFd,unsigned,unsigned,uint64_t);
    void run(PcmFrameQueue*,uint64_t,uint64_t,std::string,InputFd,uint64_t);
public:
    FlacSourceOwner();
    ~FlacSourceOwner();
    FlacSourceOwner(const FlacSourceOwner&)=delete;
    FlacSourceOwner& operator=(const FlacSourceOwner&)=delete;
    uint64_t replace(std::string path,unsigned rate,unsigned bits,uint64_t firstFrame=0);
    // Whole-file seekable descriptors only. Duplicates synchronously; caller
    // keeps fd valid during this call, then may close it. dup shares the file
    // position: caller must not read/seek its copy while the job is active.
    uint64_t replaceFd(int borrowedFd,unsigned rate,unsigned bits,uint64_t firstFrame=0);
    void stop();
    Snapshot snapshot() const;
    Pull pull(uint64_t source,size_t wanted,Block&);
    void wait(uint64_t observedEvent);
    // Validation and the bounded synchronous copy run under the owner lock.
    // Callback must not block, reenter this owner, or retain the data pointer.
    // This commits a copy only; it cannot retract already-copied/in-flight data.
    template<class Copy> bool deliver(const Block& block,Copy&& copy) {
        std::lock_guard<std::mutex> lock(mutex);
        if(block.owner!=identity || block.job!=source || !block.count || !queue || phase==Status::Stopped || phase==Status::Failed)return false;
        const auto state=queue->state(queueToken);
        if(state==PcmFrameQueue::State::Stopped || state==PcmFrameQueue::State::Failed || state==PcmFrameQueue::State::Stale)return false;
        copy(block.data(),block.bytes());return true;
    }
};
