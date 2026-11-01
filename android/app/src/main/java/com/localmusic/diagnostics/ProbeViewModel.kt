package com.localmusic.diagnostics

import android.app.Application
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceChoice(val path: String, val label: String)
data class ProbeState(
    val devices: List<DeviceChoice> = emptyList(), val selected: String? = null,
    val busy: Boolean = false, val message: String = "Connect your KZ M2, then refresh.", val report: ProbeReport? = null,
)

class ProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val manager = application.getSystemService(UsbManager::class.java)
    private var inspection: Job? = null
    private var generation = 0
    private var nativeBusy = false
    var exportContent: String? = null
    var state by mutableStateOf(ProbeState())
        private set

    fun refresh() {
        val devices = manager.deviceList.values.sortedBy { it.deviceName }.map { device ->
            val product = runCatching { device.productName }.getOrNull().orEmpty().ifBlank { "USB device" }
            DeviceChoice(device.deviceName, "$product · %04x:%04x".format(device.vendorId, device.productId))
        }
        val selected = state.selected?.takeIf { path -> devices.any { it.path == path } } ?: devices.firstOrNull()?.path
        if (state.busy && selected != state.selected && !nativeBusy) { generation++; inspection?.cancel() }
        state = state.copy(devices = devices, selected = selected, busy = if (nativeBusy) true else if (selected != state.selected) false else state.busy,
            message = when {
                devices.isEmpty() -> "No USB devices detected. On your OPPO, check OTG Connection and reconnect the cable. Any report below is from an earlier inspection."
                selected != state.selected -> "USB device detected. Select it and tap Inspect cable."
                else -> state.message
            })
    }

    fun select(path: String) {
        if (state.busy || state.devices.none { it.path == path }) return
        state = state.copy(selected = path, report = null, message = "Ready to inspect this USB device.")
    }

    fun message(text: String) { state = state.copy(message = text) }

    fun nativeInspect(testControls: Boolean = false) {
        if (state.busy || UsbDiagnosticGate.held) return
        val device = state.selected?.let { manager.deviceList[it] } ?: return
        if (!manager.hasPermission(device)) { message("Grant USB access with Inspect cable first."); return }
        UsbDiagnosticGate.held = true
        nativeBusy = true
        BaselineService.releaseForNative()
        val request = ++generation
        state = state.copy(busy = true, report = null, message = if (testControls)
            "Normal playback released. Checking low hardware volume and rate/alternate controls, then restoring settings. No audio will play…"
            else "Normal playback released. Running bounded native GET/ownership checks; no audio will play…")
        inspection = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { NativeUsbProbe(getApplication()).inspect(device, true, testControls) }
                val restored = org.json.JSONObject(result.json).getJSONObject("native").optBoolean("settingsRestored")
                if (request == generation) state = state.copy(busy = false, report = result, message =
                    if (testControls && !restored) "Settings restoration was not verified. Save the report, then reconnect the M2 before listening."
                    else "Native check finished. Save the report. Playback stays paused; reconnect M2 if Android audio is not restored.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == generation) state = state.copy(busy = false, message = "Native check failed: ${e.message}. Reconnect M2 before normal listening.")
            } catch (e: LinkageError) {
                if (request == generation) state = state.copy(busy = false, message = "Native library could not load: ${e.message}")
            } finally { UsbDiagnosticGate.held = false; nativeBusy = false }
        }
    }

    fun inspect(path: String) {
        if (state.busy || UsbDiagnosticGate.held) return
        val device = manager.deviceList[path] ?: run { refresh(); return }
        val request = ++generation
        state = state.copy(busy = true, message = "Reading descriptors and supported clock ranges…")
        inspection = viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { UsbProbe(getApplication()).inspect(device) }
                if (request == generation) state = state.copy(busy = false, report = report, message = "Inspection complete. These are advertised capabilities, not a playback verification.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (request == generation) state = state.copy(busy = false, message = e.message ?: "Inspection failed. Reconnect the cable and try again.")
            }
        }
    }
}
