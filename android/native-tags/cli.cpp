#include "tags.hpp"
#include <iostream>
#include <fstream>
#include <fileref.h>
#include <tvariant.h>
#include <tpropertymap.h>
int main(int argc,char** argv) {
  try {
    if(argc<3)return 2;
    if(std::string(argv[1])=="read") std::cout<<localtags::read(argv[2],argc>3?argv[3]:"");
    else if(std::string(argv[1])=="back") {
      TagLib::FileRef f(argv[2]);auto pictures=f.complexProperties("PICTURE");
      std::ifstream input(argv[3],std::ios::binary);std::string data((std::istreambuf_iterator<char>(input)),std::istreambuf_iterator<char>());
      TagLib::VariantMap pic;pic.insert("data",TagLib::ByteVector(data.data(),data.size()));pic.insert("mimeType",TagLib::String("image/png"));
      pic.insert("description",TagLib::String("Back picture preserved"));pic.insert("pictureType",TagLib::String("Back Cover"));pictures.append(pic);
      if(!f.setComplexProperties("PICTURE",pictures)||!f.save())return 1;
    } else if(std::string(argv[1])=="cover") {
      localtags::edit(argv[2],{},std::stoi(argv[3]),argc>4?argv[4]:"","image/png");
    } else {
      std::vector<std::pair<std::string,std::string>> fields;
      for(int i=3;i+1<argc;i+=2) {
        std::string value=argv[i+1];
        if(value.rfind("@",0)==0) { std::ifstream input(value.substr(1),std::ios::binary);value.assign(std::istreambuf_iterator<char>(input),std::istreambuf_iterator<char>()); }
        fields.emplace_back(argv[i],value);
      }
      localtags::edit(argv[2],fields,0,"","");
    }
    return 0;
  } catch(const std::exception& e) { std::cerr<<e.what();return 1; }
}
