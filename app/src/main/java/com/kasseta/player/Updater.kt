package com.kasseta.player

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK, downloads it and hands it to the system installer. */
object Updater {
    const val REPO = "Vikmat01/Kasseta-Android"
    private const val CHECK_EVERY_MS = 20L * 60 * 60 * 1000

    class Release(val version: String, val apkUrl: String, val notes: String, val page: String, val size: Long)

    private val main = Handler(Looper.getMainLooper())
    @Volatile var downloading = false
        private set

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (_: Exception) { "0" }

    /** true when [a] is a newer version than [b] ("1.10.0" > "1.9.2") */
    fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v', 'V').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /**
     * Looks for an update at most once in ~20 hours (or always when [force]).
     * [result] gets the release on the main thread, or null when there is nothing new.
     */
    fun check(ctx: Context, force: Boolean = false, result: (Release?) -> Unit) {
        val prefs = App.prefs
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("updChecked", 0) < CHECK_EVERY_MS) return
        Thread({
            val rel = try { fetchLatest() } catch (_: Exception) { null }
            if (rel != null || force) prefs.edit().putLong("updChecked", now).apply()
            val cur = currentVersion(ctx)
            val skipped = prefs.getString("updSkip", "") ?: ""
            val offer = rel?.takeIf { newer(it.version, cur) && (force || it.version != skipped) }
            main.post { result(offer) }
        }, "kasseta-update-check").start()
    }

    fun skip(version: String) { App.prefs.edit().putString("updSkip", version).apply() }

    private fun fetchLatest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Kasseta-Android")
        c.connectTimeout = 10000; c.readTimeout = 15000
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
            val assets = j.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) {
                    return Release(
                        j.optString("tag_name").trimStart('v', 'V'),
                        a.optString("browser_download_url"),
                        j.optString("body").trim(),
                        j.optString("html_url"),
                        a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** downloads the APK; [progress] gets 0..100 on the main thread, [done] the file or null */
    fun download(ctx: Context, rel: Release, progress: (Int) -> Unit, done: (File?) -> Unit) {
        if (downloading) return
        downloading = true
        Thread({
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, "Kasseta-${rel.version}.apk")
            var ok = false
            try {
                var url = URL(rel.apkUrl)
                var c: HttpURLConnection
                // GitHub answers with a redirect to its file storage
                var hops = 0
                while (true) {
                    c = url.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.setRequestProperty("User-Agent", "Kasseta-Android")
                    c.connectTimeout = 15000; c.readTimeout = 30000
                    val code = c.responseCode
                    if (code in 300..399 && hops < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); hops++; continue }
                    break
                }
                if (c.responseCode == 200) {
                    val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
                    var got = 0L
                    var last = -1
                    c.inputStream.use { inp ->
                        out.outputStream().use { o ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                got += n
                                val p = if (total > 0) (got * 100 / total).toInt() else 0
                                if (p != last) { last = p; main.post { progress(p) } }
                            }
                        }
                    }
                    ok = total <= 0 || got == total
                }
                c.disconnect()
            } catch (_: Exception) {
            }
            downloading = false
            main.post { done(if (ok) out else null) }
        }, "kasseta-update-download").start()
    }

    /** hands the APK to the system installer; Android asks the user to confirm */
    fun install(ctx: Context, apk: File): Boolean = try {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("kasseta.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
        true
    } catch (_: Exception) {
        false
    }
}

/** result of the install session: shows the system confirmation or reports an error */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {}
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(ctx, "Не удалось установить обновление. $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
