@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.localmusic.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Reuses output/filter buffers. Controls are immutable and published from the service thread. */
class MusicAudioProcessor:BaseAudioProcessor(){
    @Volatile var configuration=DspConfiguration()
    private var applied:DspConfiguration?=null
    private var format:AudioProcessor.AudioFormat?=null
    private val kernel=DspKernel()
    override fun onConfigure(input:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        if(input.encoding !in setOf(C.ENCODING_PCM_16BIT,C.ENCODING_PCM_24BIT,C.ENCODING_PCM_32BIT,C.ENCODING_PCM_FLOAT))throw AudioProcessor.UnhandledAudioFormatException(input)
        return input
    }
    override fun onFlush(){kernel.reset();applied=null;format=null}
    override fun queueInput(input:ByteBuffer){
        // Media3 can pass its shared EMPTY_BUFFER while draining the pipeline.
        // Do not copy it into itself or replace PCM still waiting for the sink.
        if(!input.hasRemaining())return
        val current=configuration
        if(current!=applied||format!=inputAudioFormat){kernel.configure(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,current);kernel.reset();applied=current;format=inputAudioFormat}
        val output=replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        if(!current.settings.eqEnabled&&current.replayGain.gainDb(current.settings.replayGain)==0.0){output.put(input);output.flip();return}
        input.order(ByteOrder.LITTLE_ENDIAN)
        val encoding=inputAudioFormat.encoding;val bytes=when(encoding){C.ENCODING_PCM_16BIT->2;C.ENCODING_PCM_24BIT->3;else->4}
        val channels=inputAudioFormat.channelCount
        require(input.remaining()%(bytes*channels)==0){"Decoder returned partial PCM frames."}
        var channel=0
        while(input.hasRemaining()){
            val sample=when(encoding){
                C.ENCODING_PCM_16BIT->input.short/32768.0
                C.ENCODING_PCM_24BIT->{val bits=(input.get().toInt() and 255)or((input.get().toInt()and 255)shl 8)or((input.get().toInt()and 255)shl 16);((bits shl 8)shr 8)/8388608.0}
                C.ENCODING_PCM_32BIT->input.int/2147483648.0
                else->input.float.toDouble()
            }
            val value=kernel.sample(sample,channel)
            when(encoding){
                C.ENCODING_PCM_16BIT->output.putShort((value*32768).roundToInt().coerceIn(-32768,32767).toShort())
                C.ENCODING_PCM_24BIT->{val bits=(value*8388608).roundToInt().coerceIn(-8388608,8388607);output.put(bits.toByte());output.put((bits shr 8).toByte());output.put((bits shr 16).toByte())}
                C.ENCODING_PCM_32BIT->output.putInt((value*2147483648.0).toLong().coerceIn(Int.MIN_VALUE.toLong(),Int.MAX_VALUE.toLong()).toInt())
                else->output.putFloat(value.toFloat())
            }
            channel++;if(channel==channels)channel=0
        }
        output.flip()
    }
}

class MusicRenderers(context:Context,private val processor:MusicAudioProcessor):DefaultRenderersFactory(context){
    override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink =
        DefaultAudioSink.Builder(context).setEnableFloatOutput(false).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor)).build()
}
