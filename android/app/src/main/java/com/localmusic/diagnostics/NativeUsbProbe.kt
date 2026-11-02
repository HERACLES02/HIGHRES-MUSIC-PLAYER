package com.localmusic.diagnostics

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/** Main-thread gate excludes new baseline engines until native cleanup finishes. */
object UsbDiagnosticGate { var held = false }

object NativeUsb {
    init { System.loadLibrary("usb_experiment") }
    @JvmStatic external fun inspect(fd: Int, allowDetach: Boolean, testControls: Boolean): String
    @JvmStatic external fun decodePcm(input: ByteArray, rate: Int, bits: Int): ByteArray
    @JvmStatic external fun beginStream(): Boolean
    @JvmStatic external fun runStream(fd: Int, pcm: ByteArray, rate: Int, bits: Int, seconds: Int): String
    @JvmStatic external fun streamStats(): String
    @JvmStatic external fun streamCommand(command: Int, value: Int)
}

class NativeUsbProbe(private val context: Context) {
    fun inspect(device: UsbDevice, allowDetach: Boolean, testControls: Boolean = false): ProbeReport {
        val manager = context.getSystemService(UsbManager::class.java)
        require(device.vendorId == 0x31b2 && device.productId == 0x0311) { "This experiment supports only the inspected KZ M2." }
        require(manager.hasPermission(device)) { "Use Inspect cable to grant permission first, then run the native check." }
        val connection = manager.openDevice(device) ?: error("Could not open the M2. Reconnect and retry.")
        val captured = Instant.now().toString()
        val native = try {
            val raw = connection.rawDescriptors ?: error("No descriptors available.")
            val hash = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
            require(hash == "0a6173002455ffed2bc84b2146b3968719ddaa26d62db0cd7723f70f597a1436") {
                "Descriptor layout differs from the tested M2. Use the read-only probe; no interfaces were claimed."
            }
            JSONObject(NativeUsb.inspect(connection.fileDescriptor, allowDetach, testControls))
        } finally { connection.close() }
        val root = JSONObject().put("schemaVersion", 2).put("appVersion", StreamEvidence.VERSION)
            .put("capturedAt", captured).put("phone", JSONObject().put("model", Build.MODEL)
                .put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
            .put("scope", if (testControls) "Silent low hardware-volume SET/readback and eight rate/alternate configurations, followed by restoration. No audio transfers."
                else "Native M2 ownership and GET-only preflight. No direct audio or control SET requests.")
            .put("physicalPreset", "Standard (user confirmed before start)")
            .put("native", native).put("speedEnumLegend", "0 unknown, 1 low, 2 full (12Mbps), 3 high (480Mbps), 4 super, 5 super-plus")
            .put("volumeAttributeLegend", "get_1 CUR, get_2 MIN, get_3 MAX, get_4 RES; raw volume units are 1/256 dB; CUR -32768 means silence")
            .put("limitations", "No clocking or audio delivery is validated. Restore results describe app-issued controls before Android driver reattachment; Android may reconfigure controls afterward. Low-volume verification is temporary; every future audio start must establish low volume again. Failed/unknown restoration requires reconnection before listening.")
        val text = root.toString(2)
        // Keep a fallback report if the view disappears while bounded native calls finish.
        context.openFileOutput(if (testControls) "native-usb-controls-last.json" else "native-usb-last.json", Context.MODE_PRIVATE).bufferedWriter().use { it.write(text) }
        val operations = native.optJSONArray("operations")
        val summary = mutableListOf("Negotiated speed enum: ${native.optInt("negotiatedSpeedEnum")}. No audio sent.")
        if (testControls) {
            summary.add("Low hardware-volume readback: ${native.optBoolean("lowVolumeControlVerified")}; all 8 configurations: ${native.optBoolean("configurationMatrixPassed")}; settings restored: ${native.optBoolean("settingsRestored")}")
            if (!native.optBoolean("settingsRestored")) summary.add("Reconnect the M2 before listening: settings restoration was not verified.")
        }
        if (operations != null) for (index in 0 until operations.length()) {
            val op = operations.getJSONObject(index)
            summary.add("${op.getString("operation")}: ${op.getInt("result")} ${op.getString("error")}" +
                if (op.has("rawSigned256thDb")) " · raw ${op.getInt("rawSigned256thDb")}" else "")
        }
        return ProbeReport("KZ M2 native preflight", captured, summary, text,
            if (testControls) "Native USB control test results" else "Native USB diagnostic results")
    }
}
