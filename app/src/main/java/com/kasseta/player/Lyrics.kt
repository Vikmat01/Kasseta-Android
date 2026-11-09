package com.kasseta.player

import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * Song lyrics: a .lrc file next to the track, or text embedded in the tags
 * (ID3 USLT, Vorbis LYRICS / UNSYNCEDLYRICS, MP4 ©lyr, APE Lyrics).
 * Timed lines ([mm:ss.xx]) make the lyrics follow the song.
 */
class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    class Line(val ms: Long, val text: String)

    /** index of the line being sung at [posMs] (track time), or -1 before the first one */
    fun lineAt(posMs: Long): Int {
        if (!synced) return -1
        var lo = 0; var hi = lines.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].ms <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        private val cache = object : LinkedHashMap<String, Lyrics?>(32, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Lyrics?>) = size > 40
        }
        private val loading = HashSet<String>()

        /** cached lyrics of [t]: null while not loaded yet or when the track has none; [loaded] tells which */
        fun get(t: Track): Lyrics? = synchronized(cache) { cache[t.id] }
        fun loaded(t: Track): Boolean = synchronized(cache) { cache.containsKey(t.id) }

        /** loads in the background; [done] runs on the main thread */
        fun load(t: Track, done: () -> Unit) {
            synchronized(cache) { if (cache.containsKey(t.id) || !loading.add(t.id)) return }
            Thread({
                val l = try { find(t) } catch (_: Throwable) { null }
                synchronized(cache) { cache[t.id] = l; loading.remove(t.id) }
                main.post(done)
            }, "kasseta-lyrics").start()
        }

        private val userDir by lazy { File(App.ctx.filesDir, "user_lyrics").apply { mkdirs() } }
        private fun userFile(t: Track) = File(userDir, md5(t.id) + ".lrc")
        private fun md5(s: String) = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        fun hasUser(t: Track) = userFile(t).exists()

        /** saves lyrics found on the internet for this track */
        fun saveUser(t: Track, text: String) {
            userFile(t).writeText(text)
            synchronized(cache) { cache.remove(t.id) }
        }

        fun removeUser(t: Track) {
            userFile(t).delete()
            synchronized(cache) { cache.remove(t.id) }
        }

        fun find(t: Track): Lyrics? {
            userFile(t).takeIf { it.isFile }?.let { f -> parse(f.readText(), "из интернета")?.let { return it } }
            for (f in lrcCandidates(t)) {
                if (f.isFile && f.length() in 1..512 * 1024) {
                    val text = TextFix.decode(f.readBytes())
                    parse(text, "файл ${f.name}")?.let { return it }
                }
            }
            // embedded text; a CUE image holds one file for many tracks, so only whole files
            if (t.subIndex > 0) return null
            val blob = try { Native.probe(Native.pathBytes(t.path)) } catch (_: Throwable) { null } ?: return null
            val parts = String(blob, Charsets.UTF_8).split('\u0000')
            var best: String? = null
            var i = 0
            while (i + 1 < parts.size) {
                val k = parts[i].lowercase()
                val v = parts[i + 1]
                if ((k == "lyrics" || k.startsWith("lyrics-") || k == "unsyncedlyrics" || k == "unsynced lyrics" || k == "©lyr" || k == "uslt") && v.isNotBlank()) {
                    // prefer timed lyrics if several are present
                    if (best == null || (!hasTimes(best) && hasTimes(v))) best = v
                }
                i += 2
            }
            return best?.let { parse(TextFix.fix(it), "теги файла") }
        }

        private fun lrcCandidates(t: Track): List<File> {
            val dir = File(t.dir)
            val base = t.fileName.substringBeforeLast('.')
            val list = ArrayList<File>()
            if (t.subIndex == 0) { list.add(File(dir, "$base.lrc")); list.add(File(dir, "$base.LRC")) }
            val n = if (t.trackNo > 0) "%02d".format(t.trackNo) else ""
            val clean = t.title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (n.isNotEmpty()) { list.add(File(dir, "$n - $clean.lrc")); list.add(File(dir, "$n. $clean.lrc")); list.add(File(dir, "$n $clean.lrc")) }
            list.add(File(dir, "$clean.lrc"))
            list.add(File(dir, "${t.artist} - $clean.lrc"))
            return list
        }

        private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        private val META = Regex("""^\[(ar|ti|al|au|by|re|ve|length|#):.*]$""", RegexOption.IGNORE_CASE)
        private val OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]$""", RegexOption.IGNORE_CASE)

        private fun hasTimes(s: String) = TIME.containsMatchIn(s)

        fun parse(text: String, source: String): Lyrics? {
            val raw = text.replace("\r\n", "\n").replace('\r', '\n').trim('﻿', ' ', '\n')
            if (raw.isBlank()) return null
            if (hasTimes(raw)) {
                var offset = 0L
                val out = ArrayList<Line>()
                for (line in raw.lines()) {
                    val l = line.trim()
                    OFFSET.find(l)?.let { offset = it.groupValues[1].toLong(); return@let }
                    if (META.matches(l)) continue
                    val times = TIME.findAll(l).toList()
                    if (times.isEmpty()) continue
                    val words = l.substring(times.last().range.last + 1).replace(WORD_TIME, "").trim()
                    for (m in times) {
                        val mm = m.groupValues[1].toLong()
                        val ss = m.groupValues[2].toLong()
                        val fr = m.groupValues[3]
                        val frac = when (fr.length) { 0 -> 0L; 1 -> fr.toLong() * 100; 2 -> fr.toLong() * 10; else -> fr.take(3).toLong() }
                        out.add(Line(mm * 60000 + ss * 1000 + frac, words))
                    }
                }
                if (out.isEmpty()) return null
                // LRC offset: positive means the lyrics come earlier
                val sorted = out.map { Line(it.ms - offset, it.text) }.sortedBy { it.ms }
                // drop empty lines at the very end, keep inner ones as pauses
                return Lyrics(sorted.dropLastWhile { it.text.isEmpty() }, true, source)
            }
            val lines = raw.lines().map { Line(-1, it.trim()) }
            return Lyrics(lines, false, source)
        }
    }
}
