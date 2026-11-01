#include "../pcm_decode.hpp"
#include <fstream>
#include <iostream>
#include <iterator>
int main(int argc,char** argv) {
    if(argc!=3) return 2;
    try {
        std::ifstream input(argv[1],std::ios::binary);
        std::vector<unsigned char> bytes{std::istreambuf_iterator<char>(input),{}};
        auto pcm=decodeFixture(bytes);
        std::ofstream output(argv[2],std::ios::binary);
        output.write(reinterpret_cast<const char*>(pcm.bytes.data()),pcm.bytes.size());
        std::cout<<pcm.rate<<" "<<pcm.bits<<"\n"; return output ? 0 : 3;
    } catch(const std::exception& error) { std::cerr<<error.what()<<"\n"; return 1; }
}
