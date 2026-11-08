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
    // End/Error/Cancelled persist until destruction (End can be restarted by seek).
    // A detected read/decode error takes precedence over simultaneous cancellation.
    // Cancellation is cooperative between file reads/decoded blocks, not an IO interrupt.
    Status next();
    // Borrowed storage: valid only after Block, until the next next()/seek() call.
    Block block() const;
    bool seek(uint64_t frame);
    // True only at clean End after a complete decode from zero with a stored MD5.
    // False includes in-progress, missing MD5, post-seek partial, cancel and error.
    bool md5Verified() const;
    size_t stagingBytes() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
