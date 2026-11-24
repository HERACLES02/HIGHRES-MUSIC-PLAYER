package com.localmusic.usb

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

object NativeDirect {
    private var loaded=false
    @Synchronized fun initialize(fake: Boolean=false) {
        if(!loaded){System.loadLibrary(if(fake) "usb_experiment_fake" else "usb_experiment");loaded=true}
    }
    @JvmStatic external fun available(): Boolean
    @JvmStatic external fun open(usbFd: Int): Long
    @JvmStatic external fun start(lease: Long,sourceFd: Int,rate: Int,bits: Int,first: Long,playing: Boolean): Long
    @JvmStatic external fun seek(lease: Long,generation: Long,frame: Long): Long
    @JvmStatic external fun paused(lease: Long,generation: Long,value: Boolean): Boolean
    @JvmStatic external fun stop(lease: Long)
    @JvmStatic external fun snapshot(lease: Long): String
    @JvmStatic external fun close(lease: Long): String
}

data class DirectTrack(val entryId: String,val uri: Uri,val rate: Int,val bits: Int,val positionMs: Long=0,val playing: Boolean=false)
data class DirectView(val entryId: String="",val phase: String="opening",val generation: Long=0,val positionMs: Long=0,
    val completedFrames: Long=0,val submittedFrames: Long=0,val pending: Int=0,val integrity: String="unverified",
    val closed: Boolean=false,val retired: Boolean=false,val restored: Boolean=false,val error: String?=null,val diagnostics: String="{}")

/** One bounded IO command owner. USB connection must outlive closed/retired. */
class DirectOutputController(context: Context,usbFd: Int,openNative: (Int)->Long=NativeDirect::open) {
    private sealed interface Command {
        data class Select(val track: DirectTrack): Command
        data class Play(val entry: String,val generation: Long,val value: Boolean): Command
        data class Seek(val entry: String,val generation: Long,val position: Long): Command
    }
    private val mutable=MutableStateFlow(DirectView())
    val state=mutable.asStateFlow()
    private val commands=Channel<Command>(1)
    private val stopped=AtomicBoolean(false)
    private val lease=AtomicLong(0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    init {
        val resolver=context.applicationContext.contentResolver
        scope.launch {
            var entry="";var rate=0;var bits=0;var nativeGeneration=0L
            try {
                NativeDirect.initialize()
                val handle=openNative(usbFd);check(handle>0){"Direct USB owner unavailable"};lease.set(handle)
                while(!stopped.get()) {
                    when(val command=commands.tryReceive().getOrNull()) {
                        is Command.Select -> {
                            val track=command.track
                            resolver.openFileDescriptor(track.uri,"r").use { source ->
                                check(source!=null){"Source descriptor unavailable"}
                                if(!stopped.get()) {
                                    val accepted=NativeDirect.start(handle,source.fd,track.rate,track.bits,frames(track.positionMs,track.rate),track.playing)
                                    check(accepted>0 || stopped.get()){"Source command rejected"}
                                    if(accepted>0){nativeGeneration=accepted;entry=track.entryId;rate=track.rate;bits=track.bits}
                                }
                            }
                        }
                        is Command.Play -> if(command.entry==entry && command.generation==nativeGeneration) {
                            val phase=mutable.value.phase
                            if(!(phase=="end" && !command.value))check(NativeDirect.paused(handle,nativeGeneration,!command.value) || stopped.get()){"Play/Pause rejected"}
                        }
                        is Command.Seek -> if(command.entry==entry && command.generation==nativeGeneration) {
                            nativeGeneration=NativeDirect.seek(handle,nativeGeneration,frames(command.position,rate))
                            check(nativeGeneration>0 || stopped.get()){"Seek rejected"}
                        }
                        null -> Unit
                    }
                    if(stopped.get())break
                    val value=JSONObject(NativeDirect.snapshot(handle))
                    check(!value.optBoolean("stale")){"Native owner became stale"}
                    val phase=value.optString("phase")
                    val first=value.optLong("firstFrame").coerceAtLeast(0)
                    val completed=value.optLong("completedFrames").coerceAtLeast(0)
                    val total=if(completed>Long.MAX_VALUE-first)Long.MAX_VALUE else first+completed
                    val position=if(rate>0)(total/rate).coerceAtMost(Long.MAX_VALUE/1000)*1000+(total%rate)*1000/rate else 0
                    mutable.value=DirectView(entry,phase,value.optLong("generation"),position,completed,value.optLong("submittedFrames"),
                        value.optInt("pending"),value.optString("integrity","unverified"),
                        diagnostics=value.put("sampleRateHz",rate).put("bits",bits).toString())
                    check(phase!="failed"){"Direct USB source or cleanup failed"}
                    delay(50)
                }
            } catch(error: CancellationException){throw error}
            catch(error: Exception){mutable.value=mutable.value.copy(error=error.message ?: "Direct USB failed")}
            finally {
                withContext(NonCancellable) {
                    commands.close();stopped.set(true)
                    val handle=lease.get()
                    var close=JSONObject().put("retired",handle==0L).put("restored",handle==0L)
                    try {if(handle>0){NativeDirect.stop(handle);close=JSONObject(NativeDirect.close(handle))}}
                    catch(error: Exception){mutable.value=mutable.value.copy(error="Native cleanup failed: ${error.message}")}
                    lease.set(0)
                    val first=close.optLong("firstFrame").coerceAtLeast(0)
                    val completed=close.optLong("completedFrames").coerceAtLeast(0)
                    val frames=if(completed>Long.MAX_VALUE-first)Long.MAX_VALUE else first+completed
                    val position=if(rate>0)frames/rate*1000+(frames%rate)*1000/rate else mutable.value.positionMs
                    mutable.value=mutable.value.copy(entryId=entry,phase="closed",generation=close.optLong("generation",mutable.value.generation),
                        positionMs=position,completedFrames=completed,integrity="unverified",pending=0,closed=true,retired=close.optBoolean("retired"),restored=close.optBoolean("restored"),
                        diagnostics=close.put("sampleRateHz",rate).put("bits",bits).toString())
                }
                scope.cancel()
            }
        }
    }
    private fun submit(command: Command): Boolean=!stopped.get() && commands.trySend(command).isSuccess
    fun select(track: DirectTrack): Boolean {
        if(track.entryId.length !in 1..128 || track.uri.scheme!="content" || track.uri.toString().length>2048 ||
            track.rate !in listOf(44100,48000,88200,96000) || track.bits !in listOf(16,24) || track.positionMs<0 || track.positionMs>Long.MAX_VALUE/track.rate)return false
        return submit(Command.Select(track))
    }
    fun play(value: Boolean): Boolean=state.value.let {submit(Command.Play(it.entryId,it.generation,value))}
    fun seek(positionMs: Long): Boolean {
        val view=state.value
        if(positionMs<0 || positionMs>Long.MAX_VALUE/96000)return false
        return submit(Command.Seek(view.entryId,view.generation,positionMs))
    }
    fun requestStop() {
        if(stopped.compareAndSet(false,true)) {
            commands.close()
            scope.launch(Dispatchers.IO){val handle=lease.get();if(handle>0)NativeDirect.stop(handle)}
        }
    }
    companion object {
        fun hardwareAvailable(): Boolean {NativeDirect.initialize();return NativeDirect.available()}
        private fun frames(ms: Long,rate: Int): Long=ms*rate/1000
    }
}
