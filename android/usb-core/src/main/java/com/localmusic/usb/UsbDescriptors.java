package com.localmusic.usb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounds-checked USB Audio Class descriptor inspection; no Android dependency. */
public final class UsbDescriptors {
    private UsbDescriptors() {}

    public static final class Endpoint {
        public final int address, attributes, packetSize, interval;
        public int synchAddress = -1, classAttributes = -1;
        Endpoint(int address, int attributes, int packetSize, int interval) {
            this.address = address; this.attributes = attributes;
            this.packetSize = packetSize; this.interval = interval;
        }
        public boolean isInput() { return (address & 0x80) != 0; }
        public boolean isIsochronous() { return (attributes & 3) == 1; }
        public boolean isFeedback() { return ((attributes >> 4) & 3) == 1; }
        public String synchronization() {
            return switch ((attributes >> 2) & 3) {
                case 1 -> "asynchronous"; case 2 -> "adaptive";
                case 3 -> "synchronous"; default -> "not specified";
            };
        }
    }

    public static final class Stream {
        public final int configuration, interfaceNumber, alternate, protocol;
        public final List<Endpoint> endpoints = new ArrayList<>();
        public final List<Long> rates = new ArrayList<>();
        public int channels, slotBytes, validBits, terminalLink, controlInterface = -1, clockId;
        public long minimumRate, maximumRate, formatMask;
        public String encoding = "Unknown";
        Stream(int configuration, int number, int alternate, int protocol) {
            this.configuration = configuration; this.interfaceNumber = number;
            this.alternate = alternate; this.protocol = protocol;
        }
        public boolean isPlayback() {
            return endpoints.stream().anyMatch(ep -> ep.isIsochronous() && !ep.isInput() && !ep.isFeedback());
        }
        public boolean isCapture() {
            return endpoints.stream().anyMatch(ep -> ep.isIsochronous() && ep.isInput() && !ep.isFeedback());
        }
        public boolean hasValidFormat() {
            return channels > 0 && slotBytes > 0 && slotBytes <= 8 && validBits > 0 && validBits <= slotBytes * 8;
        }
    }

    public static final class Clock {
        public final int configuration, controlInterface, id, attributes, controls;
        Clock(int configuration, int controlInterface, int id, int attributes, int controls) {
            this.configuration = configuration; this.controlInterface = controlInterface;
            this.id = id; this.attributes = attributes; this.controls = controls;
        }
    }

    public static final class Parsed {
        public final List<Stream> streams = new ArrayList<>();
        public final List<Clock> clocks = new ArrayList<>();
        public final List<FeatureUnit> featureUnits = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public int descriptorCount, bytesConsumed;
    }

    /** UAC1 controls, scoped to configuration and AudioControl interface. No USB writes. */
    public static final class FeatureUnit {
        public final int configuration, controlInterface, id, sourceId;
        public final List<Long> channelControls;
        FeatureUnit(int configuration, int control, int id, int source, List<Long> controls) {
            this.configuration = configuration; controlInterface = control;
            this.id = id; sourceId = source; channelControls = List.copyOf(controls);
        }
        public boolean hasVolume(int channel) {
            return channel >= 0 && channel < channelControls.size() && (channelControls.get(channel) & 2) != 0;
        }
    }

    /** Key both the configuration and control-interface: entity IDs are not global. */
    private static String key(int configuration, int control, int id) { return configuration + ":" + control + ":" + id; }

    public static Parsed parse(byte[] raw) {
        Parsed result = new Parsed();
        if (raw == null || raw.length == 0) { result.warnings.add("No raw descriptors were returned."); return result; }
        Map<String, Integer> streamToControl = new LinkedHashMap<>();
        Map<String, Integer> terminalToClock = new LinkedHashMap<>();
        int configuration = 0, number = -1, interfaceClass = -1, subclass = -1, protocol = -1;
        Stream stream = null;
        for (int p = 0; p < raw.length;) {
            if (p + 2 > raw.length) { result.warnings.add("Truncated descriptor header at byte " + p); break; }
            int length = u8(raw, p), type = u8(raw, p + 1);
            if (length < 2 || p + length > raw.length) {
                result.warnings.add("Invalid/truncated descriptor length at byte " + p); break;
            }
            result.descriptorCount++;
            result.bytesConsumed = p + length;
            if (type == 2 && length >= 9) {
                configuration = u8(raw, p + 5);
                stream = null; number = -1; interfaceClass = -1; subclass = -1; protocol = -1;
            } else if (type == 11 && length >= 8 && u8(raw, p + 4) == 1) {
                int first = u8(raw, p + 2), count = u8(raw, p + 3);
                // UAC2 collections begin with their AudioControl interface.
                for (int n = first; n < first + count; n++) streamToControl.put(key(configuration, 0, n), first);
            } else if (type == 4 && length >= 9) {
                number = u8(raw, p + 2); int alternate = u8(raw, p + 3);
                interfaceClass = u8(raw, p + 5); subclass = u8(raw, p + 6); protocol = u8(raw, p + 7);
                stream = null;
                if (interfaceClass == 1 && subclass == 2) {
                    stream = new Stream(configuration, number, alternate, protocol);
                    result.streams.add(stream);
                    if (protocol != 0 && protocol != 0x20) result.warnings.add("Unsupported audio protocol " + protocol + " on interface " + number);
                }
            } else if (type == 5 && length >= 7 && stream != null) {
                Endpoint endpoint = new Endpoint(u8(raw, p + 2), u8(raw, p + 3), u16(raw, p + 4), u8(raw, p + 6));
                if (length >= 9) endpoint.synchAddress = u8(raw, p + 8);
                stream.endpoints.add(endpoint);
            } else if (type == 0x25 && length >= 4 && stream != null && u8(raw, p + 2) == 1 && protocol == 0) {
                if (!stream.endpoints.isEmpty()) stream.endpoints.get(stream.endpoints.size() - 1).classAttributes = u8(raw, p + 3);
            } else if (type == 0x24 && length >= 3 && interfaceClass == 1) {
                int subtype = u8(raw, p + 2);
                if (subclass == 1 && protocol == 0 && subtype == 1 && length >= 8) {
                    int count = u8(raw, p + 7);
                    if (length < 8 + count) result.warnings.add("Truncated UAC1 interface collection.");
                    else for (int n = 0; n < count; n++) streamToControl.put(key(configuration, 0, u8(raw, p + 8 + n)), number);
                } else if (subclass == 1 && protocol == 0 && subtype == 6) {
                    if (length < 8) result.warnings.add("Truncated UAC1 Feature Unit.");
                    else {
                        int size = u8(raw, p + 5);
                        if (size < 1 || size > 4 || (length - 7) % size != 0) result.warnings.add("Invalid UAC1 Feature Unit control table.");
                        else {
                            List<Long> controls = new ArrayList<>();
                            for (int offset = 6; offset < length - 1; offset += size) {
                                long value = 0;
                                for (int b = 0; b < size; b++) value |= (long) u8(raw, p + offset + b) << (8 * b);
                                controls.add(value);
                            }
                            result.featureUnits.add(new FeatureUnit(configuration, number, u8(raw, p + 3), u8(raw, p + 4), controls));
                        }
                    }
                } else if (subclass == 1 && protocol == 0x20) {
                    if (subtype == 0x0a && length >= 8) {
                        result.clocks.add(new Clock(configuration, number, u8(raw, p + 3), u8(raw, p + 4), u8(raw, p + 5)));
                    } else if (subtype == 2 && length >= 17) {
                        terminalToClock.put(key(configuration, number, u8(raw, p + 3)), u8(raw, p + 7));
                    } else if (subtype == 3 && length >= 12) {
                        terminalToClock.put(key(configuration, number, u8(raw, p + 3)), u8(raw, p + 8));
                    } else if (subtype == 0x0b || subtype == 0x0c) {
                        result.warnings.add("Clock selector/multiplier present: unresolved clock paths must not be treated as known playback rates.");
                    }
                } else if (subclass == 2 && stream != null) {
                    if (subtype == 1 && protocol == 0 && length >= 7) {
                        stream.terminalLink = u8(raw, p + 3);
                        stream.encoding = u16(raw, p + 5) == 1 ? "PCM" : "Other UAC1 encoding (tag " + u16(raw, p + 5) + ")";
                    } else if (subtype == 1 && protocol == 0x20 && length >= 16) {
                        stream.terminalLink = u8(raw, p + 3);
                        stream.formatMask = u32(raw, p + 6);
                        stream.channels = u8(raw, p + 10);
                        stream.encoding = (stream.formatMask & 1) != 0 ? "PCM" : "Other UAC2 encoding";
                    } else if (subtype == 2 && length >= 4 && u8(raw, p + 3) == 1) {
                        if (protocol == 0) parseUac1Format(raw, p, length, stream, result);
                        else if (protocol == 0x20 && length >= 6) {
                            stream.slotBytes = u8(raw, p + 4); stream.validBits = u8(raw, p + 5);
                        }
                    } else if (subtype == 2) result.warnings.add("Non-Type-I audio format is not decoded by this probe.");
                }
            }
            p += length;
        }
        for (Stream candidate : result.streams) {
            Integer control = streamToControl.get(key(candidate.configuration, 0, candidate.interfaceNumber));
            if (control != null) {
                candidate.controlInterface = control;
                Integer clock = terminalToClock.get(key(candidate.configuration, control, candidate.terminalLink));
                if (clock != null) candidate.clockId = clock;
            }
            if ((candidate.isPlayback() || candidate.isCapture()) && !candidate.hasValidFormat()) result.warnings.add("Incomplete/invalid format on interface " + candidate.interfaceNumber + " alternate " + candidate.alternate);
        }
        return result;
    }

    private static void parseUac1Format(byte[] raw, int p, int length, Stream stream, Parsed result) {
        if (length < 8) { result.warnings.add("Truncated UAC1 Type-I format."); return; }
        stream.channels = u8(raw, p + 4); stream.slotBytes = u8(raw, p + 5); stream.validBits = u8(raw, p + 6);
        int count = u8(raw, p + 7);
        int required = count == 0 ? 14 : 8 + 3 * count;
        if (length < required) { result.warnings.add("Truncated UAC1 sampling-frequency table; rates are unknown."); return; }
        if (count == 0) {
            stream.minimumRate = u24(raw, p + 8); stream.maximumRate = u24(raw, p + 11);
            if (stream.minimumRate <= 0 || stream.maximumRate < stream.minimumRate) {
                result.warnings.add("Invalid UAC1 sampling-frequency range.");
                stream.minimumRate = stream.maximumRate = 0;
            }
        } else {
            for (int n = 0; n < count; n++) {
                long rate = u24(raw, p + 8 + 3 * n);
                if (rate > 0 && !stream.rates.contains(rate)) stream.rates.add(rate);
                else if (rate == 0) result.warnings.add("Zero sample rate ignored.");
            }
        }
    }

    public static final class RateRange {
        public final long minimum, maximum, resolution;
        RateRange(long min, long max, long step) { minimum = min; maximum = max; resolution = step; }
    }

    public static List<RateRange> parseClockRanges(byte[] bytes, int received) {
        if (bytes == null || received < 2 || received > bytes.length) throw new IllegalArgumentException("Missing clock range response");
        int count = u16(bytes, 0);
        if (count < 1 || count > 64 || received < 2 + 12 * count) throw new IllegalArgumentException("Incomplete or oversized clock range table");
        List<RateRange> result = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int p = 2 + 12 * n;
            long min = u32(bytes, p), max = u32(bytes, p + 4), step = u32(bytes, p + 8);
            if (min == 0 || max < min) throw new IllegalArgumentException("Invalid clock rate bounds");
            result.add(new RateRange(min, max, step));
        }
        return result;
    }

    public static int u8(byte[] bytes, int p) { return bytes[p] & 0xff; }
    public static int u16(byte[] bytes, int p) { return u8(bytes, p) | (u8(bytes, p + 1) << 8); }
    public static long u24(byte[] bytes, int p) { return u16(bytes, p) | ((long) u8(bytes, p + 2) << 16); }
    public static long u32(byte[] bytes, int p) { return u24(bytes, p) | ((long) u8(bytes, p + 3) << 24); }
}
