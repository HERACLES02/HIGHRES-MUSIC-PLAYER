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
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import coil3.compose.AsyncImage
import com.localmusic.core.*
import com.localmusic.data.FileEditRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalUriHandler
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FeatureActions(val pickFolder: () -> Unit, val pickCover: (List<Track>) -> Unit,
    val importLyrics: (Track) -> Unit, val exportLyrics: (Track, String) -> Unit,
    val commit: (List<FileEditRow>) -> Unit, val undo: (FileEditRow) -> Unit, val recovery: (FileEditRow) -> Unit)

@Composable fun CoverArt(path: String?, title: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        if (path != null) AsyncImage(model = File(path), contentDescription = "Cover for $title", contentScale = ContentScale.Fit)
        else Box(Modifier.padding(10.dp)) { Text("♫", style = MaterialTheme.typography.headlineMedium) }
    }
}

@Composable fun LyricsPanel(track: Track, store: MusicStore?, revision: Long, positionMs: Long, controller: MediaController?,
    send: (String, Bundle) -> Unit, actions: FeatureActions) {
    var versions by remember(track.id) { mutableStateOf(emptyList<SavedLyrics>()) }
    var selected by remember(track.id) { mutableStateOf<SavedLyrics?>(null) }
    var loadError by remember(track.id) { mutableStateOf("") }
    var edit by remember(track.id) { mutableStateOf(false) }
    var showVersions by remember(track.id) { mutableStateOf(false) }
    var search by remember(track.id) { mutableStateOf(false) }
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
    Text("Lyrics", style = MaterialTheme.typography.titleLarge)
    if (loadError.isNotBlank()) Text(loadError, color = MaterialTheme.colorScheme.error)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { edit = true }) { Text("Paste / edit") }
        TextButton(onClick = { actions.importLyrics(track) }) { Text("Import LRC") }
        TextButton(onClick = { search = true }) { Text("Search LRCLIB…") }
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
        LyricsKind.PLAIN -> Text(lyrics.plainLines.joinToString("\n"))
        LyricsKind.SYNCHRONIZED -> {
            val list = rememberLazyListState()
            LaunchedEffect(active.firstOrNull()) { active.firstOrNull()?.let { list.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
            LazyColumn(Modifier.fillMaxWidth().height(300.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(lyrics.lines) { index, line ->
                    Text(line.text.ifBlank { "…" }, modifier = Modifier.fillMaxWidth().clickable { controller?.seekTo(timeline!!.seekPosition(index)) }.padding(vertical = 8.dp),
                        color = if (index in active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (index in active) FontWeight.Bold else FontWeight.Normal)
                }
            }
            val offset = adjustment
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    selected?.takeIf { it.providerId?.startsWith("lrclib:") == true }?.sourceUrl?.let { url ->
        TextButton(onClick = { opener.openUri(url) }) { Text("Source: LRCLIB") }
    }
}

@Composable private fun LyricsSearchDialog(track: Track, store: MusicStore, send: (String, Bundle) -> Unit, dismiss: () -> Unit) {
    var query by remember { mutableStateOf("${track.tags.title} ${track.tags.artists.joinToString(" ")}") }
    var results by remember { mutableStateOf(emptyList<LyricsChoice>()) }
    var selected by remember { mutableStateOf<LyricsChoice?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = dismiss, title = { Text("Find lyrics on LRCLIB") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Search sends this query to LRCLIB. Only the version you choose is saved for offline use; your music tags stay unchanged.")
            OutlinedTextField(query, { if (it.length <= 300) query = it }, label = { Text("Title, artist, album or manual query") })
            Button(enabled = !busy && query.isNotBlank(), onClick = {
                busy = true; message = ""; selected = null
                scope.launch {
                    try { results = store.searchLyrics(query); if (results.isEmpty()) message = "No matching versions. Try another query." }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { message = e.message ?: "Search failed; saved lyrics are unchanged." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Searching…" else "Search") }
            if (message.isNotBlank()) Text(message)
            results.forEach { choice ->
                val value = choice.candidate
                TextButton(onClick = { selected = choice }) { Text("${value.title} — ${value.artist}\n${value.album} · ${value.durationSeconds.toInt()}s · ${if (value.instrumental) "instrumental" else if (!value.synchronized.isNullOrBlank()) "synchronized" else "plain text"}") }
            }
            selected?.candidate?.let { value ->
                Text("Preview: ${value.title}", fontWeight = FontWeight.Bold)
                Text("Duration difference: ${kotlin.math.abs(value.durationSeconds - track.audio.durationMs / 1000.0).toInt()}s. Confirm the right recording/version.")
                Text(if (value.instrumental) "Instrumental" else value.text.take(12000))
                if (value.text.length > 12000) Text("Preview shortened; the complete chosen text will be cached.")
            }
        }
    }, confirmButton = { TextButton(enabled = selected?.candidate?.let { it.instrumental || it.text.isNotBlank() } == true, onClick = {
        send(PlaybackService.LYRICS_PROVIDER, Bundle().apply { putString("id", track.id.value.toString()); putString("receipt", selected!!.receipt) }); dismiss()
    }) { Text("Save chosen version") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Close") } })
}

@Composable fun LyricsEditorDialog(track: Track, initial: String, position: Long, send: (String, Bundle) -> Unit, initiallyInstrumental: Boolean = false, dismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var instrumental by remember { mutableStateOf(initiallyInstrumental) }
    val parsed = remember(text, instrumental) { runCatching { LrcParser.parse(text, instrumental) } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Lyrics · ${track.tags.title}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save a new app-private version. Existing versions and music files are kept.")
            OutlinedTextField(text, { if (it.length <= 200000) text = it }, modifier = Modifier.fillMaxWidth().height(260.dp), label = { Text("Plain text or LRC") })
            TextButton(onClick = { text += "\n" + LrcParser.stamp(position.coerceAtLeast(0), "") }) { Text("Insert current-position timestamp") }
            Row { Checkbox(instrumental, { instrumental = it }); Text("Instrumental") }
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

@Composable fun TagEditorDialog(tracks: List<Track>, library: LibraryState, send: (String, Bundle) -> Unit,
    actions: FeatureActions, openNative: (String) -> Unit, dismiss: () -> Unit) {
    val enabled = remember { mutableStateMapOf<String, Boolean>() }
    val values = remember { mutableStateMapOf<String, String>().apply { for (key in TagPatch.KEYS) {
        val shared = tracks.map { tagValue(it.tags, key) }.distinct(); put(key, shared.singleOrNull().orEmpty())
    } } }
    var preview by remember { mutableStateOf(false) }
    val fields = TagPatch.KEYS.filter { enabled[it] == true }.associateWith { values[it].orEmpty() }
    val patch = runCatching { TagPatch(fields) }
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (preview) "Review ${tracks.size} song(s)" else "Edit ${tracks.size} song(s)") }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!preview) {
                Text("Only checked fields change. Clear explicitly to remove a value. Use one artist per line.")
                TagPatch.KEYS.forEach { key ->
                    Row { Checkbox(enabled[key] == true, { enabled[key] = it }); Text("Change ${fieldLabel(key).lowercase()}") }
                    OutlinedTextField(values[key].orEmpty(), { values[key] = it; enabled[key] = true }, modifier = Modifier.fillMaxWidth(), singleLine = key !in setOf("ARTIST", "COMMENT"),
                        label = { Text(if (tracks.map { tagValue(it.tags, key) }.distinct().size > 1) "Mixed — unchanged unless checked" else "Value") })
                    TextButton(onClick = { values[key] = ""; enabled[key] = true }) { Text("Clear this field") }
                }
                TextButton(onClick = { actions.pickCover(tracks) }) { Text("Choose artwork (separate edit)") }
            } else for (track in tracks) {
                Text(track.tags.title, fontWeight = FontWeight.Bold)
                fields.forEach { (key, value) -> Text("${fieldLabel(key)}: ${tagValue(track.tags, key).ifEmpty { "(empty)" }} → ${value.ifEmpty { "(clear)" }}") }
            }
            patch.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (preview) Text("Use in this app keeps the music file unchanged. Embedding prepares a backed-up file preview before writing.")
        }
    }, confirmButton = {
        if (!preview) TextButton(enabled = fields.isNotEmpty() && patch.isSuccess, onClick = { preview = true }) { Text("Preview") }
        else TextButton(onClick = {
            send(PlaybackService.APP_TAGS, Bundle().apply { putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() })); putString("patch", JSONObject(fields).toString()) }); dismiss()
        }) { Text("Use in this app") }
    }, dismissButton = {
        Row {
            if (preview) TextButton(enabled = !library.scanning, onClick = {
                val batch = UUID.randomUUID().toString()
                send(PlaybackService.EDIT_PREPARE, Bundle().apply { putString("batch", batch); putStringArrayList("ids", ArrayList(tracks.map { it.id.value.toString() })); putString("patch", JSONObject(fields).toString()) })
                dismiss(); openNative(batch)
            }) { Text("Embed in files…") }
            TextButton(onClick = dismiss) { Text("Cancel") }
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
    Text("Edits and recovery", style = MaterialTheme.typography.titleLarge)
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
