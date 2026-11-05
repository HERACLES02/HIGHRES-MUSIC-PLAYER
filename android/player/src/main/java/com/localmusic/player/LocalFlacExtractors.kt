@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.localmusic.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.extractor.*
import androidx.media3.extractor.flac.FlacExtractor

/** Reuse the pinned FLAC parser; only its seek-map policy changes. Other formats are untouched. */
class LocalFlacExtractors(private val indexes: FlacSeekIndexes) : ExtractorsFactory {
    private val defaults = DefaultExtractorsFactory()
    override fun createExtractors(): Array<Extractor> = defaults.createExtractors()
    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        defaults.createExtractors(uri, responseHeaders).map {
            if (it is FlacExtractor && uri.scheme in setOf("content", "file")) IndexedFlac(it, indexes, uri) else it
        }.toTypedArray()
}

private class IndexedFlac(private val delegate: FlacExtractor, private val indexes: FlacSeekIndexes,
    private val uri: Uri) : Extractor {
    private var input: ExtractorInput? = null
    private var info: FlacStreamMetadata? = null
    private var infoBytes = ByteArray(0)
    private var fallback: LocalFlacBinarySeeker? = null
    private var handle: FlacSeekIndexes.Handle? = null
    private var marker = 0
    private var pendingPoint: FlacSeekIndexes.Point? = null
    private var targetUs = 0L

    override fun sniff(input: ExtractorInput) = delegate.sniff(input)
    override fun init(output: ExtractorOutput) {
        delegate.init(object : ForwardingExtractorOutput(output) {
            override fun track(id: Int, type: Int): TrackOutput {
                val actual = output.track(id, type)
                return object : TrackOutput by actual {
                    override fun format(format: Format) {
                        infoBytes = format.initializationData.firstOrNull() ?: ByteArray(0)
                        if (infoBytes.size >= 42) info = FlacStreamMetadata(infoBytes, 8)
                        actual.format(format)
                    }
                }
            }
            override fun seekMap(map: SeekMap) {
                val source = requireNotNull(input)
                val metadata = info
                // A real embedded table already has the desired behavior. Unknown-length or
                // sample-count streams retain upstream's unseekable behavior.
                if (map.isSeekable || metadata == null || metadata.totalSamples <= 0 || source.length == C.LENGTH_UNSET.toLong()) {
                    fallback = null; handle = null; output.seekMap(map); return
                }
                marker = FlacMetadataReader.getFrameStartMarker(source)
                fallback = LocalFlacBinarySeeker(metadata, marker, source.position, source.length)
                handle = indexes.prepare(uri, source.position, source.length, infoBytes)
                val ordinary = requireNotNull(fallback).seekMap
                output.seekMap(object : SeekMap {
                    override fun isSeekable() = true
                    override fun getDurationUs() = ordinary.durationUs
                    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                        val points = handle?.points ?: return ordinary.getSeekPoints(timeUs)
                        var low = 0; var high = points.lastIndex
                        while (low < high) {
                            val mid = (low + high + 1) / 2
                            if (points[mid].timeUs <= timeUs) low = mid else high = mid - 1
                        }
                        val point = points[low]
                        return SeekMap.SeekPoints(SeekPoint(point.timeUs, point.position))
                    }
                })
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        this.input = input
        pendingPoint?.let { point ->
            val sample = FlacFrameReader.SampleNumberHolder()
            input.resetPeekPosition()
            val valid = try {
                input.position == point.position && FlacFrameReader.checkFrameHeaderFromPeek(input, requireNotNull(info), marker, sample) &&
                    sample.sampleNumber * 1_000_000 / requireNotNull(info).sampleRate == point.timeUs
            } catch (_: java.io.IOException) { false } finally { input.resetPeekPosition() }
            pendingPoint = null
            if (!valid) {
                handle?.let { indexes.invalidate(it) }
                indexes.noteSeek(uri,"binary_search_invalid_index",targetUs)
                fallback?.setSeekTargetUs(targetUs)
            }
        }
        fallback?.let { seeker ->
            if (seeker.isSeeking) {
                val result = seeker.handlePendingSeek(input, seekPosition)
                if (result == Extractor.RESULT_SEEK) return result
            }
        }
        // Prevent the delegate from creating a second binary seeker. Its parser, native
        // embedded-table handling and sample timestamps remain upstream's implementation.
        return delegate.read(object : ForwardingExtractorInput(input) {
            override fun getLength(): Long = C.LENGTH_UNSET.toLong()
        }, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        targetUs = timeUs
        pendingPoint = null
        fallback?.cancel()
        delegate.seek(position, timeUs)
        if (position == 0L) { fallback = null; indexes.noteSeek(uri,"metadata_reset",timeUs); return }
        val point = handle?.points?.firstOrNull { it.position == position }
        indexes.noteSeek(uri,if(point!=null)"private_index" else if(fallback!=null)"binary_search" else "embedded_table",timeUs)
        if (point != null) pendingPoint = point else fallback?.setSeekTargetUs(timeUs)
    }
    override fun release() = delegate.release()
}
