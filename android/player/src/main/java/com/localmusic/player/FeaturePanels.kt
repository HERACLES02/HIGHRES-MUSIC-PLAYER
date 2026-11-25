@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.snapshots.SnapshotStateMap
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.localmusic.core.*
import com.localmusic.data.FileEditRow
import com.localmusic.data.LyricsSource
import com.localmusic.data.RecordingCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalUriHandler
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FeatureActions(val pickFolder: () -> Unit, val pickCover: (List<Track>) -> Unit,
                          val pickPlaylistCover: (Playlist) -> Unit,
    val importLyrics: (Track) -> Unit, val exportLyrics: (Track, String) -> Unit,
    val commit: (List<FileEditRow>) -> Unit, val undo: (FileEditRow) -> Unit, val recovery: (FileEditRow) -> Unit,
    val createBackup: () -> Unit, val restoreBackup: () -> Unit, val backupBusy: Boolean)

@Composable fun CoverArt(path: String?, title: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        if (path != null) AsyncImage(model = File(path), contentDescription = "Cover for $title", contentScale = contentScale)
        else Box(Modifier.padding(10.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(PlayerGlyph.Music, "No cover artwork", Modifier.size(32.dp)) }
    }
}

@Composable fun LyricsPanel(track: Track, store: MusicStore?, revision: Long, positionMs: Long, controller: MediaController?,
    send: (String, Bundle) -> Unit, actions: FeatureActions, expanded: Boolean = false, beforeImport: () -> Unit = {}) {
    var versions by remember(track.id) { mutableStateOf(emptyList<SavedLyrics>()) }
    var selected by remember(track.id) { mutableStateOf<SavedLyrics?>(null) }
    var loadError by remember(track.id) { mutableStateOf("") }
    var edit by rememberSaveable(track.id) { mutableStateOf(false) }
    var showVersions by rememberSaveable(track.id) { mutableStateOf(false) }
    var showTools by rememberSaveable(track.id) { mutableStateOf(!expanded) }
    var search by rememberSaveable(track.id) { mutableStateOf(false) }
    LaunchedEffect(track.id, revision) {
        try {
            val repo = store?.repository() ?: return@LaunchedEffect
            versions = repo.lyricVersions(track.id); selected = repo.selectedLyrics(track.id)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = "Cannot load lyrics: ${e.message}" }
    }
    val lyrics = remember(selected) { selected?.let { runCatching { LrcParser.parse(it.originalText, it.instrumental) }.getOrNull() } }
    val adjustment = store?.state?.value?.details?.get(track.id)?.lyricsAdjustmentMs ?: selected?.timingAdjustmentMs ?: 0
    val timeline = remember(lyrics, adjustment) { lyrics?.let { LyricsTimeline(it, adjustment) } }
    val active = timeline?.activeIndices(positionMs).orEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Lyrics", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (expanded) IconButton(onClick = { showTools = !showTools }) { Icon(PlayerGlyph.More, "Lyrics actions and timing") }
    }
    if (loadError.isNotBlank()) Text(loadError, color = MaterialTheme.colorScheme.error)
    if (showTools) PlayerActions() {
        TextButton(onClick = { edit = true }) { Text("Paste / edit") }
        TextButton(onClick = { beforeImport(); actions.importLyrics(track) }) { Text("Import LRC") }
        TextButton(onClick = { search = true }) { Text("Find lyrics…") }
        if (versions.isNotEmpty()) TextButton(onClick = { showVersions = !showVersions }) { Text("Versions (${versions.size})") }
        selected?.let { value -> TextButton(onClick = { actions.exportLyrics(track, value.originalText) }) { Text("Export") } }
    }
    if (showVersions) Column {
        TextButton(onClick = { send(PlaybackService.LYRICS_SELECT, Bundle().apply { putString("id", track.id.value.toString()) }) }) { Text("Use automatic source priority") }
        versions.forEach { value -> TextButton(onClick = {
            send(PlaybackService.LYRICS_SELECT, Bundle().apply { putString("id", track.id.value.toString()); putString("version", value.id.toString()) })
        }) { Text((if (value.id == selected?.id) "✓ " else "") + when (value.origin) {
            LyricsOrigin.USER_SELECTED -> "Saved / imported"; LyricsOrigin.NEIGHBORING_LRC -> "Neighboring LRC"; LyricsOrigin.EMBEDDED -> "Embedded"
        } + " · " + value.originalText.lineSequence().firstOrNull().orEmpty().take(55)) } }
    }
    when (lyrics?.kind) {
        LyricsKind.INSTRUMENTAL -> Text("Instrumental")
        LyricsKind.PLAIN -> Text(lyrics.plainLines.joinToString("\n"), style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
        LyricsKind.SYNCHRONIZED -> {
            val list = rememberLazyListState()
            LaunchedEffect(active.firstOrNull()) { active.firstOrNull()?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
            LazyColumn(Modifier.fillMaxWidth().height(if (expanded) 400.dp else 300.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(lyrics.lines) { index, line ->
                    Text(line.text.ifBlank { "…" }, modifier = Modifier.fillMaxWidth().clickable { controller?.seekTo(timeline!!.seekPosition(index)) }.padding(vertical = 8.dp),
                        color = if (index in active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (index in active) FontWeight.Bold else FontWeight.Normal,
                        style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge)
                }
            }
            val offset = adjustment
            if (showTools) PlayerActions() {
                TextButton(onClick = { send(PlaybackService.LYRICS_OFFSET, Bundle().apply { putString("id", track.id.value.toString()); putLong("offset", (offset - 250).coerceAtLeast(-LrcParser.MAX_ADJUSTMENT_MS)) }) }) { Text("Earlier 0.25s") }
                TextButton(onClick = { send(PlaybackService.LYRICS_OFFSET, Bundle().apply { putString("id", track.id.value.toString()); putLong("offset", (offset + 250).coerceAtMost(LrcParser.MAX_ADJUSTMENT_MS)) }) }) { Text("Later 0.25s") }
            }
            Text("Track adjustment: ${offset / 1000.0}s. Tap a line to seek.", style = MaterialTheme.typography.bodySmall)
        }
        else -> Text("No lyrics selected. Import a neighboring LRC by choosing the music folder, or paste your own lyrics.")
    }
    if (lyrics != null && lyrics.warnings.isNotEmpty()) Text("${lyrics.warnings.size} malformed lyric tags were kept as text.", style = MaterialTheme.typography.bodySmall)
    if (edit) LyricsEditorDialog(track, selected?.originalText.orEmpty(), positionMs, send, selected?.instrumental == true) { edit = false }
    if (search && store != null) LyricsSearchDialog(track, store, send) { search = false }
    val opener = LocalUriHandler.current
    selected?.takeIf { it.providerId?.let{p->p.startsWith("lrclib:")||p.startsWith("lyrics.ovh:")} == true }?.let { saved ->
        saved.sourceUrl?.let{url->TextButton(onClick={runCatching{opener.openUri(url)}}){Text("Source: "+if(saved.providerId?.startsWith("lrclib:")==true)"LRCLIB" else "Lyrics.ovh")}}
    }
}

@Composable private fun LyricsSearchDialog(track: Track, store: MusicStore, send: (String, Bundle) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("${track.tags.title} ${track.tags.artists.joinToString(" ")}".take(300)) }
    var source by rememberSaveable { mutableStateOf(LyricsSource.LRCLIB) }
    var artist by rememberSaveable { mutableStateOf(track.tags.artists.joinToString(", ").take(300)) }
    var title by rememberSaveable { mutableStateOf(track.tags.title.take(300)) }
    var results by remember { mutableStateOf(emptyList<LyricsChoice>()) }
    var selected by remember { mutableStateOf<LyricsChoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val opener=LocalUriHandler.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Find lyrics") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayerActions(){LyricsSource.entries.forEach{item->
                FilterChip(source==item,{source=item;results=emptyList();selected=null;message=""},enabled=!busy,label={Text(item.label)})}}
            Text("Search sends these details to ${source.label}. Review before saving for offline use. Your music tags stay unchanged.")
            if(source==LyricsSource.LRCLIB)OutlinedTextField(query, { if (it.length <= 300) query = it },enabled=!busy,label = { Text("Title, artist, album or manual query") })
            else {
                Text("Lyrics.ovh finds plain lyrics by artist and title. Timing is not supplied.",style=MaterialTheme.typography.bodySmall)
                OutlinedTextField(artist,{artist=it.take(300)},enabled=!busy,label={Text("Artist")})
                OutlinedTextField(title,{title=it.take(300)},enabled=!busy,label={Text("Song title")})
            }
            Button(enabled = !busy && (if(source==LyricsSource.LRCLIB)query.isNotBlank() else artist.isNotBlank()&&title.isNotBlank()), onClick = {
                busy = true; message = ""; selected = null;results=emptyList()
                scope.launch {
                    try { results = store.searchLyrics(query,source,artist,title); if (results.isEmpty()) message = "No matching versions. Try another source or spelling." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Search failed; saved lyrics are unchanged." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Searching…" else "Search") }
            if (message.isNotBlank()) Text(message)
            results.forEach { choice ->
                val value = choice.candidate
                TextButton(onClick = { selected = choice }) { Text("${value.title} — ${value.artist}\n${value.source.label} · ${if(value.source==LyricsSource.LRCLIB)"${value.album} · ${value.durationSeconds.toInt()}s · " else ""}${if (value.instrumental) "instrumental" else if (!value.synchronized.isNullOrBlank()) "synchronized" else "plain text"}") }
            }
            selected?.candidate?.let { value ->
                Text("Preview: ${value.title}", fontWeight = FontWeight.Bold)
                Text(if(value.source==LyricsSource.LRCLIB)"Duration difference: ${kotlin.math.abs(value.durationSeconds - track.audio.durationMs / 1000.0).toInt()}s. Confirm the right recording/version." else "Duration is unavailable. Confirm these lyrics match your recording.")
                Text(if (value.instrumental) "Instrumental" else value.text.take(12000))
                if (value.text.length > 12000) Text("Preview shortened; the complete chosen text will be cached.")
            }
            HorizontalDivider();Text("Open elsewhere",style=MaterialTheme.typography.titleSmall)
            Text("Spotify and Genius open externally. Their lyrics are not imported here.",style=MaterialTheme.typography.bodySmall)
            PlayerActions(){
                val externalQuery=java.net.URLEncoder.encode(if(source==LyricsSource.LRCLIB)query else "$title $artist","UTF-8").replace("+","%20")
                TextButton(onClick={runCatching{opener.openUri("https://open.spotify.com/search/$externalQuery")}.onFailure{message="Could not open Spotify."}}){Text("Spotify")}
                TextButton(onClick={runCatching{opener.openUri("https://genius.com/search?q=$externalQuery")}.onFailure{message="Could not open Genius."}}){Text("Genius")}
            }
        }
    }, confirmButton = { TextButton(enabled = selected?.candidate?.let { it.instrumental || it.text.isNotBlank() } == true, onClick = {
        send(PlaybackService.LYRICS_PROVIDER, Bundle().apply { putString("id", track.id.value.toString()); putString("receipt", selected!!.receipt) }); dismiss()
    }) { Text("Save chosen version") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable fun LyricsEditorDialog(track: Track, initial: String, position: Long, send: (String, Bundle) -> Unit, initiallyInstrumental: Boolean = false, dismiss: () -> Unit) {
    if (initial.length > 200000) {
        AlertDialog(onDismissRequest = dismiss, title = { Text("Lyrics editor limit") },
            text = { Text("This version is too large for the editor. Your saved lyrics are unchanged. Import a file under 200,000 characters to create another version.") },
            confirmButton = { TextButton(onClick = dismiss) { Text("Close") } })
        return
    }
    var text by rememberSaveable(track.id.value.toString()) { mutableStateOf(initial) }
    var instrumental by rememberSaveable(track.id.value.toString()) { mutableStateOf(initiallyInstrumental) }
    val parsed = remember(text, instrumental) { runCatching { LrcParser.parse(text, instrumental) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Edit lyrics") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save a new app-private version. Existing versions and music files are kept.")
            OutlinedTextField(text, { if (it.length <= 200000) text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 240.dp), label = { Text("Plain text or LRC") })
            TextButton(onClick = { text += "\n" + LrcParser.stamp(position.coerceAtLeast(0), "") }) { Text("Insert current-position timestamp") }
            SettingCheckbox("Instrumental", instrumental) { instrumental = it }
            Text(parsed.getOrNull()?.let { "Preview: ${it.kind.name.lowercase()} · ${it.lines.size} timed lines" } ?: "This version cannot be parsed.")
            if (initial.length > 200000) Text("This version is too large for the editor. Import a smaller file to create another version.")
        }
    }, confirmButton = { TextButton(enabled = parsed.isSuccess && text.length <= 200000, onClick = {
        send(PlaybackService.LYRICS_SAVE, Bundle().apply { putString("id", track.id.value.toString()); putString("text", text); putBoolean("instrumental", instrumental) }); dismiss()
    }) { Text("Save new version") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

private fun tagValue(tags: MusicTags, key: String): String = when (key) {
    "TITLE" -> tags.title; "ARTIST" -> tags.artists.joinToString("\n"); "ALBUMARTIST" -> tags.albumArtist.orEmpty(); "ALBUM" -> tags.album.orEmpty()
    "TRACKNUMBER" -> tags.trackNumber?.toString().orEmpty(); "DISCNUMBER" -> tags.discNumber?.toString().orEmpty()
    "DATE" -> tags.date.orEmpty(); "GENRE" -> tags.genre.orEmpty(); else -> tags.comment.orEmpty()
}
private fun fieldLabel(key: String): String = when (key) {
    "TITLE" -> "Title"; "ARTIST" -> "Artists"; "ALBUMARTIST" -> "Album artist"; "ALBUM" -> "Album"
    "TRACKNUMBER" -> "Track number"; "DISCNUMBER" -> "Disc number"; "DATE" -> "Date"; "GENRE" -> "Genre"; else -> "Comment"
}

@Composable
private fun CandidateThumbnail(url: String?, title: String, store: MusicStore?, modifier: Modifier = Modifier) {
    var path by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(url != null) }
    LaunchedEffect(url, store) {
        if (url == null || store == null) { loading = false; return@LaunchedEffect }
        loading = true
        path = store.artistLookup.thumbnail(url)
        loading = false
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (path != null) {
                AsyncImage(model = File(path!!), contentDescription = "Cover for $title", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(PlayerGlyph.Music, null, Modifier.size(24.dp))
            }
        }
    }
}

@Composable fun TagEditorDialog(tracks: List<Track>, library: LibraryState, store: MusicStore?, send: (String, Bundle) -> Unit,
    actions: FeatureActions, openNative: (String) -> Unit, dismiss: () -> Unit) {
    val draftKey = tracks.joinToString { it.id.value.toString() }
    val enabled = rememberSaveable(draftKey, saver = mapSaver<SnapshotStateMap<String, Boolean>>(
        save = { it.toMap() }, restore = { saved -> mutableStateMapOf<String, Boolean>().apply { saved.forEach { (key, value) -> put(key, value as Boolean) } } }
    )) { mutableStateMapOf<String, Boolean>() }
    val values = rememberSaveable(draftKey, saver = mapSaver<SnapshotStateMap<String, String>>(
        save = { it.toMap() }, restore = { saved -> mutableStateMapOf<String, String>().apply { saved.forEach { (key, value) -> put(key, value as String) } } }
    )) { mutableStateMapOf<String, String>().apply { for (key in TagPatch.KEYS) {
        val shared = tracks.map { tagValue(it.tags, key) }.distinct(); put(key, shared.singleOrNull().orEmpty())
    } } }
    var preview by rememberSaveable(draftKey) { mutableStateOf(false) }
    var manualMode by rememberSaveable(draftKey) { mutableStateOf(false) }

    var searchTitle by rememberSaveable(draftKey) { mutableStateOf(tracks.map { it.tags.title }.distinct().singleOrNull().orEmpty()) }
    var searchArtist by rememberSaveable(draftKey) { mutableStateOf(tracks.flatMap { it.tags.artists }.distinct().firstOrNull().orEmpty()) }
    var selectedProvider by rememberSaveable(draftKey) { mutableStateOf("All") }
    var searchBusy by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<RecordingCandidate>>(emptyList()) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var selectedCandidate by remember(draftKey) { mutableStateOf<RecordingCandidate?>(null) }
    val coverUrl = selectedCandidate?.imageUrl
    var coverResult by remember(selectedCandidate) { mutableStateOf<Result<String>?>(null) }
    var applyArtwork by remember(selectedCandidate) { mutableStateOf(coverUrl != null) }
    val stagedCoverPath = coverResult?.getOrNull()
    val stagingArt = coverUrl != null && coverResult == null
    val waitingForArtwork = applyArtwork && coverUrl != null && stagedCoverPath == null
    val artworkSource = selectedCandidate?.source.orEmpty()
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedCandidate, store) {
        if (coverUrl != null) {
            coverResult = try {
                val lookup = requireNotNull(store).artistLookup
                Result.success(lookup.thumbnail(coverUrl) ?: lookup.stageCoverUrl(coverUrl))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    val providers = listOf("All", "iTunes", "Deezer", "MusicBrainz")
    val fields = TagPatch.KEYS.filter { enabled[it] == true }.associateWith { values[it].orEmpty() }
    val patch = runCatching { TagPatch(fields) }

    AlertDialog(onDismissRequest = dismiss, title = {
        Text(when {
            preview -> "Review ${countLabel(tracks.size, "song")}"
            manualMode -> "Manual edit: ${countLabel(tracks.size, "song")}"
            else -> "Find tags online: ${countLabel(tracks.size, "song")}"
        })
    }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (preview) {
                for (track in tracks) {
                    Text(track.tags.title, fontWeight = FontWeight.Bold)
                    fields.forEach { (key, value) -> Text("${fieldLabel(key)}: ${tagValue(track.tags, key).ifEmpty { "(empty)" }} → ${value.ifEmpty { "(clear)" }}") }
                    if (applyArtwork && stagedCoverPath != null) {
                        Text("Artwork: Replace front cover with online image from ${selectedCandidate?.source ?: "search"}", color = MaterialTheme.colorScheme.primary)
                    }
                }
                patch.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Use in this app keeps the music file unchanged. Embedding prepares a backed-up file preview before writing.")
            } else if (!manualMode) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Search for tags & artwork", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    OutlinedButton(onClick = { manualMode = true }) { Text("Manual edit") }
                }

                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    providers.forEach { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider; searchError = null },
                            label = { Text(provider) }
                        )
                    }
                }

                OutlinedTextField(searchTitle, { searchTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Track title") }, singleLine = true)
                OutlinedTextField(searchArtist, { searchArtist = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Artist") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !searchBusy && (searchTitle.isNotBlank() || searchArtist.isNotBlank()) && store != null,
                        onClick = {
                            scope.launch {
                                searchBusy = true
                                searchError = null
                                try {
                                    val results = store?.artistLookup?.searchRecordings(searchTitle, searchArtist, selectedProvider).orEmpty()
                                    searchResults = results
                                    if (results.isEmpty()) searchError = "No matching tracks found on $selectedProvider. Try refining or switch provider."
                                } catch (e: Exception) {
                                    searchError = e.message ?: "Failed to query $selectedProvider."
                                } finally {
                                    searchBusy = false
                                }
                            }
                        }
                    ) {
                        Text(if (searchBusy) "Searching…" else "Search online")
                    }
                    if (searchBusy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                searchError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                if (selectedCandidate != null) {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f))) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("Selected match (${selectedCandidate?.source ?: "online"})", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                                SuggestionChip(onClick = {}, label = { Text(selectedCandidate?.source.orEmpty(), style = MaterialTheme.typography.labelSmall) })
                            }
                            Text("${values["TITLE"].orEmpty()} · ${values["ARTIST"].orEmpty()}", fontWeight = FontWeight.SemiBold)
                            val selectedMeta = listOfNotNull(values["ALBUM"]?.let { "Album: $it" }, values["TRACKNUMBER"]?.let { "#$it" }, values["DATE"]?.let { "Year: $it" }).joinToString(" · ")
                            if (selectedMeta.isNotBlank()) Text(selectedMeta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            if (selectedCandidate?.imageUrl != null || stagedCoverPath != null) {
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(Modifier.size(60.dp), shape = MaterialTheme.shapes.small) {
                                        if (stagedCoverPath != null) {
                                            AsyncImage(model = File(stagedCoverPath), contentDescription = "Selected cover", modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                        } else if (stagingArt) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                                        } else {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(PlayerGlyph.Music, null, Modifier.size(24.dp)) }
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(checked = applyArtwork, onCheckedChange = { applyArtwork = it })
                                            Text("Apply artwork to ${countLabel(tracks.size, "song")}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                        if (coverResult?.isFailure == true) {
                                            Text("Cover download failed. Uncheck Apply artwork to save only tags.",
                                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                        TextButton(onClick = { actions.pickCover(tracks) }) {
                                            Text("Choose a different image…")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (fields.isNotEmpty()) {
                    Text("Ready to apply (${fields.size} fields set):", fontWeight = FontWeight.SemiBold)
                    fields.forEach { (key, value) -> Text("${fieldLabel(key)}: $value", style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }

                if (searchResults.isNotEmpty()) {
                    Text(if (selectedCandidate != null) "Other online matches:" else "Matching tracks & artwork:", fontWeight = FontWeight.SemiBold)
                    searchResults.forEach { candidate ->
                        val isSelected = candidate.id == selectedCandidate?.id
                        Card(
                            onClick = {
                                selectedCandidate = candidate
                                values["TITLE"] = candidate.title; enabled["TITLE"] = true
                                values["ARTIST"] = candidate.artist; enabled["ARTIST"] = true
                                candidate.album?.let { values["ALBUM"] = it; enabled["ALBUM"] = true }
                                candidate.trackNumber?.let { values["TRACKNUMBER"] = it; enabled["TRACKNUMBER"] = true }
                                candidate.date?.let { values["DATE"] = it; enabled["DATE"] = true }
                                candidate.disambiguation?.let { if (values["GENRE"].isNullOrBlank()) { values["GENRE"] = it; enabled["GENRE"] = true } }
                            },
                            colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CandidateThumbnail(candidate.imageUrl, candidate.title, store, Modifier.size(56.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(candidate.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                        SuggestionChip(onClick = {}, label = { Text(candidate.source, style = MaterialTheme.typography.labelSmall) })
                                    }
                                    Text(candidate.artist, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val meta = listOfNotNull(
                                        candidate.album?.let { "Album: $it" },
                                        candidate.trackNumber?.let { "#$it" },
                                        candidate.date?.let { "Year: $it" },
                                        candidate.country
                                    ).joinToString(" · ")
                                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { actions.pickCover(tracks) }) { Text("Choose artwork from phone") }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Manual tag editing", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    OutlinedButton(onClick = { manualMode = false }) { Text("Search online") }
                }
                Text("Only checked fields change. Clear explicitly to remove a value. Use one artist per line.")
                TagPatch.KEYS.forEach { key ->
                    SettingCheckbox("Change ${fieldLabel(key).lowercase()}", enabled[key] == true) { enabled[key] = it }
                    OutlinedTextField(values[key].orEmpty(), { values[key] = it; enabled[key] = true }, modifier = Modifier.fillMaxWidth(), singleLine = key !in setOf("ARTIST", "COMMENT"),
                        label = { Text(fieldLabel(key) + if (tracks.map { tagValue(it.tags, key) }.distinct().size > 1) " (mixed)" else "") })
                    TextButton(onClick = { values[key] = ""; enabled[key] = true }) { Text("Clear ${fieldLabel(key).lowercase()}") }
                }
                TextButton(onClick = { actions.pickCover(tracks) }) { Text("Choose artwork (separate edit)") }
            }
            patch.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (preview) Text("Use in this app keeps the music file unchanged. Embedding prepares a backed-up file preview before writing.")
        }
    }, confirmButton = {
        if (!preview) TextButton(enabled = fields.isNotEmpty() && patch.isSuccess && !waitingForArtwork, onClick = { preview = true }) { Text("Preview") }
        else TextButton(enabled = !waitingForArtwork, onClick = {
            send(PlaybackService.APP_TAGS, Bundle().apply { putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() })); putString("patch", JSONObject(fields).toString()) })
            if (applyArtwork && stagedCoverPath != null) {
                send(PlaybackService.APP_ART, Bundle().apply {
                    putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() }))
                    putString("uri", Uri.fromFile(File(stagedCoverPath)).toString())
                    putString("sourceUrl", coverUrl.orEmpty())
                    putString("credit", artworkSource)
                })
            }
            dismiss()
        }) { Text("Use in this app") }
    }, dismissButton = {
        Column(horizontalAlignment = Alignment.End) {
            if (preview) {
                TextButton(enabled = !library.scanning && !waitingForArtwork, onClick = {
                    val batch = UUID.randomUUID().toString()
                    send(PlaybackService.EDIT_PREPARE, Bundle().apply {
                        putString("batch", batch)
                        putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() }))
                        putString("patch", JSONObject(fields).toString())
                        if (applyArtwork && stagedCoverPath != null) {
                            putString("coverUri", Uri.fromFile(File(stagedCoverPath)).toString())
                            putInt("coverMode", 1)
                        }
                    })
                    dismiss(); openNative(batch)
                }) { Text("Embed in files…") }
                TextButton(onClick = { preview = false }) { Text("Back") }
            } else {
                TextButton(onClick = dismiss) { Text("Cancel") }
            }
        }
    })
}

@Composable fun NativePreviewDialog(batch: String, library: LibraryState, send: (String, Bundle) -> Unit, actions: FeatureActions, dismiss: () -> Unit) {
    val rows = library.edits.filter { it.batchId == batch }
    val prepared = rows.filter { it.phase == "PREPARED" }
    val writing = library.editBusy
    fun close() {
        if (!writing && prepared.isNotEmpty()) send(PlaybackService.EDIT_DISCARD, Bundle().apply { putStringArrayList("edits", ArrayList(prepared.map { it.id })) })
        dismiss()
    }
    AlertDialog(onDismissRequest = ::close, title = { Text("Music-file edit preview") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (rows.isEmpty()) Text(if (writing) "Preparing original backups and checking the edited copies…" else "No writable previews were created. Review the error or choose Use in this app.")
            if (writing && rows.isNotEmpty()) Text("Working on these files. You can review results in Settings.")
            if (library.error.isNotBlank()) Text(library.error, color = MaterialTheme.colorScheme.error)
            rows.forEach { row ->
                Text(row.displayName, fontWeight = FontWeight.Bold); Text("${row.phase}: ${row.result}")
                if (row.phase == "PREPARED") {
                    val patch = runCatching { JSONObject(row.patch) }.getOrDefault(JSONObject())
                    val old = runCatching { JSONObject(row.beforeJson).getJSONObject("properties") }.getOrDefault(JSONObject())
                    val next = runCatching { JSONObject(row.afterJson).getJSONObject("properties") }.getOrDefault(JSONObject())
                    patch.keys().asSequence().filter { it in TagPatch.KEYS }.forEach { key -> Text("$key: ${old.optJSONArray(key) ?: "(empty)"} → ${next.optJSONArray(key) ?: "(clear)"}") }
                    if (row.coverMode != 0) Text(if (row.coverMode == 1) "Replace front cover" else "Remove front cover")
                    Text("Encoded audio/configuration verified unchanged in the staged copy.", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (prepared.isNotEmpty()) Text("Keep originals for 7 days after completion. Android may ask permission for these files. Each file is verified again after saving.")
        }
    }, confirmButton = { TextButton(enabled = prepared.isNotEmpty() && !writing, onClick = { actions.commit(prepared) }) { Text("Write ${prepared.size} prepared file(s)") } },
        dismissButton = { TextButton(onClick = ::close) { Text(if (prepared.isEmpty() || writing) "Close" else "Cancel edits") } })
}

@Composable fun EditHistory(library: LibraryState, openBatch: (String) -> Unit, actions: FeatureActions) {
    if (library.edits.isEmpty()) Text("No music-file edits yet.")
    library.edits.take(30).forEach { row ->
        Text(row.displayName, fontWeight = FontWeight.Bold)
        Text("${row.phase}: ${row.result}")
        when (row.phase) {
            "PREPARED" -> TextButton(onClick = { openBatch(row.batchId) }) { Text("Review prepared edit") }
            "VERIFIED" -> TextButton(onClick = { actions.undo(row) }) { Text("Undo — restore original") }
            "NEEDS_RECOVERY" -> TextButton(onClick = { actions.recovery(row) }) { Text("Review original recovery") }
        }
    }
}
