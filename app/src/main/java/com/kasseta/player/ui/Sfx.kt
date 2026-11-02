package com.kasseta.player.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kasseta.player.App
import com.kasseta.player.R

/** Interface sounds: case opening and cassette fast-forward / rewind. */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()
    private val main = Handler(Looper.getMainLooper())
    private const val VOL = 0.55f

    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        for (r in intArrayOf(R.raw.box_jewel, R.raw.box_tape, R.raw.tape_key, R.raw.tape_wind, R.raw.tape_stop)) ids[r] = p.load(ctx, r, 1)
        pool = p
    }

    private fun play(res: Int, vol: Float = VOL, loop: Int = 0, rate: Float = 1f): Int {
        if (!App.sounds) return 0
        val p = pool ?: return 0
        val id = ids[res] ?: return 0
        return p.play(id, vol, vol, 1, loop, rate)
    }

    fun boxOpen(jewel: Boolean) { play(if (jewel) R.raw.box_jewel else R.raw.box_tape) }

    // ---------- fast-forward / rewind ----------

    private var wind = 0
    private var windRate = 1f
    private var ramp: Runnable? = null

    /** [dir] 1 forward, -1 rewind */
    fun windStart(dir: Int) {
        windStop(false)
        play(R.raw.tape_key)
        windRate = if (dir > 0) 1f else 1.08f
        // the reels spin up: start slow and quiet, then reach full speed
        val s = play(R.raw.tape_wind, 0f, -1, 0.55f)
        if (s == 0) return
        wind = s
        val t0 = SystemClock.uptimeMillis() + 60
        val r = object : Runnable {
            override fun run() {
                val k = ((SystemClock.uptimeMillis() - t0) / 420f).coerceIn(0f, 1f)
                val e = 1f - (1f - k) * (1f - k)
                pool?.setRate(wind, 0.55f + (windRate - 0.55f) * e)
                pool?.setVolume(wind, VOL * e, VOL * e)
                if (k < 1f && wind == s) main.postDelayed(this, 16)
            }
        }
        ramp = r
        main.postDelayed(r, 60)
    }

    /** scanning got faster: the motor sound goes up a little */
    fun windFaster() {
        if (wind == 0) return
        windRate *= 1.12f
        pool?.setRate(wind, windRate)
    }

    fun windStop(clunk: Boolean = true) {
        ramp?.let { main.removeCallbacks(it) }; ramp = null
        val s = wind
        wind = 0
        if (s != 0) {
            // spin down quickly, then silence
            val t0 = SystemClock.uptimeMillis()
            val from = windRate
            main.post(object : Runnable {
                override fun run() {
                    val k = ((SystemClock.uptimeMillis() - t0) / 180f).coerceIn(0f, 1f)
                    pool?.setRate(s, from - (from - 0.5f) * k)
                    pool?.setVolume(s, VOL * (1 - k), VOL * (1 - k))
                    if (k < 1f) main.postDelayed(this, 16) else pool?.stop(s)
                }
            })
        }
        if (clunk) play(R.raw.tape_stop)
    }
}
