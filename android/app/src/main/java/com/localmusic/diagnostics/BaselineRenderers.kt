@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.localmusic.diagnostics

import android.content.Context
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import org.json.JSONArray
import org.json.JSONObject

/** Observe the AudioTrack Media3 creates without changing its normal configuration. */
class BaselineRenderers(context: Context, private val observe: (JSONObject) -> Unit) : DefaultRenderersFactory(context) {
    private val handler = Handler(Looper.getMainLooper())
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackProvider(object : DefaultAudioSink.AudioTrackProvider {
                override fun getAudioTrack(config: AudioSink.AudioTrackConfig, attributes: AudioAttributes, session: Int, context: Context?): AudioTrack {
                    val track = DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config, attributes, session, context)
                    track.addOnRoutingChangedListener({ routing ->
                        val device = routing.routedDevice
                        observe(JSONObject().put("routedDevice", device?.let {
                            JSONObject().put("type", it.type).put("product", it.productName.toString())
                                .put("supportedRatesHz", JSONArray(it.sampleRates.toList()))
                                .put("supportedEncodingConstants", JSONArray(it.encodings.toList()))
                        } ?: JSONObject.NULL).put("meaning", "Android AudioTrack routed device; supported arrays are capabilities. Physical USB stream rate/bit depth remain unknown."))
                    }, handler)
                    return track
                }
            }).build()
}
