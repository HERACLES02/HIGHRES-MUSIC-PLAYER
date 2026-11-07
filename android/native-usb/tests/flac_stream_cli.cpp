#include "../experimental/flac_stream_reader.hpp"
#include <atomic>
#include <cstdio>
#include <iostream>
#include <memory>
#ifdef _WIN32
#include <fcntl.h>
#include <io.h>
#endif
int main(int argc,char** argv) {
    if(argc!=2)return 2;
    std::unique_ptr<FILE,decltype(&std::fclose)> file(std::fopen(argv[1],"rb"),&std::fclose);
    if(!file)return 2;
#ifdef _WIN32
    _setmode(_fileno(stdout),_O_BINARY);
#endif
    std::atomic<bool> cancel{false};FlacStreamReader reader(file.get(),cancel);
    for(;;) {
        auto status=reader.next();
        if(status==FlacStreamReader::Status::End)return 0;
        if(status!=FlacStreamReader::Status::Block)return 1;
        auto block=reader.block();
        if(std::fwrite(block.data,1,block.bytes,stdout)!=block.bytes)return 1;
    }
}
