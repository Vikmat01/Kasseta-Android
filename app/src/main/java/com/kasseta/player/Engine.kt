package com.kasseta.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 * Gapless playback engine: FFmpeg decoder (native) → AudioTrack at the source sample rate.
 * All decoding and AudioTrack work happens on one audio thread; the UI sends it commands.
 */
class Engine(private val ctx: Context) {
    enum class Repeat { OFF, ALL, ONE }

    fun interface Listener { fun onEngineChanged() }

    // ---- settings (read by the audio thread when a track is opened) ----
    @Volatile var maxRate = 192000
    @Volatile var output16 = false
    @Volatile var dsdFilter = true
    @Volatile var dsdBoost = true
    @Volatile var replayGain = 0 // 0 off, 1 track, 2 album

    // ---- queue & position state, guarded by `lock` ----
    private val lock = Any()
    private var queue: List<Track> = emptyList()
    private var order = IntArray(0)
    private var shuffleOn = false
    @Volatile private var repeatMode = Repeat.OFF

    /** a point in the output stream where a queue item starts */
    private class Marker(val frame: Long, var qpos: Int, val track: Track, val offsetMs: Long, val rate: Int)
    private val markers = ArrayDeque<Marker>()
    private var heard: Marker? = null
    private var at: AudioTrack? = null
    private var written = 0L
    private var headWrap = 0L
    private var lastHead = 0L

    @Volatile private var playing = false
    @Volatile private var pendingSeekMs = -1L
    @Volatile var lastError: String? = null
        private set
    @Volatile var outputLine = ""
        private set

    private val listeners = ArrayList<Listener>()
    private val main = Handler(Looper.getMainLooper())
    private val notifyRunnable = Runnable { listeners.toList().forEach { it.onEngineChanged() } }

    // ---- commands ----
    private sealed class Cmd {
        class Play(val qpos: Int, val ms: Long, val start: Boolean) : Cmd()
        class Seek(val ms: Long) : Cmd()
        object Pause : Cmd()
        object Resume : Cmd()
        object Stop : Cmd()
    }

    private val cmdLock = ReentrantLock()
    private val cmdCond = cmdLock.newCondition()
    private val cmds = ArrayDeque<Cmd>()
    @Volatile private var alive = true

    // ---- audio-thread-only state ----
    private var dec = 0L
    private var decPath: String? = null
    private var decInfo: StreamInfo? = null
    private var atRate = 0
    private var at16 = false
    private var cur: Track? = null
    private var curPos = -1
    private var segRemaining = Long.MAX_VALUE
    private var wantPlay = false
    private var endOfQueue = false
    private val fbuf = FloatArray(4096 * 2)
    private var sbuf = ShortArray(4096 * 2)
    private var pendOff = 0
    private var pendTotal = 0
    private val dither = Random(1)

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wake = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kasseta:playback").apply { setReferenceCounted(false) }

    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocus = false
    private val focusListener = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) { resumeOnFocus = true; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> synchronized(lock) { at?.setVolume(0.25f) }
            AudioManager.AUDIOFOCUS_GAIN -> {
                synchronized(lock) { at?.setVolume(1f) }
                if (resumeOnFocus) { resumeOnFocus = false; resume() }
            }
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }
    private val thread: Thread

    init {
        ctx.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            loop()
        }, "kasseta-audio")
        thread.start()
    }

    // ================= public API (main thread) =================

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    val isPlaying get() = playing
    val queueTracks: List<Track> get() = synchronized(lock) { queue }
    val shuffle get() = shuffleOn
    val repeat get() = repeatMode

    /** queue indices in playing order */
    fun playingOrder(): IntArray = synchronized(lock) { order.copyOf() }

    fun currentTrack(): Track? = synchronized(lock) { (heard ?: markers.firstOrNull())?.track }
    fun currentQueuePos(): Int = synchronized(lock) { (heard ?: markers.firstOrNull())?.qpos ?: -1 }
    fun durationMs(): Long = currentTrack()?.durMs ?: 0

    fun positionMs(): Long {
        val ps = pendingSeekMs
        if (ps >= 0) return ps
        synchronized(lock) {
            val played = playedFrames()
            val m = heard ?: return 0
            return m.offsetMs + max(0L, played - m.frame) * 1000 / max(1, m.rate)
        }
    }

    /** Starts playing [tracks] from [index] (an index into [tracks]). */
    fun play(tracks: List<Track>, index: Int, shuffle: Boolean = shuffleOn, startMs: Long = 0, autoplay: Boolean = true) {
        if (tracks.isEmpty()) return
        val start = index.coerceIn(0, tracks.size - 1)
        val qpos = synchronized(lock) {
            queue = tracks
            shuffleOn = shuffle
            order = makeOrder(tracks.size, start, shuffle)
            order.indexOf(start)
        }
        pendingSeekMs = startMs
        if (autoplay) { playing = true; gainFocus() }
        send(Cmd.Play(qpos, startMs, autoplay))
        notifyChanged()
    }

    fun toggle() { if (playing) pause() else resume() }

    fun pause() {
        if (!playing) return
        playing = false
        send(Cmd.Pause)
        notifyChanged()
    }

    fun resume() {
        if (queueTracks.isEmpty()) return
        playing = true
        gainFocus()
        send(Cmd.Resume)
        notifyChanged()
    }

    fun stop() {
        playing = false
        send(Cmd.Stop)
        notifyChanged()
    }

    fun seekTo(ms: Long) {
        if (currentTrack() == null) return
        val v = max(0, ms)
        pendingSeekMs = v
        send(Cmd.Seek(v))
        notifyChanged()
    }

    fun next() {
        val p = currentQueuePos()
        if (p < 0) return
        val n = synchronized(lock) { if (p + 1 < order.size) p + 1 else if (order.isNotEmpty()) 0 else -1 }
        if (n >= 0) jump(n)
    }

    fun prev() {
        val p = currentQueuePos()
        if (p < 0) return
        if (positionMs() > 3000 || (p == 0 && repeatMode != Repeat.ALL)) { seekTo(0); return }
        val n = synchronized(lock) { if (p > 0) p - 1 else order.size - 1 }
        jump(n)
    }

    /** jump to a position in playing order */
    fun jump(qpos: Int) {
        pendingSeekMs = 0
        send(Cmd.Play(qpos, 0, playing))
        notifyChanged()
    }

    /** play the item at [index] of the current queue list */
    fun playQueueIndex(index: Int) {
        val q = synchronized(lock) { order.indexOf(index) }
        if (q < 0) return
        if (!playing) { playing = true; gainFocus() }
        pendingSeekMs = 0
        send(Cmd.Play(q, 0, true))
        notifyChanged()
    }

    fun setShuffle(on: Boolean) {
        synchronized(lock) {
            if (on == shuffleOn) return
            shuffleOn = on
            if (queue.isEmpty()) return@synchronized
            val old = order
            val curIdx = (heard ?: markers.firstOrNull())?.let { old.getOrNull(it.qpos) } ?: 0
            val neu = makeOrder(queue.size, curIdx, on)
            val inv = IntArray(neu.size).also { a -> neu.forEachIndexed { pos, idx -> a[idx] = pos } }
            fun remap(p: Int) = old.getOrNull(p)?.let { inv[it] } ?: p
            markers.forEach { it.qpos = remap(it.qpos) }
            heard?.let { it.qpos = remap(it.qpos) }
            pendingRemap = pendingRemap.let { prev -> if (prev == null) IntArray(old.size) { remap(it) } else IntArray(prev.size) { remap(prev[it]) } }
            order = neu
        }
        notifyChanged()
    }

    /** old→new playing positions after a shuffle change; applied to curPos by the audio thread */
    @Volatile private var pendingRemap: IntArray? = null

    /** Adds tracks after the current one ([next]) or at the end of the queue. */
    fun enqueue(tracks: List<Track>, next: Boolean) {
        if (tracks.isEmpty()) return
        if (queueTracks.isEmpty()) { play(tracks, 0, false); return }
        synchronized(lock) {
            val base = queue.size
            queue = queue + tracks
            val curP = (heard ?: markers.firstOrNull())?.qpos ?: -1
            val at = if (next) (curP + 1).coerceIn(0, order.size) else order.size
            val ins = IntArray(tracks.size) { base + it }
            val old = order
            order = IntArray(old.size + ins.size).also { o ->
                old.copyInto(o, 0, 0, at)
                ins.copyInto(o, at)
                old.copyInto(o, at + ins.size, at, old.size)
            }
            fun remap(p: Int) = if (p >= at) p + ins.size else p
            markers.forEach { it.qpos = remap(it.qpos) }
            heard?.let { it.qpos = remap(it.qpos) }
            pendingRemap = pendingRemap.let { prev -> if (prev == null) IntArray(old.size) { remap(it) } else IntArray(prev.size) { remap(prev[it]) } }
        }
        notifyChanged()
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) { Repeat.OFF -> Repeat.ALL; Repeat.ALL -> Repeat.ONE; Repeat.ONE -> Repeat.OFF }
        notifyChanged()
    }

    fun setRepeat(r: Repeat) { repeatMode = r; notifyChanged() }

    /** Re-applies output settings by reopening the current track at the current position. */
    fun reapplySettings() {
        val p = currentQueuePos()
        if (p < 0) return
        val pos = positionMs()
        pendingSeekMs = pos
        send(Cmd.Play(p, pos, playing))
    }

    fun release() {
        alive = false
        send(Cmd.Stop)
        thread.join(1500)
        try { ctx.unregisterReceiver(noisy) } catch (_: Exception) {}
        abandonFocus()
    }

    // ================= internals =================

    private fun notifyChanged() {
        main.removeCallbacks(notifyRunnable)
        main.post(notifyRunnable)
    }

    private fun send(c: Cmd) {
        cmdLock.withLock {
            if (c is Cmd.Seek) cmds.removeAll { it is Cmd.Seek }
            if (c is Cmd.Play) cmds.removeAll { it is Cmd.Play || it is Cmd.Seek }
            cmds.addLast(c)
            cmdCond.signalAll()
        }
    }

    private fun hasCmd() = cmdLock.withLock { cmds.isNotEmpty() }

    private fun makeOrder(n: Int, first: Int, shuffle: Boolean): IntArray {
        if (!shuffle) return IntArray(n) { it }
        val rest = (0 until n).filter { it != first }.shuffled().toMutableList()
        rest.add(0, first)
        return rest.toIntArray()
    }

    private fun gainFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs())
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focusRequest = it }
        audio.requestAudioFocus(req)
    }

    private fun abandonFocus() { focusRequest?.let { audio.abandonAudioFocusRequest(it) } }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** frames played by the current AudioTrack; advances the "heard" marker. Call with `lock` held. */
    private fun playedFrames(): Long {
        val t = at ?: return 0
        val h = try { t.playbackHeadPosition.toLong() and 0xffffffffL } catch (e: Exception) { lastHead }
        if (h < lastHead && lastHead - h > 0x80000000L) headWrap += 0x100000000L
        lastHead = h
        val played = h + headWrap
        while (markers.isNotEmpty() && markers.first().frame <= played) {
            val m = markers.removeFirst()
            if (heard !== m) {
                heard = m
                main.post(notifyRunnable)
            }
        }
        return played
    }

    private fun computeGain(t: Track, info: StreamInfo?): Float {
        var g = 1f
        if (replayGain != 0) {
            val album = replayGain == 2
            val db = if (album) (if (!t.rgAlbum.isNaN()) t.rgAlbum else t.rgTrack) else (if (!t.rgTrack.isNaN()) t.rgTrack else t.rgAlbum)
            val peak = if (album) (if (!t.rgAlbumPeak.isNaN()) t.rgAlbumPeak else t.rgTrackPeak) else (if (!t.rgTrackPeak.isNaN()) t.rgTrackPeak else t.rgAlbumPeak)
            if (!db.isNaN()) {
                g = 10f.pow(db / 20f)
                if (!peak.isNaN() && peak > 0 && g * peak > 1f) g = 1f / peak
            }
        }
        if (dsdBoost && info?.isDsd == true) g *= 2f // DSD reference level (50% modulation) → PCM full scale
        return g
    }

    private fun frames(ms: Long, rate: Int) = ms * rate / 1000

    private fun closeDecoder() {
        if (dec != 0L) Native.close(dec)
        dec = 0; decPath = null; decInfo = null
    }

    private fun resetCounters() {
        written = 0; headWrap = 0; lastHead = 0
        pendOff = 0; pendTotal = 0
    }

    private fun releaseTrack() {
        val t = synchronized(lock) { val x = at; at = null; resetCounters(); x }
        atRate = 0
        t?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }
    }

    private fun createTrack(rate: Int, use16: Boolean): AudioTrack? {
        val enc = if (use16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_FLOAT
        val frameBytes = if (use16) 4 else 8
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, enc)
        if (min <= 0) return null
        val size = max(min * 2, rate * frameBytes / 3) // ~330 ms
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs())
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(enc).setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(size)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); null } else t
        } catch (e: Exception) {
            null
        }
    }

    /** Opens queue position [qpos] at [offsetMs]. [flush] = jump (drop queued audio); otherwise gapless continuation. */
    private fun openItem(qpos: Int, offsetMs: Long, flush: Boolean): Boolean {
        val t = synchronized(lock) { order.getOrNull(qpos)?.let { queue.getOrNull(it) } } ?: return false
        var maxR = maxRate
        var attempts = 0
        while (true) {
            val reuse = dec != 0L && decPath == t.path && (decInfo?.outRate ?: 0) <= maxR
            if (!reuse) {
                closeDecoder()
                val err = arrayOfNulls<String>(1)
                dec = Native.open(Native.pathBytes(t.path), maxR, dsdFilter, err)
                if (dec == 0L) { lastError = "${t.fileName}: ${err[0] ?: "не открывается"}"; return false }
                decPath = t.path
                val a = IntArray(10)
                decInfo = StreamInfo(a, Native.info(dec, a) ?: "")
            }
            val info = decInfo!!
            val startMs = t.startMs + offsetMs
            if (reuse || startMs > 0) Native.seek(dec, startMs)
            Native.setGain(dec, computeGain(t, info))
            segRemaining = if (t.endMs >= 0) max(0L, frames(t.endMs - startMs, info.outRate)) else Long.MAX_VALUE

            val use16 = output16
            if (at == null || atRate != info.outRate || at16 != use16) {
                if (!flush) drain()
                releaseTrack()
                val track = createTrack(info.outRate, use16)
                if (track == null) {
                    // the device refused this rate: try the next lower one of the same family
                    if (++attempts > 4 || info.outRate <= 48000) { lastError = "Устройство не принимает ${info.outRate} Гц"; return false }
                    maxR = info.outRate / 2
                    closeDecoder()
                    continue
                }
                synchronized(lock) { at = track; resetCounters() }
                atRate = info.outRate; at16 = use16
            } else if (flush) {
                synchronized(lock) {
                    at?.let { it.pause(); it.flush() }
                    resetCounters()
                }
            }
            cur = t; curPos = qpos
            synchronized(lock) {
                val m = Marker(written, qpos, t, offsetMs, info.outRate)
                if (flush) { markers.clear(); heard = m } else markers.addLast(m)
            }
            pendingSeekMs = -1
            outputLine = buildOutputLine(info, use16)
            if (wantPlay) at?.play()
            notifyChanged()
            return true
        }
    }

    private fun buildOutputLine(i: StreamInfo, use16: Boolean): String {
        val src = if (i.isDsd) "DSD ${Track.fmt1(i.dsdRate / 1e6)} МГц" else "${Track.fmtKhz(i.srcRate)} кГц"
        return "$src → PCM ${Track.fmtKhz(i.outRate)} кГц · ${if (use16) "16 бит" else "32 бит float"}"
    }

    /** next position after the natural end of [p], or -1 */
    private fun nextAuto(p: Int): Int = synchronized(lock) {
        when {
            repeatMode == Repeat.ONE -> p
            p + 1 < order.size -> p + 1
            repeatMode == Repeat.ALL && order.isNotEmpty() -> 0
            else -> -1
        }
    }

    /** waits until everything written has been played */
    private fun drain() {
        val t = at ?: return
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return
        val deadline = SystemClock.uptimeMillis() + 3000
        while (alive && !hasCmd() && SystemClock.uptimeMillis() < deadline) {
            val done = synchronized(lock) { playedFrames() >= written }
            if (done) break
            Thread.sleep(15)
        }
    }

    /** writes pending samples; returns true when all were written, false if interrupted by a command */
    private fun pump(): Boolean {
        val t = at ?: run { pendOff = 0; pendTotal = 0; return true }
        while (pendOff < pendTotal) {
            val n = if (at16) t.write(sbuf, pendOff, pendTotal - pendOff, AudioTrack.WRITE_NON_BLOCKING)
            else t.write(fbuf, pendOff, pendTotal - pendOff, AudioTrack.WRITE_NON_BLOCKING)
            if (n < 0) { pendOff = 0; pendTotal = 0; return true }
            pendOff += n
            synchronized(lock) { written += n / 2; playedFrames() }
            if (pendOff < pendTotal) {
                if (hasCmd() || !alive) return false
                if (wantPlay && t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                Thread.sleep(6)
            }
        }
        return true
    }

    // ---------- what is being heard, for the visualizer ----------
    private val vizMask = (1 shl 15) - 1
    private val vizL = FloatArray(vizMask + 1)
    private val vizR = FloatArray(vizMask + 1)
    @Volatile private var vizRate = 44100

    /**
     * Copies the last [n] stereo frames that are coming out of the speaker right now.
     * Returns the sample rate, or 0 when nothing is playing.
     */
    fun vizSnapshot(l: FloatArray, r: FloatArray, n: Int): Int {
        if (!playing) return 0
        val end = synchronized(lock) { if (at == null) -1L else min(playedFrames(), written) }
        if (end < n) return 0
        var i = end - n
        for (k in 0 until n) { val j = (i and vizMask.toLong()).toInt(); l[k] = vizL[j]; r[k] = vizR[j]; i++ }
        return vizRate
    }

    private fun queueSamples(frames: Int) {
        val total = frames * 2
        // keep a copy for the visualizer, indexed by the frame number in the AudioTrack
        val base = synchronized(lock) { written }
        for (k in 0 until frames) {
            val j = ((base + k) and vizMask.toLong()).toInt()
            vizL[j] = fbuf[2 * k]; vizR[j] = fbuf[2 * k + 1]
        }
        vizRate = atRate
        if (at16) {
            for (i in 0 until total) {
                val v = fbuf[i] * 32767f + (dither.nextFloat() - dither.nextFloat()) // TPDF dither
                sbuf[i] = (if (v > 32767f) 32767f else if (v < -32768f) -32768f else v).toInt().toShort()
            }
        }
        pendOff = 0
        pendTotal = total
    }

    private fun handle(c: Cmd) {
        when (c) {
            is Cmd.Play -> {
                wantPlay = c.start
                endOfQueue = false
                if (!openItem(c.qpos, c.ms, true)) skipBroken(c.qpos)
                if (!wantPlay) at?.pause()
            }
            is Cmd.Seek -> {
                val p = synchronized(lock) { heard?.qpos ?: curPos }
                if (p >= 0) { endOfQueue = false; openItem(p, c.ms, true) }
                pendingSeekMs = -1
            }
            Cmd.Pause -> { wantPlay = false; at?.pause() }
            Cmd.Resume -> {
                wantPlay = true
                if (endOfQueue || dec == 0L) {
                    endOfQueue = false
                    val p = synchronized(lock) { heard?.qpos ?: 0 }
                    if (!openItem(max(0, p), 0, true)) skipBroken(p)
                } else at?.play()
            }
            Cmd.Stop -> {
                wantPlay = false
                releaseTrack(); closeDecoder()
                synchronized(lock) { markers.clear(); heard = null }
                cur = null
            }
        }
        updateWake()
    }

    private fun skipBroken(from: Int) {
        var p = from
        val n = synchronized(lock) { order.size }
        repeat(n) {
            p = synchronized(lock) { if (p + 1 < order.size) p + 1 else -1 }
            if (p < 0) return@repeat
            if (openItem(p, 0, true)) return
        }
        wantPlay = false
        playing = false
        pendingSeekMs = -1
        notifyChanged()
    }

    private fun updateWake() {
        if (wantPlay) { if (!wake.isHeld) wake.acquire(6 * 60 * 60 * 1000L) } else if (wake.isHeld) wake.release()
    }

    private fun onQueueEnd() {
        endOfQueue = true
        drain()
        if (hasCmd()) return
        wantPlay = false
        playing = false
        at?.pause()
        updateWake()
        // back to the first track, paused
        send(Cmd.Play(0, 0, false))
        notifyChanged()
    }

    private fun loop() {
        var failures = 0
        while (alive) {
            pendingRemap?.let { r -> synchronized(lock) { if (curPos in r.indices) curPos = r[curPos]; pendingRemap = null } }
            val c = cmdLock.withLock { cmds.removeFirstOrNull() }
            if (c != null) {
                if (c is Cmd.Play || c is Cmd.Seek || c is Cmd.Stop) { pendOff = 0; pendTotal = 0 }
                try { handle(c) } catch (e: Exception) { lastError = e.toString() }
                continue
            }
            if (!wantPlay || dec == 0L || endOfQueue) {
                cmdLock.withLock { if (cmds.isEmpty()) cmdCond.await(if (wantPlay) 50L else 500L, TimeUnit.MILLISECONDS) }
                synchronized(lock) { playedFrames() }
                continue
            }
            if (pendOff < pendTotal) { pump(); continue }
            val info = decInfo ?: continue
            val want = min(4096L, segRemaining).toInt()
            val got = if (want > 0) Native.read(dec, fbuf, want) else 0
            if (got > 0) {
                failures = 0
                segRemaining -= got
                queueSamples(got)
                pump()
                continue
            }
            if (got < 0) failures++
            // this item has ended
            val prevTrack = cur
            val n = nextAuto(curPos)
            if (n < 0 || failures > 8) { failures = 0; onQueueEnd(); continue }
            val next = synchronized(lock) { order.getOrNull(n)?.let { queue.getOrNull(it) } }
            if (next != null && prevTrack != null && got == 0 && segRemaining == 0L && decPath == next.path &&
                prevTrack.endMs >= 0 && next.startMs == prevTrack.endMs && next !== prevTrack
            ) {
                // the next CUE track continues in the same file: keep decoding — truly gapless
                cur = next; curPos = n
                Native.setGain(dec, computeGain(next, info))
                segRemaining = if (next.endMs >= 0) frames(next.endMs - next.startMs, info.outRate) else Long.MAX_VALUE
                synchronized(lock) { markers.addLast(Marker(written, n, next, 0, info.outRate)) }
                continue
            }
            if (!openItem(n, 0, false)) {
                failures++
                curPos = n
                if (failures > 8) { failures = 0; onQueueEnd() }
            }
        }
        releaseTrack()
        closeDecoder()
        if (wake.isHeld) wake.release()
    }
}
