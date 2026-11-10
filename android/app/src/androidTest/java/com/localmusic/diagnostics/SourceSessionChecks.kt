package com.localmusic.diagnostics

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Debug
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual ContentResolver/provider -> bound service/coroutine -> JNI -> fake ISO. */
internal class SourceSessionChecks(private val test: Instrumentation) {
    private val context=test.targetContext
    private val cases=JSONArray()
    private val memory=JSONArray()
    private var assertions=0
    private fun verify(ok: Boolean,why: String) { assertions++;check(ok) { why } }
    private fun main(block: () -> Unit) {
        var failure: Throwable?=null
        test.runOnMainSync { try { block() } catch(error: Throwable) { failure=error } }
        failure?.let { throw it }
    }
    private fun until(why: String,condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+45_000
        while(!condition()){check(SystemClock.elapsedRealtime()<end){"Timeout: $why"};Thread.sleep(10)}
    }
    private fun uri(bits: Int,rate: Int,extension: String)=Uri.parse("content://${context.packageName}.fixtures/stereo-$bits-$rate.$extension")
    private fun snapshot(service: SourceSessionService)=JSONObject(service.state.value.native)
    private fun bind(block: (SourceSessionService) -> Unit) {
        val connected=CountDownLatch(1);var owner: SourceSessionService?=null
        val connection=object: ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder){owner=(binder as SourceSessionService.OwnerBinder).owner;connected.countDown()}
            override fun onServiceDisconnected(name: ComponentName) {}
        }
        main { verify(context.bindService(Intent(context,SourceSessionService::class.java),connection,Context.BIND_AUTO_CREATE),"Bind actual source service") }
        try {
            verify(connected.await(10,TimeUnit.SECONDS),"Bound service connects")
            val service=requireNotNull(owner)
            until("native owner opens") { service.state.value.ready || service.state.value.error!=null }
            verify(service.state.value.ready,"Native source owner becomes ready: ${service.state.value.error}")
            block(service)
        } finally {
            SourceFixtureProvider.nextOpen.getAndSet(null)?.release?.countDown()
            owner?.requestStop()
            main { context.unbindService(connection) }
            owner?.let { service ->
                until("destroy drains native owner") { service.state.value.closed }
                val cleanup=JSONObject(service.state.value.cleanup)
                if(service.state.value.ready) {
                    verify(cleanup.optBoolean("closed") && cleanup.optInt("pending",-1)==0,"Service destroy drains actual JNI")
                    verify(cleanup.optInt("allocations")==8 && cleanup.optInt("frees")==8,"Exactly eight native transfers freed after close")
                }
            }
        }
    }
    private fun replace(service: SourceSessionService,bits: Int,rate: Int): Long {
        val before=snapshot(service).optLong("generation")
        verify(service.replace(uri(bits,rate,"flac"),uri(bits,rate,"wav"),rate,bits),"Queue provider source")
        until("new source active") { service.state.value.error?.let{error(it)};snapshot(service).optLong("generation")>before }
        return snapshot(service).getLong("generation")
    }
    private fun eof(service: SourceSessionService,rate: Int,first: Long=0) {
        until("source EOF") { service.state.value.error?.let{error(it)};snapshot(service).optString("phase")=="end" }
        val s=snapshot(service)
        verify(s.getLong("submittedFrames")==rate*6L-first && s.getLong("completedFrames")==rate*6L-first,"Exact provider PCM count/suffix")
        verify(s.getString("integrity")==if(first==0L) "whole" else "partial","Whole versus seek integrity")
        verify(s.getInt("pending")==0,"EOF has no outstanding borrow")
    }
    private fun controls() {
        bind { service ->
            for(bits in listOf(16,24))for(rate in listOf(44100,48000,88200,96000)) {
                val generation=replace(service,bits,rate)
                lateinit var player: SourceSessionPlayer
                main {
                    player=SourceSessionPlayer(service)
                    player.observe(service.state.value,androidx.media3.common.MediaItem.Builder().setMediaId("control-$bits-$rate").build(),rate,6000)
                    verify(player.isCommandAvailable(androidx.media3.common.Player.COMMAND_PLAY_PAUSE),"Streaming Media3 exposes Play/Pause")
                    player.pause()
                }
                try {
                    until("Media3 Pause reaches native") { snapshot(service).optString("phase")=="paused" }
                    val frames=snapshot(service).getLong("submittedFrames")
                    Thread.sleep(100)
                    verify(snapshot(service).getLong("submittedFrames")==frames && snapshot(service).getInt("pending")==0,"Paused PCM and borrows stay still")
                    val rejected=service.rejectedCount
                    verify(service.setPaused(generation+1,false),"Queue stale Resume")
                    until("stale Resume rejected") { service.rejectedCount>rejected }
                    verify(snapshot(service).getString("phase")=="paused","Stale Resume leaves source paused")
                    main { player.play() }
                    eof(service,rate)
                    verify(snapshot(service).getLong("generation")==generation,"Media3 Resume preserves source identity")
                    cases.put("media3-pause-resume-$bits-$rate")
                } finally { main { player.release() } }
                if((bits==16 && rate==48000) || (bits==24 && rate==96000)) {
                    val runtime=Runtime.getRuntime();val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                    memory.put(JSONObject().put("javaUsed",runtime.totalMemory()-runtime.freeMemory())
                        .put("nativeAllocated",Debug.getNativeHeapAllocatedSize()).put("pss",info.totalPss*1024L)
                        .put("fds",File("/proc/self/fd").list()!!.size))
                }
            }
        }
        val warm=memory.getJSONObject(0);val last=memory.getJSONObject(1)
        for(key in listOf("javaUsed","nativeAllocated","pss"))verify(last.getLong(key)<256L*1024*1024 && last.getLong(key)-warm.getLong(key)<16L*1024*1024,"Control lifecycle memory budget: $key")
        verify(last.getInt("fds")-warm.getInt("fds")<=4,"Control lifecycle descriptor budget")
        bind { service ->
            val generation=replace(service,24,96000)
            verify(service.setPaused(generation,true),"Pause before Stop")
            until("native paused before Stop") { snapshot(service).optString("phase")=="paused" }
            service.requestStop();until("Stop closes paused owner") { service.state.value.closed }
            verify(!service.setPaused(generation,false),"Resume after Stop rejected")
            cases.put("paused-stop-no-revival")
        }
    }
    fun run(controlsOnly: Boolean=false): JSONObject {
        val root=File(context.filesDir,"source-fixtures");check(root.mkdirs() || root.isDirectory)
        val manifest=JSONObject(context.assets.open("fixtures-manifest.json").bufferedReader().use{it.readText()}).getJSONArray("fixtures")
        for(i in 0 until manifest.length()) {
            val entry=manifest.getJSONObject(i);val name=entry.getString("file")
            if(name.matches(Regex("stereo-(16|24)-(44100|48000|88200|96000)\\.(flac|wav)")))
                File(root,name).outputStream().use { out -> context.assets.open(name).use { input -> StreamingFixtureFile.copyVerified(input,out,entry.getString("fileSha256")) } }
        }
        if(controlsOnly) {
            controls()
            return JSONObject().put("cases",cases).put("assertions",assertions).put("memory",memory)
        }
        bind { service ->
            for(bits in listOf(16,24))for(rate in listOf(44100,48000,88200,96000)) {
                replace(service,bits,rate);eof(service,rate);cases.put("provider-$bits-$rate")
            }
            val old=replace(service,24,96000)
            until("in-flight packets") { snapshot(service).optInt("pending")>0 }
            verify(service.seek(old,5001),"Queue unaligned seek")
            until("seek generation") { snapshot(service).optLong("generation")>old }
            eof(service,96000,5001)
            val rejected=service.rejectedCount
            verify(service.seek(old,0),"Stale seek enters bounded command queue")
            until("stale seek rejected by native generation") { service.rejectedCount>rejected }
            verify(snapshot(service).getString("phase")=="end","Stale seek cannot restart playback")
            replace(service,16,44100)
            until("replacement in flight") { snapshot(service).optInt("pending")>0 }
            replace(service,24,96000);eof(service,96000);cases.put("seek-stale-inflight-format-change")
        }
        bind { service ->
            val generation=replace(service,24,96000);eof(service,96000)
            lateinit var player: SourceSessionPlayer
            main {
                player=SourceSessionPlayer(service)
                player.observe(service.state.value,androidx.media3.common.MediaItem.Builder().setMediaId("disposable-24-96").build(),96000,6000)
                verify(player.playbackState==androidx.media3.common.Player.STATE_ENDED,"Media3 observes clean EOF")
                verify(!player.isCommandAvailable(androidx.media3.common.Player.COMMAND_PLAY_PAUSE),"Unsupported pause is not advertised")
                verify(player.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),"Media3 advertises streaming seek")
                player.seekTo(125)
            }
            try {
                until("Media3 seek reaches JNI generation") { snapshot(service).optLong("generation")>generation }
                eof(service,96000,12000)
                main {
                    player.observe(service.state.value,androidx.media3.common.MediaItem.Builder().setMediaId("disposable-24-96").build(),96000,6000)
                    verify(player.currentPosition==6000L,"Media3 position includes seek origin")
                    player.stop()
                }
                until("Media3 Stop drains actual source") { service.state.value.closed }
                verify(service.state.value.error==null,"Media3 Stop remains cancellation, not failure")
            } finally { main { player.release() } }
            cases.put("media3-seek-stop")
        }
        bind { service ->
            val gate=SourceFixtureProvider.Gate();SourceFixtureProvider.nextOpen.set(gate)
            verify(service.replace(uri(24,96000,"flac"),uri(24,96000,"wav"),96000,24),"Start provider open on IO")
            verify(gate.entered.await(10,TimeUnit.SECONDS),"Actual provider open held")
            verify(service.replace(uri(16,44100,"flac"),uri(16,44100,"wav"),44100,16),"Exactly one queued request")
            verify(!service.replace(uri(24,96000,"flac"),uri(24,96000,"wav"),96000,24),"Mailbox applies backpressure")
            service.requestStop();gate.release.countDown()
            until("Stop cancels open and queued source") { service.state.value.closed }
            verify(snapshot(service).optLong("submittedFrames")==0L,"Stop during provider open submits nothing")
            cases.put("provider-open-backpressure-stop")
        }
        bind { service ->
            replace(service,24,96000);until("destroy while in flight") { snapshot(service).optInt("pending")>0 };cases.put("destroy-inflight")
        }
        // Change only a disposable provider fixture's stored whole-stream MD5.
        val damaged=File(root,"stereo-24-96000.flac")
        java.io.RandomAccessFile(damaged,"rw").use { file ->file.seek(26);val value=file.read();file.seek(26);file.write(value xor 1) }
        try {
            bind { service ->
                replace(service,24,96000)
                until("corruption fails and closes") { service.state.value.closed }
                verify(service.state.value.error!=null && snapshot(service).optString("phase")=="failed","Corruption remains failure, not EOF/Stop")
                cases.put("provider-corrupt-md5")
            }
        } finally { damaged.outputStream().use { out ->context.assets.open("stereo-24-96000.flac").use { it.copyTo(out,8192) } } }
        bind { service ->
            val before=service.rejectedCount
            verify(service.replace(Uri.parse("content://${context.packageName}.fixtures/pipe"),uri(24,96000,"wav"),96000,24),"Queue special provider descriptor")
            until("pipe rejected without decoding") { service.rejectedCount>before }
            verify(snapshot(service).optLong("submittedFrames")==0L,"Rejected pipe emits no PCM")
            cases.put("provider-pipe-rejected")
        }
        for(cycle in 0 until 12) {
            bind { service ->replace(service,if(cycle%2==0)24 else 16,if(cycle%2==0)96000 else 44100);eof(service,if(cycle%2==0)96000 else 44100) }
            if(cycle==2 || cycle==11) {
                val runtime=Runtime.getRuntime();val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                memory.put(JSONObject().put("cycle",cycle+1).put("javaUsed",runtime.totalMemory()-runtime.freeMemory())
                    .put("nativeAllocated",Debug.getNativeHeapAllocatedSize()).put("pss",info.totalPss*1024L).put("fds",File("/proc/self/fd").list()!!.size))
            }
        }
        val warm=memory.getJSONObject(0);val last=memory.getJSONObject(1)
        for(key in listOf("javaUsed","nativeAllocated","pss")) {
            verify(last.getLong(key)<256L*1024*1024 && last.getLong(key)-warm.getLong(key)<16L*1024*1024,"Finite app memory budget: $key")
        }
        verify(last.getInt("fds")-warm.getInt("fds")<=4,"Bounded app descriptor population")
        cases.put("12-service-owner-cycles")
        return JSONObject().put("cases",cases).put("assertions",assertions).put("memory",memory)
    }
}
