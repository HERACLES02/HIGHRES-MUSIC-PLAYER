#include "flac_stream_reader.hpp"
#include <FLAC/stream_decoder.h>
#include <algorithm>
#include <array>
#include <limits>
#include <sys/types.h>

namespace {
int fileSeek(FILE* file,int64_t offset,int origin) {
#ifdef _WIN32
    return _fseeki64(file,offset,origin);
#elif defined(__ANDROID__)
    return fseeko64(file,offset,origin);
#else
    return fseeko(file,offset,origin);
#endif
}
int64_t fileTell(FILE* file) {
#ifdef _WIN32
    return _ftelli64(file);
#elif defined(__ANDROID__)
    return ftello64(file);
#else
    return ftello(file);
#endif
}
bool supported(unsigned rate,unsigned bits) {
    return (rate==44100 || rate==48000 || rate==88200 || rate==96000) && (bits==16 || bits==24);
}
}

struct FlacStreamReader::Impl {
    static constexpr size_t MAX_FRAMES=65535;
    FILE* file;
    const std::atomic<bool>& cancel;
    std::unique_ptr<FLAC__StreamDecoder,decltype(&FLAC__stream_decoder_delete)> decoder{FLAC__stream_decoder_new(),FLAC__stream_decoder_delete};
    std::array<unsigned char,MAX_FRAMES*6> staging{};
    Info format;
    uint64_t length=0,position=0,blockStart=0;
    size_t used=0;
    bool initialized=false,bad=false,cancelled=false,metadataSeen=false,pending=false,
         delivered=false,ended=false,digestPresent=false,fromStart=true,verified=false;

    bool interrupted() {cancelled=cancelled || cancel.load();return cancelled;}
    static FLAC__StreamDecoderReadStatus read(const FLAC__StreamDecoder*,FLAC__byte out[],size_t* bytes,void* ctx) {
        auto& s=*static_cast<Impl*>(ctx);
        if(s.interrupted())return FLAC__STREAM_DECODER_READ_STATUS_ABORT;
        if(*bytes==0){s.bad=true;return FLAC__STREAM_DECODER_READ_STATUS_ABORT;}
        *bytes=std::fread(out,1,std::min<size_t>(*bytes,16384),s.file);
        if(std::ferror(s.file)){s.bad=true;return FLAC__STREAM_DECODER_READ_STATUS_ABORT;}
        return *bytes?FLAC__STREAM_DECODER_READ_STATUS_CONTINUE:FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
    }
    static FLAC__StreamDecoderSeekStatus seekFile(const FLAC__StreamDecoder*,FLAC__uint64 offset,void* ctx) {
        auto& s=*static_cast<Impl*>(ctx);
        if(s.interrupted() || offset>uint64_t(std::numeric_limits<int64_t>::max()) ||
           fileSeek(s.file,int64_t(offset),SEEK_SET)!=0)return FLAC__STREAM_DECODER_SEEK_STATUS_ERROR;
        return FLAC__STREAM_DECODER_SEEK_STATUS_OK;
    }
    static FLAC__StreamDecoderTellStatus tell(const FLAC__StreamDecoder*,FLAC__uint64* offset,void* ctx) {
        auto& s=*static_cast<Impl*>(ctx);auto value=fileTell(s.file);
        if(value<0)return FLAC__STREAM_DECODER_TELL_STATUS_ERROR;
        *offset=uint64_t(value);return FLAC__STREAM_DECODER_TELL_STATUS_OK;
    }
    static FLAC__StreamDecoderLengthStatus size(const FLAC__StreamDecoder*,FLAC__uint64* bytes,void* ctx) {
        *bytes=static_cast<Impl*>(ctx)->length;return FLAC__STREAM_DECODER_LENGTH_STATUS_OK;
    }
    static FLAC__bool eof(const FLAC__StreamDecoder*,void* ctx) {return std::feof(static_cast<Impl*>(ctx)->file)!=0;}
    static void metadata(const FLAC__StreamDecoder*,const FLAC__StreamMetadata* value,void* ctx) {
        auto& s=*static_cast<Impl*>(ctx);
        if(value->type!=FLAC__METADATA_TYPE_STREAMINFO)return;
        const auto& info=value->data.stream_info;
        if(s.metadataSeen || info.channels!=2 || !supported(info.sample_rate,info.bits_per_sample) ||
           info.max_blocksize>MAX_FRAMES){s.bad=true;return;}
        s.metadataSeen=true;s.format={info.sample_rate,info.bits_per_sample,info.total_samples};
        s.digestPresent=std::any_of(std::begin(info.md5sum),std::end(info.md5sum),[](auto x){return x!=0;});
    }
    static FLAC__StreamDecoderWriteStatus write(const FLAC__StreamDecoder*,const FLAC__Frame* frame,
                                                const FLAC__int32* const samples[],void* ctx) {
        auto& s=*static_cast<Impl*>(ctx);const auto& h=frame->header;
        if(s.interrupted())return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
        if(s.bad || !s.metadataSeen || s.pending || h.channels!=2 || h.sample_rate!=s.format.rate ||
           h.bits_per_sample!=s.format.bits || h.blocksize==0 || h.blocksize>MAX_FRAMES) {
            s.bad=true;return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
        }
        size_t offset=0;
        for(unsigned i=0;i<h.blocksize;++i)for(int ch=0;ch<2;++ch) {
            const int32_t sample=samples[ch][i];
            if(sample<-(1<<int(s.format.bits-1)) || sample>=(1<<int(s.format.bits-1))) {
                s.bad=true;return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
            }
            for(unsigned b=0;b<s.format.bits/8;++b)s.staging[offset++]=(uint32_t(sample)>>(b*8))&255;
        }
        s.used=offset;s.pending=true;return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
    }
    static void error(const FLAC__StreamDecoder*,FLAC__StreamDecoderErrorStatus,void* ctx) {static_cast<Impl*>(ctx)->bad=true;}
    Impl(FILE* input,const std::atomic<bool>& flag):file(input),cancel(flag) {
        if(interrupted())return;
        if(!file || !decoder || fileSeek(file,0,SEEK_END)!=0){bad=true;return;}
        auto end=fileTell(file);
        if(end<0){bad=true;return;}
        length=uint64_t(end);initialize();
    }
    ~Impl(){if(initialized)FLAC__stream_decoder_finish(decoder.get());}
    bool initialize() {
        if(interrupted() || bad)return false;
        if(initialized){FLAC__stream_decoder_finish(decoder.get());initialized=false;}
        used=0;position=0;pending=delivered=ended=verified=metadataSeen=digestPresent=false;fromStart=true;
        format={};
        std::clearerr(file);
        if(fileSeek(file,0,SEEK_SET)!=0){bad=true;return false;}
        FLAC__stream_decoder_set_md5_checking(decoder.get(),true);
        FLAC__stream_decoder_set_metadata_ignore_all(decoder.get());
        FLAC__stream_decoder_set_metadata_respond(decoder.get(),FLAC__METADATA_TYPE_STREAMINFO);
        auto status=FLAC__stream_decoder_init_stream(decoder.get(),read,seekFile,tell,size,eof,write,metadata,error,this);
        if(status!=FLAC__STREAM_DECODER_INIT_STATUS_OK){bad=true;return false;}
        initialized=true;
        if(!FLAC__stream_decoder_process_until_end_of_metadata(decoder.get()) || !metadataSeen || bad) {
            if(!interrupted())bad=true;
            return false;
        }
        return !interrupted();
    }
    Status next() {
        if(interrupted()){used=0;return Status::Cancelled;}
        if(bad){used=0;return Status::Error;}
        if(ended)return Status::End;
        if(delivered){used=0;pending=delivered=false;}
        while(!pending) {
            bool ok=FLAC__stream_decoder_process_single(decoder.get());
            if(interrupted()){used=0;return Status::Cancelled;}
            if(!ok || bad){bad=true;used=0;return Status::Error;}
            if(FLAC__stream_decoder_get_state(decoder.get())==FLAC__STREAM_DECODER_END_OF_STREAM) {
                const bool complete=!format.totalFrames || position==format.totalFrames;
                bool digest=FLAC__stream_decoder_finish(decoder.get());initialized=false;
                if(!complete || !digest){bad=true;used=0;return Status::Error;}
                verified=fromStart && digestPresent;ended=true;return Status::End;
            }
        }
        uint64_t frames=used/(2*format.bits/8);
        if(format.totalFrames && (position>format.totalFrames || frames>format.totalFrames-position)) {
            bad=true;used=0;return Status::Error;
        }
        blockStart=position;position+=frames;delivered=true;return Status::Block;
    }
    bool seek(uint64_t frame) {
        if(interrupted() || bad || (format.totalFrames && frame>format.totalFrames))return false;
        const Info prior=format;
        if(!initialize())return false;
        if(format.rate!=prior.rate || format.bits!=prior.bits || format.totalFrames!=prior.totalFrames) {bad=true;return false;}
        if(frame==0)return true;
        fromStart=false;position=frame;
        if(format.totalFrames && frame==format.totalFrames) {
            FLAC__stream_decoder_finish(decoder.get());initialized=false;ended=true;return true;
        }
        if(!FLAC__stream_decoder_seek_absolute(decoder.get(),frame) || bad) {
            if(!interrupted())bad=true;
            return false;
        }
        return !interrupted();
    }
};
FlacStreamReader::FlacStreamReader(FILE* file,const std::atomic<bool>& cancel):impl(new Impl(file,cancel)) {}
FlacStreamReader::~FlacStreamReader()=default;
FlacStreamReader::Info FlacStreamReader::info()const{return impl->format;}
FlacStreamReader::Status FlacStreamReader::next(){return impl->next();}
FlacStreamReader::Block FlacStreamReader::block()const{return {impl->staging.data(),impl->used,impl->blockStart};}
bool FlacStreamReader::seek(uint64_t frame){return impl->seek(frame);}
bool FlacStreamReader::md5Verified()const{return impl->verified;}
size_t FlacStreamReader::stagingBytes()const{return impl->staging.size();}
