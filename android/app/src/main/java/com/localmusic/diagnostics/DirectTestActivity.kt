package com.localmusic.diagnostics

import android.content.Intent
import android.hardware.usb.UsbManager
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DirectTestActivity: ComponentActivity() {
    private var message by mutableStateOf("")
    private var pending: String?=null
    private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val report=pending; pending=null
        if(uri!=null) lifecycleScope.launch {
            val content=report ?: withContext(Dispatchers.IO) { runCatching {
                openFileInput("stream-export-pending.json").bufferedReader().use { it.readText() }
            }.getOrNull() }
            if(content==null) { message="No pending export content. Export the report again."; return@launch }
            val result=withContext(Dispatchers.IO) { runCatching {
                (contentResolver.openOutputStream(uri) ?: error("Cannot open destination")).bufferedWriter().use { it.write(content) }
            } }
            message=if(result.isSuccess) "Report saved." else "Save failed: ${result.exceptionOrNull()?.message}"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    var bits by rememberSaveable { mutableStateOf(16) }
                    var rate by rememberSaveable { mutableStateOf(44100) }
                    var kind by rememberSaveable { mutableStateOf("wav") }
                    var channelCheck by rememberSaveable { mutableStateOf(true) }
                    var duration by rememberSaveable { mutableStateOf(12) }
                    var notes by rememberSaveable { mutableStateOf("") }
                    var confirm by remember { mutableStateOf(false) }
                    var acknowledged by rememberSaveable { mutableStateOf(false) }
                    val state=UsbStreamService.state
                    val stats=runCatching { JSONObject(state.stats) }.getOrDefault(JSONObject())
                    val phase=stats.optInt("phase")
                    val currentRate=state.fixture.substringAfterLast('-').substringBefore('.').toIntOrNull() ?: rate
                    val busy=state.busy
                    val filename=if(channelCheck) "channels-$bits-$rate.wav" else "stereo-$bits-$rate.$kind"
                    if(confirm) AlertDialog(onDismissRequest={ confirm=false },title={ Text("Start experimental USB audio?") },
                        text={ Text("Select Standard on M2, stop other audio apps, lower media volume, and keep earphones out initially. This plays the quiet six-second fixture repeatedly at low hardware volume. Android volume may not control it. The asynchronous clocking arrangement is unvalidated: this nominal-rate transport may drift or glitch. No microphone timing is used. Stop if playback is wrong; save the report. Driver ownership may interrupt cable microphone/buttons. No automatic restart after detach or calls.") },
                        confirmButton={ Button(onClick={
                            confirm=false
                            val usb=getSystemService(UsbManager::class.java)
                            val device=usb.deviceList.values.firstOrNull { it.vendorId==0x31b2 && it.productId==0x0311 }
                            if(device==null || !usb.hasPermission(device)) message="Return to Inspect cable to grant M2 USB permission first."
                            else ContextCompat.startForegroundService(this@DirectTestActivity,Intent(this@DirectTestActivity,UsbStreamService::class.java)
                                .setAction(UsbStreamService.START).putExtra("confirmed",true).putExtra("fixture",filename)
                                .putExtra("device",device.deviceName).putExtra("seconds",duration))
                        }) { Text("Start experimental audio") } },
                        dismissButton={ OutlinedButton(onClick={ confirm=false }) { Text("Cancel") } })
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Direct USB experiment",style=MaterialTheme.typography.headlineMedium)
                        Text("Nominal packet cadence · missing-feedback clocking UNVALIDATED. No bit-perfect badge or daily-use approval.")
                        Row {
                            Checkbox(checked=channelCheck,enabled=!busy,onCheckedChange={ channelCheck=it })
                            Text("Equal-tone channel comparison (440 Hz, silent gaps, WAV)")
                        }
                        Text(DiagnosticFixture.pattern(if(busy) state.fixture else filename))
                        Text("Source peak −42 dBFS; native decoding must match the pinned PCM hash before audio starts. The comparison signal is identical on each active channel.")
                        Row { for(b in listOf(16,24)) OutlinedButton(enabled=!busy,onClick={ bits=b }) { Text("$b-bit") } }
                        for(row in listOf(44100,48000,88200,96000).chunked(2)) Row {
                            for(hz in row) OutlinedButton(enabled=!busy,onClick={ rate=hz }) { Text("$hz Hz") }
                        }
                        if(!channelCheck) Row { for(format in listOf("wav","flac")) OutlinedButton(enabled=!busy,onClick={ kind=format }) { Text(format.uppercase()) } }
                        Row { for(seconds in listOf(12,60,3660)) OutlinedButton(enabled=!busy,onClick={ duration=seconds }) { Text(when(seconds) {12->"12 sec"; 60->"1 min"; else->"61 min"}) } }
                        Text("Selected: $filename · $duration seconds of transport. Use the 12-second test first.")
                        Row { Checkbox(checked=acknowledged,enabled=!busy,onCheckedChange={ acknowledged=it }); Text("I understand the clocking is experimental and have lowered volume / selected Standard.") }
                        Button(enabled=!busy && acknowledged,onClick={ confirm=true }) { Text("Play selected USB fixture") }
                        Text(state.message)
                        if(state.fixture.isNotBlank()) Text("Active/last: ${state.fixture}")
                        Text("Phase $phase · submitted ${stats.optLong("submittedBytes")} bytes · completed ${stats.optLong("completedBytes")} bytes · nominal active ${stats.optLong("completedIntervals")/1000}s")
                        if(busy && phase==2 && DiagnosticFixture.channelCheck(state.fixture)) {
                            val second=(stats.optLong("positionFrames").toDouble()/currentRate)%6
                            Text("Expected source section (approximate): "+when {
                                second<2 -> "LEFT only · 440 Hz"
                                second<3 -> "SILENCE"
                                second<5 -> "RIGHT only · 440 Hz"
                                else -> "SILENCE"
                            })
                        }
                        if(phase in listOf(2,3)) Text("Last verified hardware levels: ${stats.optInt("hardwareLevelRaw1")/256.0} / ${stats.optInt("hardwareLevelRaw2")/256.0} dB")
                        Text("Transfer errors ${stats.optLong("transferErrors")} · packet errors ${stats.optLong("packetErrors")} · PCM mismatches ${stats.optLong("pcmMismatches")} · pending ${stats.optInt("pendingTransfers")}")
                        Row {
                            OutlinedButton(enabled=busy,onClick={ UsbStreamService.command(1,1) }) { Text("Pause") }
                            OutlinedButton(enabled=busy,onClick={ UsbStreamService.command(1,0) }) { Text("Resume") }
                            Button(enabled=busy,onClick={ UsbStreamService.command(0) }) { Text("Stop / release") }
                        }
                        Row {
                            OutlinedButton(enabled=busy,onClick={ UsbStreamService.command(2,0) }) { Text("Seek 0s") }
                            OutlinedButton(enabled=busy,onClick={ UsbStreamService.command(2,currentRate*3) }) { Text("Seek 3s") }
                        }
                        Text("Hardware volume only. Start low; increase only if needed after checking the quiet signal. Paused changes apply on Resume.")
                        Row { for(level in listOf(-40,-30,-20)) OutlinedButton(enabled=busy && phase in listOf(2,3),onClick={ UsbStreamService.command(3,level) }) { Text("$level dB") } }
                        OutlinedTextField(value=notes,onValueChange={ notes=it.take(3000) },label={ Text("Heard correct speed/channels? Glitches, screen-off, pause/seek, reconnect notes") })
                        Button(enabled=!busy,onClick={
                            lifecycleScope.launch {
                                val saved=state.report ?: withContext(Dispatchers.IO) { runCatching { openFileInput("native-stream-last.json").bufferedReader().use { it.readText() } }.getOrNull() }
                                if(saved==null) message="No completed report yet."
                                else {
                                    prepareExport("m2-direct-stream.json",JSONObject(saved).put("userObservation",notes).put("observationUserReported",true).toString(2))
                                }
                            }
                        }) { Text("Save last stream report as JSON") }
                        OutlinedButton(onClick={ lifecycleScope.launch {
                            val partial=withContext(Dispatchers.IO) { runCatching { openFileInput("native-stream-incomplete.json").bufferedReader().use { it.readText() } }.getOrNull() }
                            if(partial==null) message="No interrupted/in-progress checkpoint available."
                            else prepareExport("m2-direct-stream-incomplete.json",partial)
                        } }) { Text("Save latest in-progress / interrupted checkpoint") }
                        Text(message)
                        OutlinedButton(enabled=!busy,onClick={
                            startActivity(Intent(this@DirectTestActivity,BaselineActivity::class.java).putExtra("channelCheck",true))
                        }) { Text("Compare the same signal in Normal mode") }
                        Text("USB errors cannot measure the DAC's internal buffering or DSP. Zero transport errors alone do not establish unchanged bus delivery or long-term clock stability.")
                        OutlinedButton(onClick={ finish() }) { Text("Back to USB probe (test keeps running)") }
                    }
                }
            }
        }
    }
    private suspend fun prepareExport(name: String,report: String) {
        val saved=withContext(Dispatchers.IO) { runCatching {
            openFileOutput("stream-export-pending.json",MODE_PRIVATE).bufferedWriter().use { it.write(report) }
        } }
        if(saved.isFailure) { message="Could not prepare export: ${saved.exceptionOrNull()?.message}"; return }
        pending=report; export.launch(name)
    }
}
