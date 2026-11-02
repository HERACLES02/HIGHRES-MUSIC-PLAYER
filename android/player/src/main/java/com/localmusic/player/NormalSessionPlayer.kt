@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.os.Looper
import androidx.media3.common.*
import com.localmusic.core.RepeatMode
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Session view only; the authority owns the queue, timer and sole ExoPlayer. */
class NormalSessionPlayer(private val owner: PlaybackAuthority) : SimpleBasePlayer(Looper.getMainLooper()) {
    private var closed = false
    override fun getState(): State {
        val engine = owner.engine
        val track = owner.current
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES)
        if (owner.ready) commands.addAll(Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE)
        if (owner.ready && track != null) commands.addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT)
        val state = State.Builder().setAvailableCommands(commands.build())
            .setAudioAttributes(engine.audioAttributes).setPlayWhenReady(engine.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (track == null) Player.STATE_IDLE else engine.playbackState)
            .setPlayerError(engine.playerError)
            .setRepeatMode(when (owner.queue.repeat) { RepeatMode.ALL -> Player.REPEAT_MODE_ALL; RepeatMode.ONE -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF })
            .setShuffleModeEnabled(owner.queue.shuffle)
        if (track != null) {
            val duration = engine.duration.takeIf { it > 0 } ?: track.audio.durationMs.takeIf { it > 0 }
            state.setPlaylist(listOf(MediaItemData.Builder(owner.queue.current!!.id.value.toString())
                .setMediaItem(owner.mediaItem(track)).setDurationUs(duration?.times(1000) ?: C.TIME_UNSET)
                .setIsSeekable(true).build())).setCurrentMediaItemIndex(0)
                .setContentPositionMs(engine.currentPosition.coerceAtLeast(0))
        }
        return state.build()
    }
    fun refresh() { if (!closed) invalidateState() }
    private fun done(action: () -> Unit): ListenableFuture<*> { action(); return Futures.immediateVoidFuture() }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean) = done { if (playWhenReady) owner.play() else owner.pause() }
    override fun handleStop() = done { owner.stop() }
    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int) = done {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT -> owner.advance()
            Player.COMMAND_SEEK_TO_PREVIOUS -> owner.previous()
            else -> owner.seek(if (positionMs == C.TIME_UNSET) 0 else positionMs)
        }
    }
    override fun handleSetRepeatMode(repeatMode: Int) = done { owner.repeat(repeatMode) }
    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean) = done { owner.shuffle(shuffleModeEnabled) }
    override fun handleRelease() = done { closed = true }
    companion object {
        fun allowedCommands() = Player.Commands.Builder().addAll(Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES,
            Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION, Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SET_REPEAT_MODE, Player.COMMAND_SET_SHUFFLE_MODE).build()
    }
}
