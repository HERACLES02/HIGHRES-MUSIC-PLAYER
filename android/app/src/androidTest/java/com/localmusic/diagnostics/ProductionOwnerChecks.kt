package com.localmusic.diagnostics

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import android.os.Debug
import com.localmusic.usb.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

internal class ProductionOwnerChecks(private val test: Instrumentation) {
    private var assertions=0
    private fun verify(value: Boolean,why: String){assertions++;check(value){why}}
    private fun until(why: String,timeoutMs: Long=45000,condition: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(!condition()){check(SystemClock.elapsedRealtime()<deadline){"Timeout: $why"};Thread.sleep(10)}
    }
    fun run(): JSONObject {
        NativeDirect.initialize(true)
        verify(!NativeDirect.available() && NativeDirect.open(-1)==0L,"Beta physical gate rejects before any USB work")
        val context=test.targetContext;val root=File(context.filesDir,"source-fixtures");root.mkdirs()
        val manifest=JSONObject(context.assets.open("fixtures-manifest.json").bufferedReader().use{it.readText()}).getJSONArray("fixtures")
        for(i in 0 until manifest.length()) {
            val entry=manifest.getJSONObject(i);val name=entry.getString("file")
            if(name.matches(Regex("stereo-(16|24)-(44100|48000|88200|96000)\\.(flac|wav)")))
                File(root,name).outputStream().use { out ->context.assets.open(name).use { StreamingFixtureFile.copyVerified(it,out,entry.getString("fileSha256")) } }
        }
        val cases=JSONArray()
        val memory=JSONArray()
        run {
            fun uri(ext: String)=Uri.parse("content://${context.packageName}.fixtures/stereo-24-96000.$ext")
            context.contentResolver.openFileDescriptor(uri("wav"),"r")!!.use { reference ->
                SelfTestNative.prepare(reference.fd,96000,24,0)
                NativeDirectTest.armReads()
                val controller=DirectOutputController(context,reference.fd,NativeDirectTest::open)
                try {
                    verify(controller.select(DirectTrack("old-entry",uri("flac"),96000,24)),"Start held source")
                    NativeDirectTest.waitReads()
                    until("held source paused") {controller.state.value.phase=="paused"}
                    verify(controller.select(DirectTrack("new-entry",uri("flac"),96000,24,125)),"Queue replacement while old decoder is held")
                    until("replacement joins old source") {NativeDirectTest.snapshot().contains("changing")}
                    controller.requestStop();NativeDirectTest.stop();NativeDirectTest.releaseReads()
                    until("cancelled replacement closes") {controller.state.value.closed}
                    verify(controller.state.value.entryId=="old-entry","Rejected replacement cannot label old PCM as the new song")
                    verify(controller.state.value.submittedFrames==0L && controller.state.value.restored,"Cancelled replacement submits no new PCM and restores controls")
                    cases.put("cancel-during-source-replacement")
                } finally {
                    NativeDirectTest.releaseReads();controller.requestStop();until("race cleanup"){controller.state.value.closed};SelfTestNative.release()
                }
            }
        }
        for(bits in listOf(16,24))for(rate in listOf(44100,48000,88200,96000)) {
            fun uri(ext: String)=Uri.parse("content://${context.packageName}.fixtures/stereo-$bits-$rate.$ext")
            context.contentResolver.openFileDescriptor(uri("wav"),"r")!!.use { reference ->
                SelfTestNative.prepare(reference.fd,rate,bits,0)
                val controller=DirectOutputController(context,reference.fd,NativeDirectTest::open)
                try {
                    verify(controller.select(DirectTrack("entry-$bits-$rate",uri("flac"),rate,bits,playing=false)),"Bounded production source command accepted")
                    until("source paused") { controller.state.value.phase=="paused" || controller.state.value.error!=null }
                    verify(controller.state.value.error==null && controller.state.value.pending==0,"Production source paused with no borrowed USB buffers")
                    verify(controller.play(true),"Production Resume accepted")
                    try {
                        until("source EOF") { controller.state.value.phase=="end" || controller.state.value.error!=null }
                    } catch(error:Exception){throw IllegalStateException("EOF state: ${controller.state.value}; native: ${NativeDirectTest.snapshot()}",error)}
                    val view=controller.state.value
                    verify(view.error==null && view.completedFrames==6L*rate && view.submittedFrames==6L*rate,"Exact actual production-owner PCM count")
                    verify(view.integrity=="whole","Production owner whole-stream integrity")
                    val diagnostics=JSONObject(view.diagnostics)
                    verify(diagnostics.getLong("completedFrames")==6L*rate && diagnostics.getInt("sampleRateHz")==rate && diagnostics.getInt("bits")==bits,"Report retains actual completed count and source format")
                    verify(diagnostics.getLong("packetErrors")==0L && diagnostics.getLong("transferErrors")==0L && diagnostics.getLong("maxEventServiceGapUs")>=0 && view.diagnostics.length<2048,"Bounded healthy native diagnostics")
                    SelfTestNative.prepare(reference.fd,rate,bits,0)
                    NativeDirectTest.referenceFrame(125L*rate/1000,bits)
                    verify(controller.seek(125),"Production seek accepted")
                    try {
                        until("seek EOF",15000) { controller.state.value.generation>view.generation && controller.state.value.phase=="end" || controller.state.value.error!=null }
                    } catch(error:Exception){throw IllegalStateException("Seek state: ${controller.state.value}",error)}
                    verify(controller.state.value.error==null && controller.state.value.integrity=="partial","Production seek integrity remains partial")
                    verify(controller.state.value.completedFrames==6L*rate-125L*rate/1000,"Exact production seek suffix frame count")
                    cases.put("production-owner-$bits-$rate")
                } finally {
                    controller.requestStop();until("owner close") { controller.state.value.closed }
                    verify(controller.state.value.retired && controller.state.value.restored,"Production close acknowledges retirement and restore")
                    SelfTestNative.release()
                }
            }
            if((bits==16 && rate==48000) || (bits==24 && rate==96000)) {
                val runtime=Runtime.getRuntime();val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
                val status=File("/proc/self/status").readText()
                memory.put(JSONObject().put("javaUsed",runtime.totalMemory()-runtime.freeMemory()).put("nativeAllocated",Debug.getNativeHeapAllocatedSize())
                    .put("pss",info.totalPss*1024L).put("fds",File("/proc/self/fd").list()!!.size)
                    .put("residentHighWater",Regex("VmHWM:\\s+(\\d+) kB").find(status)!!.groupValues[1].toLong()*1024))
            }
        }
        run {
            fun uri(ext: String)=Uri.parse("content://${context.packageName}.fixtures/stereo-24-96000.$ext")
            context.contentResolver.openFileDescriptor(uri("wav"),"r")!!.use { reference ->
                SelfTestNative.prepare(reference.fd,96000,24,3)
                val controller=DirectOutputController(context,reference.fd,NativeDirectTest::open)
                try {
                    verify(controller.select(DirectTrack("packet-fault",uri("flac"),96000,24,playing=true)),"Packet-fault source accepted")
                    until("packet-fault retirement"){controller.state.value.closed}
                    val diagnostics=JSONObject(controller.state.value.diagnostics)
                    verify(diagnostics.getLong("packetErrors")==1L && diagnostics.getLong("transferErrors")==0L && diagnostics.getInt("error")!=0,"Actual packet fault reaches final Android report distinctly")
                    verify(controller.state.value.error!=null && controller.state.value.retired && controller.state.value.restored,"Failure retains diagnostics through acknowledged cleanup")
                    cases.put("packet-error-report-after-close")
                } finally {controller.requestStop();until("fault cleanup"){controller.state.value.closed};SelfTestNative.release()}
            }
        }
        val warm=memory.getJSONObject(0);val last=memory.getJSONObject(1)
        for(key in listOf("javaUsed","nativeAllocated","pss"))verify(last.getLong(key)<256L*1024*1024 && last.getLong(key)-warm.getLong(key)<16L*1024*1024,"Production controller memory budget: $key")
        verify(last.getInt("fds")-warm.getInt("fds")<=4,"Production controller descriptor budget")
        return JSONObject().put("assertions",assertions).put("cases",cases).put("memory",memory)
    }
}
