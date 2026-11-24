#include "../experimental/flac_stream_reader.hpp"
#include <atomic>
#include <cstdio>
#include <iostream>
#include <memory>
#include <string>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
int main(int argc,char** argv) {
    if(argc<2)return 2;
    std::unique_ptr<FILE,decltype(&std::fclose)> file(std::fopen(argv[1],"rb"),&std::fclose);
    if(!file)return 2;
#ifdef _WIN32
    _setmode(_fileno(stdout),_O_BINARY);
#endif
    std::atomic<bool> cancel{false};FlacStreamReader reader(file.get(),cancel);
    using Status=FlacStreamReader::Status;
    auto name=[](Status s) {
        switch(s) {
            case Status::Block:return "Block";
            case Status::End:return "End";
            case Status::Cancelled:return "Cancelled";
            default:return "Error";
        }
    };
    Status status=Status::Block;
    uint64_t position=0;
    // Test commands run on the owning thread. Only the external cancellation
    // flag may be set from another thread. No audio device is opened here.
    for(int arg=2;arg<argc || (argc==2 && arg==2);++arg) {
        std::string command=argc==2?"drain":argv[arg];
        if(command=="cancel") {cancel=true;continue;}
        if(command=="clear") {cancel=false;continue;}
        if(command.rfind("seek=",0)==0) {
            auto target=std::stoull(command.substr(5));
            bool ok=reader.seek(target);
            if(ok)position=target;
            std::cerr << "{\"seek\":" << target << ",\"ok\":" << ok
                      << ",\"md5\":" << reader.md5Verified() << "}\n";
            continue;
        }
        uint64_t limit=command=="drain"?UINT64_MAX:std::stoull(command.substr(5));
        uint64_t blocks=0;
        while(blocks<limit) {
            status=reader.next();
            if(status!=Status::Block)break;
            auto block=reader.block();
            const auto info=reader.info();
            size_t width=2*info.bits/8;
            if(!width || !block.bytes || block.bytes%width || block.firstFrame!=position ||
               block.bytes>reader.stagingBytes() || reader.md5Verified())return 2;
            position+=block.bytes/width;++blocks;
            if(std::fwrite(block.data,1,block.bytes,stdout)!=block.bytes)return 2;
        }
        if(status!=Status::Block && reader.block().bytes!=0)return 2;
        std::cerr << "{\"status\":\"" << name(status) << "\",\"md5\":" << reader.md5Verified()
                  << ",\"position\":" << position << ",\"blocks\":" << blocks << "}\n";
    }
    return status==Status::Error?1:status==Status::Cancelled?3:0;
}
