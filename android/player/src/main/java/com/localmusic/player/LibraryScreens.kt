package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.localmusic.core.*
import kotlinx.coroutines.*

@Composable
fun TrackMenu(track: Track, ready: Boolean, send: (String, Bundle) -> Unit, addToPlaylist: () -> Unit, details: () -> Unit, menuState: MutableState<Boolean>? = null) {
    val removeSong=LocalSongRemoval.current
    val state = menuState ?: remember(track.id) { mutableStateOf(false) }
    var open by state
    fun args() = Bundle().apply { putString("id", track.id.value.toString()) }
    Box {
        IconButton(onClick = { open = true }, enabled = ready) { Icon(PlayerGlyph.More, "Options for ${track.tags.title}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play Next") }, onClick = { open = false; send(PlaybackService.NEXT, args()) })
            DropdownMenuItem(text = { Text("Add to Queue") }, onClick = { open = false; send(PlaybackService.ADD, args()) })
            DropdownMenuItem(text = { Text("Add to playlist…") }, onClick = { open = false; addToPlaylist() })
            DropdownMenuItem(text = { Text("Details") }, onClick = { open = false; details() })
            DropdownMenuItem(text = { Text("Remove from library...") }, onClick = { open=false;removeSong(track,false) })
            DropdownMenuItem(text = { Text("Delete file from phone...") }, onClick = { open=false;removeSong(track,true) })
        }
    }
}

@Composable
fun HomeLibraryScreen(library: LibraryState, store: MusicStore?, grid: Boolean, search: String, ready: Boolean,
    onSearch: (String) -> Unit, changeGrid: (Boolean) -> Unit, scan: () -> Unit,
    play: (List<Track>, Int, Boolean, String) -> Unit, send: (String, Bundle) -> Unit,
    addToPlaylist: (Track) -> Unit, details: (Track) -> Unit, playlistCard: @Composable (Playlist, Int) -> Unit, allPlaylists: () -> Unit, allSongs: () -> Unit) {
    val tracks = remember(library.tracks, search) { library.tracks.filter {
        (it.tags.title + " " + it.tags.artists.joinToString(" ") + " " + it.tags.album.orEmpty()).contains(search, true)
    }.sortedByDescending { it.addedAtMs } }
    val continues = remember(tracks, library.continues) { tracks.filter { it.id in library.continues && it.availability == Availability.AVAILABLE } }
    LazyVerticalGrid(columns = if (grid) GridCells.Adaptive(144.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.4f)) else GridCells.Fixed(1), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Your music, offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Make time for a good song.", style = MaterialTheme.typography.headlineSmall)
            MusicSearchField(search,onSearch,"Search your music")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(countLabel(tracks.size, "song"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconToggleButton(checked = grid, onCheckedChange = { changeGrid(true) }, enabled = ready) { Icon(PlayerGlyph.Grid, "Grid view") }
                IconToggleButton(checked = !grid, onCheckedChange = { changeGrid(false) }, enabled = ready) { Icon(PlayerGlyph.List, "List view") }
            }
            if (library.tracks.isEmpty()) {
                Text("Choose a music folder to start. Only selected folders and their subfolders appear here.")
                Button(onClick = scan, enabled = ready && !library.scanning) { Text("Choose music folder") }
            } else if (tracks.isEmpty()) Text("No matching music. Try another search.")
        } }
        if (search.isBlank() && continues.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Continue listening", style = MaterialTheme.typography.titleLarge) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(continues.take(12), key = { it.id.value.toString() }) { track ->
                        val menu = remember(track.id) { mutableStateOf(false) }
                        ElevatedCard(modifier = Modifier.width(180.dp).combinedClickable(enabled = ready,
                            onClick = { play(listOf(track), 0, true, "Continue listening") },
                            onLongClickLabel = "Song options", onLongClick = { menu.value = true })) {
                            CoverArt(store?.artwork(track.id), track.tags.title, Modifier.fillMaxWidth().aspectRatio(1f))
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f)) { MarqueeText(track.tags.title, style = MaterialTheme.typography.titleSmall) }
                                    TrackMenu(track, ready, send, { addToPlaylist(track) }, { details(track) }, menu)
                                }
                                val saved = library.continues[track.id] ?: 0
                                Text("Continue at ${saved / 60000}:${"%02d".format(saved / 1000 % 60)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        if (search.isBlank()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your playlists", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = allPlaylists) { Text("View all") }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (library.playlists.isEmpty()) OutlinedCard(onClick = allPlaylists, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(PlayerGlyph.Playlists, null, Modifier.size(36.dp)); Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) { Text("Make a playlist", style = MaterialTheme.typography.titleMedium); Text("Create a mix or import a playlist file.") }
                    }
                } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(library.playlists.take(12), key = { it.first.id.value.toString() }) { (playlist, count) ->
                        Box(Modifier.width(160.dp)) { playlistCard(playlist, count) }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (search.isBlank()) "Recently added" else "Search results", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (search.isBlank()) TextButton(onClick = allSongs) { Text("View all") }
            }
        }
        items(if (search.isBlank()) tracks.take(if (grid) 8 else 5) else tracks, key = { "recent-${it.id}" }) { track ->
            HomeTrack(track, store?.artwork(track.id), grid, ready, { play(tracks, tracks.indexOf(track), false, "Home · Recently added") },
                send, { addToPlaylist(track) }, { details(track) })
        }

    }
}

@Composable
private fun HomeTrack(track: Track, art: String?, grid: Boolean, ready: Boolean, play: () -> Unit,
    send: (String, Bundle) -> Unit, addToPlaylist: () -> Unit, details: () -> Unit) {
    val menu = remember(track.id) { mutableStateOf(false) }
    val enabled = ready && track.availability == Availability.AVAILABLE
    if (grid) Column(Modifier.fillMaxWidth().combinedClickable(enabled = ready, onClick = { if (enabled) play() }, onLongClickLabel = "Song options", onLongClick = { menu.value = true })) {
        CoverArt(art, track.tags.title, Modifier.fillMaxWidth().aspectRatio(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                MarqueeText(track.tags.title, style = MaterialTheme.typography.titleSmall)
                MarqueeText(track.tags.artists.joinToString(", ").ifBlank { "Unknown artist" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (track.availability != Availability.AVAILABLE) Text("Unavailable", style = MaterialTheme.typography.labelSmall)
            }
            TrackMenu(track, ready, send, addToPlaylist, details, menu)
        }
    } else ListItem(modifier = Modifier.combinedClickable(enabled = ready, onClick = { if (enabled) play() }, onLongClickLabel = "Song options", onLongClick = { menu.value = true }),
        leadingContent = { CoverArt(art, track.tags.title, Modifier.size(48.dp)) },
        headlineContent = { MarqueeText(track.tags.title) },
        supportingContent = { MarqueeText(track.tags.artists.joinToString(", ") + if (enabled) "" else " · Unavailable") },
        trailingContent = { TrackMenu(track, ready, send, addToPlaylist, details, menu) })
}

@Composable
fun PlaylistCard(playlist: Playlist, count: Int, store: MusicStore?, revision: Long, open: () -> Unit,
    play: () -> Unit, shuffle: () -> Unit, rename: () -> Unit, delete: () -> Unit, modifier: Modifier = Modifier) {
    var menu by remember(playlist.id) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().combinedClickable(onClick = open, onLongClickLabel = "Playlist options", onLongClick = { menu = true })) {
        PlaylistCover(playlist, store, revision, Modifier.fillMaxWidth().aspectRatio(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                MarqueeText(playlist.name, style = MaterialTheme.typography.titleSmall)
                Text(countLabel(count, "song"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(PlayerGlyph.More, "Options for ${playlist.name}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play") }, enabled = count > 0, onClick = { menu = false; play() })
                    DropdownMenuItem(text = { Text("Play shuffled") }, enabled = count > 0, onClick = { menu = false; shuffle() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; delete() })
                }
            }
        }
    }
}

@Composable
fun PlaylistCover(playlist: Playlist, store: MusicStore?, revision: Long, modifier: Modifier = Modifier) {
    var covers by remember(playlist.id) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(playlist.id, revision, store) {
        try {
            val currentStore = store ?: return@LaunchedEffect
            covers = withContext(Dispatchers.IO) { currentStore.repository().playlist(playlist.id)?.second.orEmpty().take(128).mapNotNull { currentStore.artwork(it.trackId) }.distinct().take(4) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { covers = emptyList() } // Artwork is optional; the saved playlist remains available.
    }
        Surface(modifier.clearAndSetSemantics { contentDescription = "Cover for ${playlist.name}" }, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            if (covers.isEmpty()) Box(Modifier.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer))), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(PlayerGlyph.Playlists, null, Modifier.size(52.dp))
                    Text(String(Character.toChars(playlist.name.codePointAt(0))).uppercase(), style = MaterialTheme.typography.headlineMedium)
                }
            }
            else if (covers.size == 1) CoverArt(covers.first(), playlist.name, Modifier.fillMaxSize())
            else Column {
                repeat(2) { row -> Row(Modifier.weight(1f)) { repeat(2) { column ->
                    CoverArt(covers.getOrNull((row * 2 + column) % covers.size), playlist.name, Modifier.weight(1f).fillMaxHeight())
                } } }
            }
        }
}

/** Shared popup for grouped library rows; tap and long press use the same actions. */
@Composable
fun CollectionRow(title: String, art: String?, count: Int, kind: String, ready: Boolean,
    open: () -> Unit, play: () -> Unit, shuffle: () -> Unit, addToPlaylist: () -> Unit,
    extraActions: List<Pair<String, () -> Unit>> = emptyList()) {
    var menu by remember(title, kind) { mutableStateOf(false) }
    ListItem(modifier = Modifier.combinedClickable(onClick = open,
        onLongClickLabel = "$kind options", onLongClick = { menu = true }),
        leadingContent = { CoverArt(art, title, Modifier.size(56.dp)) },
        headlineContent = { MarqueeText(title) }, supportingContent = { Text(countLabel(count, "song")) },
        trailingContent = { Box {
            IconButton(onClick = { menu = true }) { Icon(PlayerGlyph.More, "Options for $title") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Open ${kind.lowercase()}") }, onClick = { menu = false; open() })
                DropdownMenuItem(text = { Text("Play") }, enabled = ready && count > 0, onClick = { menu = false; play() })
                DropdownMenuItem(text = { Text("Play shuffled") }, enabled = ready && count > 0, onClick = { menu = false; shuffle() })
                DropdownMenuItem(text = { Text("Add to playlist") }, enabled = ready && count > 0, onClick = { menu = false; addToPlaylist() })
                extraActions.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, enabled = ready, onClick = { menu = false; action() })
                }
            }
        } })
}
