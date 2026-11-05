package com.localmusic.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localmusic.core.*

@Composable
fun PlaylistFileDialogs(ui: PlaylistUiState, ready: Boolean, confirmImport: () -> Unit,
    export: (Playlist, PlaylistFileFormat) -> Unit) {
    ui.importPreview.value?.let { preview ->
        val matched = preview.matches.count { it.id != null }
        AlertDialog(onDismissRequest = { if (!ui.fileBusy.value) ui.importPreview.value = null },
            title = { Text("Import playlist") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(ui.importName.value, { ui.importName.value = it.take(Playlists.MAX_NAME_CHARS) },
                    enabled = !ui.fileBusy.value, singleLine = true, label = { Text("New playlist name") })
                Text("$matched matched · ${preview.matches.size - matched} skipped. Order and repeated songs stay intact.")
                Text("Only songs already scanned into your library can be added. Missing or ambiguous paths and online links are skipped. Unavailable songs stay saved for later.")
                Text("Paths are case-sensitive. Relative paths use the playlist folder when Android provides it; otherwise only unique library path endings match. No music is copied or downloaded.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    itemsIndexed(preview.matches) { index, match ->
                        Text("${index + 1}. ${match.reason}: ${match.reference}" + (match.id?.let { " → ${preview.titles[it]}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (ui.fileMessage.value.isNotBlank()) Text(ui.fileMessage.value)
            } },
            confirmButton = { TextButton(enabled = ready && !ui.fileBusy.value && (matched > 0 || preview.matches.isEmpty()) && ui.importName.value.isNotBlank(), onClick = confirmImport) { Text("Import $matched entries") } },
            dismissButton = { TextButton(enabled = !ui.fileBusy.value, onClick = { ui.importPreview.value = null }) { Text("Cancel") } })
    }
    ui.exportTarget.value?.let { playlist ->
        AlertDialog(onDismissRequest = { ui.exportTarget.value = null }, title = { Text("Export playlist") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium)
                Text("Choose a format, then choose where to save it. All formats use UTF-8 and keep order and repeated songs.")
                Text("This saves song locations, not the music. Local storage paths require the same folder layout. Android content addresses may work only on this phone and with existing access.")
                PlaylistFileFormat.entries.forEach { format ->
                    OutlinedButton(enabled = !ui.fileBusy.value && ui.pendingExport.value == null, onClick = {
                        ui.exportTarget.value = null; export(playlist, format)
                    }, modifier = Modifier.fillMaxWidth()) { Text(format.name + if (format == PlaylistFileFormat.M3U8) " (recommended)" else "") }
                }
            } }, confirmButton = { TextButton(onClick = { ui.exportTarget.value = null }) { Text("Cancel") } })
    }
}
