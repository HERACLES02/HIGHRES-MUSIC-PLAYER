package com.localmusic.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localmusic.core.Track

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch

/**
 * Local vector assets shared by the native player screens: Material Symbols Outlined
 * (Apache 2.0, notice in assets) copied as path data instead of the icons-extended
 * dependency. Transport controls and the favorite heart use the filled variant.
 */
object PlayerGlyph {
    // Material Symbols paths use a 960-unit grid whose y runs from -960 to 0.
    private fun vector(name: String, data: String): ImageVector = ImageVector.Builder(
        name, 24.dp, 24.dp, 960f, 960f
    ).addGroup(translationY = 960f)
        .addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black))
        .clearGroup().build()

    val Home = vector("Home", "M240-200h120v-240h240v240h120v-360L480-740 240-560v360Zm-80 80v-480l320-240 320 240v480H520v-240h-80v240H160Zm320-350Z")
    val Library = vector("Library", "M500-360q42 0 71-29t29-71v-220h120v-80H560v220q-13-10-28-15t-32-5q-42 0-71 29t-29 71q0 42 29 71t71 29ZM320-240q-33 0-56.5-23.5T240-320v-480q0-33 23.5-56.5T320-880h480q33 0 56.5 23.5T880-800v480q0 33-23.5 56.5T800-240H320Zm0-80h480v-480H320v480ZM160-80q-33 0-56.5-23.5T80-160v-560h80v560h560v80H160Zm160-720v480-480Z")
    val Playlists = vector("Playlists", "M120-320v-80h320v80H120Zm0-160v-80h480v80H120Zm0-160v-80h480v80H120Zm520 520v-320l240 160-240 160Z")
    val Settings = vector("Settings", "m370-80-16-128q-13-5-24.5-12T307-235l-119 50L78-375l103-78q-1-7-1-13.5v-27q0-6.5 1-13.5L78-585l110-190 119 50q11-8 23-15t24-12l16-128h220l16 128q13 5 24.5 12t22.5 15l119-50 110 190-103 78q1 7 1 13.5v27q0 6.5-2 13.5l103 78-110 190-118-50q-11 8-23 15t-24 12L590-80H370Zm70-80h79l14-106q31-8 57.5-23.5T639-327l99 41 39-68-86-65q5-14 7-29.5t2-31.5q0-16-2-31.5t-7-29.5l86-65-39-68-99 42q-22-23-48.5-38.5T533-694l-13-106h-79l-14 106q-31 8-57.5 23.5T321-633l-99-41-39 68 86 64q-5 15-7 30t-2 32q0 16 2 31t7 30l-86 65 39 68 99-42q22 23 48.5 38.5T427-266l13 106Zm42-180q58 0 99-41t41-99q0-58-41-99t-99-41q-59 0-99.5 41T342-480q0 58 40.5 99t99.5 41Zm-2-140Z")
    val Play = vector("Play", "M320-200v-560l440 280-440 280Z")
    val Pause = vector("Pause", "M560-200v-560h160v560H560Zm-320 0v-560h160v560H240Z")
    val Next = vector("Next", "M660-240v-480h80v480h-80Zm-440 0v-480l360 240-360 240Z")
    val Previous = vector("Previous", "M220-240v-480h80v480h-80Zm520 0L380-480l360-240v480Z")
    val Back = vector("Back", "m313-440 224 224-57 56-320-320 320-320 57 56-224 224h487v80H313Z")
    val Queue = vector("Queue", "M640-160q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 1.5t19 6.5v-328h200v80H760v360q0 50-35 85t-85 35ZM120-320v-80h320v80H120Zm0-160v-80h480v80H120Zm0-160v-80h480v80H120Z")
    val Drag = vector("Drag", "M160-360v-80h640v80H160Zm0-160v-80h640v80H160Z")
    val Lyrics = vector("Lyrics", "M160-320v-480 480ZM80-80v-720q0-33 23.5-56.5T160-880h440q33 0 56.5 23.5T680-800v17q-24 11-44 27t-36 36v-80H160v480h440v-160q16 20 36 36t44 27v97q0 33-23.5 56.5T600-240H240L80-80Zm160-320h160v-80H240v80Zm520-80q-50 0-85-35t-35-85q0-50 35-85t85-35q11 0 21 2t19 5v-207h160v80h-80v240q0 50-35 85t-85 35Zm-520-40h280v-80H240v80Zm0-120h280v-80H240v80Z")
    val More = vector("More", "M480-160q-33 0-56.5-23.5T400-240q0-33 23.5-56.5T480-320q33 0 56.5 23.5T560-240q0 33-23.5 56.5T480-160Zm0-240q-33 0-56.5-23.5T400-480q0-33 23.5-56.5T480-560q33 0 56.5 23.5T560-480q0 33-23.5 56.5T480-400Zm0-240q-33 0-56.5-23.5T400-720q0-33 23.5-56.5T480-800q33 0 56.5 23.5T560-720q0 33-23.5 56.5T480-640Z")
    val Search = vector("Search", "M784-120 532-372q-30 24-69 38t-83 14q-109 0-184.5-75.5T120-580q0-109 75.5-184.5T380-840q109 0 184.5 75.5T640-580q0 44-14 83t-38 69l252 252-56 56ZM380-400q75 0 127.5-52.5T560-580q0-75-52.5-127.5T380-760q-75 0-127.5 52.5T200-580q0 75 52.5 127.5T380-400Z")
    val Close = vector("Close", "m256-200-56-56 224-224-224-224 56-56 224 224 224-224 56 56-224 224 224 224-56 56-224-224-224 224Z")
    val Music = vector("Music", "M400-120q-66 0-113-47t-47-113q0-66 47-113t113-47q23 0 42.5 5.5T480-418v-422h240v160H560v400q0 66-47 113t-113 47Z")
    val Scan = vector("Scan", "M480-160q-134 0-227-93t-93-227q0-134 93-227t227-93q69 0 132 28.5T720-690v-110h80v280H520v-80h168q-32-56-87.5-88T480-720q-100 0-170 70t-70 170q0 100 70 170t170 70q77 0 139-44t87-116h84q-28 106-114 173t-196 67Z")
    val Grid = vector("Grid", "M120-520v-320h320v320H120Zm0 400v-320h320v320H120Zm400-400v-320h320v320H520Zm0 400v-320h320v320H520ZM200-600h160v-160H200v160Zm400 0h160v-160H600v160Zm0 400h160v-160H600v160Zm-400 0h160v-160H200v160Zm400-400Zm0 240Zm-240 0Zm0-240Z")
    val List = vector("List", "M360-240h440v-107H360v107ZM160-613h120v-107H160v107Zm0 187h120v-107H160v107Zm0 186h120v-107H160v107Zm200-186h440v-107H360v107Zm0-187h440v-107H360v107ZM160-160q-33 0-56.5-23.5T80-240v-480q0-33 23.5-56.5T160-800h640q33 0 56.5 23.5T880-720v480q0 33-23.5 56.5T800-160H160Z")
    val Shuffle = vector("Shuffle", "M560-160v-80h104L537-367l57-57 126 126v-102h80v240H560Zm-344 0-56-56 504-504H560v-80h240v240h-80v-104L216-160Zm151-377L160-744l56-56 207 207-56 56Z")
    val Repeat = vector("Repeat", "M280-80 120-240l160-160 56 58-62 62h406v-160h80v240H274l62 62-56 58Zm-80-440v-240h486l-62-62 56-58 160 160-160 160-56-58 62-62H280v160h-80Z")
    val Timer = vector("Timer", "M360-840v-80h240v80H360Zm80 440h80v-240h-80v240Zm40 320q-74 0-139.5-28.5T226-186q-49-49-77.5-114.5T120-440q0-74 28.5-139.5T226-694q49-49 114.5-77.5T480-800q62 0 119 20t107 58l56-56 56 56-56 56q38 50 58 107t20 119q0 74-28.5 139.5T734-186q-49 49-114.5 77.5T480-80Zm0-80q116 0 198-82t82-198q0-116-82-198t-198-82q-116 0-198 82t-82 198q0 116 82 198t198 82Zm0-280Z")
    val Up = vector("Up", "M480-528 296-344l-56-56 240-240 240 240-56 56-184-184Z")
    val Heart = vector("Heart", "m480-120-58-52q-101-91-167-157T150-447.5Q111-500 95.5-544T80-634q0-94 63-157t157-63q52 0 99 22t81 62q34-40 81-62t99-22q94 0 157 63t63 157q0 46-15.5 90T810-447.5Q771-395 705-329T538-172l-58 52Z")
    val HeartOutline = vector("HeartOutline", "m480-120-58-52q-101-91-167-157T150-447.5Q111-500 95.5-544T80-634q0-94 63-157t157-63q52 0 99 22t81 62q34-40 81-62t99-22q94 0 157 63t63 157q0 46-15.5 90T810-447.5Q771-395 705-329T538-172l-58 52Zm0-108q96-86 158-147.5t98-107q36-45.5 50-81t14-70.5q0-60-40-100t-100-40q-47 0-87 26.5T518-680h-76q-15-41-55-67.5T300-774q-60 0-100 40t-40 100q0 35 14 70.5t50 81q36 45.5 98 107T480-228Zm0-273Z")
    val Warning = vector("Warning", "m40-120 440-760 440 760H40Zm138-80h604L480-720 178-200Zm302-40q17 0 28.5-11.5T520-280q0-17-11.5-28.5T480-320q-17 0-28.5 11.5T440-280q0 17 11.5 28.5T480-240Zm-40-120h80v-200h-80v200Zm40-100Z")
}

val mainDestinations = listOf("Home", "Library", "Playlists", "Settings")

@Composable
fun PlayerRail(selected: String, soulseekEnabled: Boolean = false, navigate: (String) -> Unit) {
    NavigationRail {
        (if (soulseekEnabled) mainDestinations + "Soulseek" else mainDestinations).forEach { destination ->
            NavigationRailItem(selected = selected == destination, onClick = { navigate(destination) },
                icon = { Icon(when (destination) {
                    "Home" -> PlayerGlyph.Home; "Library" -> PlayerGlyph.Library
                    "Playlists" -> PlayerGlyph.Playlists; "Soulseek" -> PlayerGlyph.Search
                    else -> PlayerGlyph.Settings
                }, contentDescription = destination) },
                label = { Text(destination, maxLines = 1, overflow = TextOverflow.Ellipsis) }, alwaysShowLabel = false)
        }
    }
}

@Composable
fun PlayerNavigation(selected: String, soulseekEnabled: Boolean = false, navigate: (String) -> Unit) {
    NavigationBar(tonalElevation = 0.dp) {
        (if (soulseekEnabled) mainDestinations + "Soulseek" else mainDestinations).forEach { destination ->
            NavigationBarItem(selected = selected == destination, onClick = { navigate(destination) },
                icon = { Icon(when (destination) {
                    "Home" -> PlayerGlyph.Home
                    "Library" -> PlayerGlyph.Library
                    "Playlists" -> PlayerGlyph.Playlists
                    "Soulseek" -> PlayerGlyph.Search
                    else -> PlayerGlyph.Settings
                }, contentDescription = null) },
                label = { Text(destination, maxLines = 1, overflow = TextOverflow.Ellipsis) })
        }
    }
}

@Composable
fun CompactPlayer(track: Track, art: String?, playing: Boolean, ready: Boolean, progress: ()->Float,
    open: () -> Unit, toggle: () -> Unit, previous: () -> Unit, next: () -> Unit) {
    val scope = rememberCoroutineScope()
    var offset by remember(track.id) { mutableFloatStateOf(0f) }
    var settling by remember(track.id) { mutableStateOf(false) }
    var epoch by remember(track.id) { mutableIntStateOf(0) }
    val swipeThreshold = with(LocalDensity.current) { 80.dp.toPx() }
    val flingThreshold = with(LocalDensity.current) { 500.dp.toPx() }

    suspend fun settle(velocity: Float) {
        if (settling) return
        settling = true
        val started = epoch
        try {
            val triggerPrevious = offset > swipeThreshold || velocity > flingThreshold
            val triggerNext = offset < -swipeThreshold || velocity < -flingThreshold
            if (triggerPrevious && ready) previous()
            else if (triggerNext && ready) next()

            animate(offset, 0f, initialVelocity = velocity,
                animationSpec = spring(dampingRatio = .9f, stiffness = 500f)) { value, _ ->
                if (started == epoch) offset = value
            }
        } finally {
            if (started == epoch) {
                offset = 0f
                settling = false
            }
        }
    }

    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        .graphicsLayer { translationX = offset }
        .draggable(
            state = rememberDraggableState { delta -> if (!settling) offset += delta },
            orientation = Orientation.Horizontal,
            enabled = ready,
            onDragStopped = { velocity -> scope.launch { settle(velocity) } }
        ),
        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).testTag("miniPlayer").clickable(onClickLabel = "Open Now Playing", onClick = open).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CoverArt(art, track.tags.title, Modifier.size(44.dp), contentScale = ContentScale.Crop)
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
