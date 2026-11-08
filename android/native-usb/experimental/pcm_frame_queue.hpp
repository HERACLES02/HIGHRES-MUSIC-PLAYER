#pragma once
#include <cstddef>
#include <cstdint>
#include <algorithm>
#include <cstring>
#include <limits>
#include <mutex>
#include <stdexcept>
#include <vector>

// Offline prototype only. Not connected to JNI, USB transfers or Local Music.
class PcmFrameQueue {
    enum class Life { Open, Finished, Stopped, Failed };
    static constexpr size_t MAX_BYTES=1024*1024;
    const unsigned sampleRate,sampleBits;
    const size_t frameBytes, capacity;
    std::vector<unsigned char> bytes;
    mutable std::mutex mutex;
    size_t head=0, filled=0;
    uint64_t epoch=1;
    Life life=Life::Open;
    static size_t frameSize(int rate,int bits) {
        if((rate!=44100 && rate!=48000 && rate!=88200 && rate!=96000) ||
           (bits!=16 && bits!=24)) throw std::invalid_argument("Unsupported PCM format");
        return size_t(2*bits/8);
    }
    static size_t storageSize(size_t width,size_t frames) {
        if(frames==0 || frames>MAX_BYTES/width) throw std::invalid_argument("Invalid PCM queue capacity");
        return width*frames;
    }
public:
    enum class Write { Accepted, Full, Stale, Closed, Invalid };
    enum class Read { Data, NeedData, End, Stopped, Failed, Stale, Invalid };
    enum class State { Open, Finished, Stopped, Failed, Stale };
    struct Result { Read status; size_t frames; bool final; };
    PcmFrameQueue(int rate,int bits,size_t maxFrames)
        :sampleRate(rate),sampleBits(bits),frameBytes(frameSize(rate,bits)),capacity(maxFrames),bytes(storageSize(frameBytes,maxFrames)) {}
    bool matchesFormat(unsigned rate,unsigned bits) const { return rate==sampleRate && bits==sampleBits; }
    State state(uint64_t token) const {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch)return State::Stale;
        switch(life) {
            case Life::Open:return State::Open;
            case Life::Finished:return State::Finished;
            case Life::Stopped:return State::Stopped;
            default:return State::Failed;
        }
    }
    size_t capacityBytes() const { return bytes.size(); }
    size_t bufferedFrames() const { std::lock_guard<std::mutex> lock(mutex); return filled; }
    uint64_t generation() const { std::lock_guard<std::mutex> lock(mutex); return epoch; }
    // Decoder chunks from a previous seek/track can never enter the new source.
    uint64_t reset() {
        std::lock_guard<std::mutex> lock(mutex);
        if(epoch==std::numeric_limits<uint64_t>::max()) throw std::overflow_error("PCM generation exhausted");
        ++epoch;head=filled=0;life=Life::Open;return epoch;
    }
    Write push(uint64_t token,const unsigned char* input,size_t count) {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch)return Write::Stale;
        if(life!=Life::Open)return Write::Closed;
        if(!input || count==0 || count%frameBytes!=0)return Write::Invalid;
        const size_t frames=count/frameBytes;
        if(frames>capacity-filled)return Write::Full;
        const size_t tail=(head+filled)%capacity;
        const size_t first=std::min(frames,capacity-tail);
        std::memcpy(bytes.data()+tail*frameBytes,input,first*frameBytes);
        if(frames>first)std::memcpy(bytes.data(),input+first*frameBytes,(frames-first)*frameBytes);
        filled+=frames;return Write::Accepted;
    }
    // A short read is allowed only after the producer explicitly finishes.
    // NeedData never advances the queue or overwrites the output with silence.
    // This is a source read, not an isochronous packet or rendered timestamp.
    Result pull(uint64_t token,size_t wanted,unsigned char* output,size_t outputBytes) {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch)return {Read::Stale,0,false};
        if(life==Life::Stopped)return {Read::Stopped,0,false};
        if(life==Life::Failed)return {Read::Failed,0,false};
        if(!output || wanted==0 || wanted>capacity || outputBytes/frameBytes<wanted)
            return {Read::Invalid,0,false};
        if(filled==0 && life==Life::Finished)return {Read::End,0,true};
        if(filled<wanted && life!=Life::Finished)return {Read::NeedData,0,false};
        const size_t frames=std::min(filled,wanted);
        const size_t first=std::min(frames,capacity-head);
        std::memcpy(output,bytes.data()+head*frameBytes,first*frameBytes);
        if(frames>first)std::memcpy(output+first*frameBytes,bytes.data(),(frames-first)*frameBytes);
        head=(head+frames)%capacity;filled-=frames;
        return {Read::Data,frames,life==Life::Finished && filled==0};
    }
    // Call finish only once decoding/integrity checks have completed. End is
    // distinct from corruption and interruption; neither loops nor auto-starts.
    bool finish(uint64_t token) {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch || life!=Life::Open)return false;
        life=Life::Finished;return true;
    }
    bool stop(uint64_t token) {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch || life==Life::Stopped || life==Life::Failed)return false;
        life=Life::Stopped;head=filled=0;return true;
    }
    bool fail(uint64_t token) {
        std::lock_guard<std::mutex> lock(mutex);
        if(token!=epoch || life==Life::Stopped || life==Life::Failed)return false;
        life=Life::Failed;head=filled=0;return true;
    }
};
