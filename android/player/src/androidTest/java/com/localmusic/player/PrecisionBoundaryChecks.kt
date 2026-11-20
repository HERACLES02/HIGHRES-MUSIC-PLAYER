@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.localmusic.core.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Observe the real precision adapter at its downstream boundary. The receiver
 * controls partial writes; it does not simulate EQ, conversion or buffer state. */
internal object PrecisionBoundaryChecks {
    private class Receiver(context:Context):ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
        var format:Format?=null
        var maxSamples=Int.MAX_VALUE
        var ended=false
        val samples=mutableListOf<Float>()
        val times=mutableListOf<Long>()
        override fun configure(inputFormat:Format,specifiedBufferSize:Int,outputChannels:IntArray?){format=inputFormat;ended=false}
        override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
            var count=0
            while(buffer.hasRemaining()&&count++<maxSamples){samples+=buffer.float;times+=presentationTimeUs}
            return !buffer.hasRemaining()
        }
        override fun playToEndOfStream(){ended=true}
        override fun isEnded()=ended
        override fun hasPendingData()=false
        override fun flush(){ended=false}
    }
    private fun format(encoding:Int,rate:Int=96000)=Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(rate).setChannelCount(2).setPcmEncoding(encoding).build()
    private fun bytes(vararg values:Int)=ByteBuffer.allocateDirect(values.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach{put(it.toByte())};flip()
    }
    private fun floats(values:List<Float>)=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.LITTLE_ENDIAN).apply {
        values.forEach{putFloat(it)};flip()
    }
    fun run(context:Context):Int {
        var count=0
        fun verify(ok:Boolean,label:String){count++;check(ok){"Precision boundary: $label"}}
        val receiver=Receiver(context)
        val processor=MusicAudioProcessor()
        val sink=PrecisionAudioSink(receiver,processor)
        try {
            fun submit(encoding:Int,input:ByteBuffer,expected:List<Float>) {
                sink.flush();receiver.samples.clear()
                sink.configure(format(encoding),0,null)
                check(sink.handleBuffer(input,12345,1))
                verify(receiver.samples==expected,"exact PCM conversion: $encoding")
                verify(receiver.format!!.pcmEncoding==C.ENCODING_PCM_FLOAT&&receiver.format!!.sampleRate==96000,
                    "float output preserves source rate: $encoding")
            }
            submit(C.ENCODING_PCM_24BIT,bytes(1,0,0,255,255,255),listOf(1f/8388608,-1f/8388608))
            submit(C.ENCODING_PCM_24BIT_BIG_ENDIAN,bytes(0,0,1,255,255,255),listOf(1f/8388608,-1f/8388608))
            submit(C.ENCODING_PCM_16BIT,bytes(1,0,255,255),listOf(1f/32768,-1f/32768))
            submit(C.ENCODING_PCM_16BIT_BIG_ENDIAN,bytes(0,1,255,255),listOf(1f/32768,-1f/32768))
            submit(C.ENCODING_PCM_32BIT,bytes(1,0,0,0,255,255,255,255),listOf(1f/2147483648.0f,-1f/2147483648.0f))
            submit(C.ENCODING_PCM_32BIT_BIG_ENDIAN,bytes(0,0,0,1,255,255,255,255),listOf(1f/2147483648.0f,-1f/2147483648.0f))
            submit(C.ENCODING_PCM_8BIT,bytes(129,127),listOf(1f/128,-1f/128))
            submit(C.ENCODING_PCM_FLOAT,floats(listOf(.000001f,-.000001f)),listOf(.000001f,-.000001f))

            sink.flush();receiver.samples.clear();receiver.times.clear();receiver.maxSamples=1
            sink.configure(format(C.ENCODING_PCM_24BIT),0,null)
            val input=bytes(1,0,0,2,0,0,3,0,0,4,0,0)
            verify(!sink.handleBuffer(input,777,1),"partial downstream write requires retry")
            // A new format cannot change the output rate of the pending old buffer.
            sink.configure(format(C.ENCODING_PCM_16BIT,44100),0,null)
            verify(receiver.format!!.sampleRate==96000,"reconfigure is deferred while old buffer is pending")
            repeat(3){sink.handleBuffer(input,999,1)}
            verify(receiver.samples==listOf(1f,2f,3f,4f).map{it/8388608},"partial retries preserve low bits without duplicates")
            verify(receiver.times.all{it==777L},"partial retries retain original timestamp")
            receiver.maxSamples=Int.MAX_VALUE
            sink.handleBuffer(bytes(1,0,2,0),1000,1)
            verify(receiver.format!!.sampleRate==44100,"new format applies after old buffer is accepted")

            sink.flush();receiver.samples.clear();receiver.maxSamples=1
            sink.configure(format(C.ENCODING_PCM_FLOAT),0,null)
            sink.handleBuffer(floats(listOf(.01f,.02f,.03f,.04f)),2000,1)
            verify(sink.hasPendingData()&&!sink.isEnded(),"pending adapter PCM is visible")
            sink.playToEndOfStream()
            verify(!receiver.ended,"EOS waits for every pending sample")
            repeat(3){sink.playToEndOfStream()}
            verify(receiver.samples==listOf(.01f,.02f,.03f,.04f)&&sink.isEnded(),"EOS drains exactly once")

            sink.flush();receiver.samples.clear()
            sink.handleBuffer(floats(listOf(.1f,.2f,.3f,.4f)),3000,1)
            sink.flush();receiver.samples.clear();receiver.maxSamples=Int.MAX_VALUE
            sink.handleBuffer(floats(listOf(.05f,-.05f)),4000,1)
            verify(receiver.samples==listOf(.05f,-.05f),"seek flush discards pending old samples")
            val before=receiver.samples.size
            verify(sink.handleBuffer(ByteBuffer.allocateDirect(0),5000,1)&&receiver.samples.size==before,"empty input adds no samples")

            sink.configure(format(C.ENCODING_PCM_FLOAT),0,null)
            processor.configuration=DspConfiguration(AudioSettings(eqEnabled=true,preset=EqPreset(filters=listOf(EqFilter(frequencyHz=1000.0,gainDb=6.0)))))
            val wave=List(19200){i->(.001*sin(2*PI*1000*(i/2)/96000)).toFloat()}
            receiver.samples.clear();sink.handleBuffer(floats(wave),6000,1)
            val wet=receiver.samples.drop(9600).sumOf{it.toDouble()*it}
            val dry=wave.drop(9600).sumOf{it.toDouble()*it}
            verify(abs(10*log10(wet/dry)-6)<.05,"EQ remains active on float PCM")
            processor.configuration=DspConfiguration()
            receiver.samples.clear();sink.handleBuffer(floats(wave),106000,1)
            verify(receiver.samples==wave,"EQ toggle restores unchanged float samples")
            processor.configuration=DspConfiguration(AudioSettings(replayGain=ReplayGainMode.TRACK),ReplayGainInfo(trackGainDb=-6.0))
            receiver.samples.clear();sink.handleBuffer(floats(wave),206000,1)
            val leveled=receiver.samples.drop(9600).sumOf{it.toDouble()*it}
            verify(abs(10*log10(leveled/dry)+6)<.1,"ReplayGain remains active on float PCM")
            processor.configuration=DspConfiguration(AudioSettings(eqEnabled=true,preset=EqPreset(filters=listOf(EqFilter(frequencyHz=1000.0,gainDb=6.0)))))
            sink.handleBuffer(floats(wave),306000,1)
            sink.flush();receiver.samples.clear()
            sink.handleBuffer(floats(listOf(0f,0f)),406000,1)
            verify(receiver.samples==listOf(0f,0f),"seek resets filter history")
            sink.handleBuffer(floats(wave),506000,1)
            sink.handleDiscontinuity();receiver.samples.clear()
            sink.handleBuffer(floats(listOf(0f,0f)),606000,1)
            verify(receiver.samples==listOf(0f,0f),"discontinuity resets filter history")
            return count
        } finally { sink.reset();sink.release() }
    }
}
