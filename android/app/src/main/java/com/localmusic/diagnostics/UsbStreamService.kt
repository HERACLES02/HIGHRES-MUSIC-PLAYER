@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.localmusic.diagnostics

import android.app.Notification
import android.app.PendingIntent
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
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
class UsbStreamService: MediaLibraryService() {
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
    private var sessionPlayer: DirectSessionPlayer?=null
    private var librarySession: MediaLibrarySession?=null
    private var sessionWasCreated=false
    private var sessionWasReleased=false
    private val controllerEvents=JSONArray()
    private val mediaCommands=JSONArray()
    private var evictedSamples=0
    private val samples=JSONArray()
    private val ticker=object: Runnable {
        override fun run() {
            if(!running) return
            val snapshot=runCatching { NativeUsb.streamStats() }.getOrDefault("{}")
            state=state.copy(stats=snapshot)
            sessionPlayer?.observe(JSONObject(snapshot),running,closing,reason.isNotBlank())
            publishSessionEvidence()
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
    override fun onCreate() {
        super.onCreate()
        NotificationEvidence.ensureChannel(this)
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NotificationEvidence.STREAM_ID).setChannelId(NotificationEvidence.CHANNEL).build())
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = librarySession
    override fun onUpdateNotification(session: MediaSession,startInForegroundRequired: Boolean) {
        // Paused Direct retains native ownership until explicit Stop; keep its foreground service.
        super.onUpdateNotification(session,startInForegroundRequired || (running && !closing))
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if(!running) super.onTaskRemoved(rootIntent) }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(intent?.action in listOf(START,STOP,PAUSE,RESUME) && intent?.getStringExtra(INTERNAL_TOKEN)!=internalToken) {
            if(!running) stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==STOP) { requestStop("notification_stop"); if(!running) stopSelf(); return START_NOT_STICKY }
        if(intent?.action==PAUSE || intent?.action==RESUME) {
            setPaused(intent.action==PAUSE,"notification")
            if(!running) stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action!=START) { super.onStartCommand(intent,flags,startId); return START_NOT_STICKY }
        if(running || closing) return START_NOT_STICKY
        NotificationEvidence.ensureChannel(this)
        val stop=PendingIntent.getService(this,2,internalIntent(this,STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open=PendingIntent.getActivity(this,3,Intent(this,DirectTestActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,NotificationEvidence.CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Experimental Direct USB").setContentText("Quiet fixture test · clocking unvalidated")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"Stop and release USB",stop).build()).build()
        startForeground(NotificationEvidence.STREAM_ID,notification)
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
                createLibrarySession(fixture)
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
                closeLibrarySession()
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
            val finalReport=JSONObject(report).put("mediaCard",sessionEvidence())
                .put("mediaControlRequests",mediaCommands).put("controllerEvents",controllerEvents).toString(2)
            val restored=native?.optBoolean("settingsRestored")==true
            state=state.copy(busy=false,stats=runCatching { NativeUsb.streamStats() }.getOrDefault("{}"),report=finalReport,
                message=if(preparationError!=null) "Test failed: $preparationError. Save the report; reconnect before listening if USB was claimed."
                else if(!restored) "Test ended. Restoration not verified: reconnect M2 before listening. Save the report."
                else "Test ended; settings restoration checked. Save the report and record what you heard.")
            withContext(reportIo) { saveAtomic("native-stream-last.json",finalReport) }
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
        sessionPlayer?.observe(runCatching { JSONObject(NativeUsb.streamStats()) }.getOrDefault(JSONObject()),running,closing,true)
        runCatching { NativeUsb.streamCommand(0,0) }
        state=state.copy(message="Stopping and draining USB transfers…")
    }
    private fun createLibrarySession(fixture: String) {
        val player=DirectSessionPlayer(this,fixture,{ setPaused(it,"media3_player") },{ requestStop("media3_player_stop") })
        sessionPlayer=player
        val stopCommand=SessionCommand("com.localmusic.diagnostics.MEDIA_STOP",Bundle.EMPTY)
        val stopButton=CommandButton.Builder(CommandButton.ICON_STOP).setSessionCommand(stopCommand)
            .setDisplayName("Stop and release USB").setSlots(CommandButton.SLOT_FORWARD).build()
        val root=MediaItem.Builder().setMediaId("direct-diagnostic-root").setMediaMetadata(MediaMetadata.Builder()
            .setTitle("Direct USB diagnostic").setIsBrowsable(true).setIsPlayable(false).build()).build()
        val callback=object: MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession,controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                val allowed=controller.uid==applicationInfo.uid || controller.isTrusted
                if(controllerEvents.length()<50) controllerEvents.put(JSONObject().put("at",Instant.now().toString())
                    .put("trusted",controller.isTrusted).put("sameUid",controller.uid==applicationInfo.uid).put("accepted",allowed))
                if(!allowed) return MediaSession.ConnectionResult.reject()
                val commands=Player.Commands.Builder().addAll(Player.COMMAND_PLAY_PAUSE,Player.COMMAND_STOP,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE,Player.COMMAND_GET_METADATA,
                    Player.COMMAND_GET_AUDIO_ATTRIBUTES).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(stopCommand).build())
                    .setAvailablePlayerCommands(commands).build()
            }
            override fun onCustomCommand(session: MediaSession,controller: MediaSession.ControllerInfo,
                command: SessionCommand,args: Bundle): ListenableFuture<SessionResult> {
                if(command.customAction!=stopCommand.customAction) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                requestStop("media3_notification_stop")
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            override fun onGetLibraryRoot(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,
                params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root,params))
            override fun onGetItem(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,
                mediaId: String): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
                    when(mediaId) {
                        root.mediaId -> LibraryResult.ofItem(root,null)
                        player.item.mediaId -> LibraryResult.ofItem(player.item,null)
                        else -> LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                    })
            override fun onGetChildren(session: MediaLibrarySession,browser: MediaSession.ControllerInfo,
                parentId: String,page: Int,pageSize: Int,params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                Futures.immediateFuture(if(parentId==root.mediaId) LibraryResult.ofItemList(if(page==0) listOf(player.item) else emptyList(),params)
                    else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }
        val open=PendingIntent.getActivity(this,3,Intent(this,DirectTestActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        librarySession=MediaLibrarySession.Builder(this,player,callback).setId("direct-diagnostic")
            .setSessionActivity(open).setMediaButtonPreferences(listOf(stopButton)).build()
        sessionWasCreated=true
        addSession(librarySession!!)
        publishSessionEvidence()
    }
    private fun sessionEvidence()=JSONObject().put("implementation","Media3 MediaLibraryService / SimpleBasePlayer")
        .put("created",sessionWasCreated).put("released",sessionWasReleased)
        .put("sessionPresent",librarySession!=null).put("player",sessionPlayer?.evidence() ?: JSONObject.NULL)
        .put("playbackState",sessionPlayer?.playbackState ?: Player.STATE_IDLE)
        .put("playWhenReady",sessionPlayer?.playWhenReady ?: false).put("isPlaying",sessionPlayer?.isPlaying ?: false)
    private fun publishSessionEvidence() { mediaSessionState=sessionEvidence().toString() }
    private fun closeLibrarySession() {
        librarySession?.let { removeSession(it); it.release() }
        librarySession=null
        sessionPlayer?.let { it.observe(JSONObject(),false,true,true); it.release() }
        if(sessionWasCreated) sessionWasReleased=true
        publishSessionEvidence()
    }
    private fun setPaused(paused: Boolean,source: String) {
        if(!running || closing || reason.isNotBlank()) return
        val phase=runCatching { JSONObject(NativeUsb.streamStats()).optInt("phase") }.getOrDefault(0)
        if(!DirectPlaybackView.from(phase,running,closing,reason.isNotBlank(),0,96000).canToggle()) return
        if(mediaCommands.length()<100) mediaCommands.put(JSONObject().put("at",Instant.now().toString())
            .put("request",if(paused) "pause" else "resume").put("source",source))
        NativeUsb.streamCommand(1,if(paused) 1 else 0)
    }
    private fun saveAtomic(name: String,text: String) {
        val file=AtomicFile(java.io.File(filesDir,name))
        var output: java.io.FileOutputStream?=null
        try { output=file.startWrite(); output.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch(_: Exception) { output?.let { file.failWrite(it) } }
    }
    override fun onDestroy() {
        requestStop("service_destroyed")
        closeLibrarySession()
        super.onDestroy()
    }
    companion object {
        const val START="com.localmusic.diagnostics.STREAM_START"
        const val STOP="com.localmusic.diagnostics.STREAM_STOP"
        const val PAUSE="com.localmusic.diagnostics.STREAM_PAUSE"
        const val RESUME="com.localmusic.diagnostics.STREAM_RESUME"
        private const val INTERNAL_TOKEN="internalControlToken"
        private val internalToken=java.util.UUID.randomUUID().toString()
        fun internalIntent(context: Context,action: String)=Intent(context,UsbStreamService::class.java)
            .setAction(action).putExtra(INTERNAL_TOKEN,internalToken)
        var mediaSessionState="{}"
            private set
        var state by mutableStateOf(StreamUi())
            private set
        fun command(command: Int,value: Int=0) { if(state.busy) NativeUsb.streamCommand(command,value) }
        private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
