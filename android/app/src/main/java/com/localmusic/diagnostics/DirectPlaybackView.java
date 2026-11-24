package com.localmusic.diagnostics;

/** Pure mapping of native observations to media-controller state. Never starts an engine. */
public final class DirectPlaybackView {
    private final int phase;
    private final boolean accepting;
    private final long positionMs;
    private final boolean finite;
    private DirectPlaybackView(int phase, boolean accepting, long positionMs, boolean finite) {
        this.phase = phase; this.accepting = accepting; this.positionMs = positionMs; this.finite = finite;
    }
    public int phase() { return phase; }
    public boolean accepting() { return accepting; }
    public long positionMs() { return positionMs; }
    public static DirectPlaybackView from(int phase, boolean running, boolean closing,
            boolean stopRequested, long positionFrames, int sampleRateHz) {
        return from(phase, running, closing, stopRequested, positionFrames, sampleRateHz, false);
    }
    public static DirectPlaybackView from(int phase, boolean running, boolean closing,
            boolean stopRequested, long positionFrames, int sampleRateHz, boolean finite) {
        if (sampleRateHz != 44100 && sampleRateHz != 48000 && sampleRateHz != 88200 && sampleRateHz != 96000) {
            throw new IllegalArgumentException("Unsupported source rate");
        }
        long frames = Math.max(0, positionFrames);
        long position = (finite ? Math.min(frames, sampleRateHz * 6L) : frames % (sampleRateHz * 6L)) * 1000 / sampleRateHz;
        return new DirectPlaybackView(phase, running && !closing && !stopRequested && phase >= 1 && phase <= 4, position, finite);
    }
    public boolean canToggle() { return !finite && accepting && (phase == 2 || phase == 3); }
    public boolean playWhenReady() { return accepting && phase != 3; }
    public boolean ready() { return accepting && (phase == 2 || phase == 3); }
    public boolean buffering() { return accepting && (phase == 1 || phase == 4); }
}
