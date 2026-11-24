package com.localmusic.diagnostics

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object SourceSessionNative {
    init { check(BuildConfig.USB_SELF_TEST);NativeUsb.hashCode() }
    @JvmStatic external fun open(): Long
    @JvmStatic external fun replace(handle: Long,source: Int,reference: Int,rate: Int,bits: Int,referenceOffset: Long): Long
    @JvmStatic external fun seek(handle: Long,generation: Long,frame: Long): Long
    @JvmStatic external fun setPaused(handle: Long,generation: Long,value: Boolean): Boolean
    @JvmStatic external fun stop(handle: Long)
    @JvmStatic external fun step(handle: Long): String
    @JvmStatic external fun close(handle: Long): String
}

data class SourceSessionView(val ready: Boolean=false,val native: String="{}",val closed: Boolean=false,
    val error: String?=null,val cleanup: String="{}")

/** Registered only in the isolated fake-backend manifest. No Android audio.
 * One IO owner, one mailbox slot, one native pending command. Destruction requests
 * Stop; the owner stays alive until native drain/close returns, then releases gate.
 */
class SourceSessionService: Service() {
    private sealed interface Command {
        data class Replace(val source: Uri,val reference: Uri,val rate: Int,val bits: Int): Command
        data class Seek(val generation: Long,val frame: Long): Command
        data class Pause(val generation: Long,val value: Boolean): Command
    }
    private val mutable=MutableStateFlow(SourceSessionView())
    val state=mutable.asStateFlow()
    private val commands=Channel<Command>(1)
    private val stopped=AtomicBoolean(false)
    private val rejected=AtomicInteger(0)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO.limitedParallelism(1))
    private var owner: Job?=null
    val rejectedCount: Int get()=rejected.get()
    inner class OwnerBinder: Binder() { val owner: SourceSessionService get()=this@SourceSessionService }
    override fun onBind(intent: Intent?): IBinder=OwnerBinder()
    override fun onCreate() {
        super.onCreate()
        if(!BuildConfig.USB_SELF_TEST || UsbDiagnosticGate.held) {
            mutable.value=SourceSessionView(closed=true,error="Isolated source session unavailable")
            commands.close();scope.cancel();return
        }
        // Main-thread gate also excludes the existing finite/legacy Probe route.
        UsbDiagnosticGate.held=true
        owner=scope.launch {
            var handle=0L
            try {
                handle=SourceSessionNative.open()
                check(handle>0) { "Native source session already owned or unavailable" }
                mutable.value=SourceSessionView(ready=true,native=SourceSessionNative.step(handle))
                while(!stopped.get()) {
                    // Do not drain the mailbox into growing native pending work.
                    if(JSONObject(mutable.value.native).optString("phase")!="changing") {
                        when(val command=commands.tryReceive().getOrNull()) {
                            is Command.Replace -> {
                                contentResolver.openFileDescriptor(command.source,"r").use { source ->
                                    check(source!=null) { "Provider returned no source descriptor" }
                                    if(!stopped.get())contentResolver.openFileDescriptor(command.reference,"r").use { reference ->
                                        check(reference!=null) { "Provider returned no reference descriptor" }
                                        if(!stopped.get() && SourceSessionNative.replace(handle,source.fd,reference.fd,command.rate,command.bits,44)==0L)rejected.incrementAndGet()
                                    }
                                }
                            }
                            is Command.Seek -> if(SourceSessionNative.seek(handle,command.generation,command.frame)==0L)rejected.incrementAndGet()
                            is Command.Pause -> if(!SourceSessionNative.setPaused(handle,command.generation,command.value))rejected.incrementAndGet()
                            null -> Unit
                        }
                    }
                    if(stopped.get())SourceSessionNative.stop(handle)
                    val next=SourceSessionNative.step(handle)
                    mutable.value=mutable.value.copy(native=next)
                    check(JSONObject(next).optString("phase")!="failed") { "Native source session failed" }
                    delay(1)
                }
                SourceSessionNative.stop(handle)
                do {
                    val next=SourceSessionNative.step(handle)
                    mutable.value=mutable.value.copy(native=next)
                } while(JSONObject(next).optInt("pending")>0 || JSONObject(next).optString("phase") !in listOf("stopped","failed"))
                check(JSONObject(mutable.value.native).optString("phase")!="failed") { "Native source failed during Stop" }
            } catch(error: CancellationException) { throw error }
            catch(error: Exception) { mutable.value=mutable.value.copy(error=error.message ?: error.javaClass.simpleName) }
            finally {
                // Cancellation must not close/reuse fds or release the owner gate
                // before native callbacks retire. No Java call from USB callback.
                withContext(NonCancellable) {
                    commands.close()
                    var cleanup="{}"
                    try { if(handle>0)cleanup=SourceSessionNative.close(handle) }
                    catch(error: Exception) { mutable.value=mutable.value.copy(error="Native close failed: ${error.message}") }
                    val safe=handle==0L || JSONObject(cleanup).optBoolean("closed")
                    if(safe)withContext(Dispatchers.Main.immediate) { UsbDiagnosticGate.held=false }
                    // If close cannot establish retirement, keep gate held.
                    mutable.value=mutable.value.copy(closed=true,cleanup=cleanup)
                }
                scope.cancel()
            }
        }
    }
    private fun submit(command: Command): Boolean {
        if(stopped.get() || mutable.value.closed || !commands.trySend(command).isSuccess) {
            rejected.incrementAndGet();return false
        }
        return true
    }
    fun replace(source: Uri,reference: Uri,rate: Int,bits: Int): Boolean {
        if(source.scheme!="content" || reference.scheme!="content" || source.toString().length>2048 || reference.toString().length>2048)return false
        return submit(Command.Replace(source,reference,rate,bits))
    }
    fun seek(generation: Long,frame: Long): Boolean=submit(Command.Seek(generation,frame))
    fun setPaused(generation: Long,value: Boolean): Boolean=submit(Command.Pause(generation,value))
    fun requestStop() { stopped.set(true);commands.close() }
    override fun onDestroy() {
        requestStop()
        // Blocking IO/JNI drain continues on the owned worker, never Main.
        if(owner==null)scope.cancel()
        super.onDestroy()
    }
}
