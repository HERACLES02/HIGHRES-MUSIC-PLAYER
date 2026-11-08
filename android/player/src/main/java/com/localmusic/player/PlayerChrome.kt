package com.localmusic.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.core.Track

/** Local vector assets shared by the native player screens. */
object PlayerGlyph {
    private fun vector(name: String, data: String): ImageVector = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f
    ).addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black)).build()

    val Home = vector("Home", "M3,10 L12,3 L21,10 L21,21 L14,21 L14,14 L10,14 L10,21 L3,21 Z")
    val Library = vector("Library", "M3,4 L5,4 L5,21 L3,21 Z M8,3 L10,3 L10,21 L8,21 Z M13,5 L16,4 L22,20 L19,21 Z")
    val Playlists = vector("Playlists", "M3,4 L18,4 L18,6 L3,6 Z M3,9 L18,9 L18,11 L3,11 Z M3,14 L11,14 L11,16 L3,16 Z M16,13 L23,17 L16,21 Z")
    val Settings = vector("Settings", "M4,3 L6,3 L6,7 L9,7 L9,11 L6,11 L6,21 L4,21 L4,11 L1,11 L1,7 L4,7 Z M12,3 L14,3 L14,14 L17,14 L17,18 L14,18 L14,21 L12,21 L12,18 L9,18 L9,14 L12,14 Z M20,3 L22,3 L22,6 L24,6 L24,10 L22,10 L22,21 L20,21 L20,10 L18,10 L18,6 L20,6 Z")
    val Play = vector("Play", "M8,4 L21,12 L8,20 Z")
    val Pause = vector("Pause", "M6,4 L10,4 L10,20 L6,20 Z M14,4 L18,4 L18,20 L14,20 Z")
    val Next = vector("Next", "M5,5 L16,12 L5,19 Z M17,5 L20,5 L20,19 L17,19 Z")
    val Previous = vector("Previous", "M19,5 L8,12 L19,19 Z M4,5 L7,5 L7,19 L4,19 Z")
    val Back = vector("Back", "M20,11 L7,11 L12,6 L10.6,4.6 L3.2,12 L10.6,19.4 L12,18 L7,13 L20,13 Z")
    val Queue = vector("Queue", "M3,4 L21,4 L21,6 L3,6 Z M3,9 L21,9 L21,11 L3,11 Z M3,14 L13,14 L13,16 L3,16 Z M17,13 L23,17 L17,21 Z")
    val Drag = vector("Drag", "M4,8 L20,8 L20,10 L4,10 Z M4,14 L20,14 L20,16 L4,16 Z")
    val Lyrics = vector("Lyrics", "M3,4 L14,4 L14,6 L3,6 Z M3,9 L14,9 L14,11 L3,11 Z M3,14 L10,14 L10,16 L3,16 Z M18,3 L22,3 L22,7 L20,7 L20,17 A4,4 0,1 1,18,13.54 Z")
    val More = vector("More", "M10,3 L14,3 L14,7 L10,7 Z M10,10 L14,10 L14,14 L10,14 Z M10,17 L14,17 L14,21 L10,21 Z")
    val Search = vector("Search", "M10,2 A8,8 0,1 0,15.6,15.6 L21,21 L22.4,19.6 L17,14.2 A8,8 0,0 0,10,2 Z M10,4 A6,6 0,1 1,10,16 A6,6 0,1 1,10,4 Z")
    val Close = vector("Close", "M5,3.6 L12,10.6 L19,3.6 L20.4,5 L13.4,12 L20.4,19 L19,20.4 L12,13.4 L5,20.4 L3.6,19 L10.6,12 L3.6,5 Z")
    val Music = vector("Music", "M10,3 L21,1 L21,16 A4,4 0,1 1,19,12.5 L19,6 L12,7.3 L12,18 A4,4 0,1 1,10,14.5 Z")
    val Scan = vector("Scan", "M3,3 L9,3 L9,5 L5,5 L5,9 L3,9 Z M15,3 L21,3 L21,9 L19,9 L19,5 L15,5 Z M3,15 L5,15 L5,19 L9,19 L9,21 L3,21 Z M19,15 L21,15 L21,21 L15,21 L15,19 L19,19 Z M7,11 L17,11 L17,13 L7,13 Z")
    val Grid = vector("Grid", "M3,3 L10,3 L10,10 L3,10 Z M14,3 L21,3 L21,10 L14,10 Z M3,14 L10,14 L10,21 L3,21 Z M14,14 L21,14 L21,21 L14,21 Z")
    val List = vector("List", "M3,4 L6,4 L6,7 L3,7 Z M9,4 L21,4 L21,7 L9,7 Z M3,11 L6,11 L6,14 L3,14 Z M9,11 L21,11 L21,14 L9,14 Z M3,18 L6,18 L6,21 L3,21 Z M9,18 L21,18 L21,21 L9,21 Z")
    val Shuffle = vector("Shuffle", "M3,5 L6,5 L18,17 L20,17 L20,14 L24,18 L20,22 L20,19 L17,19 L5,7 L3,7 Z M3,17 L6,17 L9,14 L10.4,15.4 L7,19 L3,19 Z M13,9 L17,5 L20,5 L20,2 L24,6 L20,10 L20,7 L18,7 L14.4,10.4 Z")
    val Repeat = vector("Repeat", "M4,5 L18,5 L18,2 L23,6 L18,10 L18,7 L5,7 L5,11 L3,11 L3,7 Z M20,19 L6,19 L6,22 L1,18 L6,14 L6,17 L19,17 L19,13 L21,13 L21,17 Z")
    val Timer = vector("Timer", "M10,1 L14,1 L14,3 L10,3 Z M12,4 A9,9 0,1 0,12,22 A9,9 0,1 0,12,4 Z M12,6 A7,7 0,1 1,12,20 A7,7 0,1 1,12,6 Z M11,8 L13,8 L13,13 L17,15 L16,17 L11,14 Z")
    val Up = vector("Up", "M5,14 L12,7 L19,14 L17.5,15.5 L12,10 L6.5,15.5 Z")
    val Heart = vector("Heart", "M12,21 L3,12 C-3,4 6,-1 12,6 C18,-1 27,4 21,12 Z")
    val HeartOutline = vector("HeartOutline", "M16.5,3 C14.76,3 13.09,3.81 12,5.09 C10.91,3.81 9.24,3 7.5,3 C4.42,3 2,5.42 2,8.5 C2,12.28 5.4,15.36 10.55,20.04 L12,21.35 L13.45,20.03 C18.6,15.36 22,12.28 22,8.5 C22,5.42 19.58,3 16.5,3 Z M12.1,18.55 L12,18.65 L11.9,18.55 C7.14,14.24 4,11.39 4,8.5 C4,6.5 5.5,5 7.5,5 C9.04,5 10.54,5.99 11.07,7.36 L12.94,7.36 C13.46,5.99 14.96,5 16.5,5 C18.5,5 20,6.5 20,8.5 C20,11.39 16.86,14.24 12.1,18.55 Z")
}

val mainDestinations = listOf("Home", "Library", "Playlists", "Settings")

@Composable
fun PlayerRail(selected: String, navigate: (String) -> Unit) {
    NavigationRail {
        mainDestinations.forEach { destination ->
            NavigationRailItem(selected = selected == destination, onClick = { navigate(destination) },
                icon = { Icon(when (destination) {
                    "Home" -> PlayerGlyph.Home; "Library" -> PlayerGlyph.Library
                    "Playlists" -> PlayerGlyph.Playlists; else -> PlayerGlyph.Settings
                }, contentDescription = destination) },
                label = { Text(destination, maxLines = 1, overflow = TextOverflow.Ellipsis) }, alwaysShowLabel = false)
        }
    }
}

@Composable
fun PlayerNavigation(selected: String, navigate: (String) -> Unit) {
    NavigationBar(tonalElevation = 0.dp) {
        mainDestinations.forEach { destination ->
            NavigationBarItem(selected = selected == destination, onClick = { navigate(destination) },
                icon = { Icon(when (destination) {
                    "Home" -> PlayerGlyph.Home
                    "Library" -> PlayerGlyph.Library
                    "Playlists" -> PlayerGlyph.Playlists
                    else -> PlayerGlyph.Settings
                }, contentDescription = null) },
                label = { Text(destination, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

@Composable
fun CompactPlayer(track: Track, art: String?, playing: Boolean, ready: Boolean, progress: ()->Float,
    open: () -> Unit, toggle: () -> Unit, next: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClickLabel = "Open Now Playing", onClick = open).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoverArt(art, track.tags.title, Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        MarqueeText(track.tags.title,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,autoScroll=true)
                        MarqueeText(track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,autoScroll=true)
                    }
                }
                IconButton(onClick = toggle, enabled = ready, modifier = Modifier.size(48.dp)) {
                    Icon(if (playing) PlayerGlyph.Pause else PlayerGlyph.Play, if (playing) "Pause" else "Play")
                }
                IconButton(onClick = next, enabled = ready, modifier = Modifier.size(48.dp)) { Icon(PlayerGlyph.Next, "Next track") }
            }
            LinearProgressIndicator(progress = { progress().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

@Composable
fun PlaybackButtons(playing: Boolean, ready: Boolean, previous: () -> Unit, toggle: () -> Unit, next: () -> Unit,prominent:Boolean=false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = previous, enabled = ready, modifier = Modifier.size(56.dp)) { Icon(PlayerGlyph.Previous, "Previous track", Modifier.size(32.dp)) }
        FilledIconButton(onClick = toggle, enabled = ready, modifier = Modifier.size(if(prominent)80.dp else 72.dp)) {
            Icon(if (playing) PlayerGlyph.Pause else PlayerGlyph.Play, if (playing) "Pause" else "Play", Modifier.size(36.dp))
        }
        IconButton(onClick = next, enabled = ready, modifier = Modifier.size(56.dp)) { Icon(PlayerGlyph.Next, "Next track", Modifier.size(32.dp)) }
    }
}
