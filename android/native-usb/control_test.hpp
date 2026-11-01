// Included after Report. No audio transfers: only validated UAC1 controls.
struct ControlTest {
    libusb_device_handle* handle;
    Report& report;
    int original[2]{}, target[2]{};
    bool volumeTouched[2]{}, rateTouched = false, altTouched = false;
    bool lowVerified = false, matrixPassed = false, restored = false, finished = false;
    int originalRate = 0;

    int transfer(const std::string& name, int type, int request, int value, int index,
                 unsigned char* bytes, int size) {
        errno = 0;
        int result = libusb_control_transfer(handle, type, request, value, index, bytes, size, 500);
        report.result(name.c_str(), result, errno, bytes, size);
        return result;
    }
    bool volumeGet(const std::string& name, int channel, int attribute, int& value) {
        unsigned char bytes[2]{};
        if (transfer(name, 0xa1, 0x80 | attribute, 0x0200 | channel, 0x0700, bytes, 2) != 2) return false;
        value = static_cast<int16_t>(bytes[0] | bytes[1] << 8);
        return true;
    }
    bool volumeSet(const std::string& name, int channel, int value) {
        const auto raw = static_cast<uint16_t>(value);
        unsigned char bytes[2]{static_cast<unsigned char>(raw & 255), static_cast<unsigned char>(raw >> 8)};
        if (transfer(name + "_set", 0x21, 1, 0x0200 | channel, 0x0700, bytes, 2) != 2) return false;
        int readback = 0;
        bool good = volumeGet(name + "_readback", channel, 1, readback) && readback == value;
        report.result((name + "_exact").c_str(), good ? 0 : LIBUSB_ERROR_IO);
        return good;
    }
    bool alternateGet(const std::string& name, int& value) {
        unsigned char byte = 255;
        if (transfer(name, 0x81, 10, 0, 2, &byte, 1) != 1) return false;
        value = byte; return true;
    }
    bool alternateSet(const std::string& name, int value) {
        altTouched = true; // Mark before call: even a failed operation could have changed hardware.
        int result = libusb_set_interface_alt_setting(handle, 2, value);
        report.result((name + "_set").c_str(), result);
        int readback = -1;
        bool good = result == 0 && alternateGet(name + "_readback", readback) && readback == value;
        report.result((name + "_exact").c_str(), good ? 0 : LIBUSB_ERROR_IO);
        return good;
    }
    bool rateGet(const std::string& name, int& value) {
        unsigned char bytes[3]{};
        if (transfer(name, 0xa2, 0x81, 0x0100, 4, bytes, 3) != 3) return false;
        value = bytes[0] | bytes[1] << 8 | bytes[2] << 16; return true;
    }
    bool rateSet(const std::string& name, int value) {
        rateTouched = true;
        unsigned char bytes[3]{static_cast<unsigned char>(value & 255), static_cast<unsigned char>((value >> 8) & 255), static_cast<unsigned char>(value >> 16)};
        if (transfer(name + "_set", 0x22, 1, 0x0100, 4, bytes, 3) != 3) return false;
        int readback = 0;
        bool good = rateGet(name + "_readback", readback) && readback == value;
        report.result((name + "_exact").c_str(), good ? 0 : LIBUSB_ERROR_IO);
        return good;
    }
    bool lowStillSet(const std::string& name) {
        for (int ch = 1; ch <= 2; ++ch) {
            int value = 0;
            if (!volumeGet(name + "_volume_ch" + std::to_string(ch), ch, 1, value) || value != target[ch-1]) {
                report.result("low_volume_changed_guard", LIBUSB_ERROR_IO); return false;
            }
        }
        return true;
    }
    void run(bool matrix = true) {
        int alternate = -1;
        if (!alternateGet("snapshot_alternate", alternate) || alternate != 0 ||
            !rateGet("snapshot_rate", originalRate) ||
            (originalRate != 44100 && originalRate != 48000 && originalRate != 88200 && originalRate != 96000)) {
            report.result("idle_snapshot_guard", LIBUSB_ERROR_NOT_SUPPORTED); return;
        }
        // Snapshot and validate BOTH channels before any writes. Never increase a lower existing level.
        for (int ch = 1; ch <= 2; ++ch) {
            int current, minimum, maximum, step;
            const auto prefix = "snapshot_ch" + std::to_string(ch);
            if (!volumeGet(prefix + "_cur", ch, 1, current) || !volumeGet(prefix + "_min", ch, 2, minimum) ||
                !volumeGet(prefix + "_max", ch, 3, maximum) || !volumeGet(prefix + "_res", ch, 4, step)) return;
            if (minimum < -32767 || minimum > -40*256 || maximum > 0 || maximum <= minimum || step <= 0 ||
                step > maximum-minimum || (current != -32768 && (current < minimum || current > maximum))) {
                report.result("volume_range_guard", LIBUSB_ERROR_NOT_SUPPORTED); return;
            }
            original[ch-1] = current;
            const int ceiling = current < -40*256 ? current : -40*256;
            target[ch-1] = current == -32768 ? -32768 : minimum + ((ceiling-minimum)/step)*step;
        }
        for (int ch = 1; ch <= 2; ++ch) {
            volumeTouched[ch-1] = true;
            if (!volumeSet("low_ch" + std::to_string(ch), ch, target[ch-1])) return;
        }
        lowVerified = true;
        if (!matrix) return;
        for (int alt = 1; alt <= 2; ++alt) {
            if (!alternateSet("test_alt" + std::to_string(alt), alt) || !lowStillSet("after_alt" + std::to_string(alt))) return;
            for (int rate : {44100, 48000, 88200, 96000}) {
                const auto name = "alt" + std::to_string(alt) + "_rate" + std::to_string(rate);
                if (!rateSet(name, rate) || !lowStillSet(name)) return;
            }
        }
        matrixPassed = true;
    }
    void finish() {
        if (finished) return;
        bool rateRestored = !rateTouched;
        if (rateTouched) {
            // Re-establish a known active endpoint, restore frequency, then return to idle.
            rateRestored = alternateSet("restore_rate_alt1", 1) && rateSet("restore_rate", originalRate);
        }
        bool idle = true;
        if (altTouched) idle = alternateSet("restore_idle_alt0", 0);
        else if (volumeTouched[0] || volumeTouched[1]) {
            int alt = -1; idle = alternateGet("restore_idle_check", alt) && alt == 0;
        }
        if (idle && rateTouched && rateRestored) {
            int finalRate = 0;
            rateRestored = rateGet("restore_idle_rate_readback", finalRate) && finalRate == originalRate;
        }
        bool volumesRestored = true;
        if (idle && rateRestored) {
            for (int ch = 1; ch <= 2; ++ch) if (volumeTouched[ch-1]) {
                // Evaluate both even if one fails.
                bool good = volumeSet("restore_ch" + std::to_string(ch), ch, original[ch-1]);
                volumesRestored = good && volumesRestored;
            }
        } else {
            volumesRestored = false;
            report.result("original_volume_restore_blocked", LIBUSB_ERROR_IO);
        }
        restored = idle && rateRestored && volumesRestored;
        report.result("settings_restore_verified", restored ? 0 : LIBUSB_ERROR_IO);
        finished = true;
    }
    ~ControlTest() { if (!finished) { try { finish(); } catch (...) {} } }
};
