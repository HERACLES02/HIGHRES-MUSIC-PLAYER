#pragma once
#include <string>
#include <vector>
#include <utility>
namespace localtags {
std::string read(const std::string& path, const std::string& artworkPath);
void edit(const std::string& path, const std::vector<std::pair<std::string,std::string>>& fields,
          int coverMode, const std::string& coverPath, const std::string& mime);
}
