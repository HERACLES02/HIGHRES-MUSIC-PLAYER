package com.localmusic.diagnostics;

public final class DirectPlaybackViewTest {
    private static int assertions;
    private static void check(boolean value, String why) {
        assertions++;
        if (!value) throw new AssertionError(why);
    }
    public static void main(String[] args) {
        var preparing = DirectPlaybackView.from(1, true, false, false, 0, 96000);
        check(preparing.buffering() && preparing.playWhenReady(), "Explicitly started test prepares without claiming ready");
        check(!preparing.canToggle(), "Preparing cannot be resumed as a new stream");
        var playing = DirectPlaybackView.from(2, true, false, false, 96000, 96000);
        check(playing.ready() && playing.canToggle() && playing.playWhenReady(), "Active stream advertises playing controls");
        var paused = DirectPlaybackView.from(3, true, false, false, 96000, 96000);
        check(paused.ready() && paused.canToggle() && !paused.playWhenReady(), "Paused stream remains ready but not playing");
        check(paused.positionMs() == playing.positionMs(), "Pause preserves the observed position");
        var draining = DirectPlaybackView.from(4, true, false, false, 96000, 96000);
        check(draining.buffering() && !draining.canToggle(), "Reconfiguration cannot accept an unsafe resume");
        for (int terminal : new int[] {0, 5, 6, -1}) {
            var ended = DirectPlaybackView.from(terminal, true, false, false, 1, 96000);
            check(!ended.canToggle() && !ended.playWhenReady() && !ended.accepting(), "Idle/end/error never advertises restart");
        }
        var removed = DirectPlaybackView.from(2, false, false, false, 1, 96000);
        check(!removed.accepting() && !removed.playWhenReady(), "Missing owner prevents playback even with stale playing stats");
        var stopping = DirectPlaybackView.from(2, true, false, true, 1, 96000);
        check(!stopping.canToggle() && !stopping.playWhenReady(), "Focus/detach stop blocks stale play requests");
        var closing = DirectPlaybackView.from(3, true, true, false, 1, 96000);
        check(!closing.canToggle(), "Closing paused owner cannot resume");
        for (int rate : new int[] {44100, 48000, 88200, 96000}) {
            check(DirectPlaybackView.from(2, true, false, false, rate, rate).positionMs() == 1000, "Position respects source rate");
            check(DirectPlaybackView.from(2, true, false, false, 6L * rate, rate).positionMs() == 0, "Fixture loops at six seconds");
            check(DirectPlaybackView.from(2, true, false, false, 7L * rate, rate).positionMs() == 1000, "Position after loop");
            long huge = DirectPlaybackView.from(2, true, false, false, Long.MAX_VALUE, rate).positionMs();
            check(huge >= 0 && huge < 6000, "Long position cannot overflow during millisecond conversion");
        }
        check(DirectPlaybackView.from(2, true, false, false, -1, 96000).positionMs() == 0, "Invalid negative observation is bounded");
        boolean rejected = false;
        try { DirectPlaybackView.from(2, true, false, false, 1, 0); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "Unknown source clock is rejected");
        for (int rate : new int[] {44100, 48000, 88200, 96000}) {
            var finite = DirectPlaybackView.from(2, true, false, false, 6L * rate, rate, true);
            check(finite.positionMs() == 6000 && finite.ready() && !finite.canToggle(), "Finite EOF position never wraps and exposes Stop only");
            check(DirectPlaybackView.from(2, true, false, false, Long.MAX_VALUE, rate, true).positionMs() == 6000, "Finite position clamps before conversion");
            for (int bits : new int[] {16, 24}) {
                String name = "stereo-" + bits + "-" + rate + ".flac";
                check(StreamingFixturePolicy.supported(name, 6), "All eight finite FLAC formats accepted");
                check(!StreamingFixturePolicy.supported(name, 12), "Finite source cannot be looped by duration");
            }
        }
        for (String name : new String[] {"stereo-16-44100.wav", "channels-24-96000.wav", "../stereo-16-44100.flac", "stereo-24-192000.flac", ""})
            check(!StreamingFixturePolicy.supported(name, 6), "Reject sources outside finite fixture scope");
        check(StreamingFixturePolicy.acceptsCommand(true, 0) && StreamingFixturePolicy.acceptsCommand(true, 4), "Stop and release stay available");
        for (int command : new int[] {1, 2, 3}) check(!StreamingFixturePolicy.acceptsCommand(true, command), "Finite pause/seek/volume unavailable");
        check(StreamingFixturePolicy.acceptsCommand(false, 2), "Legacy controls retained");
        check(StreamingFixturePolicy.selfTestAllowed(true, true, 4), "Isolated selftest may use an owned fake descriptor");
        check(!StreamingFixturePolicy.selfTestAllowed(false, true, 4), "Normal build cannot bypass device permission");
        check(!StreamingFixturePolicy.selfTestAllowed(true, false, 4), "Selftest never bypasses legacy route guards");
        check(!StreamingFixturePolicy.selfTestAllowed(true, true, -1), "Selftest requires a live descriptor");
        System.out.println("Direct controller-state checks passed: " + assertions + " assertions.");
    }
}
