#pragma once
#include "libusb_packet_adapter.hpp"
#include <functional>
#include <sys/stat.h>
#ifdef _WIN32
#include <io.h>
#else
#include <fcntl.h>
#include <unistd.h>
#endif

// Offline only. ONE serialized worker owns commands, step, configuration and
// destruction. Only requestStop may run concurrently. No command/owner/file IO
// runs on a USB callback. Context/handle/configure captures must outlive this.
// Stop/Failure close this session: a new session is needed to play again.
class StreamSourceSession {
public:
    enum class State { Idle, Changing, Running, End, Stopped, Failed, Pausing, Paused };
    enum class Integrity { Unverified, Partial, WholeStreamVerified };
    struct Source { unsigned rate,bits; StreamPacketBridge::Endpoint endpoint; uint64_t first=0,generation=0; };
    using Configure=std::function<bool(const Source&)>;
private:
    std::atomic<bool> stopped{false};
    const std::atomic<bool>* externalStop;
    bool stopping()const{return stopped.load() || (externalStop && externalStop->load());}
    LibusbPacketAdapter adapter;
    Configure configure;
    int currentFd=-1,pendingFd=-1;
    Source current{},pending{};
    uint64_t sequence=0;
    State phase=State::Idle;
    static void closeInput(int& fd) {
        if(fd<0)return;
#ifdef _WIN32
        _close(fd);
#else
        close(fd);
#endif
        fd=-1;
    }
    static int retain(int fd) {
        if(fd<0)return -1;
#ifdef _WIN32
        struct _stat64 info{};
        if(_fstat64(fd,&info) || (info.st_mode&_S_IFMT)!=_S_IFREG)return -1;
        return _dup(fd);
#else
        struct stat info{};
        if(fstat(fd,&info) || !S_ISREG(info.st_mode))return -1;
        return fcntl(fd,F_DUPFD_CLOEXEC,0);
#endif
    }
    void finish(State why) {
        phase=why;closeInput(pendingFd);closeInput(currentFd);
    }
public:
    StreamSourceSession(libusb_context* ctx,libusb_device_handle* device,Configure change,const std::atomic<bool>* stop=nullptr):
        externalStop(stop),adapter(ctx,device,&stopped,stop),configure(std::move(change)) {}
    StreamSourceSession(const StreamSourceSession&)=delete;
    StreamSourceSession& operator=(const StreamSourceSession&)=delete;
    ~StreamSourceSession() {
        requestStop();closeInput(pendingFd);
        // Cancellation/NOT_FOUND is not retirement. No timeout may free storage
        // still borrowed by USB; slow storage/event completion can delay this.
        do {adapter.pump();adapter.events();} while(!adapter.drained());
        closeInput(currentFd);
    }
    // Whole seekable regular file; no APK slice, pipe or encoded staging copy.
    // Accepted fd is duplicated synchronously. Caller may close it, but must not
    // read/seek/mutate its shared open-file description while this job is active.
    // Return zero leaves the current job and incoming fd untouched.
    uint64_t replaceFd(int fd,unsigned rate,unsigned bits,StreamPacketBridge::Endpoint endpoint,uint64_t first=0) {
        if(stopping() || phase==State::Failed || phase==State::Pausing || phase==State::Paused || pendingFd>=0 || sequence==UINT64_MAX ||
                !StreamPacketBridge::supports(rate,bits,endpoint))return 0;
        const int retained=retain(fd);if(retained<0)return 0;
        pendingFd=retained;pending={rate,bits,endpoint,first,++sequence};
        phase=State::Changing;adapter.requestStop();return sequence;
    }
    uint64_t seek(uint64_t frame) {
        if(currentFd<0)return 0;
        return replaceFd(currentFd,current.rate,current.bits,current.endpoint,frame);
    }
    void requestStop() noexcept{stopped=true;}
    // Worker only, like seek/replace. Pause drains successful completions rather
    // than cancelling PCM. Seek/replace while paused are rejected; resume first.
    bool setPaused(bool value) {
        if(stopping() || (phase!=State::Running && phase!=State::Pausing && phase!=State::Paused))return false;
        adapter.setPaused(value);
        phase=value?(adapter.stats().pending?State::Pausing:State::Paused):State::Running;
        return true;
    }
    void step() {
        if(stopping() || phase==State::Failed)adapter.requestStop();
        adapter.pump();adapter.events();adapter.pump();
        // A failure while retiring the outgoing source cancels the queued
        // replacement. It must not be cleared by adapter.startFd.
        if(adapter.error()) {
            closeInput(pendingFd);phase=State::Failed;
            if(adapter.drained())finish(State::Failed);
            return;
        }
        if(stopping()) {
            closeInput(pendingFd);
            if(adapter.drained())finish(State::Stopped);
            return;
        }
        if(pendingFd>=0) {
            if(!adapter.drained())return;
            closeInput(currentFd);
            bool configured=false;
            try {configured=configure(pending);} catch(...) {finish(State::Failed);return;}
            if(stopping()){finish(State::Stopped);return;}
            if(!configured){finish(State::Failed);return;}
            current=pending;currentFd=pendingFd;pendingFd=-1;
            bool started=false;
            try {started=adapter.startFd(currentFd,current.rate,current.bits,current.endpoint,current.first);}
            catch(...) {finish(State::Failed);return;}
            if(!started) {
                finish(State::Failed);return;
            }
            phase=State::Running;return;
        }
        if((phase==State::Running || phase==State::Pausing || phase==State::Paused) && adapter.state()==LibusbPacketAdapter::State::End)phase=State::End;
        else if(phase==State::Pausing && !adapter.stats().pending)phase=State::Paused;
    }
    State state()const{return phase;}
    Integrity integrity()const {
        if(phase!=State::End)return Integrity::Unverified;
        if(current.first)return Integrity::Partial;
        return adapter.md5Verified()?Integrity::WholeStreamVerified:Integrity::Unverified;
    }
    uint64_t generation()const{return current.generation;}
    uint64_t firstFrame()const{return current.first;}
    bool drained()const{return pendingFd<0 && adapter.drained();}
    bool starved()const{return phase==State::Running && !adapter.stats().pending;}
    LibusbPacketAdapter::Stats stats()const{return adapter.stats();}
};
