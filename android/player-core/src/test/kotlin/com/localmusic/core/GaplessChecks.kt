package com.localmusic.core

import java.util.UUID

fun gaplessChecks(){
    var count=0
    fun verify(ok:Boolean,name:String){count++;check(ok){"Gapless planner: $name"}}
    val a=TrackId(UUID(0,1));val b=TrackId(UUID(0,2));val c=TrackId(UUID(0,3))
    val q=ListeningQueue().apply{start(listOf(a,b,a),0)}
    val initial=q.snapshot();val p=GaplessPlanner.prepare(initial)
    verify(p?.next?.entry?.trackId==b,"predict next without consuming current")
    verify(q.snapshot()==initial,"preparation leaves live order and position untouched")
    q.updatePosition(9000);verify(p!!.matches(q.snapshot()),"progress does not invalidate next occurrence")
    q.addToQueue(c);verify(!p.matches(q.snapshot()),"queue edit invalidates stale preload")
    val manual=GaplessPlanner.prepare(q.snapshot())!!
    verify(manual.next.entry.trackId==c,"manual priority is captured")
    val adopted=ListeningQueue();verify(adopted.restore(manual.after),"transition snapshot can be adopted")
    verify(adopted.current?.trackId==c&&adopted.upcomingManual.isEmpty(),"one automatic transition consumes exactly once")
    verify(!adopted.isPlaying,"adopting a plan does not autoplay by itself")
    q.start(listOf(a,a),0);val duplicate=GaplessPlanner.prepare(q.snapshot())!!
    verify(duplicate.next.entry.id!=q.current!!.id&&duplicate.next.entry.trackId==a,"duplicate tracks keep occurrence identities")
    q.repeat=RepeatMode.ONE;val repeat=GaplessPlanner.prepare(q.snapshot())!!
    verify(repeat.next.entry.id==q.current!!.id,"repeat one plans the same occurrence")
    q.repeat=RepeatMode.OFF;q.next();verify(GaplessPlanner.prepare(q.snapshot())==null,"end of collection does not invent a next track")
    q.repeat=RepeatMode.ALL;verify(GaplessPlanner.prepare(q.snapshot())!=null,"repeat all plans its cycle boundary")
    q.setShuffle(true);val shuffled=GaplessPlanner.prepare(q.snapshot())!!
    verify(ListeningQueue().restore(shuffled.after),"shuffle rollover snapshot stays valid")
    q.setShuffle(false);verify(!shuffled.matches(q.snapshot()),"shuffle change invalidates old plan")
    println("Gapless planner checks passed: $count assertions.")
}
