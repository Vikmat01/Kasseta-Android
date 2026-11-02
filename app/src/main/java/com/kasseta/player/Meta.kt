package com.kasseta.player

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Text decoding helpers: Russian collections are full of cp1251 tags and cue sheets. */
object TextFix {
    private val CP1251: Charset = Charset.forName("windows-1251")
    private val LATIN1: Charset = Charsets.ISO_8859_1

    /** Decodes bytes that are UTF-8 if valid, otherwise cp1251. Honors BOMs. */
    fun decode(b: ByteArray, off: Int = 0, len: Int = b.size - off): String {
        if (len >= 3 && b[off] == 0xEF.toByte() && b[off + 1] == 0xBB.toByte() && b[off + 2] == 0xBF.toByte())
            return String(b, off + 3, len - 3, Charsets.UTF_8)
        if (len >= 2 && b[off] == 0xFF.toByte() && b[off + 1] == 0xFE.toByte()) return String(b, off + 2, len - 2, Charsets.UTF_16LE)
        if (len >= 2 && b[off] == 0xFE.toByte() && b[off + 1] == 0xFF.toByte()) return String(b, off + 2, len - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, len)).toString()
        } catch (e: CharacterCodingException) {
            String(b, off, len, CP1251)
        }
    }

    /**
     * Repairs mojibake produced when cp1251 bytes were read as Latin-1 ("Êèíî" → "Кино"),
     * or when UTF-8 was read as Latin-1 ("ÐšÐ¸Ð½Ð¾" → "Кино").
     */
    fun fix(s: String): String {
        if (s.isEmpty()) return s
        var high = 0
        for (c in s) {
            if (c.code > 0xFF) return s
            if (c.code >= 0x80) high++
        }
        if (high == 0) return s
        val bytes = s.toByteArray(LATIN1)
        // UTF-8 read as Latin-1?
        try {
            val u = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            if (u.any { it.code > 0xFF }) return u
        } catch (_: CharacterCodingException) {
        }
        // cp1251 read as Latin-1: most high chars fall into À..ÿ (= А..я in cp1251)
        val letters = s.count { it.code in 0xC0..0xFF || it.code == 0xA8 || it.code == 0xB8 }
        val ascii = s.count { it in 'a'..'z' || it in 'A'..'Z' }
        // "Björk" or "Mötley Crüe" are real Latin-1: only a couple of accents among Latin letters
        val looksRussian = (letters >= 2 && letters * 2 >= ascii) || (letters >= 1 && ascii == 0)
        if (looksRussian && letters * 2 >= high) {
            val r = String(bytes, CP1251)
            if (r.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' } * 2 >= letters) return r
        }
        return s
    }
}

/** Parsed result of Native.probe() for one file. */
class Probe(val tags: Map<String, String>, val chapters: List<Triple<Long, Long, String>>) {
    fun tag(vararg keys: String): String {
        for (k in keys) {
            val v = tags[k]
            if (!v.isNullOrBlank()) return v.trim()
        }
        return ""
    }

    fun int(key: String): Int = tags[key]?.trim()?.toIntOrNull() ?: 0
    val durMs: Long get() = tags["_dur_ms"]?.toLongOrNull() ?: 0L
    val rate get() = int("_rate")
    val channels get() = int("_ch")
    val bits get() = int("_bits")
    val dsd get() = int("_dsd")
    val codec get() = tags["_codec"] ?: ""
    val kbps get() = int("_bitrate")
    val hasCover get() = tags["_cover"] == "1"

    companion object {
        fun parse(blob: ByteArray): Probe {
            val tags = HashMap<String, String>()
            val chapters = ArrayList<Triple<Long, Long, String>>()
            var i = 0
            val n = blob.size
            fun next(): String? {
                if (i >= n) return null
                var j = i
                while (j < n && blob[j] != 0.toByte()) j++
                val s = TextFix.decode(blob, i, j - i)
                i = j + 1
                return s
            }
            while (true) {
                val k = next() ?: break
                val v = next() ?: break
                if (k == "_chapter") {
                    val p = v.split('|', limit = 3)
                    if (p.size == 3) chapters.add(Triple(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, TextFix.fix(p[2])))
                } else if (!tags.containsKey(k)) {
                    tags[k] = if (k.startsWith("_") || k == "cuesheet") v else TextFix.fix(v)
                }
            }
            return Probe(tags, chapters)
        }

        /** "3/12" → 3 */
        fun leadingInt(s: String): Int = Regex("""\d+""").find(s)?.value?.toIntOrNull() ?: 0

        fun year(s: String): Int = Regex("""(1[89]|20)\d\d""").find(s)?.value?.toIntOrNull() ?: 0

        /** "-7.25 dB" → -7.25 */
        fun gain(s: String): Float = Regex("""[-+]?\d+(\.\d+)?""").find(s.replace(',', '.'))?.value?.toFloatOrNull() ?: Float.NaN
    }
}

class CueTrack(val number: Int, val title: String, val performer: String, val startMs: Long, val rgGain: Float, val rgPeak: Float)
class CueFile(val name: String, val tracks: List<CueTrack>)

class CueSheet(
    val title: String,
    val performer: String,
    val year: Int,
    val genre: String,
    val disc: Int,
    val rgAlbum: Float,
    val rgAlbumPeak: Float,
    val files: List<CueFile>,
) {
    companion object {
        private fun tokens(line: String): List<String> {
            val out = ArrayList<String>()
            var i = 0
            val s = line.trim()
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                if (s[i] == '"') {
                    val j = s.indexOf('"', i + 1)
                    if (j < 0) { out.add(s.substring(i + 1)); break }
                    out.add(s.substring(i + 1, j)); i = j + 1
                } else {
                    var j = i
                    while (j < s.length && !s[j].isWhitespace()) j++
                    out.add(s.substring(i, j)); i = j
                }
            }
            return out
        }

        private fun msf(s: String): Long {
            val p = s.split(':')
            if (p.size != 3) return -1
            val m = p[0].toLongOrNull() ?: return -1
            val sec = p[1].toLongOrNull() ?: return -1
            val f = p[2].toLongOrNull() ?: return -1
            return (m * 60 + sec) * 1000 + f * 1000 / 75
        }

        fun parse(text: String): CueSheet? {
            var title = ""; var performer = ""; var year = 0; var genre = ""; var disc = 0
            var rgA = Float.NaN; var rgAP = Float.NaN
            val files = ArrayList<CueFile>()
            var curFile: String? = null
            var curTracks = ArrayList<CueTrack>()
            var tNum = -1; var tTitle = ""; var tPerf = ""; var i0 = -1L; var i1 = -1L; var tRg = Float.NaN; var tRgP = Float.NaN
            fun endTrack() {
                if (tNum >= 0 && curFile != null) {
                    val st = if (i1 >= 0) i1 else i0
                    if (st >= 0) curTracks.add(CueTrack(tNum, tTitle, tPerf, st, tRg, tRgP))
                }
                tNum = -1; tTitle = ""; tPerf = ""; i0 = -1; i1 = -1; tRg = Float.NaN; tRgP = Float.NaN
            }
            fun endFile() {
                endTrack()
                curFile?.let { if (curTracks.isNotEmpty()) files.add(CueFile(it, curTracks)) }
                curTracks = ArrayList()
            }
            for (raw in text.lines()) {
                val t = tokens(raw)
                if (t.isEmpty()) continue
                val inTrack = tNum >= 0
                when (t[0].uppercase()) {
                    "FILE" -> { endFile(); curFile = t.getOrNull(1) ?: "" }
                    "TRACK" -> { endTrack(); tNum = t.getOrNull(1)?.toIntOrNull() ?: 0 }
                    "TITLE" -> if (inTrack) tTitle = t.getOrElse(1) { "" } else title = t.getOrElse(1) { "" }
                    "PERFORMER" -> if (inTrack) tPerf = t.getOrElse(1) { "" } else performer = t.getOrElse(1) { "" }
                    "INDEX" -> {
                        val n = t.getOrNull(1)?.toIntOrNull() ?: -1
                        val ms = msf(t.getOrElse(2) { "" })
                        if (n == 0) i0 = ms else if (n == 1) i1 = ms
                    }
                    "REM" -> {
                        val k = t.getOrElse(1) { "" }.uppercase()
                        val v = t.drop(2).joinToString(" ")
                        when (k) {
                            "DATE" -> year = Probe.year(v)
                            "GENRE" -> genre = v
                            "DISCNUMBER" -> disc = Probe.leadingInt(v)
                            "REPLAYGAIN_ALBUM_GAIN" -> rgA = Probe.gain(v)
                            "REPLAYGAIN_ALBUM_PEAK" -> rgAP = Probe.gain(v)
                            "REPLAYGAIN_TRACK_GAIN" -> if (inTrack) tRg = Probe.gain(v)
                            "REPLAYGAIN_TRACK_PEAK" -> if (inTrack) tRgP = Probe.gain(v)
                        }
                    }
                }
            }
            endFile()
            if (files.isEmpty()) return null
            return CueSheet(title, performer, year, genre, disc, rgA, rgAP, files)
        }

        fun read(f: File): CueSheet? = try {
            if (f.length() > 1_000_000) null else parse(TextFix.decode(f.readBytes()))
        } catch (e: Exception) {
            null
        }
    }
}
