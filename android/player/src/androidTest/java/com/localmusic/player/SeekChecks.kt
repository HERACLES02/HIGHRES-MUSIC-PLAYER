@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.datasource.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/** Real extractor, decoder and sink; generated fixtures only. No library writes. */
object SeekChecks {
    fun run(test: Instrumentation, uri: String, variant: String? = null): String {
        val context=test.targetContext
        val bytes=AtomicLong(); val opens=AtomicLong(); val firstAudio=AtomicLong()
        val firstPcm=AtomicLong();val flushed=AtomicLong();val sinkInit=AtomicLong();val ready=AtomicLong()
        var sinkBufferBytes=0
        lateinit var measuredSink:DefaultAudioSink
        val factory=DataSource.Factory {
            val upstream:DataSource=DefaultDataSource.Factory(context).createDataSource()
            object:DataSource by upstream {
                override fun open(spec:DataSpec):Long { opens.incrementAndGet();return upstream.open(spec) }
                override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                    val count=upstream.read(buffer,offset,length)
                    if(count>0)bytes.addAndGet(count.toLong())
                    return count
                }
            }
        }
        lateinit var player:ExoPlayer
        fun main(block:()->Unit) {
            var failure:Throwable?=null
            test.runOnMainSync { try { block() } catch(cause:Throwable) { failure=cause } }
            failure?.let { throw it }
        }
        fun waitFor(label:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+30000
            while(true) {
                var ok=false
                main { player.playerError?.let { throw it };ok=condition() }
                if(ok)return
                check(SystemClock.elapsedRealtime()<deadline){"Timed out: $label"}
                SystemClock.sleep(10)
            }
        }
        val output=StringBuilder()
        main {
            val load=DefaultLoadControl.Builder().setBufferDurationsMs(50000,50000,100,2000).build()
            val renderers=object:DefaultRenderersFactory(context) {
                override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioTrackPlaybackParams:Boolean):AudioSink {
                    val builder=DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams).setAudioProcessors(arrayOf(MusicAudioProcessor()))
                    if(variant=="small")builder.setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(50000).setPcmBufferMultiplicationFactor(1).build())
                    if(variant=="threshold")builder.setAudioTrackProvider(object:DefaultAudioSink.AudioTrackProvider by DefaultAudioSink.AudioTrackProvider.DEFAULT {
                        override fun getAudioTrack(config:AudioSink.AudioTrackConfig,attributes:AudioAttributes,sessionId:Int,context:Context?):android.media.AudioTrack {
                            return DefaultAudioSink.AudioTrackProvider.DEFAULT.getAudioTrack(config,attributes,sessionId,context).apply {
                                if(android.os.Build.VERSION.SDK_INT>=31)setStartThresholdInFrames(config.sampleRate/10)
                            }
                        }
                    })
                    val sink=if(variant=="production") {
                        val method=MusicRenderers::class.java.getDeclaredMethod("buildAudioSink",Context::class.java,
                            Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply { isAccessible=true }
                        method.invoke(MusicRenderers(context,MusicAudioProcessor()),context,enableFloatOutput,enableAudioTrackPlaybackParams) as DefaultAudioSink
                    } else builder.build()
                    measuredSink=sink
                    return object:ForwardingAudioSink(sink) {
                        override fun flush(){flushed.set(SystemClock.elapsedRealtime());firstPcm.set(0);super.flush()}
                        override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
                            firstPcm.compareAndSet(0,SystemClock.elapsedRealtime())
                            return super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
                        }
                    }
                }
            }
            player=ExoPlayer.Builder(context,renderers)
                .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(factory))
                .setLoadControl(load).build()
            player.addAnalyticsListener(object:AnalyticsListener {
                override fun onAudioPositionAdvancing(eventTime:AnalyticsListener.EventTime,playoutStartSystemTimeMs:Long) {
                    firstAudio.compareAndSet(0,SystemClock.elapsedRealtime())
                }
                override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
                    sinkInit.set(SystemClock.elapsedRealtime())
                    sinkBufferBytes=audioTrackConfig.bufferSize
                }
                override fun onPlaybackStateChanged(eventTime:AnalyticsListener.EventTime,playbackState:Int) {
                    if(playbackState==Player.STATE_READY)ready.set(SystemClock.elapsedRealtime())
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)));player.prepare();player.play()
        }
        try {
            waitFor("initial playback"){player.isPlaying&&player.currentPosition>300}
            if(variant=="production"&&android.os.Build.VERSION.SDK_INT>=31)main {
                // Inspect the real output, not a duplicate calculation of the policy. This field
                // is in our pinned Media3 dependency, not a hidden Android platform API.
                val trackField=DefaultAudioSink::class.java.getDeclaredField("audioTrack").apply { isAccessible=true }
                val track=trackField.get(measuredSink) as android.media.AudioTrack
                check(track.startThresholdInFrames<=track.sampleRate/10) {
                    "Output waits for ${track.startThresholdInFrames} frames before starting; expected at most 100ms (${track.sampleRate/10}) without shrinking capacity ${track.bufferCapacityInFrames}."
                }
                output.append("production capacityFrames=${track.bufferCapacityInFrames} startFrames=${track.startThresholdInFrames}\n")
            }
            for(target in listOf(25000L,83000L,147000L,17000L)) {
                // Start from a stopped loader so byte counts belong to this seek.
                waitFor("buffer settled"){!player.isLoading}
                val initialBytes=bytes.get();val initialOpens=opens.get()
                firstAudio.set(0);firstPcm.set(0);sinkInit.set(0);ready.set(0);flushed.set(0)
                val start=SystemClock.elapsedRealtime()
                main { player.seekTo(target) }
                waitFor("seek to $target"){firstAudio.get()!=0L&&player.isPlaying&&player.currentPosition>=target}
                output.append("target=$target audioResumeMs=${firstAudio.get()-start} flushMs=${flushed.get()-start} pcmMs=${firstPcm.get()-start} initMs=${sinkInit.get()-start} readyMs=${ready.get()-start} sinkBufferBytes=$sinkBufferBytes bytes=${bytes.get()-initialBytes} opens=${opens.get()-initialOpens}\n")
            }
            main { player.pause();player.seekTo(95000) }
            waitFor("paused seek ready"){player.playbackState==Player.STATE_READY}
            main { check(!player.playWhenReady&&!player.isPlaying){"Paused seek started playback"} }
            return output.toString()
        } finally { main { player.release() } }
    }
}
