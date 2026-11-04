#include "tags.hpp"
#include <jni.h>
#include <codecvt>
#include <locale>
#include <stdexcept>
static std::string utf(JNIEnv* e,jstring s) {
  if(!s)return {};
  const jchar* p=e->GetStringChars(s,nullptr);
  if(!p)throw std::runtime_error("Cannot read Java string");
  std::u16string text;for(jsize i=0;i<e->GetStringLength(s);i++)text+=char16_t(p[i]);
  e->ReleaseStringChars(s,p);
  return std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.to_bytes(text);
}
extern "C" JNIEXPORT jstring JNICALL Java_com_localmusic_player_NativeTags_read(JNIEnv* e,jobject,jstring path,jstring art) {
  try {
    auto s=localtags::read(utf(e,path),utf(e,art));
    auto text=std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s);
    return e->NewString(reinterpret_cast<const jchar*>(text.data()),text.size());
  }
  catch(const std::exception& ex) { e->ThrowNew(e->FindClass("java/io/IOException"),ex.what());return nullptr; }
}
extern "C" JNIEXPORT jstring JNICALL Java_com_localmusic_player_NativeTags_readDescriptor(JNIEnv* e,jobject,jint descriptor,jstring art) {
  try {
    auto s=localtags::readDescriptor(descriptor,utf(e,art));
    auto text=std::wstring_convert<std::codecvt_utf8_utf16<char16_t>,char16_t>{}.from_bytes(s);
    return e->NewString(reinterpret_cast<const jchar*>(text.data()),text.size());
  }
  catch(const std::exception& ex) { e->ThrowNew(e->FindClass("java/io/IOException"),ex.what());return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_localmusic_player_NativeTags_edit(JNIEnv* e,jobject,jstring path,jobjectArray keys,jobjectArray values,jint mode,jstring cover,jstring mime) {
  try {
    if(e->GetArrayLength(keys)!=e->GetArrayLength(values))throw std::runtime_error("Mismatched edit arrays");
    std::vector<std::pair<std::string,std::string>> fields;
    for(jsize i=0;i<e->GetArrayLength(keys);i++) {
      auto k=(jstring)e->GetObjectArrayElement(keys,i);auto v=(jstring)e->GetObjectArrayElement(values,i);
      fields.emplace_back(utf(e,k),utf(e,v));e->DeleteLocalRef(k);e->DeleteLocalRef(v);
    }
    localtags::edit(utf(e,path),fields,mode,utf(e,cover),utf(e,mime));
  } catch(const std::exception& ex) { e->ThrowNew(e->FindClass("java/io/IOException"),ex.what()); }
}
