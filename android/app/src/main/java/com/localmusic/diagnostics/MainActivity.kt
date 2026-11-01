package com.localmusic.diagnostics

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var model: ProbeViewModel
    private val usb by lazy { getSystemService(UsbManager::class.java) }
    private val permissionAction get() = "$packageName.USB_PERMISSION"
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = model.exportContent
        model.exportContent = null
        if (uri != null && content != null) lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching {
                val output = contentResolver.openOutputStream(uri) ?: error("Could not open the selected file.")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(content) }
            } }
            model.message(if (saved.isSuccess) "Report saved. You can send that JSON file back for analysis." else "Could not save report: ${saved.exceptionOrNull()?.message}")
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == permissionAction) {
                val offered = usbDevice(intent)
                val actual = offered?.let { usb.deviceList[it.deviceName] }
                // Do not trust a broadcast's granted flag without checking the manager.
                if (actual != null && actual.deviceName == model.state.selected && usb.hasPermission(actual)) model.inspect(actual.deviceName)
                else model.message("USB access was denied or the cable was disconnected. Tap Inspect cable to retry.")
            } else model.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[ProbeViewModel::class.java]
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ProbeScreen(model.state, model::refresh, model::select, ::inspectSelected,
                    onStream = { startActivity(Intent(this,DirectTestActivity::class.java)) },
                    onNative = { model.nativeInspect() }, onControls = { model.nativeInspect(true) },
                    onBaseline = { startActivity(Intent(this, BaselineActivity::class.java)) },
                    onCopy = { model.state.report?.let { report ->
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("USB audio report", report.json))
                        model.message("Report copied. Paste it into a text file or this conversation when you are ready.")
                    } },
                    onExport = { model.state.report?.let { report ->
                        model.exportContent = report.json
                        export.launch(when (report.heading) {
                            "Native USB control test results" -> "m2-controls-test.json"
                            "Native USB diagnostic results" -> "m2-native-preflight.json"
                            else -> "music-usb-probe.json"
                        })
                    } })
            }
        }
    }

    override fun onResume() { super.onResume(); model.refresh() }
    override fun onDestroy() { unregisterReceiver(receiver); super.onDestroy() }

    private fun inspectSelected() {
        val device = model.state.selected?.let { usb.deviceList[it] } ?: run { model.refresh(); return }
        if (usb.hasPermission(device)) model.inspect(device.deviceName)
        else {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(permissionAction).setPackage(packageName)
            // Mutable is required for the system's USB result extras; package-scoped.
            usb.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
            model.message("Allow access to the USB device in Android's permission dialog.")
        }
    }

    private fun usbDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") (intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice)
}

@Composable
private fun ProbeScreen(state: ProbeState, onRefresh: () -> Unit, onSelect: (String) -> Unit,
    onInspect: () -> Unit, onNative: () -> Unit, onControls: () -> Unit, onStream: () -> Unit, onBaseline: () -> Unit, onCopy: () -> Unit, onExport: () -> Unit) {
    var confirmNative by remember { mutableStateOf(false) }
    var controlMode by remember { mutableStateOf(false) }
    if (confirmNative) AlertDialog(onDismissRequest = { confirmNative = false },
        title = { Text(if (controlMode) "Run silent hardware-control test?" else "Temporarily take control of M2?") },
        text = { Text(if (controlMode) "Select Standard on the M2, lower Android media volume, and remove earphones from your ears. This stops normal playback, temporarily claims interfaces 0/2, writes a hardware level of −40 dB or lower on both channels, and checks the eight advertised rate/bit-depth configurations without sending audio. It then attempts to restore the original settings and Android drivers. Cable audio/microphone may be unavailable during the check. Do not disconnect during the check. If restoration is not verified, reconnect before listening."
            else "Select the M2's Standard preset. Normal playback will stop and release its audio engine. This check may temporarily release Android's audio driver and claim interfaces 0 and 2, then restore them. Cable audio/microphone may be unavailable during the check. No sound, recording, app-issued volume writes or rate changes. Reconnect the cable afterward if Android audio does not return.") },
        confirmButton = { Button(onClick = { confirmNative = false; if (controlMode) onControls() else onNative() }) { Text("Standard preset selected · Run check") } },
        dismissButton = { OutlinedButton(onClick = { confirmNative = false }) { Text("Cancel") } })
    androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Music USB Probe", style = MaterialTheme.typography.headlineLarge)
            Text("USB diagnostics · OPPO A9 2020 / KZ M2", style = MaterialTheme.typography.titleSmall)
            Text("Inspect cable remains read-only. Normal-route and experimental Direct USB playback are separate tests. Direct clocking remains unvalidated.")
            Button(onClick = onBaseline, enabled = !state.busy) { Text("Open normal-route audio test") }
            Button(onClick = onStream, enabled = !state.busy) { Text("Open experimental direct USB playback") }
            OutlinedButton(onClick = { controlMode = false; confirmNative = true }, enabled = !state.busy && state.selected != null) { Text("Run native M2 ownership check") }
            Button(onClick = { controlMode = true; confirmNative = true }, enabled = !state.busy && state.selected != null) { Text("Run silent volume / rate test") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("1 · Connect and select your cable", style = MaterialTheme.typography.titleMedium)
                    Text("If nothing appears, check OPPO Settings → Additional Settings → OTG Connection. The path may vary with ColorOS.")
                    OutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("Refresh USB devices") }
                    state.devices.forEach { device ->
                        OutlinedButton(onClick = { onSelect(device.path) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text((if (state.selected == device.path) "Selected: " else "") + device.label)
                        }
                    }
                    Button(onClick = onInspect, enabled = state.selected != null && !state.busy) { Text("2 · Inspect cable") }
                    if (state.busy) CircularProgressIndicator()
                    Text(state.message)
                }
            }
            state.report?.let { report ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(report.heading, style = MaterialTheme.typography.titleLarge)
                        Text("${report.device}\nCaptured: ${report.captured}")
                        if (report.playback.isEmpty()) Text("No playback format was decoded. Read the report warnings; this does not establish that the cable cannot play audio.")
                        report.playback.forEach { Text(it) }
                        Text("The cable's microphones are reported separately. Descriptor limits are not a measurement of the active audio stream.")
                        Button(onClick = onExport) { Text("3 · Save report as JSON") }
                        OutlinedButton(onClick = onCopy) { Text("Copy report") }
                    }
                }
                Text("Full report", style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(report.json, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
