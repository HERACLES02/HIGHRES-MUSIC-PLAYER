package com.localmusic.player

import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.localmusic.core.ThemeAccent

@Composable
fun SettingsPanel(library: LibraryState, preferences: MusicPreferences, ready: Boolean,
    backupUi: BackupUiState, actions: FeatureActions, send: (String, Bundle) -> Unit,
    audio: () -> Unit, widgets: () -> Unit, scan: () -> Unit, notifications: () -> Unit,
    report: () -> Unit, licenses: () -> Unit, openEdit: (String) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard("Sound & widgets") {
            FilledTonalButton(onClick = audio, enabled = ready, modifier = Modifier.fillMaxWidth()) {
                Icon(PlayerGlyph.Settings, null); Spacer(Modifier.width(8.dp)); Text("Audio controls & equalizer")
            }
            OutlinedButton(onClick = widgets, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Home-screen widgets") }
            Text("Find artist pages and album covers in your library.", style = MaterialTheme.typography.bodySmall)
        }
        SettingsCard("Appearance") {
            Text("Theme",style=MaterialTheme.typography.titleMedium)
            PlayerActions {
                listOf("System", "Light", "Dark", "AMOLED").forEach { name ->
                    FilterChip(selected = preferences.appearance == name, enabled = ready, onClick = {
                        send(PlaybackService.PREFERENCES, Bundle().apply {
                            putString("appearance", name); putBoolean("autoScan", preferences.autoScan)
                        })
                    }, label = { Text(name) })
                }
            }
            Text("System follows your phone. AMOLED uses black backgrounds.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Accent color",style=MaterialTheme.typography.titleMedium)
            PlayerActions {
                ThemeAccent.entries.forEach { accent ->
                    FilterChip(selected=preferences.accent==accent,enabled=ready,
                        onClick={send(PlaybackService.THEME_ACCENT,Bundle().apply{putString("accent",accent.name)})},
                        label={Text(accent.label)},leadingIcon={
                            Box(Modifier.size(14.dp).background(accentPreviewColor(accent),CircleShape)
                                .border(1.dp,MaterialTheme.colorScheme.outlineVariant,CircleShape))
                        })
                }
            }
        }
        SettingsCard("Now Playing artwork") {
            Text("Choose how much space the album cover uses.", style = MaterialTheme.typography.bodyMedium)
            PlayerActions {
                listOf("Compact", "Large", "Full width").forEach { size ->
                    FilterChip(selected = preferences.artworkSize == size, enabled = ready,
                        onClick = { send(PlaybackService.ARTWORK_SIZE, Bundle().apply { putString("size", size) }) },
                        label = { Text(size) })
                }
            }
        }
        SettingsCard("Music library") {
            Text("Only chosen folders and their subfolders are scanned. Add a folder below to include its music.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = scan, enabled = ready && !library.scanning, modifier = Modifier.fillMaxWidth()) {
                Icon(PlayerGlyph.Scan, null); Spacer(Modifier.width(8.dp)); Text("Scan chosen folders")
            }
            OutlinedButton(onClick = actions.pickFolder, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Choose music folder / SD card") }
            library.folders.forEach { folder ->
                val path=Uri.parse(folder.treeUri).lastPathSegment.orEmpty()
                val storage=if(path.startsWith("primary:"))"Phone" else "Storage"
                Text("$storage / ${folderDisplayPath(path)}", style = MaterialTheme.typography.bodySmall)
            }
            SettingSwitch("Rescan chosen folders when music changes", preferences.autoScan, ready) { value ->
                send(PlaybackService.PREFERENCES, Bundle().apply {
                    putString("appearance", preferences.appearance); putBoolean("autoScan", value)
                })
            }
            if (library.message.isNotBlank()) Text(library.message, style = MaterialTheme.typography.bodySmall)
        }
        SettingsCard("Backup & restore") {
            Text("Save playlists, lyrics, chosen covers, settings and the paused queue. Music files are stored separately.")
            Text("Playback pauses during backup or restore.", style = MaterialTheme.typography.bodySmall)
            PlayerActions {
                FilledTonalButton(onClick = actions.createBackup, enabled = ready && !actions.backupBusy && !library.scanning && !library.editBusy) { Text("Create backup") }
                OutlinedButton(onClick = actions.restoreBackup, enabled = ready && !actions.backupBusy && !library.scanning && !library.editBusy) { Text("Restore backup") }
            }
            if (actions.backupBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (backupUi.message.isNotBlank()) {
                Row(Modifier.fillMaxWidth()) {
                    Text(backupUi.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (!actions.backupBusy) IconButton(onClick = { backupUi.message = "" }) { Icon(PlayerGlyph.Close, "Dismiss backup status") }
                }
            }
        }
        SettingsCard("Playback & support") {
            Text("Normal playback", style = MaterialTheme.typography.titleMedium)
            Text("Audio settings include an optional Android USB high-resolution route. Experimental Direct USB remains separate.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = notifications, modifier = Modifier.fillMaxWidth()) { Text("Playback notifications") }
            OutlinedButton(onClick = report, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("Save playback report") }
        }
        SettingsCard("Music-file edits") { EditHistory(library, openEdit, actions) }
        SettingsCard("About Local Music") {
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
            Text("Your music, offline. No account needed.")
            Text("Online artist and lyric searches run only when you request them. Saved results remain available offline.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = licenses) { Text("Open-source notices") }
        }
    }
}
