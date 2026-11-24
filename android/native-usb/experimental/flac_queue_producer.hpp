#pragma once
#include "flac_stream_reader.hpp"
#include "pcm_frame_queue.hpp"

// Offline single-owner handoff only. Owner keeps reader, queue and cancel alive
// until its worker is joined. No USB callback or player integration.
class FlacQueueProducer {
public:
    enum class Status { Progress, Full, End, Stopped, Failed, Stale };
    struct Result { Status status; bool md5Verified=false; };
private:
    FlacStreamReader& reader;
    PcmFrameQueue& queue;
    const uint64_t token;
    const std::atomic<bool>& cancel;
    FlacStreamReader::Block pending{nullptr,0,0};
    size_t offset=0;
    bool verified=false;
    Result current() const {
        switch(queue.state(token)) {
            case PcmFrameQueue::State::Open:return {Status::Full,false};
            case PcmFrameQueue::State::Finished:return {Status::End,verified};
            case PcmFrameQueue::State::Stopped:return {Status::Stopped,false};
            case PcmFrameQueue::State::Failed:return {Status::Failed,false};
            default:return {Status::Stale,false};
        }
    }
public:
    FlacQueueProducer(FlacStreamReader& source,PcmFrameQueue& destination,uint64_t generation,const std::atomic<bool>& flag)
        :reader(source),queue(destination),token(generation),cancel(flag) {}
    FlacQueueProducer(const FlacQueueProducer&)=delete;
    FlacQueueProducer& operator=(const FlacQueueProducer&)=delete;
    // Worker-only. Full retains borrowed decoder storage and returns to the
    // owner; it neither spins nor decodes another block. Only this producer may
    // push/finish this generation. Owner may stop/fail/reset, then wake/join it.
    // Strong stop: stop the queue under its lock, then set cancel/wake the
    // worker. The atomic flag alone is cooperative, not a publication barrier.
    Result step() {
        if(queue.state(token)!=PcmFrameQueue::State::Open)return current();
        if(cancel.load()){queue.stop(token);return current();}
        if(offset==pending.bytes) {
            auto status=reader.next();
            if(status==FlacStreamReader::Status::Error){queue.fail(token);return current();}
            if(status==FlacStreamReader::Status::Cancelled){queue.stop(token);return current();}
            if(status==FlacStreamReader::Status::End) {
                verified=reader.md5Verified();
                if(cancel.load())queue.stop(token);else queue.finish(token);
                return current();
            }
            pending=reader.block();offset=0;
        }
        const auto info=reader.info();
        const size_t width=2*info.bits/8;
        if(!queue.matchesFormat(info.rate,info.bits) || !width || !pending.bytes || pending.bytes%width) {
            queue.fail(token);return current();
        }
        if(cancel.load()){queue.stop(token);return current();}
        // Fill currently available whole frames, not a fixed decoder-sized
        // chunk: partial consumer reads must not strand data and deadlock Full.
        const size_t free=queue.capacityBytes()-queue.bufferedFrames()*width;
        if(!free)return current();
        const size_t count=std::min(pending.bytes-offset,free);
        switch(queue.push(token,pending.data+offset,count)) {
            case PcmFrameQueue::Write::Accepted:offset+=count;return {Status::Progress,false};
            case PcmFrameQueue::Write::Full:return current();
            case PcmFrameQueue::Write::Stale:return {Status::Stale,false};
            case PcmFrameQueue::Write::Closed:return current();
            default:queue.fail(token);return current();
        }
    }
};
