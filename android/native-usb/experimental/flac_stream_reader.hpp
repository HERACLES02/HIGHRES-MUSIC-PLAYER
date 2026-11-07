#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <memory>

// Offline only. Caller owns the seekable FILE and keeps it open until destruction.
// Only cancellation may be changed from another thread; other methods have one owner.
class FlacStreamReader {
public:
    enum class Status { Block, End, Cancelled, Error };
    struct Info { unsigned rate=0,bits=0; uint64_t totalFrames=0; };
    struct Block { const unsigned char* data; size_t bytes; uint64_t firstFrame; };
    FlacStreamReader(FILE*,const std::atomic<bool>&);
    ~FlacStreamReader();
    FlacStreamReader(const FlacStreamReader&)=delete;
    FlacStreamReader& operator=(const FlacStreamReader&)=delete;
    Info info() const;
    Status next();
    Block block() const;
    bool seek(uint64_t frame);
    bool md5Verified() const;
    size_t stagingBytes() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
