@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.localmusic.diagnostics

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class BaselineActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private lateinit var future: ListenableFuture<MediaController>
    private var message by mutableStateOf("Connecting to the local playback service…")
    private var pendingExport: String? = null
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report = pendingExport
        pendingExport = null
        if (uri != null && report != null) lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                (contentResolver.openOutputStream(uri) ?: error("Cannot open selected destination")).bufferedWriter().use { it.write(report) }
            } }
            message = if (result.isSuccess) "Baseline report saved." else "Save failed: ${result.exceptionOrNull()?.message}"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        future = MediaController.Builder(this, SessionToken(this, ComponentName(this, BaselineService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it; message = "Ready. Playback starts only when you choose a fixture." }
            .onFailure { message = "Could not connect: ${it.message}. Reopen this screen." } }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var confirmed by rememberSaveable { mutableStateOf(false) }
                    var loop by rememberSaveable { mutableStateOf(intent.getBooleanExtra("channelCheck",false)) }
                    var channelCheck by rememberSaveable { mutableStateOf(intent.getBooleanExtra("channelCheck",false)) }
                    var preset by rememberSaveable { mutableStateOf("Standard") }
                    var notes by rememberSaveable { mutableStateOf("") }
                    // Reading this counter refreshes evidence when the service records an event.
                    val revision = BaselineEvidence.revision
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Normal-route baseline", style = MaterialTheme.typography.headlineMedium)
                        Text("Bundled WAV / FLAC. No PC or internet needed. This screen uses Android's normal audio path; experimental Direct USB is a separate test.")
                        Text("1. Connect M2. Select its Standard physical preset. Lower Android's media volume before listening. Start with earphones out of your ears.")
                        Text("Preset recorded with each new test: $preset")
                        Row {
                            OutlinedButton(onClick = { preset = "Standard" }) { Text("Standard") }
                            OutlinedButton(onClick = { preset = "Other / unknown" }) { Text("Other / unknown") }
                        }
                        Row {
                            Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                            Text("I lowered media volume and checked the cable preset.")
                        }
                        Row {
                            Checkbox(checked = loop, onCheckedChange = { loop = it })
                            Text("Repeat fixture (applies to next test)")
                        }
                        Row {
                            Checkbox(checked=channelCheck,onCheckedChange={ channelCheck=it })
                            Text("Equal-tone channel comparison (440 Hz, silent gaps)")
                        }
                        Text("2. Choose a test. "+DiagnosticFixture.pattern(if(channelCheck) "channels-16-44100.wav" else "stereo-16-44100.wav"))
                        Text("Each file lasts 6 seconds, peak −42 dBFS. Compare left/right balance within each route; absolute volume need not match between routes.")
                        for (bits in listOf(16, 24)) for (rate in listOf(44100, 48000, 88200, 96000)) {
                            Text("Stereo $bits-bit / $rate Hz")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (format in if(channelCheck) listOf("wav") else listOf("wav", "flac")) Button(enabled = confirmed && controller != null, onClick = {
                                    val name = if(channelCheck) "channels-$bits-$rate.wav" else "stereo-$bits-$rate.$format"
                                    controller?.sendCustomCommand(SessionCommand(BaselineService.START_FIXTURE, Bundle.EMPTY), Bundle().apply {
                                        putString("fixture", name); putString("preset", preset)
                                        putBoolean("quietConfirmed", confirmed); putBoolean("loop", loop)
                                    })?.let { result -> result.addListener({
                                        message = runCatching { if (result.get().resultCode == 0) "Requested $name. Check notification and listen for the channel pattern." else "Test rejected. Recheck safety confirmation." }
                                            .getOrElse { "Test command failed: ${it.message}" }
                                    }, ContextCompat.getMainExecutor(this@BaselineActivity)) }
                                }) { Text(format.uppercase()) }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { controller?.pause() }) { Text("Pause") }
                            OutlinedButton(onClick = { controller?.play() }) { Text("Continue") }
                            OutlinedButton(onClick = { controller?.stop() }) { Text("Stop") }
                        }
                        Text(message)
                        Text("Latest Android observations", style = MaterialTheme.typography.titleMedium)
                        Text(BaselineEvidence.latestOutput(this@BaselineActivity), style = MaterialTheme.typography.bodySmall)
                        Text("3. Lock the screen and check playback. Remove the cable: playback must pause. Reconnect: sound must stay stopped. Start another fixture explicitly.")
                        OutlinedTextField(value = notes, onValueChange = { notes = it.take(2000) }, label = { Text("Observed speed, channels, lock-screen, detach; note failures") })
                        Button(enabled = notes.isNotBlank(), onClick = {
                            BaselineEvidence.event(this@BaselineActivity, "user_observation", JSONObject().put("fixture", controller?.currentMediaItem?.mediaId)
                                .put("notes", notes).put("userReported", true))
                            notes = ""; message = "Observation added. Export the report when finished."
                        }) { Text("Record observation") }
                        Button(onClick = {
                            pendingExport = BaselineEvidence.snapshot(this@BaselineActivity)
                            export.launch("music-normal-baseline.json")
                        }) { Text("4. Save baseline report as JSON") }
                        Text("Recorded updates: $revision. AudioTrack configuration and available-output capabilities are separate from the physical USB stream. Physical USB format and internal DSP remain unknown.")
                        OutlinedButton(onClick = { finish() }) { Text("Back to read-only USB probe") }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        MediaController.releaseFuture(future)
        super.onDestroy()
    }
}
