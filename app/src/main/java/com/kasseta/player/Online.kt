package com.kasseta.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looking up a cover or lyrics on the internet, only when the user asks for one album or track.
 * Covers: MusicBrainz + Cover Art Archive and Deezer. Lyrics: LRCLIB (free, with timed lyrics).
 */
object Online {
    private const val UA = "Kasseta/1.5 (https://github.com/Vikmat01/Kasseta-Android)"
    private val main = Handler(Looper.getMainLooper())

    class CoverHit(val title: String, val artist: String, val year: String, val source: String, val thumbUrl: String, val fullUrls: List<String>) {
        @Volatile var thumb: Bitmap? = null
    }

    class LyricsHit(val title: String, val artist: String, val album: String, val durSec: Int, val synced: String, val plain: String) {
        val isSynced get() = synced.isNotBlank()
        val text get() = if (isSynced) synced else plain
    }

    /** null result = network error (no internet, server down) */
    fun findCovers(artist: String, album: String, done: (List<CoverHit>?) -> Unit, thumbLoaded: () -> Unit) {
        Thread({
            val hits = ArrayList<CoverHit>()
            var ok = false
            try { hits += musicBrainz(artist, album); ok = true } catch (_: Exception) {}
            try { hits += deezer(artist, album); ok = true } catch (_: Exception) {}
            main.post { done(if (ok) hits else null) }
            // small pictures for the grid, one by one
            for (h in hits) {
                try {
                    h.thumb = fetchBytes(h.thumbUrl)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    main.post(thumbLoaded)
                } catch (_: Exception) {}
            }
        }, "kasseta-covers-online").start()
    }

    /** downloads the big picture (tries the sizes in order); bytes or null */
    fun fetchFull(hit: CoverHit, done: (ByteArray?) -> Unit) {
        Thread({
            var b: ByteArray? = null
            for (u in hit.fullUrls) {
                b = try { fetchBytes(u) } catch (_: Exception) { null }
                if (b != null && b.size > 2000) break
            }
            main.post { done(b) }
        }, "kasseta-cover-full").start()
    }

    fun findLyrics(t: Track, done: (List<LyricsHit>?) -> Unit) {
        Thread({
            val res = try {
                val q = "track_name=" + enc(t.title) + "&artist_name=" + enc(t.artist)
                var arr = JSONArray(fetchText("https://lrclib.net/api/search?$q"))
                if (arr.length() == 0) arr = JSONArray(fetchText("https://lrclib.net/api/search?q=" + enc(t.artist + " " + t.title)))
                val list = ArrayList<LyricsHit>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("instrumental")) continue
                    val h = LyricsHit(
                        o.optString("trackName"), o.optString("artistName"), o.optString("albumName"),
                        o.optDouble("duration", 0.0).toInt(), o.optString("syncedLyrics").let { if (it == "null") "" else it },
                        o.optString("plainLyrics").let { if (it == "null") "" else it },
                    )
                    if (h.text.isNotBlank()) list.add(h)
                }
                // timed lyrics with the closest length first
                val len = (t.durMs / 1000).toInt()
                list.sortedWith(compareBy({ !it.isSynced }, { if (len > 0 && it.durSec > 0) kotlin.math.abs(it.durSec - len) else 999 })).take(20)
            } catch (_: Exception) {
                null
            }
            main.post { done(res) }
        }, "kasseta-lyrics-online").start()
    }

    // ---------- sources ----------

    private fun musicBrainz(artist: String, album: String): List<CoverHit> {
        val q = "releasegroup:\"" + lucene(album) + "\"" + if (artist.isNotBlank()) " AND artist:\"" + lucene(artist) + "\"" else ""
        val j = JSONObject(fetchText("https://musicbrainz.org/ws/2/release-group/?fmt=json&limit=6&query=" + enc(q)))
        val arr = j.optJSONArray("release-groups") ?: return emptyList()
        val out = ArrayList<CoverHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id")
            val ac = o.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name") ?: ""
            val base = "https://coverartarchive.org/release-group/$id/front"
            out.add(CoverHit(o.optString("title"), ac, o.optString("first-release-date").take(4), "MusicBrainz", "$base-250", listOf("$base-1200", "$base-500", base)))
        }
        return out
    }

    private fun deezer(artist: String, album: String): List<CoverHit> {
        val q = if (artist.isNotBlank()) "artist:\"$artist\" album:\"$album\"" else album
        val j = JSONObject(fetchText("https://api.deezer.com/search/album?limit=8&q=" + enc(q)))
        val arr = j.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<CoverHit>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val xl = o.optString("cover_xl")
            if (xl.isBlank() || xl == "null") continue
            out.add(CoverHit(o.optString("title"), o.optJSONObject("artist")?.optString("name") ?: "", "", "Deezer", o.optString("cover_medium"), listOf(xl, o.optString("cover_big"))))
        }
        return out
    }

    // ---------- http ----------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun lucene(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept", "application/json, image/*")
            c.connectTimeout = 10000; c.readTimeout = 20000
            val code = c.responseCode
            if (code in 300..399 && hops < 6) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); hops++; continue }
            return c
        }
    }

    private fun fetchText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    private fun fetchBytes(url: String): ByteArray? {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}
