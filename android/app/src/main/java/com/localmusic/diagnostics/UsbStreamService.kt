package com.localmusic.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.AtomicFile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

data class StreamUi(val busy: Boolean=false,val message: String="Ready for experimental direct test audio.",
    val fixture: String="",val stats: String="{}",val report: String?=null)

/** Diagnostic-only foreground owner. It never creates an Android audio player. */
class UsbStreamService: Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val handler=Handler(Looper.getMainLooper())
    private val reportIo=Dispatchers.IO.limitedParallelism(1)
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private var wake: PowerManager.WakeLock?=null
    private var focus: AudioFocusRequest?=null
    private var running=false
    private var closing=false
    private var ownsGate=false
    private var reserved=false
    private var receiverRegistered=false
    private var reason=""
    private var ticks=0
    private var runId=""
    private var startedAt=""
    private var requestedSeconds=0
    private var evictedSamples=0
    private val samples=JSONArray()
    private val ticker=object: Runnable {
        override fun run() {
            if(!running) return
            val snapshot=runCatching { NativeUsb.streamStats() }.getOrDefault("{}")
            state=state.copy(stats=snapshot)
            if(ticks++%10==0) {
                if(samples.length()>=450) { samples.remove(0); evictedSamples++ }
                samples.put(JSONObject().put("at",Instant.now().toString())
                    .put("interactive",getSystemService(PowerManager::class.java).isInteractive).put("native",JSONObject(snapshot)))
                val partial=JSONObject().put("schemaVersion",1).put("appVersion",StreamEvidence.VERSION)
                    .put("runId",runId).put("startedAt",startedAt).put("processId",android.os.Process.myPid())
                    .put("durationSecondsRequested",requestedSeconds).put("interruptionReason",reason.ifBlank { "none_recorded_yet" })
                    .put("incomplete",true).put("fixture",state.fixture).put("capturedAt",Instant.now().toString())
                    .put("progress",JSONArray(samples.toString())).put("evictedSamples",evictedSamples).toString()
                scope.launch(reportIo) { saveAtomic("native-stream-incomplete.json",partial) }
            }
            handler.postDelayed(this,1000)
        }
    }
    private val receiver=object: BroadcastReceiver() {
        override fun onReceive(context: Context,intent: Intent) { requestStop("usb_detached") }
    }
    override fun onBind(intent: Intent?): IBinder?=null
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action==STOP) { requestStop("notification_stop"); if(!running) stopSelf(); return START_NOT_STICKY }
        if(intent?.action!=START || running || closing) return START_NOT_STICKY
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Direct USB experiment",NotificationManager.IMPORTANCE_LOW))
        val stop=PendingIntent.getService(this,2,Intent(this,UsbStreamService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,3,Intent(this,DirectTestActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Experimental Direct USB").setContentText("Quiet fixture test · clocking unvalidated")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop and release USB",stop).build()).build()
        startForeground(505,notification)
        if(UsbDiagnosticGate.held || !intent.getBooleanExtra("confirmed",false)) {
            state=state.copy(message="Another USB check is active, or confirmation is missing."); stopSelf(); return START_NOT_STICKY
        }
        val fixture=intent.getStringExtra("fixture").orEmpty()
        val seconds=intent.getIntExtra("seconds",12)
        if(!DiagnosticFixture.supported(fixture) || seconds !in listOf(12,60,3660)) {
            state=state.copy(message="Invalid diagnostic fixture or duration."); stopSelf(); return START_NOT_STICKY
        }
        val device=usb.deviceList[intent.getStringExtra("device")]
        if(device==null || device.vendorId!=0x31b2 || device.productId!=0x0311 || !usb.hasPermission(device)) {
            state=state.copy(message="M2 USB permission is needed. Return to Inspect cable first."); stopSelf(); return START_NOT_STICKY
        }
        ownsGate=true; UsbDiagnosticGate.held=true; BaselineService.releaseForNative()
        runId=java.util.UUID.randomUUID().toString(); startedAt=Instant.now().toString(); requestedSeconds=seconds
        running=true; state=StreamUi(true,"Checking fixture and preparing low hardware volume…",fixture)
        val filter=IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(receiver,filter,RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver,filter)
        receiverRegistered=true
        scope.launch {
            var native: JSONObject?=null
            var preparationError: String?=null
            var source: JSONObject?=null
            try {
                focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener({ change -> if(change!=AudioManager.AUDIOFOCUS_GAIN) requestStop("audio_focus_loss") },handler).build()
                check(audio.requestAudioFocus(focus!!)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus denied; no sound started." }
                reserved=NativeUsb.beginStream()
                check(reserved) { "Another native stream owns the engine." }
                wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"localmusic:usb-test").apply { acquire((seconds+300L)*1000) }
                handler.post(ticker)
                val result=withContext(Dispatchers.IO) {
                    val manifest=JSONObject(assets.open(DiagnosticFixture.manifest(fixture)).bufferedReader().use { it.readText() }).getJSONArray("fixtures")
                    val entry=(0 until manifest.length()).map { manifest.getJSONObject(it) }.first { it.getString("file")==fixture }
                    val input=assets.open(fixture).use { it.readBytes() }
                    check(hash(input)==entry.getString("fileSha256")) { "Encoded fixture checksum failed." }
                    val rate=entry.getInt("sampleRateHz"); val bits=entry.getInt("meaningfulBits")
                    val pcm=NativeUsb.decodePcm(input,rate,bits)
                    val pcmHash=hash(pcm)
                    check(pcmHash==entry.getString("pcmSha256")) { "Native decoded PCM does not match reference; audio blocked." }
                    val connection=usb.openDevice(device) ?: error("Cannot open M2.")
                    try {
                        val raw=connection.rawDescriptors ?: error("Missing descriptors")
                        check(hash(raw)=="0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436") { "M2 descriptor layout changed; audio blocked." }
                        Pair(entry,JSONObject(NativeUsb.runStream(connection.fileDescriptor,pcm,rate,bits,seconds)))
                    } finally { connection.close() }
                }
                source=result.first; native=result.second
            } catch(error: Exception) { preparationError=error.message ?: error.javaClass.simpleName }
            catch(error: LinkageError) { preparationError=error.message ?: "Native library could not load" }
            finally {
                closing=true
                handler.removeCallbacks(ticker)
                if(reserved) runCatching { NativeUsb.streamCommand(4,0) }
                if(receiverRegistered) { unregisterReceiver(receiver); receiverRegistered=false }
                focus?.let { audio.abandonAudioFocusRequest(it) }
                wake?.let { if(it.isHeld) it.release() }
                if(ownsGate) { UsbDiagnosticGate.held=false; ownsGate=false }
            }
            try {
            val report=JSONObject().put("schemaVersion",1).put("appVersion",StreamEvidence.VERSION)
                .put("runId",runId).put("startedAt",startedAt).put("processId",android.os.Process.myPid())
                .put("exportedAt",Instant.now().toString()).put("phone",JSONObject().put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT).put("buildDisplay",Build.DISPLAY))
                .put("scope","Experimental nominal-rate direct USB fixture transport; missing-feedback clocking remains unvalidated. No end-to-end bit-perfect claim.")
                .put("physicalPreset","Standard (user confirmed)").put("fixture",fixture).put("durationSecondsRequested",seconds)
                .put("signalPattern",DiagnosticFixture.pattern(fixture))
                .put("source",source ?: JSONObject.NULL).put("native",native ?: JSONObject.NULL)
                .put("nativeLiveFinalStats",runCatching { JSONObject(NativeUsb.streamStats()) }.getOrDefault(JSONObject()))
                .put("decodedPcmSha256Verified",source!=null).put("decoders","native PCM WAV / libFLAC 1.4.3")
                .put("unavailableMeasurements",JSONArray(listOf("Device internal buffer underruns / overflows","Independent physical bus PCM capture","DAC render clock and internal DSP")))
                .put("preparationError",preparationError ?: JSONObject.NULL).put("interruptionReason",reason.ifBlank { "none" })
                .put("progress",samples).put("evictedSamples",evictedSamples).toString(2)
            val restored=native?.optBoolean("settingsRestored")==true
            state=state.copy(busy=false,stats=runCatching { NativeUsb.streamStats() }.getOrDefault("{}"),report=report,
                message=if(preparationError!=null) "Test failed: $preparationError. Save the report; reconnect before listening if USB was claimed."
                else if(!restored) "Test ended. Restoration not verified: reconnect M2 before listening. Save the report."
                else "Test ended; settings restoration checked. Save the report and record what you heard.")
            withContext(reportIo) { saveAtomic("native-stream-last.json",report) }
            } finally {
                running=false
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); scope.cancel()
            }
        }
        return START_NOT_STICKY
    }
    private fun requestStop(why: String) {
        if(!running || closing) return
        reason=why
        runCatching { NativeUsb.streamCommand(0,0) }
        state=state.copy(message="Stopping and draining USB transfers…")
    }
    private fun saveAtomic(name: String,text: String) {
        val file=AtomicFile(java.io.File(filesDir,name))
        var output: java.io.FileOutputStream?=null
        try { output=file.startWrite(); output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch(_: Exception) { output?.let { file.failWrite(it) } }
    }
    override fun onDestroy() {
        requestStop("service_destroyed")
        super.onDestroy()
    }
    companion object {
        const val START="com.localmusic.diagnostics.STREAM_START"
        const val STOP="com.localmusic.diagnostics.STREAM_STOP"
        private const val CHANNEL="direct-usb-experiment"
        var state by mutableStateOf(StreamUi())
            private set
        fun command(command: Int,value: Int=0) { if(state.busy) NativeUsb.streamCommand(command,value) }
        private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
