package com.localmusic.outputprobe;

/** Bounded, frame-aligned PCM buffer requests for the diagnostic only. */
final class ProbeBuffer {
    static int bytes(int minimum, int rate, int frameBytes, boolean large) {
        if (minimum <= 0 || minimum > 1048576 || rate < 8000 || rate > 192000 || frameBytes < 1 || frameBytes > 32)
            throw new IllegalArgumentException("Invalid PCM buffer request");
        long requested = large ? Math.max(minimum,(long)rate*frameBytes/4) : minimum;
        long aligned = (requested+frameBytes-1)/frameBytes*frameBytes;
        if (aligned > 1048608) throw new IllegalArgumentException("PCM buffer too large");
        return (int)aligned;
    }
}
