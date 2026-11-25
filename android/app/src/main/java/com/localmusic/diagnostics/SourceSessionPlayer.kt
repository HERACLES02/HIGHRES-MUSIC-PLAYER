@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.diagnostics

import android.os.Looper
import androidx.media3.common.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import kotlinx.coroutines.*

/** Main-thread Media3 controls for the isolated streaming owner. Position is
 * completed USB frames, not measured DAC render time. */
class SourceSessionPlayer(private val owner: SourceSessionService): SimpleBasePlayer(Looper.getMainLooper()) {
    private var snapshot=SourceSessionView()
    private var item: MediaItem?=null
    private var rate=0
    private var duration=0L
    private var released=false
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    init {
        check(BuildConfig.USB_SELF_TEST)
        scope.launch { owner.state.collect { value ->
            snapshot=value
            if(item!=null && !released)invalidateState()
        } }
    }
    fun observe(value: SourceSessionView,mediaItem: MediaItem,sampleRate: Int,durationMs: Long) {
        check(Looper.myLooper()==applicationLooper)
        require(sampleRate in listOf(44100,48000,88200,96000) && durationMs in 0..Long.MAX_VALUE/1000)
        if(released)return
        snapshot=value;item=mediaItem;rate=sampleRate;duration=durationMs
        invalidateState()
    }
    private fun native()=JSONObject(snapshot.native)
    private fun accepting()=!released && snapshot.ready && !snapshot.closed && snapshot.error==null
    override fun getState(): State {
        val data=native();val phase=data.optString("phase")
        val commands=Player.Commands.Builder().addAll(Player.COMMAND_RELEASE,Player.COMMAND_GET_CURRENT_MEDIA_ITEM,Player.COMMAND_GET_TIMELINE)
        if(accepting())commands.add(Player.COMMAND_STOP)
        if(accepting() && phase in listOf("running","pausing","paused"))commands.add(Player.COMMAND_PLAY_PAUSE)
        if(accepting() && data.optLong("generation")>0 && phase in listOf("running","end"))commands.add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        val entries=item?.let { listOf(MediaItemData.Builder(it.mediaId).setMediaItem(it).setDurationUs(duration.coerceAtMost(Long.MAX_VALUE/1000)*1000).setIsSeekable(true).build()) } ?: emptyList()
        val limit=duration/1000*rate+duration%1000*rate/1000
        val origin=data.optLong("firstFrame").coerceIn(0,limit)
        val frames=origin+data.optLong("completedFrames").coerceIn(0,limit-origin)
        val position=if(rate>0)(frames/rate*1000+(frames%rate)*1000/rate).coerceAtMost(duration) else 0
        return State.Builder().setAvailableCommands(commands.build()).setPlaylist(entries)
            .setContentPositionMs(position).setPlayWhenReady(accepting() && phase=="running",Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when { !accepting() -> Player.STATE_IDLE;phase=="end" -> Player.STATE_ENDED;phase in listOf("running","paused") -> Player.STATE_READY;else -> Player.STATE_BUFFERING }).build()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        return if(accepting() && native().optString("phase") in listOf("running","pausing","paused") && owner.setPaused(native().optLong("generation"),!playWhenReady))Futures.immediateVoidFuture()
            else Futures.immediateFailedFuture<Void>(IllegalStateException("Source is not accepting Play/Pause"))
    }
    override fun handleSeek(mediaItemIndex: Int,positionMs: Long,seekCommand: Int): ListenableFuture<*> {
        if(!accepting() || mediaItemIndex!=0 || rate==0 || native().optString("phase") !in listOf("running","end"))
            return Futures.immediateFailedFuture<Void>(IllegalStateException("Source is not accepting seeks"))
        val position=if(positionMs==C.TIME_UNSET)0 else positionMs.coerceIn(0,duration)
        val frame=position/1000*rate+position%1000*rate/1000
        return if(owner.seek(native().optLong("generation"),frame))Futures.immediateVoidFuture()
            else Futures.immediateFailedFuture<Void>(IllegalStateException("Source command queue is full or stopped"))
    }
    override fun handleStop(): ListenableFuture<*> { if(accepting())owner.requestStop();return Futures.immediateVoidFuture() }
    override fun handleRelease(): ListenableFuture<*> { released=true;scope.cancel();invalidateState();return Futures.immediateVoidFuture() }
}
