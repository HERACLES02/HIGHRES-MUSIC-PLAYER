@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.os.Bundle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.localmusic.core.Track
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val LocalSongRemoval=staticCompositionLocalOf<(Track,Boolean)->Unit>{error("Song removal host missing")}

/** Activity recreation keeps the review; only a confirmed command can delete a file. */
class SongRemovalUi:ViewModel() {
    var track by mutableStateOf<Track?>(null);private set
    var deleting by mutableStateOf(false);private set
    var preview by mutableStateOf<JSONObject?>(null);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    fun review(value:Track,deleteFile:Boolean,controller:MediaController?) {
        if(busy)return
        track=value;deleting=deleteFile;preview=null;error=""
        if(!deleteFile)return
        busy=true
        viewModelScope.launch {
            try {
                val result=command(controller,PlaybackService.SONG_DELETE_PREVIEW,Bundle().apply{putString("id",value.id.value.toString())})
                preview=JSONObject(requireNotNull(result.extras.getString("preview")))
            } catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message?:"The file could not be checked."}
            finally{busy=false}
        }
    }
    fun confirm(controller:MediaController?) {
        val value=track?:return
        if(busy || (deleting && preview==null))return
        busy=true;error=""
        val action=if(deleting)PlaybackService.SONG_DELETE else PlaybackService.SONG_HIDE
        val args=Bundle().apply{putString("id",value.id.value.toString());preview?.let{putString("token",it.getString("token"))}}
        viewModelScope.launch {
            try {command(controller,action,args);track=null;preview=null}
            catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message?:"Result unavailable. Check the library before trying again.";if(deleting)preview=null}
            finally{busy=false}
        }
    }
    fun dismiss(controller:MediaController?) {
        if(busy)return
        val token=preview?.optString("token")
        track=null;preview=null;error=""
        if(token!=null)viewModelScope.launch {
            runCatching{command(controller,PlaybackService.SONG_DELETE_CANCEL,Bundle().apply{putString("token",token)})}
        }
    }
    private suspend fun command(controller:MediaController?,action:String,args:Bundle):SessionResult=suspendCancellableCoroutine { continuation ->
        if(controller==null){continuation.resumeWithException(IllegalStateException("Playback disconnected. Close this dialog and try again."));return@suspendCancellableCoroutine}
        val future=controller.sendCustomCommand(SessionCommand(action,Bundle.EMPTY),args)
        continuation.invokeOnCancellation{future.cancel(false)}
        future.addListener({
            if(continuation.isActive)try {
                val result=future.get()
                check(result.resultCode==SessionResult.RESULT_SUCCESS){result.extras.getString("message")?:"The operation could not be completed."}
                continuation.resume(result)
            }catch(e:Exception){continuation.resumeWithException(e)}
        },Executor{it.run()})
    }
}

@Composable
fun SongRemovalHost(state:SongRemovalUi,controller:MediaController?,content:@Composable ()->Unit) {
    CompositionLocalProvider(LocalSongRemoval provides { track,delete -> state.review(track,delete,controller) }) {content()}
    state.track?.let { track ->
        AlertDialog(onDismissRequest={state.dismiss(controller)},
            title={Text(if(state.deleting)"Delete file from phone?" else "Remove from library?")},
            text={Column(Modifier.heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(track.tags.title)
                if(state.deleting) {
                    Text("This permanently deletes the file below. There is no undo. Other copies are not deleted.")
                    state.preview?.let{Text(it.getString("name"));Text(it.getString("location"),style=MaterialTheme.typography.bodySmall)}
                } else Text("The music file stays on your device. Restore this song from Settings > Removed songs.")
                Text("Playlist and queue entries are kept as unavailable. Removing the current song pauses playback.",style=MaterialTheme.typography.bodySmall)
                if(state.busy){LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(state.preview==null&&state.deleting)"Checking file..." else "Working...")}
                if(state.error.isNotBlank())Text(state.error,color=MaterialTheme.colorScheme.error)
            }},
            confirmButton={TextButton(enabled=!state.busy&&(!state.deleting||state.preview!=null),onClick={state.confirm(controller)}) {
                Text(if(state.deleting)"Delete file" else "Remove",color=if(state.deleting)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }},
            dismissButton={TextButton(enabled=!state.busy,onClick={state.dismiss(controller)}){Text("Cancel")}})
    }
}

@Composable
fun RemovedSongsDialog(library:LibraryState,ready:Boolean,send:(String,Bundle)->Unit,dismiss:()->Unit) {
    AlertDialog(onDismissRequest=dismiss,title={Text("Removed songs")},text={Column {
        Text("Restore returns the song to your library. It cannot recover a deleted file.")
        if(library.removedTracks.isEmpty())Text("No removed songs.")
        LazyColumn(Modifier.heightIn(max=360.dp)) {
            items(library.removedTracks,key={it.id.value.toString()}) { track ->
                ListItem(headlineContent={Text(track.tags.title)},
                    supportingContent={Text(if(track.availability==com.localmusic.core.Availability.AVAILABLE)"Removed from library" else "File unavailable")},
                    trailingContent={TextButton(enabled=ready&&!library.editBusy,onClick={send(PlaybackService.SONG_RESTORE,Bundle().apply{putString("id",track.id.value.toString())})}){Text("Restore")}})
            }
        }
    }},confirmButton={TextButton(onClick=dismiss){Text("Close")}})
}
