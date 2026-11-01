#pragma once
#include <cstdint>
#include <vector>
struct DecodedPcm { int rate = 0, bits = 0; std::vector<unsigned char> bytes; };
DecodedPcm decodeFixture(const std::vector<unsigned char>& encoded);
