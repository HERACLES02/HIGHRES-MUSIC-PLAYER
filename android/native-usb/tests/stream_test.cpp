#include "../stream_transport.hpp"
#include <cassert>
#include <cstdlib>
#include <deque>
#include <iostream>
#include <set>

namespace fake {
std::deque<libusb_transfer*> queue;
std::set<libusb_transfer*> cancelled;
StreamState state;
std::vector<unsigned char> pcm;
int bytesPerFrame, capacity, allocations, frees, submits, events, failSubmit, failAllocation, shortPacketAt, errorAt, eventErrorAt, action;
uint64_t expectedOffset;
bool didAction;
void reset(int rate,int bits) {
    queue.clear(); cancelled.clear(); state.reset();
    bytesPerFrame=2*bits/8; capacity=bits==16?388:582;
    pcm.clear(); pcm.reserve(rate*6*bytesPerFrame);
    for(int frame=0;frame<rate*6;++frame) for(int channel=0;channel<2;++channel) {
        uint32_t value=uint32_t((frame*137+channel*91)^0x800001);
        for(int b=0;b<bits/8;++b) pcm.push_back((value>>(8*b))&255);
    }
    allocations=frees=submits=events=failSubmit=failAllocation=shortPacketAt=errorAt=eventErrorAt=action=0;
    expectedOffset=0; didAction=false;
}
}
extern "C" {
libusb_transfer* LIBUSB_CALL libusb_alloc_transfer(int packets) {
    assert(packets==8);
    if(++fake::allocations==fake::failAllocation) return nullptr;
    return static_cast<libusb_transfer*>(std::calloc(1,sizeof(libusb_transfer)+packets*sizeof(libusb_iso_packet_descriptor)));
}
void LIBUSB_CALL libusb_free_transfer(libusb_transfer* transfer) {
    assert(fake::queue.empty()); ++fake::frees; std::free(transfer);
}
int LIBUSB_CALL libusb_submit_transfer(libusb_transfer* transfer) {
    assert(transfer->endpoint==4 && transfer->type==LIBUSB_TRANSFER_TYPE_ISOCHRONOUS);
    assert(transfer->num_iso_packets>0 && transfer->num_iso_packets<=8 && transfer->timeout==1000);
    if(++fake::submits==fake::failSubmit) return LIBUSB_ERROR_IO;
    int offset=0;
    for(int p=0;p<transfer->num_iso_packets;++p) {
        int length=transfer->iso_packet_desc[p].length;
        assert(length<=fake::capacity && length%fake::bytesPerFrame==0);
        for(int b=0;b<length;++b) {
            assert(transfer->buffer[offset+b]==fake::pcm[fake::expectedOffset%fake::pcm.size()]);
            ++fake::expectedOffset;
        }
        offset+=length;
    }
    assert(offset==transfer->length);
    fake::queue.push_back(transfer); return 0;
}
int LIBUSB_CALL libusb_cancel_transfer(libusb_transfer* transfer) { fake::cancelled.insert(transfer); return 0; }
int LIBUSB_CALL libusb_handle_events_timeout_completed(libusb_context*,timeval*,int*) {
    ++fake::events;
    if(fake::eventErrorAt==fake::events) return LIBUSB_ERROR_IO;
    if(!fake::didAction && fake::events==5 && fake::action) {
        fake::didAction=true;
        if(fake::action==1) fake::state.stop=true;
        if(fake::action==2) fake::state.pause=true;
        if(fake::action==3) fake::state.seek=132300;
        if(fake::action==4) { fake::state.volumeDb=-30; ++fake::state.volumeCommand; }
    }
    if(fake::state.phase==3 && fake::queue.empty()) fake::state.pause=false;
    if(fake::queue.empty()) return 0;
    auto* transfer=fake::queue.front(); fake::queue.pop_front();
    const bool cancelled=fake::cancelled.erase(transfer)>0;
    transfer->status=cancelled?LIBUSB_TRANSFER_CANCELLED:LIBUSB_TRANSFER_COMPLETED;
    if(fake::errorAt==fake::events) transfer->status=LIBUSB_TRANSFER_NO_DEVICE;
    for(int p=0;p<transfer->num_iso_packets;++p) {
        auto& packet=transfer->iso_packet_desc[p];
        packet.status=LIBUSB_TRANSFER_COMPLETED;
        packet.actual_length=cancelled?0:packet.length;
        if(fake::shortPacketAt==fake::events && p==0) --packet.actual_length;
    }
    transfer->callback(transfer); return 0;
}
}
int main() {
    int tests=0;
    auto run=[&](int rate,int bits) {
        int prepares=0,idles=0;
        {
            IsoExperiment stream(nullptr,nullptr,fake::state,fake::pcm,rate,bits,12,[&](bool idle) {
                assert(fake::queue.empty());
                if(idle) ++idles;
                else { ++prepares; fake::expectedOffset=fake::state.positionFrames*fake::bytesPerFrame; }
                return true;
            });
            stream.run();
        }
        assert(fake::queue.empty() && fake::state.pending==0 && fake::frees==8);
        return std::pair<int,int>{prepares,idles};
    };
    for(int bits:{16,24}) for(int rate:{44100,48000,88200,96000}) {
        fake::reset(rate,bits); run(rate,bits);
        assert(fake::state.completedIntervals==12000 && fake::state.completedBytes==uint64_t(rate)*12*(2*bits/8));
        assert(fake::state.submittedBytes==fake::state.completedBytes && fake::state.mismatches==0 && fake::state.error==0);
        assert(fake::state.queueEmpty==0); ++tests;
    }
    fake::reset(44100,16); fake::failSubmit=1; run(44100,16); assert(fake::state.error==LIBUSB_ERROR_IO && fake::state.submittedBytes==0); ++tests;
    fake::reset(44100,16); fake::failSubmit=11; run(44100,16); assert(fake::state.error==LIBUSB_ERROR_IO); ++tests;
    fake::reset(44100,16); fake::shortPacketAt=5; run(44100,16); assert(fake::state.error!=0 && fake::state.packetErrors>0); ++tests;
    fake::reset(44100,16); fake::errorAt=5; run(44100,16); assert(fake::state.error!=0 && fake::state.transferErrors==1); ++tests;
    fake::reset(44100,16); fake::eventErrorAt=5; run(44100,16); assert(fake::state.error==LIBUSB_ERROR_IO); ++tests;
    for(int action:{1,2,3,4}) {
        fake::reset(44100,16); fake::action=action; auto configs=run(44100,16);
        assert(fake::state.cancelled>0 && fake::state.error==0);
        if(action==1) assert(fake::state.completedIntervals<12000);
        else assert(fake::state.completedIntervals==12000 && configs.first==2 && configs.second==1);
        if(action==2) assert(fake::state.pauseCount==1);
        if(action==3) assert(fake::state.seekCount==1);
        ++tests;
    }
    fake::reset(44100,16); fake::failAllocation=4;
    try { IsoExperiment stream(nullptr,nullptr,fake::state,fake::pcm,44100,16,12,[](bool){return true;}); assert(false); }
    catch(const std::runtime_error&) { assert(fake::frees==7); } ++tests;
    // Packet capacity and seeks have independent checks, including negative/low bits.
    fake::reset(44100,24); PacketPcm packets(fake::pcm,44100,24); std::array<unsigned char,582> buffer{};
    packets.seek(44100*6-1); auto size=packets.next(buffer.data(),582);
    assert(size==44*6);
    for(int i=0;i<size;++i) assert(buffer[i]==fake::pcm[(uint64_t(44100*6-1)*6+i)%fake::pcm.size()]);
    ++tests;
    try { packets.next(buffer.data(),1); assert(false); } catch(const std::runtime_error&) {} ++tests;
    fake::reset(44100,16);
    {
        IsoExperiment stream(nullptr,nullptr,fake::state,fake::pcm,44100,16,12,[](bool){return false;});
        stream.run(); assert(fake::state.error==LIBUSB_ERROR_IO && fake::submits==0);
    }
    assert(fake::frees==8); ++tests;
    fake::reset(44100,16);
    {
        int checks=0;
        IsoExperiment stream(nullptr,nullptr,fake::state,fake::pcm,44100,16,12,[](bool){return true;},[&]{return ++checks<3;},0);
        stream.run(); assert(fake::state.error==-400 && checks==3 && fake::state.pending==0);
    }
    assert(fake::frees==8); ++tests;
    std::cout<<"PASS: "<<tests<<" native transport/packet scenarios; exact submitted PCM, all formats, queueing, cancellation, pause, seek and failure cleanup.\n";
}
