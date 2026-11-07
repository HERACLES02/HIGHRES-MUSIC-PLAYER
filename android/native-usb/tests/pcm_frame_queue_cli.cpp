// Offline raw-PCM pipe test. No decoder, USB device, audio output or JNI.
#include "../experimental/pcm_frame_queue.hpp"
#include <array>
#include <cstdio>
#include <iostream>
#include <thread>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif

int main(int argc,char** argv) {
    try {
        if(argc!=3)return 2;
        const int rate=std::stoi(argv[1]),bits=std::stoi(argv[2]);
        PcmFrameQueue q(rate,bits,4096);const auto epoch=q.generation();
        const size_t width=2*bits/8;
#ifdef _WIN32
        if(_setmode(_fileno(stdin),_O_BINARY)==-1 || _setmode(_fileno(stdout),_O_BINARY)==-1)return 2;
#endif
        std::thread producer([&]{
            std::array<unsigned char,257*6> input{};
            for(;;) {
                size_t n=std::fread(input.data(),1,257*width,stdin);
                if(n==0) {if(std::ferror(stdin))q.fail(epoch);else q.finish(epoch);return;}
                if(n%width) {q.fail(epoch);return;}
                for(;;) {
                    auto status=q.push(epoch,input.data(),n);
                    if(status==PcmFrameQueue::Write::Accepted)break;
                    if(status!=PcmFrameQueue::Write::Full)return;
                    std::this_thread::yield();
                }
            }
        });
        bool success=false;uint64_t frames=0;
        std::array<unsigned char,193*6> output{};
        for(;;) {
            auto r=q.pull(epoch,193,output.data(),output.size());
            if(r.status==PcmFrameQueue::Read::NeedData) {std::this_thread::yield();continue;}
            if(r.status==PcmFrameQueue::Read::End) {success=true;break;}
            if(r.status!=PcmFrameQueue::Read::Data)break;
            if(std::fwrite(output.data(),1,r.frames*width,stdout)!=r.frames*width) {q.stop(epoch);break;}
            frames+=r.frames;
        }
        producer.join();
        if(std::fflush(stdout))success=false;
        std::cerr << "frames=" << frames << " queueBytes=" << q.capacityBytes() << "\n";
        return success?0:1;
    } catch(const std::exception& e) {std::cerr<<e.what()<<"\n";return 2;}
}
