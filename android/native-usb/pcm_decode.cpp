#include "pcm_decode.hpp"
#include <FLAC/stream_decoder.h>
#include <algorithm>
#include <cstring>
#include <memory>
#include <stdexcept>

namespace {
constexpr size_t LIMIT = 8*1024*1024;
void check(bool valid) { if (!valid) throw std::runtime_error("Unsupported or corrupt stereo WAV/FLAC fixture"); }
bool format(int rate, int bits) { return (rate==44100 || rate==48000 || rate==88200 || rate==96000) && (bits==16 || bits==24); }
uint32_t little(const std::vector<unsigned char>& bytes, size_t offset, size_t count) {
    check(offset <= bytes.size() && count <= bytes.size()-offset);
    uint32_t value=0; for(size_t n=0;n<count;++n) value |= uint32_t(bytes[offset+n]) << (8*n); return value;
}
struct FlacInput { const std::vector<unsigned char>& input; size_t position=0; DecodedPcm pcm; bool error=false; uint64_t frames=0; };
FLAC__StreamDecoderReadStatus read(const FLAC__StreamDecoder*, FLAC__byte output[], size_t* size, void* data) {
    auto& source=*static_cast<FlacInput*>(data);
    *size=std::min(*size,source.input.size()-source.position);
    if(!*size) return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    std::memcpy(output,source.input.data()+source.position,*size); source.position+=*size;
    return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}
FLAC__StreamDecoderWriteStatus write(const FLAC__StreamDecoder*, const FLAC__Frame* frame, const FLAC__int32* const samples[], void* data) {
    auto& source=*static_cast<FlacInput*>(data);
    const auto& h=frame->header;
    if(h.channels!=2 || !format(h.sample_rate,h.bits_per_sample) ||
       int(h.sample_rate)!=source.pcm.rate || int(h.bits_per_sample)!=source.pcm.bits ||
       h.blocksize > (LIMIT-source.pcm.bytes.size())/(2*(h.bits_per_sample/8))) {
        source.error=true; return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    }
    try {
        for(unsigned i=0;i<h.blocksize;++i) for(int ch=0;ch<2;++ch) {
            int32_t value=samples[ch][i];
            if(value < -(1 << (source.pcm.bits-1)) || value >= (1 << (source.pcm.bits-1))) {
                source.error=true; return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
            }
            for(int b=0;b<source.pcm.bits/8;++b) source.pcm.bytes.push_back((uint32_t(value) >> (8*b)) & 255);
        }
        source.frames+=h.blocksize;
    } catch(...) { source.error=true; return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT; }
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}
void metadata(const FLAC__StreamDecoder*, const FLAC__StreamMetadata* value, void* data) {
    auto& source=*static_cast<FlacInput*>(data);
    if(value->type==FLAC__METADATA_TYPE_STREAMINFO) {
        const auto& info=value->data.stream_info;
        source.pcm.rate=info.sample_rate; source.pcm.bits=info.bits_per_sample;
        if(info.channels!=2 || !format(info.sample_rate,info.bits_per_sample) || info.total_samples>uint64_t(info.sample_rate)*6)
            source.error=true;
    }
}
void error(const FLAC__StreamDecoder*, FLAC__StreamDecoderErrorStatus, void* data) { static_cast<FlacInput*>(data)->error=true; }
}
DecodedPcm decodeFixture(const std::vector<unsigned char>& bytes) {
    check(bytes.size()>=12 && bytes.size()<=LIMIT);
    if(std::memcmp(bytes.data(),"RIFF",4)==0) {
        check(std::memcmp(bytes.data()+8,"WAVE",4)==0 && little(bytes,4,4)==bytes.size()-8);
        DecodedPcm pcm; bool haveFormat=false,haveData=false;
        for(size_t p=12;p<bytes.size();) {
            check(bytes.size()-p>=8); size_t size=little(bytes,p+4,4), start=p+8;
            check(size<=bytes.size()-start);
            if(std::memcmp(bytes.data()+p,"fmt ",4)==0) {
                check(!haveFormat && size>=16 && little(bytes,start,2)==1 && little(bytes,start+2,2)==2);
                pcm.rate=little(bytes,start+4,4); pcm.bits=little(bytes,start+14,2);
                check(format(pcm.rate,pcm.bits) && little(bytes,start+12,2)==uint32_t(2*pcm.bits/8) &&
                      little(bytes,start+8,4)==uint32_t(pcm.rate*2*pcm.bits/8));
                haveFormat=true;
            } else if(std::memcmp(bytes.data()+p,"data",4)==0) {
                check(haveFormat && !haveData && size>0 && size%(2*pcm.bits/8)==0);
                pcm.bytes.assign(bytes.begin()+start,bytes.begin()+start+size); haveData=true;
            }
            p=start+size+(size&1); check(p<=bytes.size());
        }
        check(haveData && pcm.bytes.size()==size_t(pcm.rate)*6*2*(pcm.bits/8)); return pcm;
    }
    check(std::memcmp(bytes.data(),"fLaC",4)==0);
    FlacInput source{bytes,0,{},false,0};
    auto decoder=std::unique_ptr<FLAC__StreamDecoder,decltype(&FLAC__stream_decoder_delete)>(FLAC__stream_decoder_new(),FLAC__stream_decoder_delete);
    check(bool(decoder));
    FLAC__stream_decoder_set_md5_checking(decoder.get(),true);
    check(FLAC__stream_decoder_init_stream(decoder.get(),read,nullptr,nullptr,nullptr,nullptr,write,metadata,error,&source)==FLAC__STREAM_DECODER_INIT_STATUS_OK);
    bool success=FLAC__stream_decoder_process_until_end_of_stream(decoder.get());
    bool finished=FLAC__stream_decoder_finish(decoder.get());
    check(success && finished && !source.error && format(source.pcm.rate,source.pcm.bits) && source.frames==uint64_t(source.pcm.rate)*6);
    return source.pcm;
}
