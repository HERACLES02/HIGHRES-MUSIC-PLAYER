package com.localmusic.player

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import com.localmusic.core.*
import com.localmusic.usb.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import org.json.JSONObject

/** Main-thread output handoff; queue and persistence remain in PlaybackAuthority.
 * Only an explicitly enabled personal build permits entry. */
internal class DirectPlayback(private val context: Context,private val owner: PlaybackAuthority) {
    var selected=false;private set
    var view=DirectView();private set
    private var lastDirect: DirectView?=null
    fun report(): JSONObject=(if(selected)view else lastDirect)?.let {
        JSONObject(it.diagnostics).put("active",selected).put("controllerError",it.error)
            .put("clockingValidated",false).put("analogBalanceValidated",false)
            .put("limits","Worker event-service gap is not measured DAC timing. Queue-empty count includes source startup. Latest Direct session in this process only.")
    } ?: JSONObject().put("active",false).put("notObservedInThisProcess",true)
    private var controller: DirectOutputController?=null
    private var connection: UsbDeviceConnection?=null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var switching: Job?=null
    private var watcher: Job?=null
    private var returning=false
    private var minimumGeneration=0L
    private var expectedEntry=""
    private var endGeneration=0L
    private var permission: BroadcastReceiver?=null
    private var epoch=0L
    val matches: Boolean get()=view.entryId==owner.queue.current?.id?.value?.toString()
    val positionMs: Long get()=if(matches)view.positionMs else owner.queue.positionMs
    private fun candidate(): Boolean=owner.current?.let {it.availability==Availability.AVAILABLE && DirectUsbFormat.supported(it.audio)}==true
    fun request(): Boolean {
        if(selected)return true
        if(!BuildConfig.DIRECT_USB_ENABLED || !DirectOutputController.hardwareAvailable()) {
            owner.outputChanged("Native Direct USB is unavailable in this build.");return false
        }
        if(!candidate() || !owner.directProcessingAllowed()) {
            owner.outputChanged("Direct USB requires supported stereo FLAC with EQ, ReplayGain and crossfade off.");return false
        }
        val manager=context.getSystemService(UsbManager::class.java)
        val device=manager.deviceList.values.singleOrNull{it.vendorId==0x31b2 && it.productId==0x0311}
        if(device==null){owner.outputChanged("Connect the M2 before selecting Direct USB.");return false}
        if(manager.hasPermission(device)){begin(device);return true}
        if(permission!=null)return false
        val requestEpoch=++epoch
        val action="${context.packageName}.DIRECT_USB_PERMISSION"
        val receiver=object:BroadcastReceiver(){
            override fun onReceive(c:Context,intent:Intent) {
                clearPermission()
                if(requestEpoch!=epoch || owner.outputDestroyed)return
                val current=manager.deviceList[device.deviceName]
                if(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,false) && current!=null && manager.hasPermission(current))begin(current)
                else owner.outputChanged("USB permission was not granted.")
            }
        }
        permission=receiver
        if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,IntentFilter(action),Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") context.registerReceiver(receiver,IntentFilter(action))
        manager.requestPermission(device,PendingIntent.getBroadcast(context,0,Intent(action).setPackage(context.packageName),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        return true
    }
    private fun clearPermission(){permission?.let{context.unregisterReceiver(it)};permission=null}
    private fun begin(device: UsbDevice) {
        if(selected || owner.outputDestroyed || !candidate() || !owner.directProcessingAllowed())return
        val tickets=owner.retireNormalForDirect()
        endGeneration=0;minimumGeneration=0;expectedEntry=""
        selected=true;returning=false;view=DirectView();owner.outputChanged()
        switching=scope.launch {
            try {
                withTimeout(3000) {while(!tickets.all{it.isRetired()})delay(10)}
                if(returning || owner.outputDestroyed){finishNormal();return@launch}
                val opened=withContext(Dispatchers.IO) {
                    val manager=context.getSystemService(UsbManager::class.java)
                    check(manager.hasPermission(device)){"USB permission was revoked"}
                    val value=manager.openDevice(device) ?: error("Could not open M2")
                    try {
                        val bytes=value.rawDescriptors ?: error("No USB descriptors")
                        check(bytes.size<=65535 && MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}==
                            "0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436"){"M2 descriptor layout changed"}
                        value
                    } catch(error:Exception){value.close();throw error}
                }
                connection=opened
                if(returning || owner.outputDestroyed){withContext(Dispatchers.IO){opened.close()};connection=null;finishNormal();return@launch}
                val active=DirectOutputController(context,opened.fileDescriptor)
                controller=active
                watcher=scope.launch {
                    active.state.first { value ->
                        if(value.closed) {
                            lastDirect=value
                            view=value
                            owner.directPositionChanged()
                            if(value.retired)withContext(Dispatchers.IO){connection?.close()}
                            if(value.retired)connection=null
                            if(value.retired && value.restored)finishNormal(value.error)
                            else owner.outputChanged(value.error ?: "USB cleanup is uncertain. Reconnect M2 and restart the app.")
                            return@first true
                        }
                        if(value.error!=null){owner.outputChanged(value.error);return@first false}
                        if(value.entryId!=expectedEntry || value.generation<minimumGeneration)return@first false
                        view=if(returning)value.copy(phase="stopping")else value;owner.directPositionChanged()
                        lastDirect=value
                        if(value.phase=="end" && value.generation!=endGeneration && matches && owner.queue.isPlaying) {
                            endGeneration=value.generation;owner.advance(true)
                        }
                        false
                    }
                }
                load(owner.queue.selection,false)
            } catch(error:CancellationException){throw error}
            catch(error:Exception){
                if(controller!=null){returning=true;owner.outputChanged(error.message);controller?.requestStop()}
                else {
                    withContext(Dispatchers.IO){connection?.close()};connection=null
                    finishNormal(error.message ?: "Direct USB unavailable")
                }
            } finally {switching=null}
        }
    }
    fun load(selection: Selection?,playing: Boolean): Boolean {
        if(selection==null || !candidate()) {normal("This song needs Normal output.");return false}
        val active=controller ?: return false
        val track=owner.current ?: return false
        val source=owner.mediaItem(track).localConfiguration?.uri ?: return false
        expectedEntry=selection.entry.id.value.toString();minimumGeneration=active.state.value.generation+1
        if(!active.select(DirectTrack(expectedEntry,source,track.audio.sampleRateHz!!,track.audio.meaningfulBits!!,selection.positionMs,playing))) {
            normal("Direct source command queue is busy. Normal output restored paused.");return false
        }
        view=DirectView(entryId=expectedEntry,positionMs=selection.positionMs)
        owner.outputChanged()
        return true
    }
    fun play(value: Boolean): Boolean {
        if(view.phase=="end" && !value)return true
        return controller?.play(value)==true
    }
    fun seek(position: Long): Boolean {
        val active=controller ?: return false
        if(!active.seek(position))return false
        minimumGeneration=active.state.value.generation+1
        view=view.copy(phase="opening",positionMs=position)
        return true
    }
    fun normal(message: String=""): Boolean {
        ++epoch;clearPermission()
        if(!selected)return true
        returning=true
        owner.pauseDirectQueue(message)
        if(controller?.state?.value?.closed==true && !view.restored) {
            owner.outputChanged("USB cleanup is uncertain. Reconnect M2 and restart the app.");return false
        }
        controller?.requestStop()
        return true
    }
    private fun finishNormal(message: String?=null) {
        watcher?.cancel();watcher=null;controller=null;selected=false;returning=false;view=DirectView(phase="normal")
        owner.restoreNormalAfterDirect(message)
        if(owner.outputDestroyed)scope.cancel()
    }
    fun destroy() {
        ++epoch;clearPermission();returning=true
        controller?.requestStop()
        if(!selected && switching==null)scope.cancel()
    }
}
