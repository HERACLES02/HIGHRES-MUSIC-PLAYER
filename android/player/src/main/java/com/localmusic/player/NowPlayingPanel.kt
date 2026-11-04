@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.localmusic.core.*
import org.json.JSONObject

private fun clockText(ms: Long) = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

@Composable
fun NowPlayingPanel(track: Track, art: String?, playback: JSONObject, controller: MediaController?, ready: Boolean,
    store: MusicStore?, revision: Long, send: (String, Bundle) -> Unit, actions: FeatureActions,
    openQueue: () -> Unit, details: () -> Unit, favorite: () -> Unit,openAudio:()->Unit,openArtist:()->Unit) {
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var timerOpen by rememberSaveable { mutableStateOf(false) }
    val position = playback.optLong("position").coerceAtLeast(0)
    val duration = playback.optLong("duration").coerceAtLeast(0)
    val playing = playback.optBoolean("playWhenReady")
    val toggle = { if (playing) controller?.pause() else controller?.play(); Unit }
    val artworkSize = (LocalConfiguration.current.screenHeightDp * 0.29f).coerceIn(128f, 240f).dp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The seek bar and buttons are outside this swipe surface, so their gestures keep their own meaning.
        Column(Modifier.fillMaxWidth().pointerInput(track.id) {
            var travel = 0f
            val threshold = 64.dp.toPx()
            detectVerticalDragGestures(onDragStart = { travel = 0f }, onDragCancel = { travel = 0f },
                onDragEnd = { if (travel < -threshold) lyricsOpen = true; travel = 0f },
                onVerticalDrag = { change, distance -> change.consume(); travel += distance })
        }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CoverArt(art, track.tags.title, Modifier.size(artworkSize))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.tags.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" },modifier=Modifier.clickable(onClick=openArtist),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    track.tags.album?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                IconToggleButton(checked = track.favorite, onCheckedChange = { favorite() }, enabled = ready) {
                    Icon(PlayerGlyph.Heart, if (track.favorite) "Remove favorite" else "Add favorite")
                }
            }
        }
        TextButton(onClick = details) { Text("Source · ${PlaybackPresentation.quality(track.audio)}", style = MaterialTheme.typography.labelMedium) }
        SeekControls(track.id, position, duration, ready) { controller?.seekTo(it) }
        PlaybackButtons(playing, ready, { controller?.seekToPrevious() }, toggle, { controller?.seekToNext() })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(checked = controller?.shuffleModeEnabled == true,
                onCheckedChange = { controller?.shuffleModeEnabled = it }, enabled = ready) {
                Icon(PlayerGlyph.Shuffle, if (controller?.shuffleModeEnabled == true) "Shuffle on" else "Shuffle off")
            }
            val repeat = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
            IconToggleButton(checked = repeat != Player.REPEAT_MODE_OFF, enabled = ready,
                onCheckedChange = { controller?.repeatMode = (repeat + 1) % 3 }) {
                BadgedBox(badge = { if (repeat == Player.REPEAT_MODE_ONE) Badge { Text("1") } }) {
                    Icon(PlayerGlyph.Repeat, "Repeat ${when (repeat) { Player.REPEAT_MODE_ONE -> "one"; Player.REPEAT_MODE_ALL -> "all"; else -> "off" }}")
                }
            }
            IconButton(onClick = { timerOpen = true }, enabled = ready) {
                Icon(PlayerGlyph.Timer, "Sleep timer", tint = if (playback.optString("sleepMode", "OFF") == "OFF")
                    MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = openQueue) { Icon(PlayerGlyph.Queue, "Open queue") }
            Box {
                IconButton(onClick = { optionsOpen = true }) { Icon(PlayerGlyph.More, "Playback options") }
                DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                    DropdownMenuItem(text={Text("Audio controls")},onClick={optionsOpen=false;openAudio()})
                    DropdownMenuItem(text={Text("Artist page")},onClick={optionsOpen=false;openArtist()})
                    DropdownMenuItem(text = { Text("Song details") }, onClick = { optionsOpen = false; details() })
                    DropdownMenuItem(text = { Text("Stop playback") }, enabled = ready, onClick = { optionsOpen = false; controller?.stop() })
                }
            }
        }
        when (playback.optString("sleepMode", "OFF")) {
            "IMMEDIATE" -> Text("Sleep timer · ${clockText(playback.optLong("sleepRemaining"))} left", style = MaterialTheme.typography.labelMedium)
            "END_OF_TRACK" -> Text("Sleep timer · after this track", style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
        TextButton(onClick = { lyricsOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(PlayerGlyph.Up, null); Spacer(Modifier.width(8.dp)); Text("Swipe up for lyrics")
        }
    }
    if (lyricsOpen) ModalBottomSheet(onDismissRequest = { lyricsOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.tags.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.tags.artists.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { lyricsOpen = false }) { Icon(PlayerGlyph.Close, "Close lyrics") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LyricsPanel(track, store, revision, position, controller, send, actions, expanded = true)
            }
            SeekControls(track.id, position, duration, ready) { controller?.seekTo(it) }
            PlaybackButtons(playing, ready, { controller?.seekToPrevious() }, toggle, { controller?.seekToNext() })
            Spacer(Modifier.height(12.dp))
        }
    }
    if (timerOpen) AlertDialog(onDismissRequest = { timerOpen = false }, title = { Text("Sleep timer") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text(when (playback.optString("sleepMode", "OFF")) {
                "IMMEDIATE" -> "Pausing in ${clockText(playback.optLong("sleepRemaining"))}."
                "END_OF_TRACK" -> "Pausing after this track ends."
                else -> "No timer set."
            })
            Text("Countdown keeps running while paused. A timer is cancelled if the playback service restarts.")
            listOf(1, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                TextButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_SET, Bundle().apply { putInt("minutes", minutes) }); timerOpen = false }) { Text("$minutes minutes") }
            }
            TextButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_END_TRACK, Bundle.EMPTY); timerOpen = false }) { Text("End of track") }
            TextButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_CANCEL, Bundle.EMPTY); timerOpen = false }) { Text("Cancel timer") }
        } }, confirmButton = { TextButton(onClick = { timerOpen = false }) { Text("Close") } })
}

@Composable
private fun SeekControls(id: TrackId, position: Long, duration: Long, ready: Boolean, seek: (Long) -> Unit) {
    var dragged by remember(id) { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Slider(value = dragged ?: if (duration > 0) position.coerceAtMost(duration).toFloat() / duration else 0f,
            onValueChange = { dragged = it }, enabled = ready && duration > 0,
            onValueChangeFinished = { dragged?.let { seek((it * duration).toLong()) }; dragged = null },
            modifier = Modifier.semantics { contentDescription = "Playback position" })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockText(dragged?.let { (it * duration).toLong() } ?: position), style = MaterialTheme.typography.labelSmall)
            Text(if (duration > 0) clockText(duration) else "Unknown duration", style = MaterialTheme.typography.labelSmall)
        }
    }
}
