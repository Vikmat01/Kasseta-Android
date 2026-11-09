package com.kasseta.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Album covers: folder images or embedded pictures, scaled to square thumbnails, cached in RAM and on disk. */
object Covers {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var diskDir: File
    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    private val pending = HashSet<String>()
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingDeque())
    var onLoaded: (() -> Unit)? = null

    private lateinit var userDir: File

    fun init(cacheDir: File) {
        diskDir = File(cacheDir, "covers").apply { mkdirs() }
        userDir = File(cacheDir.parentFile, "user_covers").apply { mkdirs() }
    }

    /** cover the user downloaded for this album (kept in the app, works for SD cards too) */
    private fun userFile(album: Album) = File(userDir, md5(album.key) + ".jpg")
    fun hasUserCover(album: Album) = userFile(album).exists()

    /** saves a downloaded picture as the album's cover; false if it is not an image */
    fun setUserCover(album: Album, bytes: ByteArray): Boolean {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0) return false
        userFile(album).writeBytes(bytes)
        forget(album)
        return true
    }

    fun removeUserCover(album: Album) { userFile(album).delete(); forget(album) }

    /** drops every cached size of this album's cover so it is read again */
    private fun forget(album: Album) {
        for (b in listOf(96, 192, 384, 640, 1024)) {
            val k = album.key + "@" + b
            mem.remove(k)
            File(diskDir, md5(k) + ".jpg").delete()
        }
        synchronized(missing) { missing.remove(album.key) }
        main.post { onLoaded?.invoke() }
    }

    fun bucket(px: Int): Int = when {
        px <= 96 -> 96
        px <= 192 -> 192
        px <= 384 -> 384
        px <= 640 -> 640
        else -> 1024
    }

    /** true if the album is known to have no cover at all */
    fun isMissing(album: Album): Boolean = synchronized(missing) { album.key in missing }

    /** Cached bitmap or null; when null, loading is scheduled and [onLoaded] fires later. */
    fun get(lib: Library, albumIdx: Int, px: Int): Bitmap? {
        if (albumIdx < 0 || albumIdx >= lib.albums.size) return null
        val album = lib.albums[albumIdx]
        val b = bucket(px)
        val key = album.key + "@" + b
        mem.get(key)?.let { return it }
        if (isMissing(album)) return null
        // a different size already in memory is a fine stand-in while the right one loads
        val alt = listOf(96, 192, 384, 640, 1024).asReversed().firstNotNullOfOrNull { mem.get(album.key + "@" + it) }
        synchronized(pending) {
            if (pending.add(key)) {
                val tracks = album.tracks.map { lib.tracks[it] }
                pool.execute { load(album, tracks, b, key) }
            }
        }
        return alt
    }

    /** Synchronous load for the notification / lock screen. */
    fun getBlocking(lib: Library, albumIdx: Int, px: Int): Bitmap? {
        if (albumIdx < 0 || albumIdx >= lib.albums.size) return null
        val album = lib.albums[albumIdx]
        val b = bucket(px)
        val key = album.key + "@" + b
        mem.get(key)?.let { return it }
        if (isMissing(album)) return null
        return loadNow(album, album.tracks.map { lib.tracks[it] }, b, key)
    }

    private fun load(album: Album, tracks: List<Track>, size: Int, key: String) {
        try {
            loadNow(album, tracks, size, key)
        } catch (_: Throwable) {
        } finally {
            synchronized(pending) { pending.remove(key) }
            main.post { onLoaded?.invoke() }
        }
    }

    private fun loadNow(album: Album, tracks: List<Track>, size: Int, key: String): Bitmap? {
        val disk = File(diskDir, md5(key) + ".jpg")
        if (disk.exists()) {
            BitmapFactory.decodeFile(disk.path)?.let { mem.put(key, it); return it }
        }
        val bmp = decodeSource(album, tracks, size)
        if (bmp == null) {
            synchronized(missing) { missing.add(album.key) }
            return null
        }
        mem.put(key, bmp)
        try {
            disk.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (_: Exception) {
        }
        return bmp
    }

    private val goodNames = listOf("cover", "folder", "front", "albumart", "album", "obl", "обложка")
    private val badNames = listOf("back", "inlay", "inside", "cd", "disc", "tray", "booklet", "matrix")

    private fun folderImage(dirs: List<String>): File? {
        val imgs = ArrayList<File>()
        for (d in dirs) {
            val dir = File(d)
            dir.listFiles()?.forEach { f ->
                val n = f.name.lowercase(Locale.ROOT)
                if (f.isFile && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp"))) imgs.add(f)
                else if (f.isDirectory && n in setOf("covers", "cover", "scans", "artwork", "art", "сканы", "обложки")) {
                    f.listFiles()?.filter { x ->
                        val m = x.name.lowercase(Locale.ROOT)
                        (m.endsWith(".jpg") || m.endsWith(".jpeg") || m.endsWith(".png")) && (m.contains("front") || m.contains("cover"))
                    }?.let { imgs.addAll(it) }
                }
            }
        }
        if (imgs.isEmpty()) return null
        fun score(f: File): Int {
            val n = f.nameWithoutExtension.lowercase(Locale.ROOT)
            var s = 0
            goodNames.forEachIndexed { i, g -> if (n == g) s = maxOf(s, 100 - i) else if (n.contains(g)) s = maxOf(s, 60 - i) }
            if (badNames.any { n.contains(it) } && s < 60) s -= 50
            return s
        }
        return imgs.maxWithOrNull(compareBy<File>({ score(it) }, { it.length() }))?.takeIf { score(it) > -40 }
    }

    private fun decodeSource(album: Album, tracks: List<Track>, size: Int): Bitmap? {
        userFile(album).takeIf { it.exists() }?.let { f -> decodeFile(f, size)?.let { return it } }
        val dirs = (tracks.map { it.dir } + album.dir).distinct()
        val folder = folderImage(dirs)
        val named = folder != null && goodNames.any { folder.nameWithoutExtension.lowercase(Locale.ROOT).contains(it) }
        if (folder != null && named) decodeFile(folder, size)?.let { return it }
        for (t in tracks.distinctBy { it.path }.take(4)) {
            if (!t.hasEmbeddedCover) continue
            val bytes = try { Native.cover(Native.pathBytes(t.path)) } catch (e: Throwable) { null } ?: continue
            decodeBytes(bytes, size)?.let { return it }
        }
        if (folder != null) decodeFile(folder, size)?.let { return it }
        return null
    }

    private fun decodeFile(f: File, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        o.inJustDecodeBounds = false
        o.inSampleSize = sample(o.outWidth, o.outHeight, size)
        return BitmapFactory.decodeFile(f.path, o)?.let { square(it, size) }
    }

    private fun decodeBytes(b: ByteArray, size: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return null
        o.inJustDecodeBounds = false
        o.inSampleSize = sample(o.outWidth, o.outHeight, size)
        return BitmapFactory.decodeByteArray(b, 0, b.size, o)?.let { square(it, size) }
    }

    private fun sample(w: Int, h: Int, size: Int): Int {
        var s = 1
        while (minOf(w, h) / (s * 2) >= size) s *= 2
        return s
    }

    private fun square(src: Bitmap, size: Int): Bitmap {
        val side = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val sx = (src.width - side) / 2; val sy = (src.height - side) / 2
        Canvas(out).drawBitmap(src, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, size, size), Paint(Paint.FILTER_BITMAP_FLAG))
        if (src != out) src.recycle()
        return out
    }

    fun clear() {
        mem.evictAll()
        synchronized(missing) { missing.clear() }
        diskDir.listFiles()?.forEach { it.delete() }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
