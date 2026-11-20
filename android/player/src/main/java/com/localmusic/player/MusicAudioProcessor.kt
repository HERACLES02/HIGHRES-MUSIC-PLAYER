@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.localmusic.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Reuses output/filter buffers. Controls are immutable and published from the service thread. */
class MusicAudioProcessor:BaseAudioProcessor(){
    internal var outputAsFloat=false
    data class Pcm(val sampleRateHz:Int,val channelCount:Int,val inputEncoding:Int,val outputEncoding:Int)
    @Volatile var lastPcm:Pcm?=null
        private set
    @Volatile var configuration=DspConfiguration()
    private var applied:DspConfiguration?=null
    private var format:AudioProcessor.AudioFormat?=null
    private val kernel=DspKernel()
    override fun onConfigure(input:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat {
        val supported=if(outputAsFloat)PrecisionAudioSink.PCM_ENCODINGS else setOf(C.ENCODING_PCM_16BIT,C.ENCODING_PCM_24BIT,C.ENCODING_PCM_32BIT,C.ENCODING_PCM_FLOAT)
        if(input.encoding !in supported)throw AudioProcessor.UnhandledAudioFormatException(input)
        return if(outputAsFloat)AudioProcessor.AudioFormat(input.sampleRate,input.channelCount,C.ENCODING_PCM_FLOAT)else input
    }
    override fun onFlush(){kernel.reset();applied=null;format=null;lastPcm=null}
    override fun queueInput(input:ByteBuffer){
        // Media3 can pass its shared EMPTY_BUFFER while draining the pipeline.
        // Do not copy it into itself or replace PCM still waiting for the sink.
        if(!input.hasRemaining())return
        val current=configuration
        if(current!=applied||format!=inputAudioFormat){kernel.configure(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,current);kernel.reset();applied=current;format=inputAudioFormat
            lastPcm=Pcm(inputAudioFormat.sampleRate,inputAudioFormat.channelCount,inputAudioFormat.encoding,if(outputAsFloat)C.ENCODING_PCM_FLOAT else inputAudioFormat.encoding)}
        val encoding=inputAudioFormat.encoding;val bytes=when(encoding){
            C.ENCODING_PCM_8BIT->1
            C.ENCODING_PCM_16BIT,C.ENCODING_PCM_16BIT_BIG_ENDIAN->2
            C.ENCODING_PCM_24BIT,C.ENCODING_PCM_24BIT_BIG_ENDIAN->3
            else->4}
        val channels=inputAudioFormat.channelCount
        require(input.remaining()%(bytes*channels)==0){"Decoder returned partial PCM frames."}
        val output=replaceOutputBuffer(input.remaining()/bytes*(if(outputAsFloat)4 else bytes)).order(ByteOrder.LITTLE_ENDIAN)
        val bypass=!current.settings.eqEnabled&&current.replayGain.gainDb(current.settings.replayGain)==0.0
        if(!outputAsFloat&&bypass){output.put(input);output.flip();return}
        val bigEndian=encoding==C.ENCODING_PCM_16BIT_BIG_ENDIAN||encoding==C.ENCODING_PCM_24BIT_BIG_ENDIAN||encoding==C.ENCODING_PCM_32BIT_BIG_ENDIAN
        input.order(if(bigEndian)ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        var channel=0
        while(input.hasRemaining()){
            val sample=when(encoding){
                C.ENCODING_PCM_8BIT->((input.get().toInt()and 255)-128)/128.0
                C.ENCODING_PCM_16BIT,C.ENCODING_PCM_16BIT_BIG_ENDIAN->input.short/32768.0
                C.ENCODING_PCM_24BIT,C.ENCODING_PCM_24BIT_BIG_ENDIAN->{
                    val a=input.get().toInt()and 255;val b=input.get().toInt()and 255;val c=input.get().toInt()and 255
                    val bits=if(bigEndian)(a shl 16)or(b shl 8)or c else a or(b shl 8)or(c shl 16)
                    ((bits shl 8)shr 8)/8388608.0}
                C.ENCODING_PCM_32BIT,C.ENCODING_PCM_32BIT_BIG_ENDIAN->input.int/2147483648.0
                else->input.float.toDouble()
            }
            val value=if(bypass){if(sample.isFinite())sample else 0.0}else kernel.sample(sample,channel)
            when(if(outputAsFloat)C.ENCODING_PCM_FLOAT else encoding){
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

class MusicRenderers(context:Context,private val processor:MusicAudioProcessor,
    private val output:LocalAudioTrackProvider=LocalAudioTrackProvider()):DefaultRenderersFactory(context){
    public override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink {
        val sink=DefaultAudioSink.Builder(context).setEnableFloatOutput(true).setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioTrackProvider(output).setAudioTrackBufferSizeProvider(output.bufferSizeProvider).build()
        return PrecisionAudioSink(sink,processor)
    }
}

/** Reconfigure output without changing media items, occurrence identity or pause intent. */
internal fun reprepareForAudioRoute(player:ExoPlayer):Boolean {
    if(player.mediaItemCount==0||player.playbackState !in listOf(Player.STATE_READY,Player.STATE_BUFFERING))return false
    val index=player.currentMediaItemIndex
    if(index !in 0 until player.mediaItemCount)return false
    val position=player.currentPosition.coerceAtLeast(0)
    val playing=player.playWhenReady
    player.stop();player.seekTo(index,position);player.prepare();player.playWhenReady=playing
    return true
}
