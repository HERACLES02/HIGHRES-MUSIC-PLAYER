#include "../experimental/flac_stream_reader.hpp"
#include <atomic>
#include <cstdio>
#include <malloc.h>
#include <memory>
#include <sys/resource.h>

// Android-only offline measurement. PCM is checked as generated silence and
// discarded block by block. No audio, JNI, queue or per-track PCM allocation.
using Reader=FlacStreamReader;

bool sample(const char* phase,uint64_t frames,bool heap=false) {
    unsigned long long rss=0,hwm=0,anon=0;
    FILE* status=std::fopen("/proc/self/status","r");
    if(!status)return false;
    char line[256];
    while(std::fgets(line,sizeof(line),status)) {
        std::sscanf(line,"VmRSS: %llu kB",&rss);
        std::sscanf(line,"VmHWM: %llu kB",&hwm);
        std::sscanf(line,"RssAnon: %llu kB",&anon);
    }
    std::fclose(status);
    rusage usage{};
    if(!rss || !hwm || getrusage(RUSAGE_SELF,&usage)!=0)return false;
    std::printf("{\"phase\":\"%s\",\"frames\":%llu,\"rssKiB\":%llu,\"hwmKiB\":%llu,\"anonKiB\":%llu,\"maxRssKiB\":%ld}\n",
                phase,static_cast<unsigned long long>(frames),rss,hwm,anon,usage.ru_maxrss);
    if(heap) {
        std::fprintf(stderr,"<snapshot phase=\"%s\">\n",phase);
        if(malloc_info(0,stderr)!=0)return false;
        std::fputs("</snapshot>\n",stderr);
    }
    return true;
}

bool silence(Reader& reader,uint64_t& position,uint64_t& bytes) {
    const auto block=reader.block();
    const size_t width=reader.info().bits/8*2;
    if(!width || !block.bytes || block.bytes%width || block.firstFrame!=position ||
       block.bytes>reader.stagingBytes() || reader.md5Verified())return false;
    for(size_t i=0;i<block.bytes;++i)if(block.data[i]!=0)return false;
    position+=block.bytes/width;bytes+=block.bytes;
    return true;
}

int main(int argc,char** argv) {
    if(argc!=2)return 2;
    std::unique_ptr<FILE,decltype(&std::fclose)> file(std::fopen(argv[1],"rb"),&std::fclose);
    if(!file || !sample("before",0,true))return 2;
    uint64_t bytes=0,frames=0,blocks=0;
    std::atomic<bool> cancel{false};
    Reader::Info info;
    {
        Reader reader(file.get(),cancel);info=reader.info();
        if(info.rate!=96000 || info.bits!=24 || !info.totalFrames)return 2;
        bool middle=false;
        for(;;) {
            const auto status=reader.next();
            if(status==Reader::Status::End)break;
            if(status!=Reader::Status::Block || !silence(reader,frames,bytes))return 1;
            ++blocks;
            if(blocks==1 && !sample("warm",frames,true))return 2;
            if(!middle && frames>=info.totalFrames/2) {
                if(!sample("middle",frames,true))return 2;
                middle=true;
            }
            if(blocks%64==0 && !sample("decode",frames))return 2;
        }
        if(frames!=info.totalFrames || !reader.md5Verified() || reader.block().bytes!=0 ||
           reader.next()!=Reader::Status::End || !sample("end",frames,true))return 1;
    }
    if(!sample("after-destroy",frames,true))return 2;
    // Recreate, seek, rewind, cancel and destroy forty independent readers on
    // the same borrowed FILE. This measures repeated owner-side cleanup only.
    for(int cycle=0;cycle<40;++cycle) {
        cancel=false;
        {
            Reader reader(file.get(),cancel);
            uint64_t position=info.totalFrames-info.rate,tailBytes=0;
            if(!reader.seek(position))return 1;
            while(true) {
                auto status=reader.next();
                if(status==Reader::Status::End)break;
                if(status!=Reader::Status::Block || !silence(reader,position,tailBytes))return 1;
            }
            if(position!=info.totalFrames || tailBytes!=uint64_t(info.rate)*6 || reader.md5Verified())return 1;
            if(!reader.seek(0) || reader.next()!=Reader::Status::Block)return 1;
            position=0;tailBytes=0;
            if(!silence(reader,position,tailBytes))return 1;
            cancel=true;
            if(reader.next()!=Reader::Status::Cancelled || reader.block().bytes!=0 ||
               reader.md5Verified() || reader.seek(0))return 1;
            cancel=false;
            if(reader.next()!=Reader::Status::Cancelled)return 1;
        }
        if(!sample("cycle-destroyed",cycle+1))return 2;
    }
    if(!sample("cycles-end",frames,true))return 2;
    std::printf("{\"status\":\"End\",\"md5\":1,\"frames\":%llu,\"bytes\":%llu,\"silenceChecked\":true,\"cycles\":40}\n",
                static_cast<unsigned long long>(frames),static_cast<unsigned long long>(bytes));
}
