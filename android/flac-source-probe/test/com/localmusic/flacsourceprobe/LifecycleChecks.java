package com.localmusic.flacsourceprobe;
public final class LifecycleChecks {
    private static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args){
        LifecyclePolicy state=new LifecyclePolicy();
        check(!state.active(),"initially stopped");
        check(state.requestStart() && state.active(),"explicit foreground start accepted");
        check(state.background() && !state.active(),"background retires active work");
        check(!state.background(),"duplicate background does not issue another stop");
        check(!state.requestStart(),"queued start rejected while backgrounded");
        state.foreground();check(!state.active(),"foreground must not auto-resume");
        check(state.requestStart(),"explicit resume accepted");
        state.stopped();check(!state.active(),"completion clears activity");
        check(state.requestSample(),"one sample may be queued");
        check(!state.requestSample(),"blocked controller cannot accumulate measurement tasks");
        state.sampleFinished();check(state.requestSample(),"sample slot reusable after completion");
        System.out.println("PASS: 11 probe lifecycle policy checks");
    }
}
