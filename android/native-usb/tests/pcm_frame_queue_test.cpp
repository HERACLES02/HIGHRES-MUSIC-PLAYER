#include "../experimental/pcm_frame_queue.hpp"
#include <cassert>
#include <algorithm>
#include <array>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

using Queue=PcmFrameQueue;
std::vector<unsigned char> source(size_t frames,int bits) {
    std::vector<unsigned char> pcm;
    for(size_t i=0;i<frames;++i)for(int ch=0;ch<2;++ch) {
        uint32_t value=uint32_t((i*137+ch*8191)^0x800001);
        for(int b=0;b<bits/8;++b)pcm.push_back((value>>(8*b))&255);
    }
    return pcm;
}

int main() {
    int tests=0;
    Queue queue(44100,16,2);
    const unsigned char input[]{1,2,3,4,5,6,7,8};
    unsigned char output[8]{};
    auto token=queue.generation();
    assert(queue.push(token,input,sizeof(input))==Queue::Write::Accepted);
    auto result=queue.pull(token,2,output,sizeof(output));
    assert(result.status==Queue::Read::Data && result.frames==2);
    for(size_t i=0;i<sizeof(input);++i)assert(input[i]==output[i]);
    ++tests;
    for(int bits:{16,24})for(int rate:{44100,48000,88200,96000}) {
        Queue q(rate,bits,17);auto epoch=q.generation();
        const size_t width=2*bits/8;
        auto pcm=source(257,bits);size_t fed=0,iteration=0;
        std::vector<unsigned char> received;
        std::array<unsigned char,66> out{};
        while(true) {
            out.fill(0xa5);
            size_t wanted=(iteration++%11)+1;
            auto r=q.pull(epoch,wanted,out.data(),out.size());
            if(r.status==Queue::Read::NeedData) {
                assert(std::all_of(out.begin(),out.end(),[](auto b){return b==0xa5;}));
                size_t frames=std::min<size_t>(7,257-fed);
                assert(frames>0);
                assert(q.push(epoch,pcm.data()+fed*width,frames*width)==Queue::Write::Accepted);
                fed+=frames;
                if(fed==257)assert(q.finish(epoch));
            } else if(r.status==Queue::Read::Data) {
                assert(r.frames>0 && r.frames<=wanted);
                received.insert(received.end(),out.begin(),out.begin()+r.frames*width);
                assert(std::all_of(out.begin()+r.frames*width,out.end(),[](auto b){return b==0xa5;}));
                if(r.frames<wanted)assert(r.final);
            } else {
                assert(r.status==Queue::Read::End && r.frames==0);break;
            }
            assert(q.capacityBytes()==17*width && q.bufferedFrames()<=17);
        }
        assert(received==pcm && received.size()==257*width);
        assert(q.pull(epoch,1,out.data(),out.size()).status==Queue::Read::End);
        ++tests;
    }
    {
        Queue q(44100,16,3);auto e=q.generation();
        std::array<unsigned char,12> out;out.fill(0xa5);
        assert(q.push(e,input,8)==Queue::Write::Accepted);
        assert(q.push(e,input,8)==Queue::Write::Full && q.bufferedFrames()==2);
        assert(q.pull(e,3,out.data(),12).status==Queue::Read::NeedData && q.bufferedFrames()==2);
        assert(q.push(e,input,3)==Queue::Write::Invalid);
        assert(q.push(e,nullptr,4)==Queue::Write::Invalid);
        assert(q.pull(e,2,out.data(),7).status==Queue::Read::Invalid);
        assert(q.pull(e,0,out.data(),12).status==Queue::Read::Invalid);
        assert(q.pull(e,4,out.data(),12).status==Queue::Read::Invalid);
        assert(q.finish(e) && !q.finish(e));
        auto r=q.pull(e,3,out.data(),12);
        assert(r.status==Queue::Read::Data && r.frames==2 && r.final);
        assert(std::equal(out.begin(),out.begin()+8,input));
        assert(std::all_of(out.begin()+8,out.end(),[](auto b){return b==0xa5;}));
        assert(q.push(e,input,4)==Queue::Write::Closed);
        assert(q.pull(e,1,out.data(),12).status==Queue::Read::End);++tests;
    }
    {
        Queue q(44100,16,2);auto e=q.generation();assert(q.finish(e));
        assert(q.pull(e,1,output,8).status==Queue::Read::End);++tests;
    }
    {
        Queue q(44100,16,2);auto e=q.generation();
        assert(q.push(e,input,8)==Queue::Write::Accepted && q.stop(e));
        assert(q.bufferedFrames()==0 && !q.finish(e) && !q.fail(e));
        assert(q.pull(e,1,output,8).status==Queue::Read::Stopped);
        assert(q.push(e,input,8)==Queue::Write::Closed);++tests;
    }
    {
        Queue q(44100,16,2);auto e=q.generation();
        assert(q.push(e,input,8)==Queue::Write::Accepted && q.fail(e));
        assert(q.bufferedFrames()==0 && !q.finish(e) && !q.stop(e));
        assert(q.pull(e,1,output,8).status==Queue::Read::Failed);
        assert(q.push(e,input,8)==Queue::Write::Closed);++tests;
    }
    {
        Queue q(44100,16,2);auto old=q.generation();
        assert(q.push(old,input,8)==Queue::Write::Accepted);
        auto fresh=q.reset();assert(fresh!=old && q.bufferedFrames()==0);
        assert(q.push(fresh,input+4,4)==Queue::Write::Accepted);
        assert(q.push(old,input,4)==Queue::Write::Stale);
        assert(!q.finish(old) && !q.stop(old) && !q.fail(old));
        assert(q.pull(old,1,output,8).status==Queue::Read::Stale);
        assert(q.bufferedFrames()==1);
        assert(q.pull(fresh,1,output,8).status==Queue::Read::Data);
        assert(std::equal(output,output+4,input+4));++tests;
    }
    {
        Queue q(44100,16,2);auto old=q.generation();
        std::promise<void> resume;auto ready=resume.get_future();
        Queue::Write late=Queue::Write::Accepted;
        std::thread decoder([&]{ready.wait();late=q.push(old,input,8);assert(!q.finish(old));});
        auto fresh=q.reset();assert(q.push(fresh,input+4,4)==Queue::Write::Accepted);
        resume.set_value();decoder.join();
        assert(late==Queue::Write::Stale && q.bufferedFrames()==1);
        assert(q.pull(fresh,1,output,8).frames==1 && std::equal(output,output+4,input+4));++tests;
    }
    {
        Queue q(96000,24,64);auto e=q.generation();auto pcm=source(10003,24);
        const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(5);
        std::thread producer([&]{
            for(size_t fed=0;fed<10003;) {
                assert(std::chrono::steady_clock::now()<deadline);
                size_t frames=std::min<size_t>(7,10003-fed);
                auto r=q.push(e,pcm.data()+fed*6,frames*6);
                if(r==Queue::Write::Full)std::this_thread::yield();
                else {assert(r==Queue::Write::Accepted);fed+=frames;}
            }
            assert(q.finish(e));
        });
        std::vector<unsigned char> received;std::array<unsigned char,78> block{};
        for(;;) {
            assert(std::chrono::steady_clock::now()<deadline);
            auto r=q.pull(e,13,block.data(),block.size());
            if(r.status==Queue::Read::NeedData)std::this_thread::yield();
            else if(r.status==Queue::Read::End)break;
            else {assert(r.status==Queue::Read::Data);received.insert(received.end(),block.begin(),block.begin()+r.frames*6);}
        }
        producer.join();assert(received==pcm && q.capacityBytes()==384);++tests;
    }
    for(auto format: {std::array<int,3>{0,16,2},{44100,0,2},{44100,32,2},
                     {192000,24,2},{44100,16,0},{96000,24,200000}}) {
        bool rejected=false;
        try {Queue q(format[0],format[1],format[2]);}catch(const std::invalid_argument&){rejected=true;}
        assert(rejected);++tests;
    }
    std::cout << "PASS: " << tests << " offline PCM queue scenarios: all formats, finite EOF, exact bytes, bounded capacity, starvation, stale generations, Stop/error and concurrent transfer.\n";
}
