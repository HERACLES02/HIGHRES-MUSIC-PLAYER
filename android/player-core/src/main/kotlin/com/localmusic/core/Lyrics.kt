package com.localmusic.core

enum class LyricsKind { EMPTY, PLAIN, SYNCHRONIZED, INSTRUMENTAL }
data class TimedLine(val timestampMs: Long, val text: String, val sourceLine: Int)
data class LyricsWarning(val sourceLine: Int, val message: String)
data class ParsedLyrics(val originalText: String, val lines: List<TimedLine>, val plainLines: List<String>,
                        val metadata: Map<String, List<String>>, val fileOffsetMs: Long,
                        val kind: LyricsKind, val warnings: List<LyricsWarning>) {
    init {
        require(fileOffsetMs in -86_400_000L..86_400_000L)
        require(lines.size <= 20000 && lines.all { it.timestampMs in 0..59_999_999_999L })
        require(lines.zipWithNext().all { it.first.timestampMs <= it.second.timestampMs })
    }
}

object LrcParser {
    const val MAX_CHARS = 1_000_000
    const val MAX_LINES = 20_000
    const val MAX_ADJUSTMENT_MS = 86_400_000L
    private val prefix = Regex("^(?:\\[[^]\\r\\n]{0,128}])+")
    private val token = Regex("\\[([^]]*)]")
    private val timestamp = Regex("(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?")
    private val metadata = Regex("([A-Za-z][A-Za-z0-9_-]*):(.*)")

    fun parse(text: String, instrumental: Boolean = false): ParsedLyrics {
        require(text.length <= MAX_CHARS) { "Lyrics exceed the one-million-character limit" }
        val timed = mutableListOf<TimedLine>()
        val plain = mutableListOf<String>()
        val tags = linkedMapOf<String, MutableList<String>>()
        val warnings = mutableListOf<LyricsWarning>()
        var offset = 0L
        fun warn(line: Int, message: String) { if (warnings.size < 200) warnings += LyricsWarning(line, message) }
        text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
            require(index < MAX_LINES) { "Too many lyric source lines" }
            val line = raw.trimEnd('\r')
            val head = prefix.find(line)?.value.orEmpty()
            val times = mutableListOf<Long>()
            val unknown = StringBuilder()
            var recognizedMetadata = false
            for (tag in token.findAll(head)) {
                val value = tag.groupValues[1]
                val time = timestamp.matchEntire(value)
                val meta = metadata.matchEntire(value)
                when {
                    time != null -> {
                        val fraction = time.groupValues[3].padEnd(3, '0').ifEmpty { "000" }.toLong()
                        times += time.groupValues[1].toLong() * 60000 + time.groupValues[2].toLong() * 1000 + fraction
                    }
                    meta != null -> {
                        val key = meta.groupValues[1].lowercase(java.util.Locale.ROOT)
                        val content = meta.groupValues[2]
                        tags.getOrPut(key) { mutableListOf() }.add(content)
                        recognizedMetadata = true
                        if (key == "offset") {
                            val parsed = content.trim().toLongOrNull()
                            if (parsed == null || parsed !in -MAX_ADJUSTMENT_MS..MAX_ADJUSTMENT_MS) warn(index + 1, "Invalid offset; previous valid value retained")
                            else offset = parsed
                        }
                    }
                    else -> {
                        unknown.append(tag.value)
                        if (value.firstOrNull()?.isDigit() == true) warn(index + 1, "Malformed timestamp retained as text")
                    }
                }
            }
            if (times.isNotEmpty()) {
                require(timed.size + times.size <= MAX_LINES) { "Too many timed lyric entries" }
                val content = unknown.toString() + line.substring(head.length)
                times.forEach { timed += TimedLine(it, content, index + 1) }
            } else if (line.isNotEmpty() && (!recognizedMetadata || line.length > head.length || unknown.isNotEmpty())) {
                plain += if (recognizedMetadata) unknown.toString() + line.substring(head.length) else line
            }
        }
        val kind = when {
            instrumental -> LyricsKind.INSTRUMENTAL // explicit provider/user state, never inferred from lyric words
            timed.isNotEmpty() -> LyricsKind.SYNCHRONIZED
            plain.isNotEmpty() -> LyricsKind.PLAIN
            else -> LyricsKind.EMPTY
        }
        return ParsedLyrics(text, timed.sortedBy { it.timestampMs }, plain.toList(), tags.mapValues { it.value.toList() }, offset, kind, warnings.toList())
    }

    /** Insert a timestamp from engine position. Does not rewrite existing text or apply a selection offset. */
    fun stamp(positionMs: Long, line: String): String {
        require(positionMs in 0..59_999_999_999L); require(!line.contains('\n') && !line.contains('\r'))
        return "[%02d:%02d.%03d]%s".format(java.util.Locale.ROOT, positionMs / 60000, positionMs / 1000 % 60, positionMs % 1000, line)
    }
}

/** File offset positive = earlier; app adjustment positive = later. Both leave original text intact. */
class LyricsTimeline(val lyrics: ParsedLyrics, val adjustmentMs: Long = 0) {
    init { require(adjustmentMs in -LrcParser.MAX_ADJUSTMENT_MS..LrcParser.MAX_ADJUSTMENT_MS) }
    private fun effective(line: TimedLine) = line.timestampMs - lyrics.fileOffsetMs + adjustmentMs
    fun seekPosition(lineIndex: Int): Long = effective(lyrics.lines[lineIndex]).coerceAtLeast(0)
    fun activeIndices(positionMs: Long): List<Int> {
        if (lyrics.kind != LyricsKind.SYNCHRONIZED || positionMs < 0) return emptyList()
        var low = 0; var high = lyrics.lines.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (effective(lyrics.lines[mid]) <= positionMs) low = mid + 1 else high = mid
        }
        if (low == 0) return emptyList()
        val last = low - 1
        var first = last
        while (first > 0 && lyrics.lines[first - 1].timestampMs == lyrics.lines[last].timestampMs) --first
        return (first..last).toList()
    }
}

object LyricsSelection {
    fun choose(explicit: SavedLyrics?, neighboring: SavedLyrics?, embedded: SavedLyrics?): SavedLyrics? =
        explicit ?: neighboring ?: embedded
}
