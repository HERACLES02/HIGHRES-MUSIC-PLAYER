@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** EQ runs before Media3's float sink, keeping its trimming, mapping and clocking.
 * All methods except published processor controls run on the playback thread. */
internal class PrecisionAudioSink(sink:AudioSink,private val processor:MusicAudioProcessor):ForwardingAudioSink(sink) {
    private data class Configuration(val format:Format,val bufferSize:Int,val channels:IntArray?)
    private var nextConfiguration:Configuration?=null
    private var configured=false
    private var input:ByteBuffer?=null
    private var pending:ByteBuffer?=null
    private var pendingTimeUs=0L
    private var pendingAccessUnits=0
    private var discontinuity=false

    init { processor.outputAsFloat=true }

    override fun getFormatSupport(format:Format):Int = if(format.sampleMimeType==MimeTypes.AUDIO_RAW&&format.pcmEncoding in PCM_ENCODINGS)
        AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY else AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun supportsFormat(format:Format)=getFormatSupport(format)!=AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatOffloadSupport(format:Format):AudioOffloadSupport=AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(inputFormat:Format,specifiedBufferSize:Int,outputChannels:IntArray?) {
        if(!supportsFormat(inputFormat))throw AudioSink.ConfigurationException("Normal precision output requires decoded PCM",inputFormat)
        // A prior buffer can still be awaiting a partial write. Do not reconfigure
        // either stage until the renderer has finished submitting that old buffer.
        nextConfiguration=Configuration(inputFormat,specifiedBufferSize,outputChannels?.clone())
    }

    override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
        pending?.let {
            check(input===buffer){"Retry the same PCM buffer until accepted"}
            if(!writePending())return false
            input=null
            return true
        }
        if(!buffer.hasRemaining())return true
        nextConfiguration?.let { configuration ->
            val f=configuration.format
            try {
                processor.configure(AudioProcessor.AudioFormat(f.sampleRate,f.channelCount,f.pcmEncoding))
            }catch(e:AudioProcessor.UnhandledAudioFormatException){throw AudioSink.ConfigurationException(e,f)}
            processor.flush()
            // Encoder delay/padding and channel mapping remain in Media3, where
            // they operate on float frames without changing the sample rate.
            super.configure(f.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build(),configuration.bufferSize,configuration.channels)
            nextConfiguration=null;configured=true;discontinuity=false
        }
        check(configured){"Configure PCM before submitting a buffer"}
        if(discontinuity){processor.flush();discontinuity=false}
        processor.queueInput(buffer)
        input=buffer
        pending=processor.output
        pendingTimeUs=presentationTimeUs;pendingAccessUnits=encodedAccessUnitCount
        if(!writePending())return false
        input=null
        return true
    }

    private fun writePending():Boolean {
        val data=pending?:return true
        if(!super.handleBuffer(data,pendingTimeUs,pendingAccessUnits))return false
        check(!data.hasRemaining()){"Sink accepted incomplete PCM"}
        pending=null
        return true
    }

    override fun playToEndOfStream(){if(writePending())super.playToEndOfStream()}
    override fun hasPendingData()=pending!=null||super.hasPendingData()
    override fun isEnded()=pending==null&&super.isEnded()
    override fun handleDiscontinuity(){discontinuity=true;super.handleDiscontinuity()}
    override fun flush(){input=null;pending=null;discontinuity=false;processor.flush();super.flush()}
    override fun reset(){input=null;pending=null;nextConfiguration=null;configured=false;discontinuity=false;processor.reset();super.reset()}
    override fun release(){input=null;pending=null;nextConfiguration=null;processor.reset();super.release()}

    companion object {
        val PCM_ENCODINGS=setOf(C.ENCODING_PCM_8BIT,C.ENCODING_PCM_16BIT,C.ENCODING_PCM_16BIT_BIG_ENDIAN,
            C.ENCODING_PCM_24BIT,C.ENCODING_PCM_24BIT_BIG_ENDIAN,C.ENCODING_PCM_32BIT,C.ENCODING_PCM_32BIT_BIG_ENDIAN,C.ENCODING_PCM_FLOAT)
    }
}
