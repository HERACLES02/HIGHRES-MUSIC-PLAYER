#pragma once
#include <string>
#include <vector>
#include <utility>
namespace localtags {
std::string read(const std::string& path, const std::string& artworkPath);
#ifdef __ANDROID__
std::string readDescriptor(int descriptor, const std::string& artworkPath);
#endif
void edit(const std::string& path, const std::vector<std::pair<std::string,std::string>>& fields,
          int coverMode, const std::string& coverPath, const std::string& mime);
}
