package com.localmusic.diagnostics

import android.app.Activity
import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Platform instrumentation: no new test framework or real USB/audio backend. */
class UsbRuntimeInstrumentation: Instrumentation() {
    private val cases=JSONArray()
    private val memory=JSONArray()
    private var assertions=0
    private var only: String?=null
    private fun verify(ok: Boolean,why: String) { assertions++; check(ok) { why } }
    private fun <T> main(block: () -> T): T {
        var result: T?=null
        runOnMainSync { result=block() }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun until(why: String,timeoutMs: Long=30_000,condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+timeoutMs
        while(!condition()) { check(SystemClock.elapsedRealtime()<end) { "Timeout: $why" }; Thread.sleep(20) }
    }
    override fun onCreate(arguments: Bundle?) { only=arguments?.getString("only");super.onCreate(arguments); start() }
    override fun onStart() {
        val report=JSONObject().put("completed",false).put("simulatedUsb",true)
            .put("processId",android.os.Process.myPid()).put("sdk",android.os.Build.VERSION.SDK_INT).put("model",android.os.Build.MODEL)
            .put("cases",cases).put("memory",memory)
        try {
            verify(BuildConfig.USB_SELF_TEST,"Only isolated selftest package may execute")
            verify(targetContext.packageName=="com.localmusic.diagnostics.selftest","Daily/normal Probe package excluded")
            if(only=="production-owner") {
                val result=ProductionOwnerChecks(this).run();report.put("productionOwner",result);assertions+=result.getInt("assertions")
            } else if(only=="source-session" || only=="source-controls") {
                val result=SourceSessionChecks(this).run(only=="source-controls");report.put("sourceSession",result)
                assertions+=result.getInt("assertions")
            } else {
            val tag=File.createTempFile("fake-usb-",".tag",targetContext.cacheDir)
            try {
                ParcelFileDescriptor.open(tag,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    verify(tag.delete(),"Unlink fake device tag")
                    main { UsbStreamService.selfTestFd=fd.fd }
                    if(only!=null)runCase(24,96000,only!!)
                    else {
                    for(bits in listOf(16,24)) for(rate in listOf(44100,48000,88200,96000)) runCase(bits,rate,"eof")
                    for(event in listOf("stop","focus","detach","destroy","submit_failure","packet_failure")) runCase(24,96000,event)
                    for(cycle in 0 until 12) {
                        runCase(if(cycle%2==0) 24 else 16,if(cycle%2==0) 96000 else 44100,"eof")
                        if(cycle==2 || cycle==11) sampleMemory(cycle+1)
                    }
                    val warm=memory.getJSONObject(0);val last=memory.getJSONObject(1)
                    for(key in listOf("javaUsed","nativeAllocated","pssBytes")) {
                        verify(last.getLong(key)<256L*1024*1024,"Finite app memory budget: $key")
                        verify(last.getLong(key)-warm.getLong(key)<16L*1024*1024,"Warmed growth budget: $key")
                    }
                    verify(last.getInt("fds")-warm.getInt("fds")<=4,"No growing descriptor population")
                    }
                    main { UsbStreamService.selfTestFd=-1 }
                }
            } finally { tag.delete() }
            }
            report.put("completed",true)
        } catch(error: Throwable) {
            report.put("failure",error.stackTraceToString())
            runCatching { NativeUsb.streamCommand(0,0) }
            runCatching { until("failure drain",10_000) { main { !UsbStreamService.state.busy } } }
        } finally {
            main { UsbStreamService.selfTestFd=-1 }
            report.put("assertions",assertions)
            val file=File(targetContext.filesDir,"service-selftest.json")
            file.writeText(report.toString(2))
            val result=Bundle().apply {
                putString("report",file.absolutePath)
                putString("completed",report.getBoolean("completed").toString())
                putInt("assertions",assertions)
                putString("failure",report.optString("failure"))
            }
            finish(if(report.getBoolean("completed")) Activity.RESULT_OK else Activity.RESULT_CANCELED,result)
        }
    }
    private fun sampleMemory(cycle: Int) {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        memory.put(JSONObject().put("cycle",cycle).put("javaUsed",runtime.totalMemory()-runtime.freeMemory())
            .put("nativeAllocated",Debug.getNativeHeapAllocatedSize()).put("pssBytes",info.totalPss*1024L)
            .put("fds",File("/proc/self/fd").list()!!.size))
    }
    private fun field(service: UsbStreamService,name: String): Any? =
        UsbStreamService::class.java.getDeclaredField(name).apply { isAccessible=true }.get(service)

    private fun runCase(bits: Int,rate: Int,event: String) {
        val fixture="stereo-$bits-$rate.flac"
        val manifest=JSONObject(targetContext.assets.open("fixtures-manifest.json").bufferedReader().use { it.readText() }).getJSONArray("fixtures")
        val waveName="stereo-$bits-$rate.wav"
        val entry=(0 until manifest.length()).map { manifest.getJSONObject(it) }.first { it.getString("file")==waveName }
        val reference=File.createTempFile("independent-pcm-",".wav",targetContext.cacheDir)
        var heldService: UsbStreamService?=null
        var otherFocus: AudioFocusRequest?=null
        val audio=targetContext.getSystemService(AudioManager::class.java)
        try {
            reference.outputStream().use { output -> targetContext.assets.open(waveName).use { input ->
                StreamingFixtureFile.copyVerified(input,output,entry.getString("fileSha256"))
            } }
            ParcelFileDescriptor.open(reference,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                verify(reference.delete(),"Reference file unlinked")
                SelfTestNative.prepare(fd.fd,rate,bits,if(event=="submit_failure") 2 else if(event=="packet_failure") 3 else 0)
                main {
                    targetContext.startForegroundService(UsbStreamService.internalIntent(targetContext,UsbStreamService.START)
                        .putExtra("confirmed",true).putExtra("fixture",fixture).putExtra("seconds",6).putExtra("finiteStreaming",true))
                }
                until("service starts") { main { UsbStreamService.state.busy } }
                if(event !in listOf("submit_failure","packet_failure")) {
                    until("JNI submits bounded packets") { JSONObject(NativeUsb.streamStats()).optLong("submittedBytes")>0 }
                    heldService=main { UsbStreamService.selfTestInstance?.get() }
                    verify(heldService!=null,"Actual service instance active")
                    verify(JSONObject(main { UsbStreamService.mediaSessionState }).getBoolean("sessionPresent"),"Media3 session active")
                    main { UsbStreamService.command(1,1);UsbStreamService.command(2,rate*3);UsbStreamService.command(3,-20) }
                    verify(JSONObject(NativeUsb.streamStats()).optInt("pauseCount")==0,"Finite pause remains unavailable")
                    when(event) {
                        "stop" -> main { UsbStreamService.command(0) }
                        "detach" -> main { (field(heldService!!,"receiver") as BroadcastReceiver).onReceive(targetContext,Intent(UsbManager.ACTION_USB_DEVICE_DETACHED)) }
                        "destroy" -> main { targetContext.stopService(Intent(targetContext,UsbStreamService::class.java)) }
                        "focus" -> {
                            otherFocus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                                .setOnAudioFocusChangeListener({ },android.os.Handler(android.os.Looper.getMainLooper())).build()
                            verify(audio.requestAudioFocus(otherFocus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED,"System grants competing silent focus request")
                        }
                    }
                }
                until("service finishes and destroys",45_000) {
                    main { !UsbStreamService.state.busy && !UsbDiagnosticGate.held && UsbStreamService.selfTestInstance?.get()==null }
                }
                val result=JSONObject(main { requireNotNull(UsbStreamService.state.report) })
                val native=result.getJSONObject("native")
                val stream=native.getJSONObject("streamExperiment")
                val expected=if(event=="eof") "eof" else if(event.endsWith("failure")) "failed" else "cancelled"
                verify(stream.getString("terminal")==expected,"Terminal for $event")
                verify(result.getBoolean("simulatedTransport"),"Report identifies simulated transport")
                verify(native.getBoolean("settingsRestored"),"Actual JNI restores controls after drain")
                val stats=result.getJSONObject("nativeLiveFinalStats")
                verify(stats.getInt("pendingTransfers")==0,"No pending transfers after service completion")
                verify(!result.getJSONObject("mediaCard").getBoolean("sessionPresent"),"Media session released")
                if(event=="eof") {
                    verify(stream.getBoolean("wholeStreamMd5Verified"),"Actual JNI reports whole-pass integrity")
                    verify(stats.getLong("completedBytes")==rate*6L*bits/8*2,"Exact app/JNI completed PCM count")
                }
                if(event=="focus") verify(result.getString("interruptionReason")=="audio_focus_loss","Real system focus-loss delivery")
                if(event=="detach") verify(result.getString("interruptionReason")=="usb_detached","Real detach handler stops JNI")
                if(event=="destroy") verify(result.getString("interruptionReason")=="service_destroyed","onDestroy drains JNI")
                heldService?.let { service -> verify(!(main { field(service,"wake") } as? PowerManager.WakeLock)?.isHeld.orFalse(),"Wake lock released") }
                val evidence=JSONObject(SelfTestNative.evidence())
                verify(evidence.getInt("pending")==0 && evidence.getInt("allocations")==evidence.getInt("frees"),"Native buffers freed after late callbacks")
                verify(evidence.getInt("contexts")==1 && evidence.getInt("closed")==1,"Native context closed exactly once")
                verify(evidence.getLong("largestAllocation")<=1024*1024,"Bounded native request")
                cases.put(JSONObject().put("fixture",fixture).put("event",event).put("terminal",expected).put("native",evidence))
                SelfTestNative.release()
            }
        } finally {
            otherFocus?.let { audio.abandonAudioFocusRequest(it) }
            reference.delete()
        }
    }
    private fun Boolean?.orFalse()=this ?: false
}
