package com.localmusic.diagnostics;

/** Pure mapping of native observations to media-controller state. Never starts an engine. */
public final class DirectPlaybackView {
    private final int phase;
    private final boolean accepting;
    private final long positionMs;
    private DirectPlaybackView(int phase, boolean accepting, long positionMs) {
        this.phase = phase; this.accepting = accepting; this.positionMs = positionMs;
    }
    public int phase() { return phase; }
    public boolean accepting() { return accepting; }
    public long positionMs() { return positionMs; }
    public static DirectPlaybackView from(int phase, boolean running, boolean closing,
            boolean stopRequested, long positionFrames, int sampleRateHz) {
        if (sampleRateHz != 44100 && sampleRateHz != 48000 && sampleRateHz != 88200 && sampleRateHz != 96000) {
            throw new IllegalArgumentException("Unsupported source rate");
        }
        long position = Math.max(0, positionFrames) % (sampleRateHz * 6L) * 1000 / sampleRateHz;
        return new DirectPlaybackView(phase, running && !closing && !stopRequested && phase >= 1 && phase <= 4, position);
    }
    public boolean canToggle() { return accepting && (phase == 2 || phase == 3); }
    public boolean playWhenReady() { return accepting && phase != 3; }
    public boolean ready() { return accepting && (phase == 2 || phase == 3); }
    public boolean buffering() { return accepting && (phase == 1 || phase == 4); }
}
