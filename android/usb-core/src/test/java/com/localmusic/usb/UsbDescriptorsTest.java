package com.localmusic.usb;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** Standalone regression tests so parser validation does not depend on an SDK. */
public final class UsbDescriptorsTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int n = 0; n < values.length; n++) result[n] = (byte) values[n];
        return result;
    }
    private static byte[] join(byte[]... blocks) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] block : blocks) result.writeBytes(block);
        return result.toByteArray();
    }
    private static byte[] configuration(int value) { return bytes(9, 2, 0, 0, 3, value, 0, 0x80, 50); }
    private static byte[] stream(int number, int alternate, int protocol) { return bytes(9, 4, number, alternate, 1, 1, 2, protocol, 0); }
    private static byte[] out() { return bytes(7, 5, 1, 5, 0, 3, 1); }

    public static void main(String[] args) {
        uac1PlaybackAndCapture();
        continuousAndTruncatedRates();
        uac2ClockAndPacking();
        feedbackIsNotAudio();
        configurationIsolation();
        invalidFormatsAndDescriptors();
        clockRanges();
        physicalM2Regression();
        fuzzBounds();
        System.out.println("USB parser checks passed: " + assertions + " assertions (including 3,000 malformed-input cases).");
    }

    private static void uac1PlaybackAndCapture() {
        byte[] raw = join(configuration(1), bytes(9, 4, 0, 0, 0, 1, 1, 0, 0),
            bytes(9, 0x24, 1, 0, 1, 0, 0, 1, 1), stream(1, 1, 0),
            bytes(7, 0x24, 1, 1, 1, 1, 0), bytes(14, 0x24, 2, 1, 2, 3, 24, 2, 0x44, 0xac, 0, 0, 0x77, 1), out(),
            stream(2, 1, 0), bytes(7, 0x24, 1, 2, 1, 1, 0), bytes(11, 0x24, 2, 1, 1, 2, 16, 1, 0x80, 0xbb, 0), bytes(7, 5, 0x82, 5, 0xc0, 0, 1));
        UsbDescriptors.Parsed result = UsbDescriptors.parse(raw);
        check(result.streams.size() == 2, "both USB audio interfaces must be retained");
        var playback = result.streams.get(0);
        check(playback.isPlayback() && !playback.isCapture(), "playback is an OUT data endpoint");
        check(playback.validBits == 24 && playback.slotBytes == 3, "24-bit packed PCM must be decoded correctly");
        check(playback.rates.equals(List.of(44100L, 96000L)), "sample rates are 24-bit little-endian values");
        check(playback.controlInterface == 0, "UAC1 collection maps the correct control interface");
        check(playback.encoding.equals("PCM") && playback.hasValidFormat(), "PCM format is valid");
        check(result.streams.get(1).isCapture() && !result.streams.get(1).isPlayback(), "microphone rates must not become playback rates");
        check(result.warnings.isEmpty(), "valid fixtures must have no warnings");
    }

    private static void continuousAndTruncatedRates() {
        var continuous = UsbDescriptors.parse(join(configuration(1), stream(1, 1, 0),
            bytes(14, 0x24, 2, 1, 2, 2, 16, 0, 0x44, 0xac, 0, 0, 0xee, 2), out())).streams.get(0);
        check(continuous.minimumRate == 44100 && continuous.maximumRate == 192000, "continuous range bounds");
        check(continuous.rates.isEmpty(), "continuous ranges must not become guessed discrete values");
        var truncated = UsbDescriptors.parse(join(configuration(1), stream(1, 1, 0), bytes(8, 0x24, 2, 1, 2, 3, 24, 2), out()));
        check(truncated.streams.get(0).rates.isEmpty(), "truncated tables stay unknown");
        check(!truncated.warnings.isEmpty(), "truncated table is reported");
    }

    private static byte[] uac2(int configuration, int clock) {
        return join(configuration(configuration), bytes(8, 11, 0, 2, 1, 0, 0x20, 0),
            bytes(9, 4, 0, 0, 0, 1, 1, 0x20, 0), bytes(8, 0x24, 0x0a, clock, 3, 3, 0, 0),
            bytes(17, 0x24, 2, 1, 1, 1, 0, clock, 2, 3, 0, 0, 0, 0, 0, 0, 0),
            stream(1, 1, 0x20), bytes(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0),
            bytes(6, 0x24, 2, 1, 4, 24), out());
    }

    private static void uac2ClockAndPacking() {
        var result = UsbDescriptors.parse(uac2(1, 10));
        var format = result.streams.get(0);
        check(format.channels == 2 && format.validBits == 24 && format.slotBytes == 4, "24 meaningful bits in 32-bit slots must not be called 32-bit source audio");
        check(format.rates.isEmpty(), "UAC2 format descriptors do not contain sampling frequency lists");
        check(format.clockId == 10 && format.controlInterface == 0, "link streaming terminal to its own clock");
        check(result.clocks.size() == 1 && result.clocks.get(0).id == 10, "clock source can be queried separately");
        check(result.warnings.isEmpty(), "valid UAC2 fixture has no warnings");
    }

    private static void feedbackIsNotAudio() {
        var result = UsbDescriptors.parse(join(configuration(1), stream(1, 1, 0), bytes(7, 5, 0x81, 0x11, 3, 0, 1)));
        check(!result.streams.get(0).isCapture(), "IN feedback endpoint is not a microphone");
        check(!result.streams.get(0).isPlayback(), "feedback endpoint is not playback");
        check(result.streams.get(0).endpoints.get(0).isFeedback(), "feedback usage is retained");
    }

    private static void configurationIsolation() {
        var result = UsbDescriptors.parse(join(uac2(1, 10), uac2(2, 11)));
        check(result.streams.get(0).clockId == 10, "first configuration keeps its clock");
        check(result.streams.get(1).clockId == 11, "second configuration keeps its clock");
        check(result.clocks.get(1).configuration == 2, "clocks are scoped to configurations");
    }

    private static void invalidFormatsAndDescriptors() {
        var result = UsbDescriptors.parse(join(configuration(1), stream(1, 1, 0), bytes(11, 0x24, 2, 1, 2, 2, 24, 1, 0x80, 0xbb, 0), out()));
        check(!result.streams.get(0).hasValidFormat(), "valid bits cannot exceed container width");
        check(!result.warnings.isEmpty(), "invalid container is reported");
        check(!UsbDescriptors.parse(bytes(0, 4)).warnings.isEmpty(), "zero length cannot cause an infinite loop");
        check(!UsbDescriptors.parse(bytes(255, 4, 0)).warnings.isEmpty(), "truncated descriptor is rejected");
        check(!UsbDescriptors.parse(null).warnings.isEmpty(), "null raw descriptors are supported");
        check(!UsbDescriptors.parse(bytes(9)).warnings.isEmpty(), "single trailing byte is reported");
        var unknown = UsbDescriptors.parse(join(configuration(1), stream(1, 1, 0x30), out()));
        check(!unknown.warnings.isEmpty() && !unknown.streams.get(0).hasValidFormat(), "unsupported UAC3 remains unknown");
    }

    private static void put32(byte[] bytes, int offset, long value) {
        for (int n = 0; n < 4; n++) bytes[offset + n] = (byte) (value >> (8 * n));
    }
    private static void clockRanges() {
        byte[] data = new byte[26]; data[0] = 2;
        put32(data, 2, 44100); put32(data, 6, 44100);
        put32(data, 14, 48000); put32(data, 18, 192000); put32(data, 22, 48000);
        var ranges = UsbDescriptors.parseClockRanges(data, data.length);
        check(ranges.get(0).minimum == 44100 && ranges.get(1).resolution == 48000, "clock discrete/range values");
        try { UsbDescriptors.parseClockRanges(data, 10); throw new AssertionError("truncated response accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        data[0] = 65;
        try { UsbDescriptors.parseClockRanges(data, data.length); throw new AssertionError("oversized response accepted"); }
        catch (IllegalArgumentException expected) { assertions++; }
        check(UsbDescriptors.u32(bytes(0, 0, 0, 0xff), 0) == 4278190080L, "unsigned 32-bit values must not become negative");
    }

    private static void fuzzBounds() {
        Random random = new Random(92431);
        for (int n = 0; n < 3000; n++) {
            byte[] raw = new byte[random.nextInt(512)]; random.nextBytes(raw);
            var parsed = UsbDescriptors.parse(raw);
            check(parsed.bytesConsumed <= raw.length, "parser bounds at fuzz case " + n);
        }
        byte[] valid = uac2(1, 10);
        for (int size = 0; size < valid.length; size++) UsbDescriptors.parse(Arrays.copyOf(valid, size));
    }

    private static void physicalM2Regression() {
        // Exact 347 bytes from music-usb-probe (1).json; no serial or phone identity.
        byte[] raw = java.util.HexFormat.of().parseHex("1201000200000040b23111030300010402010902490104010080310904000000010100000a2401000147000201020c24020101020001010000000924060201010102000924030401010002000c24020501010002030000000a240607050101020200092403080203000700090401000001020000090401010101020000072401040101000b2402010102100180bb0009058405620001000007250101000000090401020101020000072401040101000b2402010103180180bb000905840593000100000725010100000009040200000102000009040201010102000007240105010100142402010202100444ac0080bb008858010077010905040584010100000725010100000009040202010102000007240105010100142402010203180444ac0088580180bb00007701090504054602010000072501010000000904030002030000000921110100012246000705830310000107050303100001");
        var parsed = UsbDescriptors.parse(raw);
        check(raw.length == 347 && parsed.bytesConsumed == 347, "physical report consumed exactly");
        check(parsed.warnings.isEmpty(), "physical descriptor has no parse warnings");
        var playback = parsed.streams.stream().filter(UsbDescriptors.Stream::isPlayback).toList();
        check(playback.size() == 2, "M2 has two playback alternates");
        for (var stream : playback) {
            check(stream.interfaceNumber == 2 && stream.controlInterface == 0 && stream.terminalLink == 5, "M2 playback topology");
            check(stream.channels == 2 && stream.rates.containsAll(List.of(44100L, 48000L, 88200L, 96000L)), "M2 stereo rates");
            var endpoint = stream.endpoints.get(0);
            check(endpoint.address == 4 && endpoint.synchAddress == 0 && endpoint.synchronization().equals("asynchronous"), "M2 missing associated feedback is retained");
            check(endpoint.classAttributes == 1, "M2 advertises endpoint sampling-frequency control");
        }
        check(playback.get(0).slotBytes == 2 && playback.get(0).validBits == 16, "M2 16-bit packing");
        check(playback.get(1).slotBytes == 3 && playback.get(1).validBits == 24, "M2 packed 24-bit");
        check(parsed.featureUnits.size() == 2, "capture/playback Feature Units kept separate");
        var volume = parsed.featureUnits.stream().filter(unit -> unit.sourceId == 5).findFirst().orElseThrow();
        check(volume.id == 7 && volume.controlInterface == 0, "playback Feature Unit is 7, not capture unit 2");
        check(!volume.hasVolume(0) && volume.hasVolume(1) && volume.hasVolume(2) && !volume.hasVolume(3), "M2 master mute and per-channel volume, bounds safe");
        check(parsed.featureUnits.get(0).sourceId == 1, "capture controls remain separate");
        for (int size = 0; size < raw.length; size++) UsbDescriptors.parse(Arrays.copyOf(raw, size));
        var invalid = UsbDescriptors.parse(join(configuration(1), bytes(9,4,0,0,0,1,1,0,0), bytes(8,0x24,6,7,5,0,0,0)));
        check(invalid.featureUnits.isEmpty() && !invalid.warnings.isEmpty(), "zero-sized Feature Unit controls rejected");
    }
}
