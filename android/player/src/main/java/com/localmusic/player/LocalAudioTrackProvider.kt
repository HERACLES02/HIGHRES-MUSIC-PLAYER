@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioTrack
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONObject
import com.localmusic.core.AndroidUsbBufferRules
import java.lang.ref.WeakReference

/** Standard buffering stays unchanged unless the user selects the Android USB route. */
class LocalAudioTrackProvider(private val appContext:Context?=null) : DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
    @Volatile var preferAndroidUsb=false
    private var plannedUsb:AudioDeviceInfo?=null
    private var lastTrack=WeakReference<AudioTrack>(null)
    val bufferSizeProvider=DefaultAudioSink.AudioTrackBufferSizeProvider { minimum,encoding,mode,frameBytes,rate,bitrate,maxSpeed ->
        val normal=DefaultAudioSink.AudioTrackBufferSizeProvider.DEFAULT.getBufferSizeInBytes(minimum,encoding,mode,frameBytes,rate,bitrate,maxSpeed)
        val usb=if(preferAndroidUsb&&AndroidUsbBufferRules.eligibleRate(rate)&&encoding==C.ENCODING_PCM_FLOAT&&frameBytes==8&&mode==DefaultAudioSink.OUTPUT_MODE_PCM&&maxSpeed<=1.0) {
            runCatching {
                val devices=appContext?.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.filter{it.type==AudioDeviceInfo.TYPE_USB_DEVICE||it.type==AudioDeviceInfo.TYPE_USB_HEADSET}.orEmpty()
                devices.singleOrNull()?.takeIf{rate in it.sampleRates}?.takeIf{
                    AudioTrack.isDirectPlaybackSupported(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build(),android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
                }
            }.getOrNull()
        }else null
        val chosen=AndroidUsbBufferRules.bytes(minimum,normal,frameBytes,preferAndroidUsb,usb!=null)
        plannedUsb=usb?.takeIf{chosen!=normal}
        chosen
    }
    data class Startup(val sampleRate: Int, val capacityFrames: Int, val originalFrames: Int?,
        val requestedFrames: Int?, val actualFrames: Int?, val status: String) {
        fun json() = JSONObject().put("sampleRate", sampleRate).put("capacityFrames", capacityFrames)
            .put("originalThresholdFrames", originalFrames ?: JSONObject.NULL)
            .put("requestedThresholdFrames", requestedFrames ?: JSONObject.NULL)
            .put("actualThresholdFrames", actualFrames ?: JSONObject.NULL).put("status", status)
    }
    @Volatile var startup: Startup? = null
        private set
    data class PcmOutput(val requestedSampleRateHz:Int,val requestedEncoding:Int,val channelCount:Int,
        val audioTrackSampleRateHz:Int,val audioTrackEncoding:Int,val strategy:String,val preferredDevice:String?,val requestedBufferBytes:Int) {
        fun json()=JSONObject().put("requestedSampleRateHz",requestedSampleRateHz).put("requestedEncoding",requestedEncoding)
            .put("channelCount",channelCount).put("audioTrackSampleRateHz",audioTrackSampleRateHz).put("audioTrackEncoding",audioTrackEncoding)
            .put("strategy",strategy).put("preferredDevice",preferredDevice?:JSONObject.NULL).put("requestedBufferBytes",requestedBufferBytes)
            .put("limits","AudioTrack request and client format; not a measured mixer, HAL or physical DAC format.")
    }
    @Volatile var pcmOutput:PcmOutput?=null
        private set

    fun outputJson():JSONObject?=pcmOutput?.json()?.put("routedDevice",runCatching{
        lastTrack.get()?.routedDevice?.let{JSONObject().put("type",it.type).put("product",it.productName.toString()).put("id",it.id)}
    }.getOrNull()?:JSONObject.NULL)

    override fun getAudioTrack(config: AudioSink.AudioTrackConfig, attributes: AudioAttributes,
        sessionId: Int, context: Context?): AudioTrack {
        val usb=plannedUsb?.takeIf{preferAndroidUsb}
        val track=if(usb==null)DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,sessionId,context)else{
            val created=AudioTrack.Builder().setAudioAttributes(attributes.audioAttributesV21.audioAttributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(config.sampleRate).setChannelMask(config.channelConfig).setEncoding(config.encoding).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(config.bufferSize).setSessionId(sessionId)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE).build()
            if(!created.setPreferredDevice(usb)){created.release();throw IllegalArgumentException("USB route unavailable. Select Normal output and retry.")}
            created
        }
        lastTrack=WeakReference(track)
        pcmOutput=PcmOutput(config.sampleRate,config.encoding,track.channelCount,track.sampleRate,track.audioFormat,
            if(usb==null)"Normal buffering" else "Preferred Android USB; minimum buffer request",usb?.productName?.toString(),config.bufferSize)
        val pcm = config.encoding in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_8BIT)
        if (Build.VERSION.SDK_INT < 31 || !pcm || track.isOffloadedPlayback) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, null, null, null,
                if (Build.VERSION.SDK_INT < 31) "Platform startup threshold (Android 11)" else "Encoded/offloaded output unchanged")
            return track
        }
        val original = track.startThresholdInFrames
        // Never raise a platform's already smaller threshold. Keep all default allocation,
        // startup behavior independent of Android route selection; no raw USB ownership.
        val requested = minOf(original, (config.sampleRate / 10).coerceAtLeast(1))
        try {
            val actual = track.setStartThresholdInFrames(requested)
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, actual,
                if (actual <= requested) "Local PCM startup threshold applied" else "Platform clamped startup threshold")
        } catch (_: IllegalArgumentException) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, null,
                "Platform rejected startup threshold; default retained")
        } catch (_: IllegalStateException) {
            startup = Startup(config.sampleRate, track.bufferCapacityInFrames, original, requested, null,
                "Platform rejected startup threshold; default retained")
        }
        return track
    }
}
