package com.localmusic.core

enum class RomajiLanguage { KOREAN, JAPANESE }
enum class RomajiMode { ORIGINAL, ROMAJI, BOTH }

/** Romaji shown beside lyrics. It is built from the original lyrics and never written into music files. */
object LyricsRomaji {
    /** Bump when a romanizer's output changes so saved copies are rebuilt. */
    const val VERSION = 2

    fun lines(lyrics: ParsedLyrics): List<String> =
        if (lyrics.kind == LyricsKind.SYNCHRONIZED) lyrics.lines.map { it.text } else lyrics.plainLines

    /** Per song, by script: any Hangul is Korean; else any kana is Japanese; Han characters alone are assumed Chinese. */
    fun language(lyrics: ParsedLyrics): RomajiLanguage? {
        if (lyrics.kind != LyricsKind.SYNCHRONIZED && lyrics.kind != LyricsKind.PLAIN) return null
        val text = lines(lyrics)
        return when {
            text.any { line -> line.any(::isHangul) } -> RomajiLanguage.KOREAN
            text.any { line -> line.any(::isKana) } -> RomajiLanguage.JAPANESE
            else -> null
        }
    }

    fun romanize(lyrics: ParsedLyrics, language: RomajiLanguage): List<String> = lines(lyrics).map { line ->
        when (language) {
            RomajiLanguage.KOREAN -> KoreanRomanizer.line(line)
            RomajiLanguage.JAPANESE -> JapaneseRomanizer.line(line)
        }.replace('\r', ' ').replace('\n', ' ')
    }

    /** The hidden saved copy: a header tying it to this romanizer version and the exact original text, then one line per lyric line. */
    fun encode(lyrics: ParsedLyrics, romaji: List<String>): String {
        require(romaji.size == lines(lyrics).size && romaji.none { '\n' in it })
        return (listOf(header(lyrics)) + romaji).joinToString("\n")
    }

    /** The saved lines, or null when the copy is stale or damaged and must be rebuilt. */
    fun decode(stored: String, lyrics: ParsedLyrics): List<String>? {
        val parts = stored.split('\n')
        if (parts.first() != header(lyrics) || parts.size - 1 != lines(lyrics).size) return null
        return parts.drop(1)
    }

    private fun header(lyrics: ParsedLyrics): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(lyrics.originalText.toByteArray(Charsets.UTF_8))
        return "v$VERSION ${digest.joinToString("") { "%02x".format(it) }} ${lines(lyrics).size}"
    }

    private fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
    // The katakana middle dot and long mark also appear in Chinese text, so they alone do not mean Japanese.
    private fun isKana(c: Char) = (c in 'ぁ'..'ヿ' && c != '・' && c != 'ー') || c in 'ㇰ'..'ㇿ' || c in 'ｦ'..'ﾝ'
}

/**
 * Revised Romanization as sung: sound changes between syllables of a word are applied
 * (liaison, palatalization, aspiration, nasalization, ㄹ assimilation), tensing is not marked,
 * as in the official system. Anything that is not a Hangul syllable is kept unchanged.
 */
object KoreanRomanizer {
    private val ONSET = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val VOWEL = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    // Onset (initial) indices.
    private const val G = 0; private const val KK = 1; private const val N = 2; private const val D = 3; private const val R = 5
    private const val M = 6; private const val B = 7; private const val S = 9; private const val SS = 10; private const val SILENT = 11
    private const val J = 12; private const val CH = 14; private const val K = 15; private const val T = 16; private const val P = 17; private const val H = 18
    private const val VOWEL_I = 20
    // Final indices used by name: ㄴ, ㄶ, ㄹ, ㄺ, ㅀ, ㅎ.
    private const val F_N = 4; private const val F_NH = 6; private const val F_L = 8; private const val F_LG = 9; private const val F_LH = 15; private const val F_H = 27
    // Spoken coda sounds.
    private const val NONE = 0; private const val CK = 1; private const val CN = 2; private const val CT = 3; private const val CL = 4
    private const val CM = 5; private const val CP = 6; private const val CNG = 7
    private val CODA = arrayOf("", "k", "n", "t", "l", "m", "p", "ng")
    /** Final jamo (0 = none, ㄱ ㄲ ㄳ ㄴ ㄵ ㄶ ㄷ ㄹ ㄺ ㄻ ㄼ ㄽ ㄾ ㄿ ㅀ ㅁ ㅂ ㅄ ㅅ ㅆ ㅇ ㅈ ㅊ ㅋ ㅌ ㅍ ㅎ) to its spoken coda. */
    private val NEUTRAL = intArrayOf(NONE, CK, CK, CK, CN, CN, CN, CT, CL, CK, CM, CL, CL, CL, CP, CL, CM, CP, CP, CT, CT, CNG, CT, CT, CK, CT, CP, CT)
    /** Before a silent ㅇ: the final that stays, and the consonant that moves to the next syllable (-1: none). */
    private val STAY = intArrayOf(0, 0, 0, 1, 0, 4, 0, 0, 0, 8, 8, 8, 8, 8, 8, 0, 0, 0, 17, 0, 0, 21, 0, 0, 0, 0, 0, 0)
    private val MOVE = intArrayOf(-1, G, KK, S, N, J, N, D, R, G, M, B, S, T, P, R, M, B, S, S, SS, -1, J, CH, K, T, P, SILENT)
    /** Before ㅎ: the final that stays, and the aspirated consonant that replaces ㅎ (-1: ㅎ is kept). */
    private val ASPIRATE_STAY = intArrayOf(0, 0, 0, 0, 0, 4, 0, 0, 0, 8, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private val ASPIRATE = intArrayOf(-1, K, K, -1, -1, CH, -1, T, -1, K, -1, P, -1, -1, -1, -1, -1, P, -1, T, T, -1, CH, CH, K, T, P, -1)

    fun line(text: String): String {
        val out = StringBuilder(text.length * 2)
        var start = -1
        for (i in 0..text.length) {
            val syllable = i < text.length && text[i] in '가'..'힣'
            if (syllable && start < 0) start = i
            if (!syllable && start >= 0) { out.append(word(text.substring(start, i))); start = -1 }
            if (!syllable && i < text.length) out.append(text[i])
        }
        return out.toString()
    }

    private fun word(text: String): String {
        val count = text.length
        val onset = IntArray(count); val vowel = IntArray(count); val final = IntArray(count); val coda = IntArray(count)
        text.forEachIndexed { i, c -> val code = c.code - 0xAC00; onset[i] = code / 588; vowel[i] = code % 588 / 28; final[i] = code % 28 }
        for (i in 0 until count) {
            if (i == count - 1) { coda[i] = NEUTRAL[final[i]]; break }
            val next = i + 1
            var f = final[i]
            var o = onset[next]
            val h = f == F_H || f == F_NH || f == F_LH
            when {
                o == SILENT && MOVE[f] >= 0 -> {
                    o = MOVE[f]; f = STAY[f]
                    if (vowel[next] == VOWEL_I) o = when (o) { D -> J; T -> CH; else -> o } // 같이 → gachi
                }
                o == H && ASPIRATE[f] >= 0 -> {
                    o = ASPIRATE[f]; f = ASPIRATE_STAY[f]
                    if (o == T && vowel[next] == VOWEL_I) o = CH // 굳히다 → guchida
                }
                h -> {
                    o = when (o) { G -> K; D -> T; J -> CH; S -> SS; else -> o }
                    f = when { onset[next] == N && f == F_H -> F_N; f == F_NH -> F_N; f == F_LH -> F_L; else -> 0 }
                }
            }
            var c = if (f == F_LG && o == G) CL else NEUTRAL[f]
            if (o == N || o == M) c = nasal(c)
            else if (o == R) when (c) {
                CK, CT, CP -> { c = nasal(c); o = N }
                CM, CNG -> o = N
                CN -> c = CL
            }
            if (c == CL && o == N) o = R
            coda[i] = c; onset[next] = o
        }
        val out = StringBuilder(count * 4)
        for (i in 0 until count) {
            out.append(if (onset[i] == R && i > 0 && coda[i - 1] == CL) "l" else ONSET[onset[i]])
            out.append(VOWEL[vowel[i]]).append(CODA[coda[i]])
        }
        return out.toString()
    }

    private fun nasal(coda: Int) = when (coda) { CK -> CNG; CT -> CN; CP -> CM; else -> coda }
}

/**
 * Plain-ASCII Hepburn from Kuromoji word readings: particles as pronounced (は wa, へ e, を o),
 * long vowels spelled out (kyou, raamen), one space between words, verb endings kept on their verb.
 * Text without a reading (Latin words, unknown kanji) is kept unchanged.
 */
object JapaneseRomanizer {
    // Loading the dictionary takes about a second and tens of MB, so it happens once, on first use.
    private val tokenizer by lazy { com.atilika.kuromoji.ipadic.Tokenizer() }
    private val CONNECTIVE = setOf("て", "で", "ば")
    private val GREETING = setOf("こんにちは", "こんばんは")
    private const val OPENING = "(\"[{"

    /** Only Japanese runs go through Kuromoji; English and other text is copied exactly, so "Both" never repeats it. */
    fun line(text: String): String {
        val out = StringBuilder()
        var start = 0
        while (start < text.length) {
            val japanese = isJapanese(text[start])
            var end = start
            while (end < text.length && isJapanese(text[end]) == japanese) end++
            if (!japanese) out.append(text, start, end)
            else romanize(text.substring(start, end)).takeIf { it.isNotEmpty() }?.let { romaji ->
                if (out.lastOrNull()?.isLetterOrDigit() == true) out.append(' ')
                out.append(romaji)
                if (end < text.length && text[end].isLetterOrDigit()) out.append(' ')
            }
            start = end
        }
        return out.toString()
    }

    // Kana, kanji, Japanese punctuation and full-width forms.
    private fun isJapanese(c: Char) = c in '　'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' ||
        c in '豈'..'﫿' || c in '！'..'ﾟ'

    private fun romanize(text: String): String {
        val out = StringBuilder()
        val kana = StringBuilder()
        var space = false
        var inflecting = false
        fun word(romaji: String) { if (space && out.isNotEmpty()) out.append(' '); out.append(romaji); space = true }
        fun flush() { if (kana.isNotEmpty()) { word(kanaToRomaji(kana.toString())); kana.clear() } }
        for (token in tokenizer.tokenize(text)) {
            val surface = token.surface
            val pos = token.partOfSpeechLevel1
            val sub = token.partOfSpeechLevel2
            if (surface.isBlank()) { flush(); inflecting = false; continue }
            val ending = inflecting && kana.isNotEmpty() && (pos == "助動詞" || (pos == "動詞" && (sub == "非自立" || sub == "接尾")) ||
                (pos == "助詞" && sub == "接続助詞" && surface in CONNECTIVE))
            if (!ending) flush()
            inflecting = ending || pos == "動詞" || pos == "形容詞" || pos == "助動詞"
            val reading = token.reading?.takeIf { it != "*" && it.isNotEmpty() } ?: surface.takeIf { it.all(::isKana) }
            when {
                pos == "記号" -> {
                    val mark = java.text.Normalizer.normalize(surface, java.text.Normalizer.Form.NFKC)
                        .replace('、', ',').replace('。', '.').replace('・', ' ').replace('〜', '~')
                        .replace(Regex("[「」『』]"), "\"")
                    if (mark.isBlank()) continue
                    if (mark[0] in OPENING) { if (space && out.isNotEmpty()) out.append(' '); out.append(mark); space = false }
                    else { out.append(mark); space = true }
                }
                pos == "助詞" && surface == "は" -> word("wa")
                pos == "助詞" && surface == "へ" -> word("e")
                surface in GREETING && reading != null -> word(kanaToRomaji(reading.dropLast(1)) + "wa")
                reading != null -> kana.append(reading)
                else -> word(java.text.Normalizer.normalize(surface, java.text.Normalizer.Form.NFKC))
            }
        }
        flush()
        return out.toString()
    }

    private fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ー'

    private val MONO: Map<Char, String> = ("ア a イ i ウ u エ e オ o カ ka キ ki ク ku ケ ke コ ko サ sa シ shi ス su セ se ソ so " +
        "タ ta チ chi ツ tsu テ te ト to ナ na ニ ni ヌ nu ネ ne ノ no ハ ha ヒ hi フ fu ヘ he ホ ho マ ma ミ mi ム mu メ me モ mo " +
        "ヤ ya ユ yu ヨ yo ラ ra リ ri ル ru レ re ロ ro ワ wa ヰ i ヱ e ヲ o ン n ガ ga ギ gi グ gu ゲ ge ゴ go ザ za ジ ji ズ zu ゼ ze ゾ zo " +
        "ダ da ヂ ji ヅ zu デ de ド do バ ba ビ bi ブ bu ベ be ボ bo パ pa ピ pi プ pu ペ pe ポ po ヴ vu " +
        "ァ a ィ i ゥ u ェ e ォ o ャ ya ュ yu ョ yo ヮ wa ヵ ka ヶ ke").split(' ').chunked(2).associate { it[0][0] to it[1] }
    private val DIGRAPH: Map<String, String> = buildMap {
        mapOf('キ' to "ky", 'ギ' to "gy", 'シ' to "sh", 'ジ' to "j", 'チ' to "ch", 'ヂ' to "j", 'ニ' to "ny",
            'ヒ' to "hy", 'ビ' to "by", 'ピ' to "py", 'ミ' to "my", 'リ' to "ry").forEach { (kana, start) ->
            put("${kana}ャ", start + "a"); put("${kana}ュ", start + "u"); put("${kana}ョ", start + "o")
        }
        ("シェ she ジェ je チェ che ティ ti ディ di トゥ tu ドゥ du テュ tyu デュ dyu ファ fa フィ fi フェ fe フォ fo フュ fyu " +
            "ウィ wi ウェ we ウォ wo ヴァ va ヴィ vi ヴェ ve ヴォ vo イェ ye ツァ tsa ツェ tse ツォ tso").split(' ').chunked(2).forEach { put(it[0], it[1]) }
    }

    private fun kanaToRomaji(text: String): String {
        val kana = String(CharArray(text.length) { val c = text[it]; if (c in 'ぁ'..'ゖ') c + 0x60 else c })
        val out = StringBuilder()
        var double = false
        var i = 0
        while (i < kana.length) {
            val c = kana[i]
            if (c == 'ッ') { double = true; i++; continue }
            if (c == 'ー') { out.lastOrNull()?.takeIf { it in "aiueo" }?.let { out.append(it) }; i++; continue }
            val pair = if (i + 1 < kana.length) DIGRAPH[kana.substring(i, i + 2)] else null
            val romaji = pair ?: MONO[c] ?: c.toString()
            i += if (pair != null) 2 else 1
            if (double && romaji[0] in "bcdfghjkmprstvwz") out.append(if (romaji.startsWith("ch")) 't' else romaji[0])
            double = false
            out.append(romaji)
            // ん before a vowel or y gets an apostrophe so "ren'ai" is not read as "re-nai".
            if (c == 'ン' && i < kana.length && MONO[kana[i]]?.first()?.let { it in "aiueoy" } == true) out.append('\'')
        }
        return out.toString()
    }
}
