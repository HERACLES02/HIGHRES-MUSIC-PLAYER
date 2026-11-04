package com.localmusic.player

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class BackupUiState : ViewModel() {
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var token by mutableStateOf<String?>(null)
    var summary by mutableStateOf("")
}

@Composable
fun BackupRestoreDialog(ui: BackupUiState, restore: () -> Unit, cancel: () -> Unit) {
    if (ui.token == null) return
    AlertDialog(onDismissRequest = { if (!ui.busy) cancel() }, title = { Text("Restore this backup?") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(ui.summary)
            Text("Backed-up playlists, favorites, app-only edits, lyrics, settings and the paused queue replace their saved versions. Unrelated newer songs and playlists stay.")
            Text("Saved artist matches and EQ presets are merged; unrelated newer entries stay.")
            Text("Music files are not included or changed. On a new installation, allow music access, choose your folders again and scan. Conflicting song identities cancel the restore.")
            if (ui.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(onClick = restore, enabled = !ui.busy) { Text(if (ui.busy) "Restoring…" else "Restore app data") } },
        dismissButton = { TextButton(onClick = cancel, enabled = !ui.busy) { Text("Cancel") } })
}
