package com.localmusic.flacsourceprobe;

// Diagnostic-only policy. Foreground return never starts a source by itself.
final class LifecyclePolicy {
    private boolean visible=true,active=false,sampleQueued=false;
    synchronized boolean requestStart(){if(!visible)return false;active=true;return true;}
    synchronized boolean background(){visible=false;boolean stop=active;active=false;return stop;}
    synchronized void foreground(){visible=true;}
    synchronized void stopped(){active=false;}
    synchronized boolean active(){return active;}
    synchronized boolean requestSample(){if(sampleQueued)return false;sampleQueued=true;return true;}
    synchronized void sampleFinished(){sampleQueued=false;}
}
