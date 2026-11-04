#include "tags.hpp"
#include <fileref.h>
#include <tpropertymap.h>
#include <tvariant.h>
#include <flacfile.h>
#include <flacproperties.h>
#include <mp4file.h>
#include <mp4properties.h>
#include <wavfile.h>
#include <wavproperties.h>
#include <tfilestream.h>
#include <mpegfile.h>
#include <id3v2tag.h>
#include <textidentificationframe.h>
#include <commentsframe.h>
#include <id3v1tag.h>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <set>
#include <map>
#ifdef __ANDROID__
#include <unistd.h>
#endif

namespace localtags {
class BoundedReadStream : public TagLib::FileStream {
  size_t consumed=0, calls=0;
public:
  explicit BoundedReadStream(const char* path):TagLib::FileStream(path,true) {}
#ifdef __ANDROID__
  explicit BoundedReadStream(int descriptor):TagLib::FileStream(descriptor,true) {}
#endif
  TagLib::ByteVector readBlock(size_t length) override {
    constexpr size_t limit=32*1024*1024;
    if(++calls>250000||length>limit-consumed)throw std::runtime_error("Metadata read exceeds safe limits");
    auto bytes=TagLib::FileStream::readBlock(length);consumed+=bytes.size();return bytes;
  }
};
static TagLib::String str(const std::string& s) { return TagLib::String(s,TagLib::String::UTF8); }
static std::string quote(const std::string& s) {
  std::string out="\"";
  for(unsigned char c:s) {
    if(c=='"'||c=='\\') { out+='\\'; out+=c; }
    else if(c<32) { const char* h="0123456789abcdef"; out+="\\u00"; out+=h[c>>4]; out+=h[c&15]; }
    else out+=c;
  }
  return out+'"';
}
static void valid(TagLib::FileRef& file) {
  if(file.isNull()||!file.file()||!file.file()->isValid()||!file.audioProperties()) throw std::runtime_error("Unsupported or invalid metadata container");
}
static std::string readMetadata(TagLib::IOStream& stream,const std::string& artworkPath) {
  TagLib::FileRef f(&stream); valid(f);
  auto p=f.properties(); auto a=f.audioProperties();
  std::ostringstream out; out<<"{\"properties\":{"; bool first=true;
  for(auto it=p.begin();it!=p.end();++it) {
    if(!first)out<<','; first=false; out<<quote(it->first.to8Bit(true))<<":["; bool item=true;
    for(const auto& v:it->second) { if(!item)out<<',';item=false;out<<quote(v.to8Bit(true)); } out<<']';
  }
  int bits=0;
  if(auto v=dynamic_cast<TagLib::FLAC::File*>(f.file())) bits=v->audioProperties()->bitsPerSample();
  if(auto v=dynamic_cast<TagLib::MP4::File*>(f.file())) if(v->audioProperties()->codec()==TagLib::MP4::Properties::ALAC)bits=v->audioProperties()->bitsPerSample();
  if(auto v=dynamic_cast<TagLib::RIFF::WAV::File*>(f.file())) bits=v->audioProperties()->bitsPerSample();
  out<<"},\"durationMs\":"<<a->lengthInMilliseconds()<<",\"sampleRateHz\":"<<a->sampleRate()<<",\"channels\":"<<a->channels()<<",\"bits\":"<<bits;
  auto pictures=f.complexProperties("PICTURE");
  out<<",\"pictureCount\":"<<pictures.size();
  out<<",\"picturesHaveRoles\":"<<(dynamic_cast<TagLib::MP4::File*>(f.file())?"false":"true")<<",\"pictureFingerprints\":[";
  bool firstPicture=true;
  for(const auto& pic:pictures) {
    if(!firstPicture)out<<',';firstPicture=false;
    auto bytes=pic.value("data").toByteVector();uint64_t hash=14695981039346656037ULL;
    for(unsigned int i=0;i<bytes.size();i++) { hash^=static_cast<unsigned char>(bytes[i]);hash*=1099511628211ULL; }
    out<<"{\"type\":"<<quote(pic.value("pictureType").toString().to8Bit(true))<<",\"mime\":"<<quote(pic.value("mimeType").toString().to8Bit(true))
       <<",\"description\":"<<quote(pic.value("description").toString().to8Bit(true))<<",\"bytes\":"<<bytes.size()<<",\"fnv1a64\":"<<quote(std::to_string(hash))<<'}';
  }
  out<<']';
  if(!artworkPath.empty()&&!pictures.isEmpty()) {
    auto selected=pictures.front();
    for(const auto& pic:pictures) if(pic.value("pictureType").toString()=="Front Cover") { selected=pic; break; }
    auto data=selected.value("data").toByteVector();
    if(!data.isEmpty()&&data.size()<=16*1024*1024) {
      std::ofstream image(artworkPath,std::ios::binary|std::ios::trunc);image.write(data.data(),data.size());
      if(!image)throw std::runtime_error("Cannot stage embedded artwork");
      out<<",\"artworkExtracted\":true";
    }
  }
  out<<'}'; auto result=out.str();if(result.size()>2*1024*1024)throw std::runtime_error("Metadata response exceeds safe limits");return result;
}
std::string read(const std::string& path,const std::string& artworkPath) {
  BoundedReadStream stream(path.c_str()); return readMetadata(stream,artworkPath);
}
#ifdef __ANDROID__
std::string readDescriptor(int descriptor,const std::string& artworkPath) {
  // Own only a duplicate; ParcelFileDescriptor remains responsible for its handle.
  struct Descriptor {
    int value;
    ~Descriptor() { if(value>=0)close(value); }
  } duplicate{dup(descriptor)};
  if(duplicate.value<0)throw std::runtime_error("Cannot duplicate metadata descriptor");
  BoundedReadStream stream(duplicate.value);
  if(!stream.isOpen())throw std::runtime_error("Cannot open metadata descriptor");
  duplicate.value=-1; // FileStream now owns and closes the duplicate, including on errors.
  return readMetadata(stream,artworkPath);
}
#endif
void edit(const std::string& path,const std::vector<std::pair<std::string,std::string>>& fields,int coverMode,const std::string& coverPath,const std::string& mime) {
  const std::set<std::string> allowed={"TITLE","ARTIST","ALBUMARTIST","ALBUM","TRACKNUMBER","DISCNUMBER","DATE","GENRE","COMMENT"};
  TagLib::FileRef f(path.c_str()); valid(f);
  auto mpeg=dynamic_cast<TagLib::MPEG::File*>(f.file());
  auto properties=f.properties();
  for(const auto& field:fields) {
    if(!allowed.count(field.first))throw std::runtime_error("Unsupported edit field");
    TagLib::StringList values;
    if(field.first=="COMMENT") { if(!field.second.empty())values.append(str(field.second)); }
    else {
      std::istringstream parts(field.second);std::string value;
      while(std::getline(parts,value)) if(!value.empty())values.append(str(value));
    }
    if(mpeg) {
      auto tag=mpeg->ID3v2Tag(true);
      auto mappedFrames=tag->frameList();
      for(auto frame:mappedFrames) {
        bool matches=false;
        if(auto text=dynamic_cast<TagLib::ID3v2::TextIdentificationFrame*>(frame))matches=text->asProperties().contains(str(field.first));
        if(auto comment=dynamic_cast<TagLib::ID3v2::CommentsFrame*>(frame))matches=comment->asProperties().contains(str(field.first));
        if(matches)tag->removeFrame(frame,true);
      }
      if(auto legacy=mpeg->ID3v1Tag()) {
        auto value=values.toString(" / ");
        if(field.first=="TITLE")legacy->setTitle(value);
        else if(field.first=="ARTIST")legacy->setArtist(value);
        else if(field.first=="ALBUM")legacy->setAlbum(value);
        else if(field.first=="COMMENT")legacy->setComment(value);
        else if(field.first=="GENRE")legacy->setGenre(value);
        else if(field.first=="TRACKNUMBER")legacy->setTrack(value.toInt());
        else if(field.first=="DATE")legacy->setYear(value.substr(0,4).toInt());
      }
      if(field.first=="COMMENT") {
        if(!values.isEmpty()) { auto comment=new TagLib::ID3v2::CommentsFrame(TagLib::String::UTF8);comment->setText(values.toString("\n"));tag->addFrame(comment); }
      } else {
        const std::map<std::string,std::string> frames={{"TITLE","TIT2"},{"ARTIST","TPE1"},{"ALBUMARTIST","TPE2"},{"ALBUM","TALB"},{"TRACKNUMBER","TRCK"},{"DISCNUMBER","TPOS"},{"DATE","TDRC"},{"GENRE","TCON"}};
        auto id=frames.at(field.first); TagLib::ByteVector frameId(id.data(),id.size());tag->removeFrames(frameId);
        if(!values.isEmpty()) { auto frame=new TagLib::ID3v2::TextIdentificationFrame(frameId,TagLib::String::UTF8);frame->setText(values);tag->addFrame(frame); }
      }
    } else if(values.isEmpty())properties.erase(str(field.first)); else properties.replace(str(field.first),values);
  }
  if(!mpeg&&!f.setProperties(properties).isEmpty())throw std::runtime_error("Container rejected one or more properties");
  if(coverMode!=0) {
    auto existing=f.complexProperties("PICTURE"); TagLib::List<TagLib::VariantMap> retained;
    bool firstMp4=dynamic_cast<TagLib::MP4::File*>(f.file())!=nullptr;
    for(const auto& pic:existing) {
      if(firstMp4) { firstMp4=false; continue; }
      if(pic.value("pictureType").toString()!="Front Cover")retained.append(pic);
    }
    if(coverMode==1) {
      std::ifstream image(coverPath,std::ios::binary);
      std::string bytes((std::istreambuf_iterator<char>(image)),std::istreambuf_iterator<char>());
      if(bytes.empty()||bytes.size()>16*1024*1024)throw std::runtime_error("Invalid replacement artwork");
      TagLib::VariantMap pic;pic.insert("data",TagLib::ByteVector(bytes.data(),bytes.size()));
      pic.insert("pictureType",TagLib::String("Front Cover"));pic.insert("mimeType",str(mime));pic.insert("description",TagLib::String());
      retained.prepend(pic);
    } else if(coverMode!=2)throw std::runtime_error("Invalid cover operation");
    if(!f.setComplexProperties("PICTURE",retained))throw std::runtime_error("Container rejected cover operation");
  }
  bool saved=mpeg?mpeg->save(TagLib::MPEG::File::ID3v2|(mpeg->ID3v1Tag()?TagLib::MPEG::File::ID3v1:0),TagLib::File::StripNone,TagLib::ID3v2::v4,TagLib::File::DoNotDuplicate):f.save();
  if(!saved)throw std::runtime_error("TagLib save failed");
}
}
