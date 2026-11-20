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

/** Real extractor, decoder and sink; read-only fixtures. No library writes. */
object SeekChecks {
    fun run(test: Instrumentation, uri: String, variant: String? = null): String {
        val context=test.targetContext
        val indexes=FlacSeekIndexes(context)
        val bytes=AtomicLong(); val opens=AtomicLong(); val firstAudio=AtomicLong()
        val firstPcm=AtomicLong();val flushed=AtomicLong();val sinkInit=AtomicLong();val ready=AtomicLong()
        var sinkBufferBytes=0
        var productionOutput:LocalAudioTrackProvider?=null
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
                    val sink:AudioSink=if(variant=="production" || variant=="production-no-index") {
                        val output=LocalAudioTrackProvider(context);productionOutput=output
                        MusicRenderers(context,MusicAudioProcessor(),output).buildAudioSink(context,enableFloatOutput,enableAudioTrackPlaybackParams)
                    } else builder.build()
                    return object:ForwardingAudioSink(sink) {
                        override fun flush(){flushed.set(SystemClock.elapsedRealtime());firstPcm.set(0);super.flush()}
                        override fun handleBuffer(buffer:ByteBuffer,presentationTimeUs:Long,encodedAccessUnitCount:Int):Boolean {
                            firstPcm.compareAndSet(0,SystemClock.elapsedRealtime())
                            return super.handleBuffer(buffer,presentationTimeUs,encodedAccessUnitCount)
                        }
                    }
                }
            }
            val mediaSources=if(variant=="production") DefaultMediaSourceFactory(context,LocalFlacExtractors(indexes)) else DefaultMediaSourceFactory(context)
            player=ExoPlayer.Builder(context,renderers)
                .setMediaSourceFactory(mediaSources.setDataSourceFactory(factory))
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
            if(variant=="production")waitFor("private index") {
                val status=indexes.status()
                status.getInt("jobs")==0 || status.toString().contains("ready") || status.toString().contains("cached")
            }
            if(variant=="production")output.append("privateIndex=${indexes.status()}\n")
            if(variant=="production"&&android.os.Build.VERSION.SDK_INT>=31)main {
                // The provider records the actual Android API return, not a duplicate policy calculation.
                val startup=requireNotNull(productionOutput?.startup)
                val actual=requireNotNull(startup.actualFrames)
                check(actual<=startup.sampleRate/10) {
                    "Output waits for $actual frames before starting; expected at most 100ms (${startup.sampleRate/10}) without shrinking capacity ${startup.capacityFrames}."
                }
                output.append("production capacityFrames=${startup.capacityFrames} startFrames=$actual\n")
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
            var mediaCount=0
            main {
                mediaCount=player.mediaItemCount
                productionOutput?.preferAndroidUsb=true
                check(reprepareForAudioRoute(player)){"Playing route reprepare was skipped"}
            }
            waitFor("playing after route reprepare"){player.isPlaying}
            main {check(player.mediaItemCount==mediaCount&&player.currentPosition>=17000){"Route reprepare lost source or position"}}
            if(variant=="production")main {
                val usb=context.getSystemService(android.media.AudioManager::class.java).getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                    .any{it.type==android.media.AudioDeviceInfo.TYPE_USB_DEVICE||it.type==android.media.AudioDeviceInfo.TYPE_USB_HEADSET}
                if(!usb)check(productionOutput?.pcmOutput?.strategy=="Normal buffering"){"USB preference changed output without a USB device"}
            }
            output.append("playing route reprepare retained media items and position; no-USB fallback checked\n")
            main { player.pause();player.seekTo(95000) }
            waitFor("paused seek ready"){player.playbackState==Player.STATE_READY}
            main { check(!player.playWhenReady&&!player.isPlaying){"Paused seek started playback"} }
            main {productionOutput?.preferAndroidUsb=false;check(reprepareForAudioRoute(player)){"Paused route reprepare was skipped"}}
            waitFor("paused route reprepare ready"){player.playbackState==Player.STATE_READY}
            main {check(!player.playWhenReady&&!player.isPlaying&&player.mediaItemCount==mediaCount&&player.currentPosition==95000L){"Paused route reprepare changed pause intent/source/position"}}
            output.append("paused route reprepare retained media items, 95000 ms and pause intent\n")
            return output.toString()
        } finally { main { player.release() }; indexes.close() }
    }
}
