package com.localmusic.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

fun pcmOutputConverterChecks() {
    var count = 0
    fun verify(ok: Boolean, msg: String) { count++; check(ok) { "PCM Output Converter: $msg" } }

    val converter = PcmOutputConverter()

    // 1. Vector checks: Negative full scale, positive saturation, low 24-bit sample bits
    // 24-bit packed integer
    val inFloat = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.LITTLE_ENDIAN) // 4 stereo frames (8 samples)
    inFloat.putFloat(-1.0f)           // Frame 0 Left: neg full scale
    inFloat.putFloat(1.0f)            // Frame 0 Right: pos full scale
    inFloat.putFloat(1.5f)            // Frame 1 Left: pos saturation
    inFloat.putFloat(-1.5f)           // Frame 1 Right: neg saturation
    inFloat.putFloat(1.0f / 8388608f) // Frame 2 Left: lowest 24-bit bit
    inFloat.putFloat(-1.0f / 8388608f)// Frame 2 Right: -1 bit
    inFloat.putFloat(0.0f)            // Frame 3 Left: zero
    inFloat.putFloat(Float.NaN)       // Frame 3 Right: non-finite -> zero
    inFloat.flip()

    val out24 = converter.convert(inFloat, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_24BIT_PACKED)
    verify(out24.remaining() == 4 * 6, "24-bit packed output length: 4 frames * 6 bytes = 24 bytes")
    verify(inFloat.position() == 0, "input position unchanged by convert; session controls consumption")

    // Frame 0 Left: -1.0f -> -8388608 = 0x800000 -> LE bytes: 0x00, 0x00, 0x80
    verify(out24.get().toInt() == 0x00, "24-bit neg full scale byte 0")
    verify(out24.get().toInt() == 0x00, "24-bit neg full scale byte 1")
    verify((out24.get().toInt() and 0xFF) == 0x80, "24-bit neg full scale byte 2")

    // Frame 0 Right: 1.0f -> +8388607 = 0x7FFFFF -> LE bytes: 0xFF, 0xFF, 0x7F
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit pos full scale byte 0")
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit pos full scale byte 1")
    verify((out24.get().toInt() and 0xFF) == 0x7F, "24-bit pos full scale byte 2")

    // Frame 1 Left: 1.5f -> saturated to +8388607
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit pos sat byte 0")
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit pos sat byte 1")
    verify((out24.get().toInt() and 0xFF) == 0x7F, "24-bit pos sat byte 2")

    // Frame 1 Right: -1.5f -> saturated to -8388608
    verify(out24.get().toInt() == 0x00, "24-bit neg sat byte 0")
    verify(out24.get().toInt() == 0x00, "24-bit neg sat byte 1")
    verify((out24.get().toInt() and 0xFF) == 0x80, "24-bit neg sat byte 2")

    // Frame 2 Left: 1 / 8388608 -> +1 -> LE bytes: 0x01, 0x00, 0x00
    verify(out24.get().toInt() == 0x01, "24-bit low bit byte 0")
    verify(out24.get().toInt() == 0x00, "24-bit low bit byte 1")
    verify(out24.get().toInt() == 0x00, "24-bit low bit byte 2")

    // Frame 2 Right: -1 / 8388608 -> -1 -> LE bytes: 0xFF, 0xFF, 0xFF
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit neg low bit byte 0")
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit neg low bit byte 1")
    verify((out24.get().toInt() and 0xFF) == 0xFF, "24-bit neg low bit byte 2")

    // Frame 3 Left & Right: 0 and NaN
    verify(out24.get().toInt() == 0 && out24.get().toInt() == 0 && out24.get().toInt() == 0, "24-bit zero")
    verify(out24.get().toInt() == 0 && out24.get().toInt() == 0 && out24.get().toInt() == 0, "24-bit NaN becomes 0")

    // 2. Vector checks: 32-bit signed integer
    val inFloat32 = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.LITTLE_ENDIAN)
    inFloat32.putFloat(-1.0f)
    inFloat32.putFloat(1.0f)
    inFloat32.putFloat(1.5f)
    inFloat32.putFloat(-1.5f)
    inFloat32.flip()

    val out32 = converter.convert(inFloat32, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_32BIT)
    verify(out32.remaining() == 2 * 8, "32-bit output length: 2 frames * 8 bytes = 16 bytes")
    verify(out32.int == Int.MIN_VALUE, "32-bit neg full scale = Int.MIN_VALUE")
    verify(out32.int == Int.MAX_VALUE, "32-bit pos full scale = Int.MAX_VALUE")
    verify(out32.int == Int.MAX_VALUE, "32-bit pos saturation = Int.MAX_VALUE")
    verify(out32.int == Int.MIN_VALUE, "32-bit neg saturation = Int.MIN_VALUE")

    // 3. Vector checks: 16-bit signed integer
    val inFloat16 = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.LITTLE_ENDIAN)
    inFloat16.putFloat(-1.0f)
    inFloat16.putFloat(1.0f)
    inFloat16.putFloat(1.5f)
    inFloat16.putFloat(-1.5f)
    inFloat16.flip()

    val out16 = converter.convert(inFloat16, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_16BIT)
    verify(out16.remaining() == 2 * 4, "16-bit output length: 2 frames * 4 bytes = 8 bytes")
    verify(out16.short == (-32768).toShort(), "16-bit neg full scale")
    verify(out16.short == 32767.toShort(), "16-bit pos full scale")
    verify(out16.short == 32767.toShort(), "16-bit pos saturation")
    verify(out16.short == (-32768).toShort(), "16-bit neg saturation")

    // 4. Float passthrough
    val inFloatPass = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.LITTLE_ENDIAN)
    inFloatPass.putFloat(0.42f); inFloatPass.putFloat(-0.84f); inFloatPass.flip()
    val outFloat = converter.convert(inFloatPass, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_FLOAT)
    verify(outFloat === inFloatPass, "Float target returns input buffer directly without overhead")

    // 5. Stateful writer session: Partial write frame accounting (0 -> 1 -> 2 -> 1 frames)
    // 4 stereo frames = 32 float bytes upstream. Target = 24-bit packed (6 bytes/frame downstream = 24 bytes).
    val session24 = PcmOutputSession(converter, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_24BIT_PACKED)
    val testInput24 = ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 1..8) testInput24.putFloat(i * 0.1f)
    testInput24.flip()

    val accepted24 = mutableListOf<Byte>()
    // Step 1: Downstream accepts 0 frames (0 bytes)
    val w0 = session24.write(testInput24, 0L) { _, _, _ -> 0 }
    verify(!w0, "Step 1: 0 frames accepted returns false")
    verify(testInput24.position() == 0, "Step 1: Input position remains at 0")
    verify(session24.hasPendingData(), "Step 1: Session holds pending data")

    // Step 2: Downstream accepts 1 frame (6 bytes)
    val w1 = session24.write(testInput24, 0L) { buf, len, _ ->
        val toWrite = minOf(6, len)
        for (i in 0 until toWrite) accepted24 += buf.get()
        toWrite
    }
    verify(!w1, "Step 2: 1 frame accepted out of 4 returns false")
    verify(testInput24.position() == 8, "Step 2: Input advanced by 1 float stereo frame (8 bytes)")
    verify(session24.hasPendingData(), "Step 2: Session holds remaining pending data")

    // Step 3: Downstream accepts 2 frames (12 bytes)
    val w2 = session24.write(testInput24, 0L) { buf, len, _ ->
        val toWrite = minOf(12, len)
        for (i in 0 until toWrite) accepted24 += buf.get()
        toWrite
    }
    verify(!w2, "Step 3: 2 frames accepted (total 3/4) returns false")
    verify(testInput24.position() == 24, "Step 3: Input advanced by 2 more float frames (total 24 bytes)")
    verify(session24.hasPendingData(), "Step 3: Session holds last frame")

    // Step 4: Downstream accepts 1 frame (6 bytes)
    val w3 = session24.write(testInput24, 0L) { buf, len, _ ->
        val toWrite = minOf(6, len)
        for (i in 0 until toWrite) accepted24 += buf.get()
        toWrite
    }
    verify(w3, "Step 4: final 1 frame accepted returns true")
    verify(testInput24.position() == 32, "Step 4: Input fully consumed (32 bytes = 4 float frames)")
    verify(!session24.hasPendingData(), "Step 4: Session has no pending data")
    verify(accepted24.size == 24, "Downstream accepted exactly 24 bytes in order")

    // Verify exact PCM once in order matches direct conversion
    val direct24 = converter.convert(
        ByteBuffer.allocateDirect(4 * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 1..8) putFloat(i * 0.1f)
            flip()
        },
        channelCount = 2,
        targetEncoding = PcmTargetEncoding.PCM_24BIT_PACKED
    )
    val directBytes = ByteArray(24).also { direct24.get(it) }
    verify(accepted24.toByteArray().contentEquals(directBytes), "Partial write downstream bytes match direct conversion once in order")

    val splitInput = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
        putFloat(0.5f); putFloat(-0.5f); flip()
    }
    val delivered = ByteArray(6)
    verify(!session24.write(splitInput, 0L) { buffer, _, _ ->
        buffer.get(delivered, 0, 2)
        2
    } && splitInput.position() == 0, "Two bytes do not consume a six-byte stereo frame")
    verify(session24.write(splitInput, 0L) { buffer, _, _ ->
        buffer.get(delivered, 2, 4)
        4
    } && splitInput.position() == 8, "The rest of a split frame consumes the float frame exactly once")
    verify(delivered.contentEquals(byteArrayOf(0, 0, 0x40, 0, 0, 0xc0.toByte())),
        "Split writes preserve the exact packed stereo frame")

    // 6. Flush discards pending data and avoids stale bytes
    val flushInput = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.LITTLE_ENDIAN)
    flushInput.putFloat(0.5f); flushInput.putFloat(-0.5f); flushInput.putFloat(0.2f); flushInput.putFloat(-0.2f); flushInput.flip()
    session24.write(flushInput, 0L) { buf, _, _ ->
        buf.position(buf.position() + 6) // accept 1 frame
        6
    }
    verify(session24.hasPendingData(), "Session has pending data before flush")
    session24.flush()
    verify(!session24.hasPendingData(), "Session has no pending data after flush")

    // 7. Test 32-bit signed integer partial writes: 2 frames (16 bytes downstream) -> 0 then 2 frames
    val session32 = PcmOutputSession(converter, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_32BIT)
    val in32 = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.LITTLE_ENDIAN)
    in32.putFloat(0.25f); in32.putFloat(-0.25f); in32.putFloat(0.75f); in32.putFloat(-0.75f); in32.flip()
    val r32_0 = session32.write(in32, 0L) { _, _, _ -> 0 }
    verify(!r32_0, "32-bit zero write returns false")
    verify(in32.position() == 0, "32-bit input untouched")
    val accepted32 = mutableListOf<Int>()
    val r32_all = session32.write(in32, 0L) { buf, len, _ ->
        while (buf.hasRemaining()) accepted32 += buf.int
        len
    }
    verify(r32_all, "32-bit full write returns true")
    verify(in32.position() == 16, "32-bit input fully advanced")
    verify(accepted32.size == 4, "32-bit 4 samples accepted")

    // 8. Test float passthrough partial write and completion
    val sessionFloat = PcmOutputSession(converter, channelCount = 2, targetEncoding = PcmTargetEncoding.PCM_FLOAT)
    val inFloatPartial = ByteBuffer.allocateDirect(2 * 8).order(ByteOrder.LITTLE_ENDIAN)
    inFloatPartial.putFloat(0.1f); inFloatPartial.putFloat(0.2f); inFloatPartial.putFloat(0.3f); inFloatPartial.putFloat(0.4f); inFloatPartial.flip()
    val rf_part = sessionFloat.write(inFloatPartial, 0L) { buf, _, _ ->
        buf.position(buf.position() + 8) // consume 1 float frame
        8
    }
    verify(!rf_part, "Float partial write returns false")
    verify(inFloatPartial.position() == 8, "Float input advanced by 8 bytes")
    val rf_done = sessionFloat.write(inFloatPartial, 0L) { buf, _, _ ->
        buf.position(buf.position() + 8)
        8
    }
    verify(rf_done, "Float completion returns true")
    verify(inFloatPartial.position() == 16, "Float input fully consumed")

    // 9. Drain at end of stream (empty input returns true)
    val emptyInput = ByteBuffer.allocateDirect(0)
    verify(session24.write(emptyInput, 0L) { _, _, _ -> 0 }, "Empty input at end of stream with no pending data returns true")

    println("PCM output converter checks passed: $count assertions.")
}
