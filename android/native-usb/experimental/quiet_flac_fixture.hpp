#pragma once
#include "flac_source_owner.hpp"
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#endif

// Worker-only whole-file preflight for the initial six-second Probe fixture.
// Not a USB permission or an immutable-source guarantee. The caller must keep
// the underlying private fixture unchanged between preflight and playback.
struct QuietFlacFixture {
    enum class Status { Valid, Cancelled, InvalidInput, InvalidFormat, SourceFailed, WrongLength, TooLoud, Unverified };
    Status status=Status::SourceFailed;
    uint64_t frames=0,fnv1a64=14695981039346656037ULL;
    bool md5Verified=false;
};
inline QuietFlacFixture validateQuietFlacFixture(int fd,unsigned rate,unsigned bits,const std::atomic<bool>& stop) {
    using Status=QuietFlacFixture::Status;
    QuietFlacFixture result;
    if(stop.load()){result.status=Status::Cancelled;return result;}
    if((rate!=44100 && rate!=48000 && rate!=88200 && rate!=96000) || (bits!=16 && bits!=24)) {
        result.status=Status::InvalidFormat;return result;
    }
    // Encoded work is also capped. Accept only a regular whole-file input;
    // descriptor offset may change through the owner's synchronous duplicate.
#ifdef _WIN32
    struct _stat64 info{};
    const bool regular=fd>=0 && _fstat64(fd,&info)==0 && (info.st_mode&_S_IFMT)==_S_IFREG;
#else
    struct stat info{};
    const bool regular=fd>=0 && fstat(fd,&info)==0 && S_ISREG(info.st_mode);
#endif
    if(!regular || info.st_size<=0 || info.st_size>8*1024*1024) {
        result.status=Status::InvalidInput;return result;
    }
    FlacSourceOwner owner;
    const auto source=owner.replaceFd(fd,rate,bits);
    FlacSourceOwner::Block block;
    const uint64_t target=uint64_t(rate)*6;
    const int64_t ceiling=((int64_t(1)<<(bits-1))-1)*8/1000;
    for(;;) {
        if(stop.load()){result.status=Status::Cancelled;return result;}
        const auto read=owner.pull(source,FlacSourceOwner::MAX_PULL_FRAMES,block);
        switch(read.status) {
            case PcmFrameQueue::Read::NeedData:
                // Worker only. Wait for existing producer notification rather
                // than spinning/polling. Stop is observed at the next event;
                // blocked IO and the final join have NO stop-latency bound.
                owner.wait(read.event);break;
            case PcmFrameQueue::Read::Data: {
                if(block.firstFrame()!=result.frames || block.frames()>target-result.frames) {
                    result.status=Status::WrongLength;return result;
                }
                bool loud=false;
                const bool current=owner.deliver(block,[&](const unsigned char* data,size_t bytes) {
                    for(size_t p=0;p<bytes;p+=bits/8) {
                        int32_t value=0;
                        for(unsigned b=0;b<bits/8;++b)value|=int32_t(data[p+b])<<(b*8);
                        if(value&(1<<(bits-1)))value-=1<<bits;
                        if(int64_t(value)>ceiling || int64_t(value)<-ceiling){loud=true;return;}
                    }
                    for(size_t p=0;p<bytes;++p){result.fnv1a64^=data[p];result.fnv1a64*=1099511628211ULL;}
                });
                if(!current){result.status=Status::SourceFailed;return result;}
                if(loud){result.status=Status::TooLoud;return result;}
                result.frames+=block.frames();break;
            }
            case PcmFrameQueue::Read::End:
                result.md5Verified=owner.snapshot().md5Verified;
                result.status=result.frames!=target?Status::WrongLength:!result.md5Verified?Status::Unverified:Status::Valid;
                // Stop racing EOF is cancellation, never approval to start USB.
                if(stop.load())result.status=Status::Cancelled;
                return result;
            default:result.status=stop.load()?Status::Cancelled:Status::SourceFailed;return result;
        }
    }
}
