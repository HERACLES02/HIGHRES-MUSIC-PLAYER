package com.localmusic.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.round

enum class PcmTargetEncoding(val bytesPerSample: Int) {
    PCM_FLOAT(4),
    PCM_32BIT(4),
    PCM_24BIT_PACKED(3),
    PCM_16BIT(2);

    fun frameBytes(channelCount: Int): Int = channelCount * bytesPerSample
}

class PcmOutputConverter {
    private var outputBuffer: ByteBuffer? = null

    private fun ensureBuffer(capacity: Int): ByteBuffer {
        val current = outputBuffer
        return if (current != null && current.capacity() >= capacity) {
            current.clear()
            current
        } else {
            val newCap = maxOf(capacity, (current?.capacity() ?: 0) * 2, 4096)
            val newBuf = ByteBuffer.allocateDirect(newCap).order(ByteOrder.LITTLE_ENDIAN)
            outputBuffer = newBuf
            newBuf
        }
    }

    /**
     * Converts float samples from [input] into [targetEncoding].
     * Does not mutate [input]'s position; callers (like [PcmOutputSession]) control advancement.
     */
    fun convert(input: ByteBuffer, channelCount: Int, targetEncoding: PcmTargetEncoding): ByteBuffer {
        if (targetEncoding == PcmTargetEncoding.PCM_FLOAT) return input
        require(channelCount > 0) { "Channel count must be positive" }
        val inFrameBytes = channelCount * 4
        val inRemaining = input.remaining()
        require(inRemaining % inFrameBytes == 0) { "Input buffer not aligned to float frames" }
        val frameCount = inRemaining / inFrameBytes
        val outFrameBytes = targetEncoding.frameBytes(channelCount)
        val outBytes = frameCount * outFrameBytes
        val output = ensureBuffer(outBytes)

        val totalSamples = frameCount * channelCount
        val inputDuplicate = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        when (targetEncoding) {
            PcmTargetEncoding.PCM_24BIT_PACKED -> {
                for (i in 0 until totalSamples) {
                    val floatVal = inputDuplicate.float
                    val sample = if (floatVal.isFinite()) floatVal.toDouble() else 0.0
                    val scaled = sample * 8388608.0
                    val bits = round(scaled).toLong().coerceIn(-8388608L, 8388607L).toInt()
                    output.put(bits.toByte())
                    output.put((bits shr 8).toByte())
                    output.put((bits shr 16).toByte())
                }
            }
            PcmTargetEncoding.PCM_32BIT -> {
                for (i in 0 until totalSamples) {
                    val floatVal = inputDuplicate.float
                    val sample = if (floatVal.isFinite()) floatVal.toDouble() else 0.0
                    val scaled = sample * 2147483648.0
                    val bits = round(scaled).toLong().coerceIn(-2147483648L, 2147483647L).toInt()
                    output.putInt(bits)
                }
            }
            PcmTargetEncoding.PCM_16BIT -> {
                for (i in 0 until totalSamples) {
                    val floatVal = inputDuplicate.float
                    val sample = if (floatVal.isFinite()) floatVal.toDouble() else 0.0
                    val scaled = sample * 32768.0
                    val bits = round(scaled).toInt().coerceIn(-32768, 32767).toShort()
                    output.putShort(bits)
                }
            }
            PcmTargetEncoding.PCM_FLOAT -> { /* handled above */ }
        }
        output.flip()
        return output
    }
}

class PcmOutputSession(
    private val converter: PcmOutputConverter = PcmOutputConverter(),
    val channelCount: Int = 2,
    val targetEncoding: PcmTargetEncoding = PcmTargetEncoding.PCM_FLOAT
) {
    private val inFrameBytes = channelCount * 4
    private val outFrameBytes = targetEncoding.frameBytes(channelCount)
    private var pendingConverted: ByteBuffer? = null

    fun hasPendingData(): Boolean = pendingConverted?.hasRemaining() == true

    fun flush() {
        pendingConverted = null
    }

    /**
     * Writes [input] float buffer to [downstreamWriter].
     * Advances [input] by exactly the number of complete float frames consumed downstream.
     * Returns true only after the input buffer is fully consumed and there is no pending data.
     */
    fun write(
        input: ByteBuffer,
        presentationTimeUs: Long,
        downstreamWriter: (buffer: ByteBuffer, sizeInBytes: Int, presentationTimeUs: Long) -> Int
    ): Boolean {
        if (targetEncoding == PcmTargetEncoding.PCM_FLOAT) {
            if (!input.hasRemaining()) return true
            val posBefore = input.position()
            val written = downstreamWriter(input, input.remaining(), presentationTimeUs)
            val actuallyConsumed = if (input.position() > posBefore) input.position() - posBefore else written
            if (actuallyConsumed > 0 && input.position() == posBefore) {
                input.position(input.position() + actuallyConsumed)
            }
            return !input.hasRemaining()
        }

        var converted = pendingConverted
        if (converted == null || !converted.hasRemaining()) {
            if (!input.hasRemaining()) return true
            val fullConverted = converter.convert(input, channelCount, targetEncoding)
            converted = fullConverted
            pendingConverted = converted
        }

        val bytesToWrite = converted.remaining()
        val posBefore = converted.position()
        val written = downstreamWriter(converted, bytesToWrite, presentationTimeUs)
        val actuallyConsumed = if (converted.position() > posBefore) converted.position() - posBefore else written
        if (actuallyConsumed > 0) {
            if (converted.position() == posBefore) {
                converted.position(converted.position() + actuallyConsumed)
            }
            val framesConsumed = converted.position() / outFrameBytes - posBefore / outFrameBytes
            val inputAdvance = framesConsumed * inFrameBytes
            input.position(input.position() + inputAdvance)
            if (!converted.hasRemaining()) {
                pendingConverted = null
            }
        }
        return !input.hasRemaining() && pendingConverted == null
    }
}
