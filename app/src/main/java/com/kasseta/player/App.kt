package com.kasseta.player

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import java.io.File

class KassetaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        App.init(this)
    }
}

/** Process-wide state: library, playback engine, settings. */
object App {
    lateinit var ctx: Context
        private set
    lateinit var engine: Engine
        private set
    lateinit var prefs: SharedPreferences
        private set

    @Volatile var library: Library = Library.EMPTY
        private set
    /** true once the cached library has been loaded (the first real scan may still be running) */
    @Volatile var loaded = false
        private set
    @Volatile var everScanned = false
        private set
    val progress = ScanProgress()
    @Volatile var scanning = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val libListeners = ArrayList<() -> Unit>()
    private var cache = ScanCache()
    private var savedQueue: List<Track>? = null
    var serviceRunning = false

    fun init(app: Application) {
        ctx = app
        prefs = app.getSharedPreferences("kasseta", Context.MODE_PRIVATE)
        everScanned = prefs.getBoolean("scanned", false)
        Covers.init(app.cacheDir)
        engine = Engine(app)
        applySettings()
        engine.addListener {
            if (engine.isPlaying && !serviceRunning) {
                val i = Intent(app, PlaybackService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                    serviceRunning = true
                } catch (_: Exception) {
                }
            }
            scheduleSave()
        }
        Thread({
            val c = ScanCache.load(File(app.filesDir, "library.bin"))
            val lib = Library(Scanner.buildTracks(c))
            main.post {
                cache = c
                library = lib
                loaded = true
                restorePlayback()
                notifyLibrary()
                if (hasStoragePermission()) rescan(false)
            }
        }, "kasseta-load").start()
    }

    // ---------- settings ----------

    var maxRate: Int
        get() = prefs.getInt("maxRate", 192000)
        set(v) { prefs.edit().putInt("maxRate", v).apply(); applySettings(); engine.reapplySettings() }
    var output16: Boolean
        get() = prefs.getBoolean("out16", false)
        set(v) { prefs.edit().putBoolean("out16", v).apply(); applySettings(); engine.reapplySettings() }
    var dsdFilter: Boolean
        get() = prefs.getBoolean("dsdFilter", true)
        set(v) { prefs.edit().putBoolean("dsdFilter", v).apply(); applySettings(); engine.reapplySettings() }
    var dsdBoost: Boolean
        get() = prefs.getBoolean("dsdBoost", true)
        set(v) { prefs.edit().putBoolean("dsdBoost", v).apply(); applySettings(); engine.reapplySettings() }
    var replayGain: Int
        get() = prefs.getInt("rg", 0)
        set(v) { prefs.edit().putInt("rg", v).apply(); applySettings(); engine.reapplySettings() }
    /** album shelf look: 0 covers, 1 CD jewel cases, 2 cassette boxes, 3 Cover Flow */
    var albumTheme: Int
        get() = prefs.getInt("albumTheme", 1)
        set(v) { prefs.edit().putInt("albumTheme", v).apply() }
    /** library filter by quality: 0 all, 1 lossy, 2 CD, 3 hi-res, 4 DSD */
    var qualityFilter: Int
        get() = prefs.getInt("qfilter", 0)
        set(v) { prefs.edit().putInt("qfilter", v).apply() }
    /** screen orientation chosen with the rotate button */
    var landscape: Boolean
        get() = prefs.getBoolean("landscape", false)
        set(v) { prefs.edit().putBoolean("landscape", v).apply() }
    /** show our own player over the lock screen */
    var lockScreen: Boolean
        get() = prefs.getBoolean("lockScreen", false)
        set(v) { prefs.edit().putBoolean("lockScreen", v).apply() }
    /** case opening and tape winding sounds */
    var sounds: Boolean
        get() = prefs.getBoolean("sounds", true)
        set(v) { prefs.edit().putBoolean("sounds", v).apply() }
    /** day (yyyy-MM-dd) on which the album of the day was last opened */
    var aodSeen: String
        get() = prefs.getString("aodSeen", "") ?: ""
        set(v) { prefs.edit().putString("aodSeen", v).apply() }

    fun today(): String = java.time.LocalDate.now().toString()

    /** album of the day: random, fixed until midnight, never the same as yesterday's; -1 if no albums */
    fun albumOfDay(): Int {
        val lib = library
        val n = lib.albums.size
        if (n == 0) return -1
        val today = today()
        val day = prefs.getString("aodDay", "") ?: ""
        val key = prefs.getString("aodKey", "") ?: ""
        if (day == today) {
            val i = lib.albums.indexOfFirst { it.key == key }
            if (i >= 0) return i
        }
        val prev = if (day != today) key else prefs.getString("aodPrev", "") ?: ""
        val rnd = java.util.Random(java.time.LocalDate.now().toEpochDay() * 1000003L + n)
        var i = rnd.nextInt(n)
        if (n > 1 && lib.albums[i].key == prev) i = (i + 1 + rnd.nextInt(n - 1)) % n
        prefs.edit().putString("aodDay", today).putString("aodKey", lib.albums[i].key).putString("aodPrev", prev).apply()
        return i
    }

    /** "pick another": a different random album becomes today's pick until midnight */
    fun rerollAlbumOfDay(): Int {
        val lib = library
        val n = lib.albums.size
        val cur = albumOfDay()
        if (n < 2) return cur
        var i = java.util.Random().nextInt(n - 1)
        if (i >= cur) i++
        prefs.edit().putString("aodDay", today()).putString("aodKey", lib.albums[i].key).apply()
        return i
    }

    var cdView: Boolean
        get() = prefs.getBoolean("cdView", false)
        set(v) { prefs.edit().putBoolean("cdView", v).apply() }

    private fun applySettings() {
        engine.maxRate = maxRate
        engine.output16 = output16
        engine.dsdFilter = dsdFilter
        engine.dsdBoost = dsdBoost
        engine.replayGain = replayGain
    }

    // ---------- library ----------

    fun addLibraryListener(l: () -> Unit) { libListeners.add(l) }
    fun removeLibraryListener(l: () -> Unit) { libListeners.remove(l) }
    private fun notifyLibrary() { libListeners.toList().forEach { it() } }

    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager()
        return ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    /** internal storage + SD cards + USB drives */
    fun storageRoots(): List<File> {
        val roots = LinkedHashSet<String>()
        @Suppress("DEPRECATION")
        roots.add(Environment.getExternalStorageDirectory().absolutePath)
        for (f in ctx.getExternalFilesDirs(null)) {
            if (f == null) continue
            val p = f.absolutePath
            val i = p.indexOf("/Android/data/")
            if (i > 0) roots.add(p.substring(0, i))
        }
        // some players expose cards only under /storage
        File("/storage").listFiles()?.forEach { d ->
            if (d.isDirectory && d.name != "self" && d.name != "emulated" && d.canRead()) roots.add(d.absolutePath)
        }
        return roots.map { File(it) }.filter { it.exists() && it.canRead() }
    }

    fun rescan(full: Boolean, done: ((Int) -> Unit)? = null) {
        if (scanning) return
        scanning = true
        notifyLibrary()
        val base = if (full) ScanCache() else cache
        if (full) Covers.clear()
        Thread({
            val res = try { Scanner.scan(storageRoots(), base, progress) } catch (e: Throwable) { null }
            val tracks = res?.first
            val newCache = res?.second
            val lib = tracks?.let { Library(it) }
            newCache?.let { try { it.save(File(ctx.filesDir, "library.bin")) } catch (_: Exception) {} }
            main.post {
                scanning = false
                if (lib != null && newCache != null) {
                    cache = newCache
                    library = lib
                    everScanned = true
                    prefs.edit().putBoolean("scanned", true).apply()
                    if (engine.queueTracks.isEmpty()) restorePlayback()
                }
                notifyLibrary()
                done?.invoke(lib?.tracks?.size ?: -1)
            }
        }, "kasseta-scan").start()
    }

    /** album index (in the current library) of a track that may come from an older library build */
    fun albumOf(t: Track?): Int {
        if (t == null) return -1
        val lib = library
        val i = lib.indexOf(t.id)
        return if (i >= 0) lib.tracks[i].albumIdx else -1
    }

    // ---------- playback state persistence ----------

    private val saveRunnable = Runnable { savePlayback() }
    private fun scheduleSave() {
        main.removeCallbacks(saveRunnable)
        main.postDelayed(saveRunnable, 1500)
    }

    fun savePlayback() {
        val q = engine.queueTracks
        if (q.isEmpty()) return
        val cur = engine.currentTrack()
        prefs.edit()
            .putString("curId", cur?.id ?: "")
            .putLong("curPos", engine.positionMs())
            .putBoolean("shuffle", engine.shuffle)
            .putInt("repeat", engine.repeat.ordinal)
            .apply()
        if (q !== savedQueue) {
            savedQueue = q
            val ids = q.joinToString("\n") { it.id }
            Thread({ try { File(ctx.filesDir, "queue.txt").writeText(ids) } catch (_: Exception) {} }, "kasseta-save").start()
        }
    }

    private fun restorePlayback() {
        if (engine.queueTracks.isNotEmpty()) return
        val lib = library
        if (lib.tracks.isEmpty()) return
        val f = File(ctx.filesDir, "queue.txt")
        if (!f.exists()) return
        val ids = try { f.readLines() } catch (e: Exception) { return }
        val tracks = ids.mapNotNull { id -> lib.indexOf(id).takeIf { it >= 0 }?.let { lib.tracks[it] } }
        if (tracks.isEmpty()) return
        val curId = prefs.getString("curId", "") ?: ""
        val idx = tracks.indexOfFirst { it.id == curId }.coerceAtLeast(0)
        val pos = if (idx >= 0 && tracks[idx].id == curId) prefs.getLong("curPos", 0) else 0
        engine.setRepeat(Engine.Repeat.entries.getOrElse(prefs.getInt("repeat", 0)) { Engine.Repeat.OFF })
        savedQueue = tracks
        engine.play(tracks, idx, prefs.getBoolean("shuffle", false), pos, autoplay = false)
        savedQueue = engine.queueTracks
    }
}
