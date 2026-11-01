// Exercise the real native ownership logic with a deterministic fake USB boundary.
// This is not a hardware/transport test.
#define USB_HOST_TEST
#include "../diagnostic.cpp"
#include <cassert>
#include <cstdio>
#include <iostream>
#include <set>
#include <stdexcept>
#include <vector>

namespace fake {
int initResult, wrapResult, autoResult, controlClaimResult, playbackClaimResult, readMode, releaseFailures;
int closes, exits, wrappedFd, requests;
bool wrongIdentity, throwOnClaimedRead;
bool controls, badRange, shortSnapshot, mismatchLow, partialVolumeFailure, roundedRate,
     failedRate, resetVolumeOnAlt, failedAlt2, failedIdle, throwOnLowRead, failedRestoreRate;
int volume[2], originalVolume[2], rate, alternate, volumeWrites, rateWrites, alternateWrites;
std::set<int> claimed;
std::vector<int> releases;
void reset() {
    initResult = wrapResult = autoResult = controlClaimResult = playbackClaimResult = readMode = releaseFailures = 0;
    closes = exits = requests = 0; wrappedFd = -1;
    wrongIdentity = throwOnClaimedRead = false;
    controls = badRange = shortSnapshot = mismatchLow = partialVolumeFailure = roundedRate =
        failedRate = resetVolumeOnAlt = failedAlt2 = failedIdle = throwOnLowRead = failedRestoreRate = false;
    volume[0] = volume[1] = -5120; rate = 48000; alternate = 0;
    volumeWrites = rateWrites = alternateWrites = 0;
    claimed.clear(); releases.clear();
}
}
extern "C" {
int LIBUSB_CALL libusb_init_context(libusb_context** ctx, const libusb_init_option options[], int count) {
    assert(count == 1 && options[0].option == LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (fake::initResult == 0) *ctx = reinterpret_cast<libusb_context*>(1);
    return fake::initResult;
}
void LIBUSB_CALL libusb_exit(libusb_context*) { ++fake::exits; }
int LIBUSB_CALL libusb_wrap_sys_device(libusb_context*, intptr_t fd, libusb_device_handle** handle) {
    fake::wrappedFd = static_cast<int>(fd);
    if (fake::wrapResult == 0) *handle = reinterpret_cast<libusb_device_handle*>(2);
    return fake::wrapResult;
}
void LIBUSB_CALL libusb_close(libusb_device_handle*) { assert(fake::claimed.empty()); ++fake::closes; }
libusb_device* LIBUSB_CALL libusb_get_device(libusb_device_handle*) { return reinterpret_cast<libusb_device*>(3); }
int LIBUSB_CALL libusb_get_device_descriptor(libusb_device*, libusb_device_descriptor* descriptor) {
    descriptor->idVendor = fake::wrongIdentity ? 0 : 0x31b2; descriptor->idProduct = 0x0311; return 0;
}
int LIBUSB_CALL libusb_get_device_speed(libusb_device*) { return LIBUSB_SPEED_FULL; }
int LIBUSB_CALL libusb_get_configuration(libusb_device_handle*, int* configuration) { *configuration = 1; return 0; }
int LIBUSB_CALL libusb_kernel_driver_active(libusb_device_handle*, int interfaceNumber) {
    assert(interfaceNumber == 0 || interfaceNumber == 2); return fake::claimed.count(interfaceNumber) ? 0 : 1;
}
int LIBUSB_CALL libusb_set_auto_detach_kernel_driver(libusb_device_handle*, int enable) { assert(enable == 0 || enable == 1); return fake::autoResult; }
int LIBUSB_CALL libusb_claim_interface(libusb_device_handle*, int interfaceNumber) {
    assert(interfaceNumber == 0 || interfaceNumber == 2);
    if (interfaceNumber == 0 && fake::controlClaimResult < 0) return fake::controlClaimResult;
    if (interfaceNumber == 2 && fake::playbackClaimResult < 0) return fake::playbackClaimResult;
    fake::claimed.insert(interfaceNumber); return 0;
}
int LIBUSB_CALL libusb_set_interface_alt_setting(libusb_device_handle*, int interfaceNumber, int value) {
    assert(fake::controls && interfaceNumber == 2 && fake::claimed.count(2));
    assert(value >= 0 && value <= 2);
    ++fake::alternateWrites;
    if (value == 0 && fake::failedIdle) return LIBUSB_ERROR_IO;
    fake::alternate = value;
    if (value > 0 && fake::resetVolumeOnAlt) fake::volume[0] = fake::volume[1] = 0;
    if (value == 2 && fake::failedAlt2) { fake::failedAlt2 = false; return LIBUSB_ERROR_IO; }
    return 0;
}
int LIBUSB_CALL libusb_release_interface(libusb_device_handle*, int interfaceNumber) {
    fake::releases.push_back(interfaceNumber);
    assert(fake::claimed.count(interfaceNumber));
    if (fake::releaseFailures-- > 0) return LIBUSB_ERROR_IO;
    fake::claimed.erase(interfaceNumber); return 0;
}
const char* LIBUSB_CALL libusb_error_name(int code) { return code < 0 ? "FAKE_ERROR" : "SUCCESS"; }
int LIBUSB_CALL libusb_control_transfer(libusb_device_handle*, uint8_t type, uint8_t request,
                                       uint16_t value, uint16_t index, unsigned char* bytes,
                                       uint16_t size, unsigned int timeout) {
    assert((type & 0x80) != 0 || fake::controls); // Legacy ownership mode remains GET-only.
    assert(timeout == 500);
    ++fake::requests;
    if (fake::readMode == 2) return LIBUSB_ERROR_NO_DEVICE;
    if (type == 0x21) {
        assert(fake::claimed.count(0) && request == 1 && index == 0x0700 && size == 2);
        assert(value == 0x0201 || value == 0x0202);
        const int writeValue = static_cast<int16_t>(bytes[0] | bytes[1]<<8);
        const bool lowerOrMute = writeValue <= -40*256 && writeValue <= fake::volume[(value & 255)-1];
        const bool verifiedIdleRestore = fake::volumeWrites > 0 && fake::alternate == 0 && fake::rate == 48000 &&
            writeValue == fake::originalVolume[(value & 255)-1];
        assert(lowerOrMute || verifiedIdleRestore);
        fake::volume[(value & 255)-1] = writeValue;
        ++fake::volumeWrites;
        if (fake::volumeWrites == 2 && fake::partialVolumeFailure) return 1;
        return 2;
    }
    if (type == 0x22) {
        assert(fake::claimed.count(2) && request == 1 && value == 0x0100 && index == 4 && size == 3);
        assert(fake::alternate > 0);
        fake::rate = bytes[0] | bytes[1]<<8 | bytes[2]<<16;
        ++fake::rateWrites;
        if (fake::rateWrites > 1 && fake::failedRestoreRate && fake::rate == 48000) return LIBUSB_ERROR_IO;
        if (fake::roundedRate) { fake::roundedRate = false; ++fake::rate; }
        if (fake::failedRate) { fake::failedRate = false; return LIBUSB_ERROR_IO; }
        return 3;
    }
    if (type == 0xa1) {
        assert(index == 0x0700 && (value == 0x0201 || value == 0x0202));
        assert(request >= 0x81 && request <= 0x84 && size == 2);
        if (!fake::claimed.count(0)) return LIBUSB_ERROR_ACCESS;
        if (fake::readMode == 1) { bytes[0] = 0x80; return 1; }
        if (fake::throwOnClaimedRead) throw std::runtime_error("Injected failure");
        if (fake::volumeWrites == 1 && fake::throwOnLowRead) {
            fake::throwOnLowRead = false;
            throw std::runtime_error("Injected failure after first low-volume write");
        }
        const int16_t values[] = {-5120, -24576, 0, 256};
        int answer = values[request - 0x81];
        if (fake::controls) {
            if (fake::volumeWrites == 0) fake::originalVolume[(value & 255)-1] = fake::volume[(value & 255)-1];
            const int attrs[]{fake::volume[(value & 255)-1], -12800, fake::badRange ? 256 : 0, 128};
            answer = attrs[request - 0x81];
            if (fake::shortSnapshot && request == 0x84 && value == 0x0202) return 1;
            if (fake::volumeWrites == 1 && request == 0x81 && fake::mismatchLow) {
                fake::mismatchLow = false; answer += 128;
            }
        }
        const uint16_t raw = static_cast<uint16_t>(answer);
        bytes[0] = raw & 255; bytes[1] = raw >> 8; return 2;
    }
    assert(fake::claimed.count(2));
    if (type == 0x81) { assert(request == 10 && index == 2 && size == 1); bytes[0] = fake::alternate; return 1; }
    assert(type == 0xa2 && request == 0x81 && value == 0x0100 && index == 4 && size == 3);
    bytes[0] = fake::rate & 255; bytes[1] = (fake::rate>>8) & 255; bytes[2] = fake::rate>>16; return 3;
}
}

int main() {
    FILE* source = tmpfile(); assert(source);
    const int fd = fileno(source);
    auto checkFd = [&] {
        const int copy = dup(fd); assert(copy >= 0); close(copy);
        if (fake::wrappedFd >= 0) { const int leaked = dup(fake::wrappedFd); assert(leaked < 0); }
    };
    fake::reset();
    const auto success = inspect(fd, true);
    assert(fake::requests == 18 && fake::closes == 1 && fake::exits == 1);
    assert((fake::releases == std::vector<int>{2, 0}));
    assert(success.find("\"rawSigned256thDb\":-5120") != std::string::npos);
    assert(success.find("\"rateHz\":48000") != std::string::npos);
    checkFd();
    // Emit valid JSON for an independent parser in the PowerShell runner.
    std::cout << success << '\n';

    fake::reset(); fake::wrapResult = LIBUSB_ERROR_ACCESS;
    inspect(fd, true); assert(fake::closes == 0 && fake::exits == 1 && fake::requests == 0); checkFd();
    fake::reset(); fake::initResult = LIBUSB_ERROR_OTHER;
    inspect(fd, true); assert(fake::closes == 0 && fake::exits == 0);
    fake::reset(); fake::wrongIdentity = true;
    inspect(fd, true); assert(fake::requests == 0 && fake::releases.empty()); checkFd();
    fake::reset(); fake::autoResult = LIBUSB_ERROR_NOT_SUPPORTED;
    inspect(fd, true); assert(fake::requests == 8 && fake::releases.empty()); checkFd();
    fake::reset(); fake::controlClaimResult = LIBUSB_ERROR_BUSY;
    inspect(fd, false); assert(fake::requests == 10 && fake::releases == std::vector<int>{2}); checkFd();
    fake::reset(); fake::releaseFailures = 1;
    inspect(fd, true); assert((fake::releases == std::vector<int>{2, 0, 2})); checkFd();
    fake::reset(); fake::throwOnClaimedRead = true;
    try { inspect(fd, true); assert(false); } catch (const std::runtime_error&) {}
    assert(fake::releases == std::vector<int>{0} && fake::closes == 1 && fake::exits == 1); checkFd();
    fake::reset(); fake::playbackClaimResult = LIBUSB_ERROR_BUSY;
    inspect(fd, true); assert(fake::requests == 16 && fake::releases == std::vector<int>{0}); checkFd();
    fake::reset(); fake::readMode = 1;
    const auto shortReads = inspect(fd, true);
    assert(shortReads.find("rawSigned256thDb") == std::string::npos && fake::requests == 18); checkFd();
    fake::reset(); fake::readMode = 2;
    const auto removed = inspect(fd, true);
    assert(removed.find("rawSigned256thDb") == std::string::npos && fake::requests == 18);
    assert((fake::releases == std::vector<int>{2, 0})); checkFd();
    // New silent control transaction: all eight configurations, exact restore.
    auto restored = [&] { assert(fake::volume[0] == -5120 && fake::volume[1] == -5120 && fake::rate == 48000 && fake::alternate == 0); checkFd(); };
    fake::reset(); fake::controls = true;
    const auto controlSuccess = inspect(fd, true, true);
    assert(controlSuccess.find("\"configurationMatrixPassed\":true") != std::string::npos);
    assert(controlSuccess.find("\"settingsRestored\":true") != std::string::npos);
    assert(fake::volumeWrites == 4 && fake::rateWrites == 9 && fake::alternateWrites == 4); restored();
    // This second JSON is separately parsed by the runner, never treated as phone evidence.
    std::cout << controlSuccess << '\n';
    fake::reset(); fake::controls = true; fake::badRange = true;
    inspect(fd, true, true); assert(fake::volumeWrites == 0 && fake::rateWrites == 0); restored();
    fake::reset(); fake::controls = true; fake::shortSnapshot = true;
    inspect(fd, true, true); assert(fake::volumeWrites == 0 && fake::alternateWrites == 0); restored();
    fake::reset(); fake::controls = true; fake::mismatchLow = true;
    const auto mismatch = inspect(fd, true, true);
    assert(mismatch.find("\"lowVolumeControlVerified\":false") != std::string::npos && fake::alternateWrites == 0); restored();
    fake::reset(); fake::controls = true; fake::partialVolumeFailure = true;
    inspect(fd, true, true); assert(fake::volumeWrites == 4 && fake::alternateWrites == 0); restored();
    fake::reset(); fake::controls = true; fake::roundedRate = true;
    const auto rounded = inspect(fd, true, true);
    assert(rounded.find("\"configurationMatrixPassed\":false") != std::string::npos && fake::rateWrites == 2); restored();
    fake::reset(); fake::controls = true; fake::failedRate = true;
    inspect(fd, true, true); assert(fake::rateWrites == 2); restored();
    fake::reset(); fake::controls = true; fake::resetVolumeOnAlt = true;
    inspect(fd, true, true); assert(fake::rateWrites == 0); restored();
    fake::reset(); fake::controls = true; fake::failedAlt2 = true;
    inspect(fd, true, true); assert(fake::rateWrites == 5); restored();
    fake::reset(); fake::controls = true; fake::failedIdle = true;
    const auto failedRestore = inspect(fd, true, true);
    assert(failedRestore.find("\"settingsRestored\":false") != std::string::npos);
    assert(fake::volume[0] <= -40*256 && fake::volume[1] <= -40*256 && fake::volumeWrites == 2); checkFd();
    fake::reset(); fake::controls = true; fake::alternate = 1;
    inspect(fd, true, true); assert(fake::volumeWrites == 0 && fake::rateWrites == 0 && fake::alternateWrites == 0); checkFd();
    fake::reset(); fake::controls = true; fake::volume[0] = fake::volume[1] = 0;
    const auto physicalRange = inspect(fd, true, true);
    assert(physicalRange.find("\"settingsRestored\":true") != std::string::npos);
    assert(fake::volume[0] == 0 && fake::volume[1] == 0 && fake::rate == 48000 && fake::alternate == 0); checkFd();
    fake::reset(); fake::controls = true; fake::volume[0] = fake::volume[1] = -32768;
    inspect(fd, true, true); assert(fake::volume[0] == -32768 && fake::volume[1] == -32768); checkFd();
    fake::reset(); fake::controls = true; fake::volume[0] = -45*256; fake::volume[1] = -46*256;
    inspect(fd, true, true); assert(fake::volume[0] == -45*256 && fake::volume[1] == -46*256); checkFd();
    fake::reset(); fake::controls = true; fake::throwOnLowRead = true;
    try { inspect(fd, true, true); assert(false); } catch (const std::runtime_error&) {}
    assert(fake::releases == (std::vector<int>{2, 0})); restored();
    fake::reset(); fake::controls = true; fake::failedRate = true; fake::failedRestoreRate = true;
    const auto rateRestoreFailure = inspect(fd, true, true);
    assert(rateRestoreFailure.find("\"settingsRestored\":false") != std::string::npos);
    assert(fake::volumeWrites == 2 && fake::alternate == 0); checkFd();
    fake::reset(); fake::controls = true; fake::rate = 192000;
    inspect(fd, true, true); assert(fake::volumeWrites == 0 && fake::rateWrites == 0 && fake::alternateWrites == 0); checkFd();
    bool invoked=false;
    PlaybackHook hook=[&](libusb_context*,libusb_device_handle*,ControlTest& controls) {
        invoked=true;
        assert(controls.lowVerified && fake::claimed.count(0) && fake::claimed.count(2));
        assert(fake::volume[0]<=-40*256 && fake::volume[1]<=-40*256);
        return PlaybackResult{"{\"simulated\":true}",true};
    };
    fake::reset(); fake::controls=true;
    const auto hooked=inspect(fd,true,false,&hook);
    assert(invoked && hooked.find("\"audioSubmitted\":true")!=std::string::npos); restored();
    fake::reset(); fake::controls=true; fake::badRange=true; invoked=false;
    inspect(fd,true,false,&hook); assert(!invoked && fake::volumeWrites==0); restored();
    PlaybackHook brokenHook=[](libusb_context*,libusb_device_handle*,ControlTest&) -> PlaybackResult {
        throw std::runtime_error("Injected playback exception");
    };
    fake::reset(); fake::controls=true;
    try { inspect(fd,true,false,&brokenHook); assert(false); } catch(const std::runtime_error&) {}
    assert(fake::releases==(std::vector<int>{2,0})); restored();
    fclose(source);
    std::cout << "PASS: 31 native scenarios, guarded control writes/readbacks, streaming-entry guards, fail-closed restoration, legacy GET-only mode and fd ownership.\n";
}
