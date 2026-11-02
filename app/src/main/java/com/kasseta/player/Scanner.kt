package com.kasseta.player

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Cached probe result of one audio file. */
class FileEntry(val path: String, val mtime: Long, val size: Long, val probe: Probe)

/** Cached CUE sheet text. */
class CueEntry(val path: String, val mtime: Long, val size: Long, val text: String)

class ScanCache(val files: HashMap<String, FileEntry> = HashMap(), val cues: HashMap<String, CueEntry> = HashMap()) {
    fun save(f: File) {
        val tmp = File(f.path + ".tmp")
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(tmp.outputStream()), 1 shl 16)).use { o ->
            o.writeInt(VERSION)
            o.writeInt(files.size)
            for (e in files.values) {
                writeStr(o, e.path); o.writeLong(e.mtime); o.writeLong(e.size)
                o.writeInt(e.probe.tags.size)
                for ((k, v) in e.probe.tags) { writeStr(o, k); writeStr(o, v) }
                o.writeInt(e.probe.chapters.size)
                for (c in e.probe.chapters) { o.writeLong(c.first); o.writeLong(c.second); writeStr(o, c.third) }
            }
            o.writeInt(cues.size)
            for (c in cues.values) { writeStr(o, c.path); o.writeLong(c.mtime); o.writeLong(c.size); writeStr(o, c.text) }
        }
        tmp.renameTo(f)
    }

    companion object {
        private const val VERSION = 3

        private fun writeStr(o: DataOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            o.writeInt(b.size); o.write(b)
        }

        private fun readStr(i: DataInputStream): String {
            val n = i.readInt()
            if (n < 0 || n > 10_000_000) throw IllegalStateException("bad cache")
            val b = ByteArray(n); i.readFully(b)
            return String(b, Charsets.UTF_8)
        }

        fun load(f: File): ScanCache {
            if (!f.exists()) return ScanCache()
            return try {
                DataInputStream(BufferedInputStream(GZIPInputStream(f.inputStream()), 1 shl 16)).use { i ->
                    if (i.readInt() != VERSION) return ScanCache()
                    val c = ScanCache()
                    repeat(i.readInt()) {
                        val p = readStr(i); val mt = i.readLong(); val sz = i.readLong()
                        val tags = HashMap<String, String>()
                        repeat(i.readInt()) { tags[readStr(i)] = readStr(i) }
                        val ch = ArrayList<Triple<Long, Long, String>>()
                        repeat(i.readInt()) { ch.add(Triple(i.readLong(), i.readLong(), readStr(i))) }
                        c.files[p] = FileEntry(p, mt, sz, Probe(tags, ch))
                    }
                    repeat(i.readInt()) {
                        val p = readStr(i); val mt = i.readLong(); val sz = i.readLong(); val t = readStr(i)
                        c.cues[p] = CueEntry(p, mt, sz, t)
                    }
                    c
                }
            } catch (e: Exception) {
                ScanCache()
            }
        }
    }
}

class ScanProgress {
    @Volatile var running = false
    @Volatile var found = 0
    @Volatile var toProbe = 0
    val probed = AtomicInteger()
    @Volatile var current = ""
}

object Scanner {
    val AUDIO_EXT = setOf(
        "mp3", "mp2", "mp1", "mpga", "flac", "fla", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "aac", "adts",
        "ape", "wv", "wav", "wave", "w64", "rf64", "bwf", "aif", "aiff", "aifc", "dsf", "dff", "wsd", "tta",
        "tak", "wma", "asf", "mpc", "mp+", "mpp", "ac3", "eac3", "ec3", "dts", "dtshd", "mlp", "thd", "truehd",
        "mka", "caf", "oma", "aa3", "at3", "amr", "awb", "au", "snd", "shn", "gsm", "xwma",
    )
    private val SKIP_DIRS = setOf("lost.dir", "system volume information", "\$recycle.bin", "android", ".thumbnails")
    private val GENERIC_DIRS = setOf("music", "музыка", "download", "downloads", "audio", "media", "sdcard", "0", "emulated", "storage", "mnt", "sd", "my music")

    fun isAudio(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO_EXT

    private class Found(val audio: ArrayList<File> = ArrayList(), val cues: ArrayList<File> = ArrayList())

    private fun walk(roots: List<File>, progress: ScanProgress): Found {
        val out = Found()
        val seen = HashSet<String>()
        val stack = ArrayDeque<Pair<File, Int>>()
        roots.forEach { stack.addLast(it to 0) }
        while (stack.isNotEmpty()) {
            val (dir, depth) = stack.removeLast()
            val canon = try { dir.canonicalPath } catch (e: Exception) { dir.path }
            if (!seen.add(canon)) continue
            val list = dir.listFiles() ?: continue
            progress.current = dir.path
            for (f in list) {
                val name = f.name
                if (name.startsWith(".")) continue
                if (f.isDirectory) {
                    val ln = name.lowercase(Locale.ROOT)
                    if (ln in SKIP_DIRS && (ln != "android" || depth == 0)) continue
                    if (depth < 24) stack.addLast(f to depth + 1)
                } else {
                    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (ext == "cue") out.cues.add(f)
                    else if (ext in AUDIO_EXT && f.length() > 1024) { out.audio.add(f); progress.found = out.audio.size }
                }
            }
        }
        return out
    }

    /** Full scan. Reuses [cache] entries of unchanged files; returns tracks and the new cache. */
    fun scan(roots: List<File>, cache: ScanCache, progress: ScanProgress): Pair<List<Track>, ScanCache> {
        progress.running = true
        progress.probed.set(0)
        try {
            val found = walk(roots, progress)
            val newCache = ScanCache()
            val todo = ArrayList<File>()
            for (f in found.audio) {
                val c = cache.files[f.path]
                if (c != null && c.mtime == f.lastModified() && c.size == f.length()) newCache.files[f.path] = c
                else todo.add(f)
            }
            progress.toProbe = todo.size
            if (todo.isNotEmpty()) {
                val pool = Executors.newFixedThreadPool(3)
                val futures = ArrayList<Future<FileEntry?>>()
                for (f in todo) futures.add(pool.submit<FileEntry?> {
                    val blob = try { Native.probe(Native.pathBytes(f.path)) } catch (e: Throwable) { null }
                    progress.probed.incrementAndGet()
                    progress.current = f.path
                    blob?.let { FileEntry(f.path, f.lastModified(), f.length(), Probe.parse(it)) }
                })
                for (fu in futures) {
                    val e = try { fu.get() } catch (ex: Exception) { null }
                    if (e != null) synchronized(newCache) { newCache.files[e.path] = e }
                }
                pool.shutdown()
            }
            for (f in found.cues) {
                val c = cache.cues[f.path]
                if (c != null && c.mtime == f.lastModified() && c.size == f.length()) newCache.cues[f.path] = c
                else if (f.length() < 1_000_000) {
                    val text = try { TextFix.decode(f.readBytes()) } catch (e: Exception) { null }
                    if (text != null) newCache.cues[f.path] = CueEntry(f.path, f.lastModified(), f.length(), text)
                }
            }
            return buildTracks(newCache) to newCache
        } finally {
            progress.running = false
        }
    }

    // ---------- turning files into tracks ----------

    private fun resolveCueFile(cuePath: String, name: String, single: Boolean, audioInDir: (String) -> List<String>): String? {
        val dir = cuePath.substringBeforeLast('/')
        val clean = name.replace('\\', '/').substringAfterLast('/')
        val files = audioInDir(dir)
        files.firstOrNull { it.substringAfterLast('/') == clean }?.let { return it }
        files.firstOrNull { it.substringAfterLast('/').equals(clean, ignoreCase = true) }?.let { return it }
        // FILE "Image.wav" while the image was compressed to .flac/.ape later
        val base = clean.substringBeforeLast('.')
        files.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.').equals(base, ignoreCase = true) }?.let { return it }
        if (single) {
            val cueBase = cuePath.substringAfterLast('/').substringBeforeLast('.')
            files.firstOrNull { it.substringAfterLast('/').substringBeforeLast('.').equals(cueBase, ignoreCase = true) }?.let { return it }
            if (files.size == 1) return files[0]
        }
        return null
    }

    fun buildTracks(cache: ScanCache): List<Track> {
        val byDir = HashMap<String, MutableList<String>>()
        for (p in cache.files.keys) byDir.getOrPut(p.substringBeforeLast('/')) { ArrayList() }.add(p)
        val audioInDir: (String) -> List<String> = { d -> byDir[d] ?: emptyList() }

        val covered = HashMap<String, Pair<CueSheet, CueFile>>()
        for (c in cache.cues.values.sortedBy { it.path }) {
            val sheet = CueSheet.parse(c.text) ?: continue
            val single = sheet.files.size == 1
            for (cf in sheet.files) {
                val target = resolveCueFile(c.path, cf.name, single, audioInDir) ?: continue
                if (!covered.containsKey(target)) covered[target] = sheet to cf
            }
        }

        val out = ArrayList<Track>()
        for (e in cache.files.values) {
            val p = e.probe
            val cue = covered[e.path]
            if (cue != null) { addCueTracks(out, e, cue.first, cue.second.tracks); continue }
            val embedded = p.tags["cuesheet"]?.let { CueSheet.parse(it) }
            val embTracks = embedded?.files?.flatMap { it.tracks }?.sortedBy { it.startMs }
            if (embedded != null && embTracks != null && embTracks.size >= 2) { addCueTracks(out, e, embedded, embTracks); continue }
            if (p.chapters.size >= 2 && p.durMs > 0) { addChapterTracks(out, e); continue }
            out.add(makeTrack(e, 0, 0, -1, "", "", 0, Float.NaN, Float.NaN, null))
        }
        return out
    }

    private fun folderArtist(dir: String): String {
        val albumDir = Library.albumDirKey(dir)
        val parent = albumDir.substringBeforeLast('/', "")
        val name = parent.substringAfterLast('/')
        if (name.isBlank() || name.lowercase(Locale.ROOT) in GENERIC_DIRS || name.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}"))) return ""
        return name
    }

    private fun makeTrack(
        e: FileEntry, sub: Int, startMs: Long, endMs: Long, cueTitle: String, cuePerformer: String, cueNo: Int,
        cueRg: Float, cueRgPeak: Float, sheet: CueSheet?,
    ): Track {
        val p = e.probe
        val dur = if (endMs >= 0) endMs - startMs else (p.durMs - startMs).coerceAtLeast(0)
        val albumArtist = p.tag("album_artist", "albumartist", "album artist", "band").ifBlank { sheet?.performer ?: "" }
        val artist = cuePerformer.ifBlank { if (sheet != null) sheet.performer.ifBlank { p.tag("artist") } else p.tag("artist", "performer") }
            .ifBlank { albumArtist }.ifBlank { folderArtist(e.path.substringBeforeLast('/')) }.ifBlank { Library.UNKNOWN_ARTIST }
        val title = cueTitle.ifBlank { if (sheet == null) p.tag("title") else "" }
            .ifBlank { if (sheet != null) "Трек %02d".format(cueNo) else Library.titleFromFile(e.path) }
        val album = (if (sheet != null) sheet.title.ifBlank { p.tag("album") } else p.tag("album"))
        val year = Probe.year(p.tag("date", "year", "originaldate", "original_date", "tdor")).takeIf { it > 0 } ?: (sheet?.year ?: 0)
        val disc = Probe.leadingInt(p.tag("disc", "discnumber", "disk")).takeIf { it > 0 }
            ?: sheet?.disc?.takeIf { it > 0 } ?: Library.discFromFolder(e.path.substringBeforeLast('/'))
        val trackNo = if (sheet != null || cueNo > 0) cueNo else Probe.leadingInt(p.tag("track", "tracknumber"))
        val rgT = if (!cueRg.isNaN()) cueRg else if (sub == 0) Probe.gain(p.tag("replaygain_track_gain")) else Float.NaN
        val rgTP = if (!cueRgPeak.isNaN()) cueRgPeak else if (sub == 0) Probe.gain(p.tag("replaygain_track_peak")) else Float.NaN
        val rgA = Probe.gain(p.tag("replaygain_album_gain")).takeIf { !it.isNaN() } ?: (sheet?.rgAlbum ?: Float.NaN)
        val rgAP = Probe.gain(p.tag("replaygain_album_peak")).takeIf { !it.isNaN() } ?: (sheet?.rgAlbumPeak ?: Float.NaN)
        return Track(
            path = e.path, startMs = startMs, endMs = endMs, subIndex = sub,
            title = title, artist = artist, album = album, albumArtist = albumArtist,
            genre = p.tag("genre").ifBlank { sheet?.genre ?: "" }, year = year, trackNo = trackNo, disc = disc,
            durMs = dur, codec = p.codec, rate = p.rate, bits = p.bits, channels = p.channels, dsdRate = p.dsd, kbps = p.kbps,
            rgTrack = rgT, rgAlbum = rgA, rgTrackPeak = rgTP, rgAlbumPeak = rgAP, hasEmbeddedCover = p.hasCover,
        )
    }

    private fun addCueTracks(out: MutableList<Track>, e: FileEntry, sheet: CueSheet, tracks: List<CueTrack>) {
        val sorted = tracks.sortedBy { it.startMs }
        val fileDur = e.probe.durMs
        for ((i, t) in sorted.withIndex()) {
            if (fileDur > 0 && t.startMs >= fileDur) continue
            val end = if (i + 1 < sorted.size) sorted[i + 1].startMs else -1
            out.add(makeTrack(e, i + 1, t.startMs, end, t.title, t.performer, t.number, t.rgGain, t.rgPeak, sheet))
        }
    }

    private fun addChapterTracks(out: MutableList<Track>, e: FileEntry) {
        val ch = e.probe.chapters.sortedBy { it.first }
        for ((i, c) in ch.withIndex()) {
            val end = if (i + 1 < ch.size) ch[i + 1].first else -1
            val name = c.third.ifBlank { if (e.probe.codec == "flac") "Трек %02d".format(i + 1) else "Глава ${i + 1}" }
            val base = makeTrack(e, i + 1, c.first, end, name, "", i + 1, Float.NaN, Float.NaN, null)
            out.add(base)
        }
    }
}
