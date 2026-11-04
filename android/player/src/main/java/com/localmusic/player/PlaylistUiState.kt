package com.localmusic.player

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.localmusic.core.Playlist
import com.localmusic.core.TrackId
import com.localmusic.core.PlaylistFileFormat
import androidx.lifecycle.viewModelScope
import android.net.Uri
import kotlinx.coroutines.*

/** Unsubmitted playlist work survives rotation, but is discarded when this Activity is finished.
 * Keep potentially large song selections in memory, outside Android's saved-state Bundle.
 * No Activity, controller, repository or callback is retained and no command runs on restoration.
 * Process death discards drafts; saved playlists remain owned by the repository.
 */
class PlaylistUiState : ViewModel() {
    val detail = mutableStateOf<Playlist?>(null)
    val picker = mutableStateOf<List<TrackId>?>(null)
    val pickerName = mutableStateOf("")
    val naming = mutableStateOf<Playlist?>(null)
    val creating = mutableStateOf(false)
    val namingText = mutableStateOf("")
    val deleting = mutableStateOf<Playlist?>(null)
    val importPreview = mutableStateOf<PlaylistImportPreview?>(null)
    val importName = mutableStateOf("")
    val exportTarget = mutableStateOf<Playlist?>(null)
    val pendingExport = mutableStateOf<PlaylistExportDocument?>(null)
    val fileBusy = mutableStateOf(false)
    val fileMessage = mutableStateOf("")

    fun readPlaylist(app: MusicApplication, uri: Uri) {
        if (fileBusy.value) return
        fileBusy.value = true; fileMessage.value = "Reading playlist…"
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { PlaylistFileTransfer.read(app, uri, app.storage.await().state.value) }
                importName.value = result.first; importPreview.value = result.second
                fileMessage.value = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fileMessage.value = "Import failed: ${e.message}" }
            finally { fileBusy.value = false }
        }
    }

    suspend fun prepareExport(app: MusicApplication, playlist: Playlist, format: PlaylistFileFormat): String? {
        if (fileBusy.value || pendingExport.value != null) return null
        fileBusy.value = true; fileMessage.value = "Preparing playlist file…"
        return try {
            val document = withContext(Dispatchers.IO) { PlaylistFileTransfer.export(app, playlist.id, format) }
            pendingExport.value = document; fileMessage.value = "Choose where to save ${document.filename}."
            document.filename
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { fileMessage.value = "Export failed: ${e.message}"; null }
        finally { fileBusy.value = false }
    }

    fun savePlaylist(app: MusicApplication, uri: Uri?) {
        val document = pendingExport.value
        pendingExport.value = null
        if (uri == null) { fileMessage.value = "Export cancelled."; return }
        if (document == null) { fileMessage.value = "Export draft expired. Choose Export again."; return }
        fileBusy.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = document.text.toByteArray(Charsets.UTF_8)
                    requireNotNull(app.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                    val saved = requireNotNull(app.contentResolver.openInputStream(uri)).use { readLimited(it, com.localmusic.core.PlaylistFiles.MAX_BYTES) }
                    check(bytes.contentEquals(saved)) { "Saved file verification failed." }
                }
                fileMessage.value = "Saved and verified ${document.filename}. Music files were not copied."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { fileMessage.value = "Export failed; the selected file may be incomplete: ${e.message}" }
            finally { fileBusy.value = false }
        }
    }
}
