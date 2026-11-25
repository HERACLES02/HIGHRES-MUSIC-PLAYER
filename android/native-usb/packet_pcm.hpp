#pragma once
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <vector>

// Shared nominal full-speed 1-ms schedule; not clock feedback.
inline unsigned nominalPacketFrames(unsigned rate,uint64_t& phase) noexcept {
    phase+=rate;const unsigned frames=unsigned(phase/1000);phase%=1000;return frames;
}

// Full-speed bInterval=1 only: nominal sample frames per 1ms USB service interval.
// This is NOT feedback compensation or evidence of device clock synchronization.
struct PacketPcm {
    const std::vector<unsigned char>& pcm;
    int rate, frameBytes;
    uint64_t cursor=0, phase=0, compared=0, mismatches=0;
    PacketPcm(const std::vector<unsigned char>& bytes,int hz,int bits):pcm(bytes),rate(hz),frameBytes(2*bits/8) {
        if((bits!=16 && bits!=24) || (hz!=44100 && hz!=48000 && hz!=88200 && hz!=96000) ||
           pcm.size()!=size_t(hz)*6*frameBytes) throw std::runtime_error("Invalid diagnostic PCM");
    }
    int next(unsigned char* output,int capacity) {
        const int frames=nominalPacketFrames(rate,phase);
        const int length=frames*frameBytes;
        if(length>capacity) throw std::runtime_error("Endpoint packet capacity exceeded");
        for(int f=0;f<frames;++f) {
            const size_t offset=(cursor % (pcm.size()/frameBytes))*frameBytes;
            std::memcpy(output+f*frameBytes,pcm.data()+offset,frameBytes);
            if(std::memcmp(output+f*frameBytes,pcm.data()+offset,frameBytes)!=0) ++mismatches;
            ++cursor; compared+=frameBytes;
        }
        return length;
    }
    void seek(uint64_t frame) { cursor=frame; phase=0; }
};
