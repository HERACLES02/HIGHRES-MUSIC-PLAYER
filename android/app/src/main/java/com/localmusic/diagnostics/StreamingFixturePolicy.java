package com.localmusic.diagnostics;

/** Only the bundled, finite six-second FLAC experiment is supported initially. */
public final class StreamingFixturePolicy {
    public static boolean selfTestAllowed(boolean enabled, boolean finite, int fd) { return enabled && finite && fd >= 0; }
    public static boolean supported(String fixture, int seconds) {
        return seconds == 6 && fixture != null && fixture.matches("stereo-(16|24)-(44100|48000|88200|96000)\\.flac");
    }
    public static boolean acceptsCommand(boolean finite, int command) { return !finite || command == 0 || command == 4; }
}
