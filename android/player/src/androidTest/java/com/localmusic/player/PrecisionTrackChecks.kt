@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import android.media.AudioTrack
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Capture bytes actually accepted by a real muted AudioTrack after Media3's
 * trimming/conversion stages. This measures the client boundary, not a DAC. */
internal object PrecisionTrackChecks {
    private class ObservedTrack(config:AudioSink.AudioTrackConfig,sessionId:Int):AudioTrack(
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        android.media.AudioFormat.Builder().setEncoding(config.encoding).setSampleRate(config.sampleRate)
            .setChannelMask(config.channelConfig).build(),config.bufferSize,MODE_STREAM,sessionId) {
        val accepted=mutableListOf<Float>()
        override fun write(audioData:ByteBuffer,sizeInBytes:Int,writeMode:Int):Int {
            val copy=audioData.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val written=super.write(audioData,sizeInBytes,writeMode)
            if(written>0){copy.limit(copy.position()+written);while(copy.hasRemaining())accepted+=copy.float}
            return written
        }
    }
    fun run(context:Context):Int {
        var track:ObservedTrack?=null
        val provider=object:DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
            override fun getAudioTrack(config:AudioSink.AudioTrackConfig,attributes:AudioAttributes,sessionId:Int,context:Context?):AudioTrack {
                check(config.encoding==C.ENCODING_PCM_FLOAT){"Full sink reduced float precision"}
                return ObservedTrack(config,sessionId).also{track=it}
            }
        }
        val sink=PrecisionAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(true)
            .setAudioTrackProvider(provider).build(),MusicAudioProcessor())
        try {
            sink.setVolume(0f)
            val format=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(96000)
                .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_24BIT).setEncoderDelay(1).setEncoderPadding(1).build()
            sink.configure(format,0,null)
            // Media3 deliberately ramps the first 20 ms. Test precision after that
            // existing fade; do not disable it merely to make a transparency claim.
            val warmupFrames=2048
            val input=ByteBuffer.allocateDirect((warmupFrames+6)*6).order(ByteOrder.LITTLE_ENDIAN)
            repeat(warmupFrames*6){input.put(0)}
            for(frame in 1..6)for(sample in listOf(frame,-frame))input.put(sample.toByte()).put((sample shr 8).toByte()).put((sample shr 16).toByte())
            input.flip()
            var accepted=false
            repeat(200){if(!accepted){accepted=sink.handleBuffer(input,0,1);if(!accepted)Thread.sleep(5)}}
            check(accepted){"Full sink did not accept the generated PCM"}
            // Media3 drops final encoder padding on a gapless reconfiguration;
            // a final stream drain alone can legitimately output the end buffer.
            sink.configure(format.buildUpon().setEncoderDelay(0).setEncoderPadding(0).build(),0,null)
            val next=ByteBuffer.allocateDirect(6).order(ByteOrder.LITTLE_ENDIAN).apply{repeat(6){put(0)};flip()}
            accepted=false
            repeat(200){if(!accepted){accepted=sink.handleBuffer(next,(warmupFrames+6)*1000000L/96000,1);if(!accepted)Thread.sleep(5)}}
            check(accepted){"Gapless next buffer did not drain"}
            sink.playToEndOfStream()
            val expected=List((warmupFrames-1)*2){0f}+(1..5).flatMap{listOf(it/8388608f,-it/8388608f)}+listOf(0f,0f)
            check(track?.accepted==expected){"Full sink changed low bits or gapless trimming: ${track?.accepted?.takeLast(16)} (size ${track?.accepted?.size}, expected ${expected.size})"}
            check(track.sampleRate==96000){"AudioTrack client rate changed"}
            return 3
        }finally{sink.reset();sink.release()}
    }
}
