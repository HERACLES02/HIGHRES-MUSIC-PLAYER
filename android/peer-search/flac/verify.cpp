#include <FLAC/stream_decoder.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>

namespace {
struct Stream {
    FLAC__StreamMetadata_StreamInfo info{};
    uint64_t samples = 0;
    bool failed = false;
};

void metadata(const FLAC__StreamDecoder*, const FLAC__StreamMetadata* block, void* data) {
    if (block->type == FLAC__METADATA_TYPE_STREAMINFO)
        static_cast<Stream*>(data)->info = block->data.stream_info;
}

void error(const FLAC__StreamDecoder*, FLAC__StreamDecoderErrorStatus, void* data) {
    static_cast<Stream*>(data)->failed = true;
}

FLAC__StreamDecoderWriteStatus write(const FLAC__StreamDecoder*, const FLAC__Frame* frame,
                                    const FLAC__int32* const[], void* data) {
    auto& stream = *static_cast<Stream*>(data);
    const auto& header = frame->header;
    if (stream.failed || header.sample_rate != stream.info.sample_rate ||
        header.bits_per_sample != stream.info.bits_per_sample || header.channels != stream.info.channels)
        return FLAC__STREAM_DECODER_WRITE_STATUS_ABORT;
    stream.samples += header.blocksize;
    return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

bool verify(FILE* input, uint64_t expected) {
    std::unique_ptr<FILE, decltype(&std::fclose)> file(input, std::fclose);
    char magic[4];
    if (!file || std::fseek(file.get(), 0, SEEK_END) != 0 ||
        static_cast<uint64_t>(std::ftell(file.get())) != expected ||
        std::fseek(file.get(), 0, SEEK_SET) != 0 ||
        std::fread(magic, 1, sizeof(magic), file.get()) != sizeof(magic) ||
        std::memcmp(magic, "fLaC", sizeof(magic)) != 0 ||
        std::fseek(file.get(), 0, SEEK_SET) != 0)
        return false;

    Stream stream;
    std::unique_ptr<FLAC__StreamDecoder, decltype(&FLAC__stream_decoder_delete)>
        decoder(FLAC__stream_decoder_new(), FLAC__stream_decoder_delete);
    if (!decoder || !FLAC__stream_decoder_set_md5_checking(decoder.get(), true) ||
        FLAC__stream_decoder_init_FILE(decoder.get(), file.get(), write, metadata, error, &stream)
            != FLAC__STREAM_DECODER_INIT_STATUS_OK)
        return false;
    file.release(); // libFLAC owns the file from here.

    uint64_t frameEnd = 0;
    bool decoded = FLAC__stream_decoder_process_until_end_of_metadata(decoder.get());
    while (decoded && !stream.failed &&
           FLAC__stream_decoder_get_state(decoder.get()) != FLAC__STREAM_DECODER_END_OF_STREAM) {
        const auto before = stream.samples;
        decoded = FLAC__stream_decoder_process_single(decoder.get());
        if (decoded && stream.samples != before)
            decoded = FLAC__stream_decoder_get_decode_position(decoder.get(), &frameEnd);
    }
    const bool checksumOk = FLAC__stream_decoder_finish(decoder.get());
    return decoded && !stream.failed && checksumOk && frameEnd == expected && stream.samples != 0 &&
        (stream.info.total_samples == 0 || stream.samples == stream.info.total_samples);
}
}

#ifdef __ANDROID__
#include <jni.h>
#include <unistd.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_localmusic_player_SoulseekFixture_nativeVerifyFlac(JNIEnv*, jobject, jint fd, jlong expected) {
    const int handle = dup(fd);
    if (handle < 0) return JNI_FALSE;
    FILE* file = fdopen(handle, "rb");
    if (!file) {
        close(handle);
        return JNI_FALSE;
    }
    return verify(file, expected) ? JNI_TRUE : JNI_FALSE;
}
#else
#include <cstdlib>
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    return verify(std::fopen(argv[1], "rb"), std::strtoull(argv[2], nullptr, 10)) ? 0 : 1;
}
#endif
