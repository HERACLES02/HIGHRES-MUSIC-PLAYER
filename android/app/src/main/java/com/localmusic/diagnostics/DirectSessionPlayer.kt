@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.localmusic.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/** Media3 view of the service-owned native experiment. Creates no audio output or decoder. */
class DirectSessionPlayer(context: Context, val fixture: String,
    private val toggle: (Boolean) -> Unit, private val stopOwner: () -> Unit, private val finite: Boolean=false) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val rate = fixture.substringAfterLast('-').substringBefore('.').toInt()
    private var view = DirectPlaybackView.from(1, true, false, false, 0, rate, finite)
    private var releasedView = false
    val item: MediaItem
    init {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val icon = requireNotNull(context.getDrawable(R.drawable.ic_local_music))
        icon.setBounds(0, 0, 256, 256); icon.draw(Canvas(bitmap))
        val artwork = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); output.toByteArray() }
        bitmap.recycle()
        item = MediaItem.Builder().setMediaId("direct-fixture:$fixture").setUri("asset:///$fixture")
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Direct USB test").setArtist("Local Music · quiet fixture")
                .setAlbumTitle(fixture).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true).setIsBrowsable(false)
                .setArtworkData(artwork, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build()).build()
    }
    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(Player.COMMAND_RELEASE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_METADATA, Player.COMMAND_GET_AUDIO_ATTRIBUTES)
        if (view.accepting()) commands.add(Player.COMMAND_STOP)
        if (view.canToggle()) commands.add(Player.COMMAND_PLAY_PAUSE)
        return State.Builder().setAvailableCommands(commands.build())
            .setPlaylist(listOf(MediaItemData.Builder(fixture).setMediaItem(item).setDurationUs(6_000_000).setIsSeekable(false).build()))
            .setCurrentMediaItemIndex(0).setContentPositionMs(view.positionMs())
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build())
            .setRepeatMode(if (finite) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ONE)
            .setPlayWhenReady(view.playWhenReady(), Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(when { view.ready() -> Player.STATE_READY; view.buffering() -> Player.STATE_BUFFERING; else -> Player.STATE_IDLE })
            .build()
    }
    fun observe(stats: JSONObject, running: Boolean, closing: Boolean, stopRequested: Boolean) {
        if (releasedView) return
        view = DirectPlaybackView.from(stats.optInt("phase", 1), running, closing, stopRequested, stats.optLong("positionFrames"), rate, finite)
        invalidateState()
    }
    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (view.canToggle()) toggle(!playWhenReady)
        return Futures.immediateVoidFuture()
    }
    override fun handleStop(): ListenableFuture<*> {
        if (view.accepting()) stopOwner()
        return Futures.immediateVoidFuture()
    }
    override fun handleRelease(): ListenableFuture<*> { releasedView = true; return Futures.immediateVoidFuture() }
    fun evidence() = JSONObject().put("implementation", "Media3 SimpleBasePlayer / service-owned native engine")
        .put("released", releasedView).put("lastNativePhase", view.phase()).put("acceptingControls", view.accepting())
        .put("mediaId", item.mediaId).put("hasArtwork", item.mediaMetadata.artworkData != null)
        .put("positionMeaning", if (finite) "Fully completed frame count; not DAC render time" else "Approximate repeating source position; not DAC render time")
}
