package com.kasseta.player

import java.io.File
import java.text.Collator
import java.util.Locale

/** One playable item: a whole file, or a region of a file described by a CUE sheet / chapters. */
class Track(
    val path: String,
    /** start/end inside the file (CUE); endMs < 0 means "to the end of the file" */
    val startMs: Long,
    val endMs: Long,
    val subIndex: Int,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val year: Int,
    val trackNo: Int,
    val disc: Int,
    val durMs: Long,
    val codec: String,
    val rate: Int,
    val bits: Int,
    val channels: Int,
    val dsdRate: Int,
    val kbps: Int,
    val rgTrack: Float,
    val rgAlbum: Float,
    val rgTrackPeak: Float,
    val rgAlbumPeak: Float,
    val hasEmbeddedCover: Boolean,
) {
    val id: String = if (subIndex > 0) "$path#$subIndex" else path
    var albumIdx = -1
    val dir: String get() = path.substringBeforeLast('/', "")
    val fileName: String get() = path.substringAfterLast('/')

    /** quality class: 1 lossy, 2 CD-quality lossless, 3 hi-res lossless, 4 DSD */
    val tier: Int
        get() = when {
            dsdRate > 0 -> 4
            isLossless(codec) && (rate > 48000 || bits > 16) -> 3
            isLossless(codec) -> 2
            else -> 1
        }

    /** "FLAC", "DSD128", "MP3"... */
    val formatName: String get() = formatName(codec, dsdRate, path)

    /** "24 бит · 96 кГц", "DSD128 · 5,6 МГц", "320 кбит/с · 44,1 кГц" */
    val qualityLine: String
        get() {
            if (dsdRate > 0) return "%s · %s МГц".format(formatName, fmt1(dsdRate / 1_000_000.0))
            val khz = if (rate > 0) fmtKhz(rate) + " кГц" else ""
            return if (isLossless(codec)) {
                listOf(if (bits > 0) "$bits бит" else "", khz).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                val std = intArrayOf(64, 96, 128, 160, 192, 224, 256, 320).firstOrNull { kotlin.math.abs(it - kbps) <= it / 40 + 1 } ?: kbps
                listOf(if (kbps > 0) "$std кбит/с" else "", khz).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        }

    companion object {
        fun formatName(codec: String, dsdRate: Int, path: String): String {
            if (dsdRate > 0) {
                val mult = (dsdRate + 1_000_000) / 2_822_400 * 64
                return if (codec == "dst") "DST$mult" else "DSD$mult"
            }
            val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return when {
                codec == "flac" -> "FLAC"
                codec == "alac" -> "ALAC"
                codec == "ape" -> "APE"
                codec == "wavpack" -> "WavPack"
                codec == "tta" -> "TTA"
                codec == "tak" -> "TAK"
                codec == "shorten" -> "SHN"
                codec == "mlp" || codec == "truehd" -> "TrueHD"
                codec == "wmalossless" -> "WMA Lossless"
                codec.startsWith("pcm_") -> when (ext) { "aif", "aiff", "aifc" -> "AIFF"; "w64" -> "W64"; else -> "WAV" }
                codec.startsWith("mp3") -> "MP3"
                codec == "mp2" || codec == "mp2float" -> "MP2"
                codec == "aac" || codec == "aac_latm" -> "AAC"
                codec == "vorbis" -> "Vorbis"
                codec == "opus" -> "Opus"
                codec.startsWith("wma") -> "WMA"
                codec == "ac3" -> "AC-3"
                codec == "eac3" -> "E-AC-3"
                codec == "dts" -> "DTS"
                codec.startsWith("musepack") || codec.startsWith("mpc") -> "Musepack"
                codec.startsWith("atrac") -> "ATRAC"
                codec.startsWith("amr") -> "AMR"
                codec.startsWith("adpcm") -> "ADPCM"
                else -> codec.uppercase(Locale.ROOT)
            }
        }

        fun isLossless(codec: String): Boolean =
            codec in setOf("flac", "alac", "ape", "wavpack", "tta", "tak", "shorten", "mlp", "truehd", "wmalossless") ||
                codec.startsWith("pcm_")

        fun fmt1(v: Double): String {
            val s = String.format(Locale.ROOT, "%.1f", v)
            return (if (s.endsWith(".0")) s.dropLast(2) else s).replace('.', ',')
        }

        fun fmtKhz(rate: Int): String = fmt1(rate / 1000.0)
    }
}

class Album(
    val key: String,
    val name: String,
    val artist: String,
    val year: Int,
    val tracks: IntArray,
    val durMs: Long,
    val dir: String,
) {
    var artistIdx = -1
}

class Artist(val name: String, val albums: IntArray, val trackCount: Int)

/** Immutable, indexed view of the music collection. */
class Library(val tracks: List<Track>) {
    val albums: List<Album>
    val artists: List<Artist>
    val tracksByTitle: IntArray
    val albumsSorted: IntArray
    private val byId = HashMap<String, Int>()

    init {
        val col = collator()
        tracks.forEachIndexed { i, t -> byId[t.id] = i }

        // group tracks into albums
        val groups = LinkedHashMap<String, MutableList<Int>>()
        tracks.forEachIndexed { i, t ->
            val albumName = t.album.ifBlank { folderAlbumName(t.dir) }
            val owner = if (t.albumArtist.isNotBlank()) t.albumArtist.lowercase() else albumDirKey(t.dir)
            val key = albumName.lowercase() + "\u0001" + owner
            groups.getOrPut(key) { ArrayList() }.add(i)
        }
        val al = ArrayList<Album>()
        for ((key, idx) in groups) {
            idx.sortWith(Comparator { a, b ->
                val x = tracks[a]; val y = tracks[b]
                when {
                    x.disc != y.disc -> x.disc.compareTo(y.disc)
                    x.trackNo > 0 && y.trackNo > 0 && x.trackNo != y.trackNo -> x.trackNo.compareTo(y.trackNo)
                    x.path != y.path -> col.compare(x.path, y.path)
                    else -> x.startMs.compareTo(y.startMs)
                }
            })
            val first = tracks[idx[0]]
            val name = first.album.ifBlank { folderAlbumName(first.dir) }
            val aa = idx.map { tracks[it].albumArtist }.firstOrNull { it.isNotBlank() }
            val artist = aa ?: run {
                val arts = idx.map { tracks[it].artist.ifBlank { UNKNOWN_ARTIST } }.distinctBy { it.lowercase() }
                if (arts.size == 1) arts[0] else VARIOUS
            }
            val year = idx.map { tracks[it].year }.filter { it > 0 }.minOrNull() ?: 0
            val dur = idx.sumOf { tracks[it].durMs }
            val ai = al.size
            idx.forEach { tracks[it].albumIdx = ai }
            al.add(Album(key, name, artist, year, idx.toIntArray(), dur, albumDirKey(first.dir)))
        }
        albums = al

        // artists = album artists
        val byArtist = LinkedHashMap<String, MutableList<Int>>()
        al.forEachIndexed { i, a -> byArtist.getOrPut(a.artist.lowercase()) { ArrayList() }.add(i) }
        val ar = ArrayList<Artist>()
        for ((_, list) in byArtist) {
            list.sortWith(Comparator { a, b ->
                val ya = al[a].year; val yb = al[b].year
                if (ya != yb && ya > 0 && yb > 0) ya.compareTo(yb) else col.compare(al[a].name, al[b].name)
            })
            ar.add(Artist(al[list[0]].artist, list.toIntArray(), list.sumOf { al[it].tracks.size }))
        }
        ar.sortWith(Comparator { a, b ->
            val va = a.name == VARIOUS || a.name == UNKNOWN_ARTIST
            val vb = b.name == VARIOUS || b.name == UNKNOWN_ARTIST
            if (va != vb) va.compareTo(vb) else col.compare(sortKey(a.name), sortKey(b.name))
        })
        artists = ar
        ar.forEachIndexed { i, a -> a.albums.forEach { al[it].artistIdx = i } }

        albumsSorted = al.indices.sortedWith(Comparator { a, b -> col.compare(sortKey(al[a].name), sortKey(al[b].name)) }).toIntArray()
        tracksByTitle = tracks.indices.sortedWith(Comparator { a, b -> col.compare(sortKey(tracks[a].title), sortKey(tracks[b].title)) }).toIntArray()
    }

    fun indexOf(id: String): Int = byId[id] ?: -1

    private val albumByKey = HashMap<String, Int>().also { m -> albums.forEachIndexed { i, a -> m[a.key] = i } }
    private val artistByName = HashMap<String, Int>().also { m -> artists.forEachIndexed { i, a -> m[a.name.lowercase()] = i } }
    fun albumIndex(key: String): Int = albumByKey[key] ?: -1
    fun artistIndex(name: String): Int = artistByName[name.lowercase()] ?: -1

    companion object {
        const val VARIOUS = "Разные исполнители"
        const val UNKNOWN_ARTIST = "Неизвестный исполнитель"
        val EMPTY = Library(emptyList())

        fun collator(): Collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.PRIMARY }

        fun sortKey(s: String): String {
            val t = s.trimStart('«', '"', '\'', '(', '[', ' ')
            return if (t.length > 4 && t.regionMatches(0, "The ", 0, 4, ignoreCase = true)) t.substring(4) else t
        }

        private val discDir = Regex("""^(cd|disc|disk|диск|side)[\s._-]*\d+.*$""", RegexOption.IGNORE_CASE)

        fun isDiscFolder(name: String) = discDir.matches(name.trim())

        /** album directory, treating CD1/CD2 subfolders as one album */
        fun albumDirKey(dir: String): String {
            val name = dir.substringAfterLast('/')
            return if (isDiscFolder(name)) dir.substringBeforeLast('/', dir) else dir
        }

        fun folderAlbumName(dir: String): String {
            val d = albumDirKey(dir)
            val n = d.substringAfterLast('/')
            return n.ifBlank { "Без альбома" }
        }

        fun discFromFolder(dir: String): Int {
            val name = dir.substringAfterLast('/')
            if (!isDiscFolder(name)) return 0
            return Regex("""\d+""").find(name)?.value?.toIntOrNull() ?: 0
        }

        fun titleFromFile(path: String): String {
            var n = File(path).nameWithoutExtension
            n = n.replace(Regex("""^\s*\d{1,3}\s*[-._)]\s*"""), "")
            n = n.replace('_', ' ').trim()
            return n.ifBlank { File(path).nameWithoutExtension }
        }
    }
}

fun fmtTime(ms: Long): String {
    if (ms < 0) return "–:––"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Russian plural: 1 трек, 2 трека, 5 треков */
fun plural(n: Int, one: String, few: String, many: String): String {
    val m10 = n % 10; val m100 = n % 100
    val w = when {
        m10 == 1 && m100 != 11 -> one
        m10 in 2..4 && m100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}
