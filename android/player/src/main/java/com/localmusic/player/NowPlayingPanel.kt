@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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
    artworkSizePreference: String, store: MusicStore?, revision: Long, send: (String, Bundle) -> Unit, actions: FeatureActions,
    openQueue: () -> Unit, details: () -> Unit,openAudio:()->Unit,openArtist:()->Unit) {
    var lyricsOpen by rememberSaveable { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var timerOpen by rememberSaveable { mutableStateOf(false) }
    val position = playback.optLong("position").coerceAtLeast(0)
    val duration = playback.optLong("duration").coerceAtLeast(0)
    val playing = playback.optBoolean("playWhenReady")
    val toggle = { if (playing) controller?.pause() else controller?.play(); Unit }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        val panelHeight = maxHeight
        val portraitArtRoom = (maxHeight - 300.dp * fontScale).coerceAtLeast(96.dp)
        val artworkSize = if (wide) {
            val columnWidth = ((maxWidth - 80.dp) * 0.45f).coerceAtLeast(96.dp)
            val preferred = when (artworkSizePreference) {
                "Compact" -> minOf(maxHeight * 0.56f, 220.dp)
                "Full width" -> columnWidth
                else -> minOf(maxHeight * 0.72f, 300.dp)
            }
            minOf(columnWidth, preferred, (maxHeight - 120.dp * fontScale).coerceAtLeast(96.dp)).coerceAtLeast(96.dp)
        } else minOf(portraitArtRoom, maxWidth - 48.dp, when (artworkSizePreference) {
            "Compact" -> 240.dp
            "Full width" -> 480.dp
            else -> 320.dp
        })
        val header: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CoverArt(art, track.tags.title, Modifier.size(artworkSize))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        MarqueeText(track.tags.title, style = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold))
                        MarqueeText(track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" },
                            modifier = Modifier.clickable(onClickLabel = "Open artist page", onClick = openArtist),
                            style = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        track.tags.album?.takeIf { it.isNotBlank() }?.let { MarqueeText(it, style = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }

                }
            }
        }
        val controls: @Composable () -> Unit = {
            TextButton(onClick = details) { Text(PlaybackPresentation.quality(track.audio), style = MaterialTheme.typography.labelMedium) }
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
                IconButton(onClick = { lyricsOpen = true }) { Icon(PlayerGlyph.Lyrics, "Open lyrics") }
                IconButton(onClick = openQueue) { Icon(PlayerGlyph.Queue, "Open queue") }
                Box {
                    IconButton(onClick = { optionsOpen = true }) { Icon(PlayerGlyph.More, "Playback options") }
                    DropdownMenu(expanded = optionsOpen, onDismissRequest = { optionsOpen = false }) {
                        DropdownMenuItem(text = { Text("Audio controls") }, onClick = { optionsOpen = false; openAudio() })
                        DropdownMenuItem(text = { Text("Artist page") }, onClick = { optionsOpen = false; openArtist() })
                        DropdownMenuItem(text = { Text("Song details") }, onClick = { optionsOpen = false; details() })
                        DropdownMenuItem(text = { Text("Stop playback") }, enabled = ready, onClick = { optionsOpen = false; controller?.stop() })
                    }
                }
            }
            when (playback.optString("sleepMode", "OFF")) {
                "IMMEDIATE" -> Text("Sleep timer · ${clockText(playback.optLong("sleepRemaining"))} left", style = MaterialTheme.typography.labelMedium)
                "END_OF_TRACK" -> Text("Sleep timer · after this track", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (wide) Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) { header() }
            Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()).heightIn(min = panelHeight).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) { controls() }
        } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = panelHeight).padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) { header(); controls() }
    }
    if (lyricsOpen) ModalBottomSheet(onDismissRequest = { lyricsOpen = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight().navigationBarsPadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    MarqueeText(track.tags.title, style = MaterialTheme.typography.titleLarge)
                    MarqueeText(track.tags.artists.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = { lyricsOpen = false }) { Icon(PlayerGlyph.Close, "Close lyrics") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LyricsPanel(track, store, revision, position, controller, send, actions, expanded = true, beforeImport = { lyricsOpen = false })
            }
            if (LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SeekControls(track.id, position, duration, ready, Modifier.weight(1f)) { controller?.seekTo(it) }
                    IconButton(onClick = { controller?.seekToPrevious() }, enabled = ready) { Icon(PlayerGlyph.Previous, "Previous track") }
                    IconButton(onClick = toggle, enabled = ready) { Icon(if (playing) PlayerGlyph.Pause else PlayerGlyph.Play, if (playing) "Pause" else "Play") }
                    IconButton(onClick = { controller?.seekToNext() }, enabled = ready) { Icon(PlayerGlyph.Next, "Next track") }
                }
            } else {
                SeekControls(track.id, position, duration, ready) { controller?.seekTo(it) }
                PlaybackButtons(playing, ready, { controller?.seekToPrevious() }, toggle, { controller?.seekToNext() })
            }
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
            Text("Choose when playback should pause. The timer continues while music is paused.")
            PlayerActions { listOf(1, 15, 30, 45, 60, 90, 120).forEach { minutes ->
                OutlinedButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_SET, Bundle().apply { putInt("minutes", minutes) }); timerOpen = false }) { Text(countLabel(minutes, "minute")) }
            } }
            TextButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_END_TRACK, Bundle.EMPTY); timerOpen = false }) { Text("End of track") }
            TextButton(enabled = ready, onClick = { send(PlaybackService.SLEEP_CANCEL, Bundle.EMPTY); timerOpen = false }) { Text("Cancel timer") }
        } }, confirmButton = { TextButton(onClick = { timerOpen = false }) { Text("Close") } })
}

@Composable
private fun SeekControls(id: TrackId, position: Long, duration: Long, ready: Boolean, modifier: Modifier = Modifier, seek: (Long) -> Unit) {
    var dragged by remember(id) { mutableStateOf<Float?>(null) }
    Column(modifier.fillMaxWidth()) {
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
