package com.localmusic.diagnostics

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioManager
import android.os.Build
import com.localmusic.usb.UsbDescriptors
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class ProbeReport(val device: String, val captured: String, val playback: List<String>, val json: String,
    val heading: String = "Advertised playback formats")
private data class ClockResult(
    val clock: UsbDescriptors.Clock,
    val ranges: List<UsbDescriptors.RateRange> = emptyList(),
    val current: Long? = null,
    val note: String? = null,
)

/** Only descriptors and USB GET requests. No claimInterface, SET requests or audio writes. */
class UsbProbe(private val context: Context) {
    private val manager = context.getSystemService(UsbManager::class.java)

    suspend fun inspect(device: UsbDevice): ProbeReport {
        require(manager.hasPermission(device)) { "USB permission is needed. Tap Inspect cable and allow access." }
        val connection = manager.openDevice(device) ?: error("Android could not open this USB device. Reconnect it and try again.")
        try {
            val raw = connection.rawDescriptors ?: byteArrayOf()
            val parsed = UsbDescriptors.parse(raw)
            val active = ByteArray(1)
            val activeConfiguration = if (connection.controlTransfer(0x80, 8, 0, 0, active, 1, 1000) == 1) active[0].toInt() and 0xff else null
            val clocks = parsed.clocks.take(64).map { clock ->
                currentCoroutineContext().ensureActive()
                readClock(connection, clock, activeConfiguration)
            }
            val captured = Instant.now().toString()
            val playback = parsed.streams.filter { it.isPlayback }.map { stream ->
                val rate = describeRates(stream, clocks)
                "Interface ${stream.interfaceNumber}, alternate ${stream.alternate}: ${stream.encoding}, " +
                    "${stream.channels} channels, ${stream.validBits} meaningful bits in ${stream.slotBytes}-byte slots. $rate" +
                    if (!stream.hasValidFormat()) " Format is incomplete; do not treat this as a confirmed capability." else ""
            }
            val root = JSONObject()
                .put("schemaVersion", 2)
                .put("appVersion", "0.6.0-channel-check")
                .put("capturedAt", captured)
                .put("scope", "Read-only advertised capabilities. No direct audio stream, active playback format, sound-quality result, or bit-perfect verification.")
                .put("phone", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
                    .put("androidRelease", Build.VERSION.RELEASE).put("sdk", Build.VERSION.SDK_INT).put("buildDisplay", Build.DISPLAY))
                .put("usb", JSONObject().put("manufacturer", safe { device.manufacturerName }).put("product", safe { device.productName })
                    .put("vendorId", device.vendorId).put("productId", device.productId)
                    .put("transientDevicePath", device.deviceName).put("activeConfiguration", activeConfiguration ?: JSONObject.NULL)
                    .put("androidInterfaceCount", device.interfaceCount).put("serialNumberCollected", false))
                .put("descriptorBytes", raw.size).put("descriptorCount", parsed.descriptorCount)
                .put("descriptorBytesConsumed", parsed.bytesConsumed)
                .put("rawDescriptorsHex", raw.joinToString("") { "%02x".format(it.toInt() and 0xff) })
                .put("streams", JSONArray().apply { parsed.streams.forEach { put(streamJson(it, clocks)) } })
                .put("clockQueries", JSONArray().apply { clocks.forEach { put(clockJson(it)) } })
                .put("uac1PlaybackPreflight", playbackPreflight(connection, parsed, activeConfiguration))
                .put("warnings", JSONArray(parsed.warnings))
                .put("androidOutputCapabilities", androidOutputs())
                .put("limitations", JSONArray(listOf(
                    "Descriptors advertise possible configurations; playback acceptance is not tested in this build.",
                    "Android output capability arrays do not identify the current physical output format.",
                    "Clock selectors/multipliers and USB Audio Class 3 are not resolved; unknown means unknown.",
                    "Hardware volume GET responses are diagnostic only; low-volume SET/readback is not performed. Internal EQ/DSP is unknown.",
                    "The KZ High-Resolution EQ preset is not evidence of sample rate or bit depth."
                )))
            return ProbeReport(safe { device.productName }.ifBlank { "USB ${device.vendorId}:${device.productId}" }, captured, playback, root.toString(2))
        } finally {
            connection.close()
        }
    }

    private suspend fun playbackPreflight(connection: UsbDeviceConnection, parsed: UsbDescriptors.Parsed, active: Int?): JSONObject {
        val features = JSONArray()
        val playback = parsed.streams.filter { it.isPlayback && it.protocol == 0 }
        val candidates = parsed.featureUnits.filter { unit -> playback.any {
            it.configuration == unit.configuration && it.controlInterface == unit.controlInterface && it.terminalLink == unit.sourceId
        } }.take(16)
        for (unit in candidates) {
            currentCoroutineContext().ensureActive()
            val item = JSONObject().put("configuration", unit.configuration).put("controlInterface", unit.controlInterface)
                .put("entityId", unit.id).put("sourceTerminal", unit.sourceId).put("channelControlMasks", JSONArray(unit.channelControls))
                .put("topologyScope", "Feature Unit directly downstream of the playback USB terminal; full output path not resolved.")
            val volumes = JSONArray()
            for (channel in unit.channelControls.indices.take(8).filter { unit.hasVolume(it) }) {
                currentCoroutineContext().ensureActive()
                val values = JSONObject().put("channel", channel)
                for ((name, request) in listOf("current" to 0x81, "minimum" to 0x82, "maximum" to 0x83, "resolution" to 0x84)) {
                    currentCoroutineContext().ensureActive()
                    val bytes = ByteArray(2)
                    val received = if (active == unit.configuration && parsed.warnings.isEmpty())
                        connection.controlTransfer(0xa1, request, 0x0200 or channel,
                            (unit.id shl 8) or unit.controlInterface, bytes, bytes.size, 500) else -1
                    values.put("${name}RawSigned256thDb", if (received == 2) UsbDescriptors.u16(bytes, 0).toShort().toInt() else JSONObject.NULL)
                    values.put("${name}ReceivedBytes", received)
                }
                values.put("meaning", "GET only. -32768 is the UAC1 silence value. Missing/short responses remain unknown.")
                volumes.put(values)
            }
            item.put("hardwareVolumeReadback", volumes)
            item.put("queriesSkipped", active != unit.configuration || parsed.warnings.isNotEmpty())
            features.put(item)
        }
        val clocking = JSONArray().apply {
            playback.forEach { stream -> stream.endpoints.filter { it.isIsochronous && !it.isInput && !it.isFeedback }.forEach { ep ->
                put(JSONObject().put("interface", stream.interfaceNumber).put("alternate", stream.alternate)
                    .put("endpoint", ep.address).put("synchronization", ep.synchronization())
                    .put("synchAddress", ep.synchAddress).put("sampleRateControlAdvertised", if (ep.classAttributes >= 0) (ep.classAttributes and 1) != 0 else JSONObject.NULL)
                    .put("clockingStatus", if (ep.synchronization() == "asynchronous" && ep.synchAddress <= 0)
                        "Unresolved: asynchronous OUT without associated explicit feedback. No fixed-packet scheduler or capture timing assumed."
                        else "Not physically validated."))
            } }
        }
        return JSONObject().put("featureUnits", features).put("endpoints", clocking)
            .put("usbLinkSpeed", JSONObject.NULL).put("linkSpeedNote", "Not measured; bcdUSB is not negotiated link speed.")
            .put("safeHardwareVolumeEstablished", false).put("directStreamingAvailable", false)
            .put("blockers", JSONArray(listOf("Supported playback clocking arrangement is unverified.",
                "Low hardware-volume SET/readback is unverified; GET alone cannot establish safe volume.",
                "Native libusb transport and direct decoder are not implemented. Physical streaming tests have not run.")))
    }

    private fun readClock(connection: UsbDeviceConnection, clock: UsbDescriptors.Clock, active: Int?): ClockResult {
        if (active != clock.configuration) return ClockResult(clock, note = "Not queried: active USB configuration is unknown or different.")
        if ((clock.controls and 1) == 0) return ClockResult(clock, note = "Clock does not advertise readable sampling-frequency control.")
        return try {
            val index = (clock.id shl 8) or clock.controlInterface
            val currentBytes = ByteArray(4)
            val current = if (connection.controlTransfer(0xa1, 1, 0x0100, index, currentBytes, 4, 1000) == 4) UsbDescriptors.u32(currentBytes, 0) else null
            val header = ByteArray(2)
            check(connection.controlTransfer(0xa1, 2, 0x0100, index, header, 2, 1000) == 2) { "GET_RANGE header was not returned." }
            val count = UsbDescriptors.u16(header, 0)
            check(count in 1..64) { "Clock range count is zero or too large to inspect safely." }
            val bytes = ByteArray(2 + 12 * count)
            val received = connection.controlTransfer(0xa1, 2, 0x0100, index, bytes, bytes.size, 1000)
            ClockResult(clock, UsbDescriptors.parseClockRanges(bytes, received), current)
        } catch (e: Exception) {
            ClockResult(clock, note = "Clock rates remain unknown: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun streamJson(stream: UsbDescriptors.Stream, clocks: List<ClockResult>): JSONObject = JSONObject()
        .put("configuration", stream.configuration).put("interface", stream.interfaceNumber).put("alternate", stream.alternate)
        .put("protocol", stream.protocol).put("playback", stream.isPlayback).put("capture", stream.isCapture)
        .put("channels", stream.channels).put("containerBytes", stream.slotBytes).put("meaningfulBits", stream.validBits)
        .put("encoding", stream.encoding).put("formatValid", stream.hasValidFormat())
        .put("controlInterface", stream.controlInterface).put("terminalLink", stream.terminalLink).put("clockId", stream.clockId)
        .put("discreteRatesHz", JSONArray(stream.rates)).put("minimumRateHz", stream.minimumRate).put("maximumRateHz", stream.maximumRate)
        .put("rateDescription", describeRates(stream, clocks))
        .put("endpoints", JSONArray().apply { stream.endpoints.forEach { endpoint ->
            put(JSONObject().put("address", endpoint.address).put("direction", if (endpoint.isInput) "IN" else "OUT")
                .put("isochronous", endpoint.isIsochronous).put("feedback", endpoint.isFeedback)
                .put("synchronization", endpoint.synchronization()).put("rawMaxPacketSize", endpoint.packetSize).put("interval", endpoint.interval)
                .put("synchAddress", endpoint.synchAddress).put("classAttributes", endpoint.classAttributes))
        } })

    private fun describeRates(stream: UsbDescriptors.Stream, clocks: List<ClockResult>): String {
        if (stream.rates.isNotEmpty()) return "Advertised rates: ${stream.rates.joinToString()} Hz."
        if (stream.minimumRate > 0) return "Advertised continuous range: ${stream.minimumRate}–${stream.maximumRate} Hz."
        val clock = clocks.firstOrNull { it.clock.configuration == stream.configuration && it.clock.controlInterface == stream.controlInterface && it.clock.id == stream.clockId }
        if (clock != null && clock.ranges.isNotEmpty()) return "Clock ranges: " + clock.ranges.joinToString { range ->
            if (range.minimum == range.maximum) "${range.minimum} Hz" else "${range.minimum}–${range.maximum} Hz (step ${range.resolution}, 0 means unspecified)"
        }
        return "Sample rates unknown; no supported rate is assumed."
    }

    private fun clockJson(result: ClockResult): JSONObject = JSONObject()
        .put("configuration", result.clock.configuration).put("controlInterface", result.clock.controlInterface).put("entityId", result.clock.id)
        .put("attributes", result.clock.attributes).put("controls", result.clock.controls)
        .put("currentClockRateHz", result.current ?: JSONObject.NULL).put("note", result.note ?: JSONObject.NULL)
        .put("ranges", JSONArray().apply { result.ranges.forEach { put(JSONObject().put("minimumHz", it.minimum).put("maximumHz", it.maximum).put("stepHz", it.resolution)) } })

    private fun androidOutputs(): JSONArray {
        val audio = context.getSystemService(AudioManager::class.java)
        return JSONArray().apply {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
                put(JSONObject().put("product", device.productName.toString()).put("type", device.type)
                    .put("sampleRatesHz", JSONArray(device.sampleRates.toList())).put("channelCounts", JSONArray(device.channelCounts.toList()))
                    .put("encodingConstants", JSONArray(device.encodings.toList()))
                    .put("meaning", "Android-reported supported properties; not the active output format."))
            }
        }
    }

    private fun safe(block: () -> String?): String = runCatching { block().orEmpty() }.getOrDefault("")
}
