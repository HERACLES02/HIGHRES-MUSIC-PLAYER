// Reuse the proven simulated transport and allocation/IO instrumentation.
#define main adapter_test_main
#include "libusb_packet_adapter_test.cpp"
#undef main
#include "../experimental/stream_source_session.hpp"
#ifndef _WIN32
#include <dirent.h>
#endif
using Session=StreamSourceSession;
using State=Session::State;
using Integrity=Session::Integrity;
size_t handles() {
#ifdef _WIN32
    DWORD count=0;check(GetProcessHandleCount(GetCurrentProcess(),&count),"process handle sample");return count;
#else
    auto* directory=opendir("/proc/self/fd");check(directory,"fd population sample");
    size_t count=0;while(readdir(directory))++count;closedir(directory);return count;
#endif
}
void advance(Session& session,const std::function<bool()>& done) {
    auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);
    auto before=fake::frames;
    do {
        check(std::chrono::steady_clock::now()<deadline,"session makes bounded test progress");
        session.step();std::this_thread::yield();
        if(before!=fake::frames){before=fake::frames;deadline=std::chrono::steady_clock::now()+std::chrono::seconds(15);}
    } while(!done());
}
void ended(Session& session,State state,Integrity integrity) {
    advance(session,[&]{return session.state()!=State::Changing && session.state()!=State::Running && session.drained();});
    check(session.state()==state && session.integrity()==integrity,"explicit session terminal and integrity");
    check(!fake::count,"terminal has no borrowed USB buffers");
    if(state==State::End && fake::reference)check(std::fgetc(fake::reference)==EOF,"entire independent PCM suffix, no padding or loop");
}
int main(int argc,char** argv) {
    check(argc==6 || argc==10,"disposable source/reference/rate/bits/mode/replacement");
    const unsigned rate=std::stoul(argv[3]),bits=std::stoul(argv[4]);const std::string mode=argv[5];
#ifdef _WIN32
    if(mode=="pipe")_setmode(_fileno(stdout),_O_BINARY);
#endif
    const unsigned otherRate=argc==10?std::stoul(argv[8]):rate,otherBits=argc==10?std::stoul(argv[9]):bits;
    size_t configurations=0;bool failConfiguration=false,stopConfiguration=false,hold=false;
    Session* active=nullptr;
    auto configure=[&](const Session::Source& source) {
        check(!inCallback && !fake::count,"format/source configuration strictly after final old callback");
        const bool other=source.bits!=bits;
        fake::reset(source.rate,source.bits,other?argv[7]:argv[2],mode=="seek-fail"?0:source.first);
        if(hold)fake::delay=100000;
        ++configurations;
        if(stopConfiguration){std::thread stop([&]{active->requestStop();});stop.join();}
        if(failConfiguration && mode=="configure-throw")throw std::runtime_error("configuration failure");
        if(mode=="source-allocation")failNextNew=true;
        return !failConfiguration;
    };
    {
        Session session(nullptr,nullptr,configure);active=&session;
        auto replace=[&](bool other=false,uint64_t first=0) {
            const int fd=inputFd(other?argv[6]:argv[1]);check(fd>=0,"open disposable regular source");
            const auto token=session.replaceFd(fd,other?otherRate:rate,other?otherBits:bits,descriptor(other?otherBits:bits),first);
            closeFd(fd);check(token!=0,"accept ordinary source and synchronously own descriptor");return token;
        };
        auto fill=[&] {hold=true;fake::delay=100000;advance(session,[&]{return session.state()==State::Running && fake::count==Bridge::SLOT_COUNT;});hold=false;};
        if(mode=="starve")gate.arm();
        stopConfiguration=mode=="configure-stop";
        const auto first=replace();
        if(mode=="pause-tail") {
            advance(session,[&]{return fake::frames==rate+137 && fake::count>0;});
            check(session.setPaused(true),"Pause while final packets are in flight");
            ended(session,State::End,Integrity::WholeStreamVerified);
            check(fake::frames==rate+137,"EOF while pausing preserves final partial packet");
        } else if(mode=="pause" || mode=="pause-stop" || mode=="pause-stop-inflight" || mode=="pause-error" || mode=="pause-destroy" || mode=="pause-toggle") {
            fill();const auto attempts=fake::attempts;
            check(session.setPaused(true),"running source accepts Pause");
            check(session.state()==State::Pausing,"Pause explicitly waits for in-flight retirement");
            fake::delay=3;
            if(mode=="pause-error"){fake::fault=3;fake::events=0;fake::delay=0;}
            if(mode=="pause-stop-inflight") {
                fake::cancelMode=1;session.requestStop();ended(session,State::Stopped,Integrity::Unverified);
                check(fake::attempts==attempts && !session.setPaused(false),"Stop while Pausing drains late callbacks without revival");
            }
            else if(mode=="pause-destroy") { /* destructor drains the paused in-flight owner */ }
            else if(mode=="pause-error")ended(session,State::Failed,Integrity::Unverified);
            else {
                if(mode=="pause-toggle") {
                    check(session.setPaused(false),"Resume can supersede pending Pause");
                    check(session.setPaused(true),"Second Pause remains bounded");
                }
                advance(session,[&]{return session.state()==State::Paused;});
                check(!fake::count && fake::attempts==attempts,"Pause retires borrows without submitting new PCM");
                const auto frames=fake::frames;
                for(int i=0;i<100;++i)session.step();
                check(fake::frames==frames && fake::attempts==attempts && session.generation()==first && configurations==1,"Paused source neither advances nor restarts/configures");
                check(session.integrity()==Integrity::Unverified && !session.starved(),"Pause is neither EOF/integrity nor starvation");
                check(!session.seek(5001),"Paused seek explicitly rejected; resume first");
                const int fd=inputFd(argv[1]);
                check(!session.replaceFd(fd,rate,bits,descriptor(bits)),"Paused replacement explicitly rejected before consuming fd");closeFd(fd);
                if(mode=="pause-stop") {
                    session.requestStop();ended(session,State::Stopped,Integrity::Unverified);
                    check(!session.setPaused(false),"Resume cannot revive Stop");
                } else {
                    check(session.setPaused(false),"Paused source accepts Resume");
                    ended(session,State::End,Integrity::WholeStreamVerified);
                    check(fake::frames==rate+137 && configurations==1 && session.generation()==first,"Resume preserves exact entire reference PCM and whole MD5");
                    check(!session.setPaused(true),"EOF cannot be silently restarted by Pause");
                }
            }
        } else if(mode=="source-allocation") {
            ended(session,State::Failed,Integrity::Unverified);
            check(configurations==1 && !fake::attempts,"source allocation failure becomes an explicit failed terminal");
        } else if(mode=="stop-before-start" || mode=="configure-stop") {
            if(mode=="stop-before-start")session.requestStop();
            ended(session,State::Stopped,Integrity::Unverified);
            check(configurations==size_t(mode=="configure-stop") && !fake::attempts,"Stop cannot start the pending source");
        } else if(mode=="stop" || mode=="configure-fail" || mode=="configure-throw" || mode=="retiring-error" || mode=="destroy") {
            fill();const auto target=replace(true);check(target>first,"bounded pending replacement generation");
            fake::cancelMode=1;fake::delay=3;
            if(mode=="stop") {std::thread stop([&]{session.requestStop();});stop.join();}
            if(mode=="configure-fail" || mode=="configure-throw")failConfiguration=true;
            if(mode=="retiring-error"){fake::delay=0;fake::events=0;fake::fault=3;}
            if(mode!="destroy") {
                ended(session,mode=="stop"?State::Stopped:State::Failed,Integrity::Unverified);
                check(configurations==size_t(failConfiguration?2:1) && session.generation()==first,"Stop/failure never promotes a pending source");
                const int fd=inputFd(argv[1]);
                check(session.replaceFd(fd,rate,bits,descriptor(bits))==0,"closed session cannot revive after Stop/failure");closeFd(fd);
            }
            // destroy intentionally leaves the scope with old transfers queued.
        } else if(mode=="seek-fail" || mode=="metadata-mismatch") {
            fill();
            if(mode=="seek-fail")check(session.seek(UINT64_MAX)!=0,"out-of-range seek reported asynchronously");
            else {
                const int fd=inputFd(argv[1]);
                check(session.replaceFd(fd,otherRate,bits,descriptor(bits))!=0,"expected metadata checked by actual reader");closeFd(fd);
            }
            fake::cancelMode=1;fake::delay=2;ended(session,State::Failed,Integrity::Unverified);
            check(!fake::attempts,"bad seek/metadata emits no source PCM");
        } else if(mode=="invalid") {
            ended(session,State::End,Integrity::WholeStreamVerified);
            const int fd=inputFd(argv[1]);
            check(!session.replaceFd(-1,rate,bits,descriptor(bits)) && !session.replaceFd(fd,192000,24,descriptor(24)),"invalid descriptor/unsupported source rate rejected");
            for(int bad:{0,1,2,3,4,5}) {
                auto endpoint=descriptor(bits);
                if(bad==0)endpoint.address=0x84;
                if(bad==1)endpoint.interval=2;
                if(bad==2)endpoint.maxPacketBytes=1;
                if(bad==3)endpoint.maxPacketBytes=583;
                if(bad==4)endpoint.fullSpeed=false;
                if(bad==5)endpoint.attributes=0x15;
                check(!session.replaceFd(fd,rate,bits,endpoint),"invalid endpoint rejected before drain/configuration");
            }
            int pipes[2];
#ifdef _WIN32
            check(_pipe(pipes,256,_O_BINARY)==0,"create disposable withheld pipe");
            check(_lseeki64(fd,0,SEEK_CUR)==0,"rejection does not consume incoming source");
#else
            check(pipe(pipes)==0,"create disposable withheld pipe");
            check(lseek(fd,0,SEEK_CUR)==0,"rejection does not consume incoming source");
#endif
            check(!session.replaceFd(pipes[0],rate,bits,descriptor(bits)),"withheld pipe rejected without reading");
            closeFd(pipes[0]);closeFd(pipes[1]);closeFd(fd);
            check(configurations==1 && session.generation()==first && session.state()==State::End,"invalid command preserves existing terminal state");
        } else if(mode=="starve") {
            session.step();gate.wait();
            for(int i=0;i<20;++i){session.step();check(session.state()==State::Running && session.starved() && !fake::attempts,"starvation emits no silence/EOF/failure");}
            std::thread stop([&]{session.requestStop();});stop.join();gate.release();
            ended(session,State::Stopped,Integrity::Unverified);
        } else if(mode=="lifecycle") {
            fill();check(session.generation()==first,"active generation identifies source");
            for(int i=0;i<20;++i){session.step();fake::checkPending();check(fake::count==Bridge::SLOT_COUNT && fake::attempts==Bridge::SLOT_COUNT,"bounded source backpressure");}
            const auto sought=session.seek(5001);check(sought>first,"seek creates a new generation");
            const int fd=inputFd(argv[6]);
            check(session.replaceFd(fd,otherRate,otherBits,descriptor(otherBits))==0,"only one outstanding command");
            closeFd(fd);
            fake::cancelMode=1;fake::delay=3;
            for(int i=0;i<3;++i){session.step();check(configurations==1 && session.generation()==first && fake::count==Bridge::SLOT_COUNT,"NOT_FOUND cancellation waits for actual late retirement");}
            ended(session,State::End,Integrity::Partial);check(session.generation()==sought,"seek generation starts after drain");
            check(fake::frames==rate+137-5001,"exact unaligned seek suffix");
            check(session.seek(0)>sought,"restart from zero explicitly");ended(session,State::End,Integrity::WholeStreamVerified);
            // Replace with a different format while transfers still own old PCM.
            check(session.seek(0)!=0,"restart for in-flight format change");fill();
            const auto replaced=replace(true);fake::cancelMode=1;fake::delay=4;
            ended(session,State::End,Integrity::WholeStreamVerified);
            check(session.generation()==replaced && fake::frames==otherRate+137,"replacement exact rate/frame count, no stale prefix");
        } else if(mode=="stress") {
            ended(session,State::End,Integrity::WholeStreamVerified);
            size_t heap10=0,resident10=0,handles10=0;
            for(int cycle=0;cycle<110;++cycle) {
                const bool other=cycle%2;replace(other);fill();
                check(session.seek(5001)!=0,"seek command during in-flight stress");fake::cancelMode=1;fake::delay=2;
                ended(session,State::End,Integrity::Partial);
                memory();if(cycle==9){heap10=peakHeap;resident10=peakResident;handles10=handles();}
            }
            check(handles()<=handles10+4,"bounded descriptor/handle population across replacements");
            std::cout<<"PASS: 110 source/seek/format cycles MEMORY "<<heap10<<' '<<resident10<<' '<<peakHeap<<' '<<peakResident<<'\n';
        } else {
            ended(session,mode=="error"?State::Failed:State::End,mode=="error" || mode=="unverified"?Integrity::Unverified:Integrity::WholeStreamVerified);
            if(mode!="error")check(fake::frames>0,"ordinary nonzero PCM passes real adapter/bridge");
            memory();
            if(mode=="pipe")std::cerr<<"PASS: pipe "<<fake::frames<<" MEMORY "<<peakHeap<<' '<<peakResident<<" MAX_ALLOC "<<largestAllocation.load()<<'\n';
        }
    }
    check(fake::allocations==Bridge::SLOT_COUNT && fake::frees==Bridge::SLOT_COUNT,"one fixed transfer pool per session, drained/freed once");
    check(largestAllocation<=1024*1024,"bounded request sizes independent of duration");
    if(fake::reference)std::fclose(fake::reference);
    fake::reference=nullptr;
    if(mode!="pipe")std::cout<<"PASS: source session "<<mode<<'\n';
    return 0;
}
