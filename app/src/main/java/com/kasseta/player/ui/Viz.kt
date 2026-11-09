package com.kasseta.player.ui

import android.graphics.Canvas
import android.graphics.Path
import android.os.SystemClock
import com.kasseta.player.Engine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Music visualizer under the cassette: 1 VU needles, 2 deck-style LED spectrum, 3 oscilloscope.
 * Reads the samples the player itself is sending to the speaker, so no microphone permission is needed.
 */
class Viz(private val g: Gfx) {
    companion object {
        const val OFF = 0; const val VU = 1; const val SPECTRUM = 2; const val SCOPE = 3
        const val BANDS = 28
        private const val FFT = 2048
    }

    private val l = FloatArray(FFT)
    private val r = FloatArray(FFT)
    private val re = FloatArray(FFT)
    private val im = FloatArray(FFT)
    private val window = FloatArray(FFT) { (0.5 - 0.5 * cos(2 * PI * it / (FFT - 1))).toFloat() }
    private val bars = FloatArray(BANDS)
    private val peaks = FloatArray(BANDS)
    private val peakAge = FloatArray(BANDS)
    private val vu = floatArrayOf(-30f, -30f)
    private var last = 0L
    private val path = Path()

    /** draws the strip; returns true while it still moves */
    fun draw(c: Canvas, engine: Engine, mode: Int, x: Float, y: Float, w: Float, h: Float): Boolean {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else min(0.1f, (now - last) / 1000f)
        last = now
        val rate = engine.vizSnapshot(l, r, FFT)
        return when (mode) {
            VU -> drawVu(c, rate, dt, x, y, w, h)
            SPECTRUM -> drawSpectrum(c, rate, dt, x, y, w, h)
            SCOPE -> drawScope(c, rate, x, y, w, h)
            else -> false
        }
    }

    // ---------- VU meters ----------

    private fun drawVu(c: Canvas, rate: Int, dt: Float, x: Float, y: Float, w: Float, h: Float): Boolean {
        // 300 ms VU ballistics on the RMS of the last ~30 ms
        val n = if (rate > 0) min(FFT, rate / 33) else 0
        for (ch in 0..1) {
            val src = if (ch == 0) l else r
            var target = -30f
            if (n > 0) {
                var s = 0.0
                for (i in FFT - n until FFT) s += src[i] * src[i]
                val rms = sqrt(s / n).toFloat()
                // 0 VU at -14 dBFS RMS, a usual reference for mastered music
                target = (20f * log10(rms + 1e-9f) + 14f).coerceIn(-30f, 5f)
            }
            vu[ch] += (target - vu[ch]) * min(1f, dt / 0.3f * 2.2f)
        }
        val gap = g.dp(14f)
        val mw = min((w - gap) / 2, h * 2.9f)
        val x0 = x + (w - (2 * mw + gap)) / 2
        meter(c, x0, y, mw, h, vu[0], "L")
        meter(c, x0 + mw + gap, y, mw, h, vu[1], "R")
        return rate > 0 || vu[0] > -29.5f || vu[1] > -29.5f
    }

    private fun angle(db: Float): Float {
        // -20 dB at the left end, +3 dB at the right
        val t = ((db + 20f) / 23f).coerceIn(-0.08f, 1.06f)
        return ((-48f + 96f * t) * PI / 180f).toFloat()
    }

    private fun meter(c: Canvas, x: Float, y: Float, w: Float, h: Float, db: Float, label: String) {
        val rad = g.dp(7f)
        g.rrect(c, x, y, w, h, rad, C.hex(0xE8DFC8))
        g.vgrad(c, x, y, w, h * 0.35f, 0f, C.argb(40, 0xFFFFFF), C.argb(0, 0xFFFFFF))
        val cx = x + w / 2; val cy = y + h * 1.45f; val rr = h * 1.22f
        val ink = C.hex(0x28241E); val red = C.hex(0xC81E28)
        c.save()
        c.clipRect(x, y, x + w, y + h)
        // scale
        var prevX = 0f; var prevY = 0f
        for (i in 0..40) {
            val db0 = -20f + 23f * i / 40f
            val a = angle(db0)
            val px = cx + rr * 0.88f * sin(a); val py = cy - rr * 0.88f * cos(a)
            if (i > 0) g.line(c, prevX, prevY, px, py, if (db0 > 0f) g.dp(2.2f) else g.dp(1f), if (db0 > 0f) red else ink)
            prevX = px; prevY = py
        }
        for (d in intArrayOf(-20, -10, -7, -5, -3, -2, -1, 0, 1, 2, 3)) {
            val a = angle(d.toFloat())
            val big = d == -20 || d == -10 || d == -5 || d == 0 || d == 3
            val r1 = rr * 0.86f; val r2 = rr * (if (big) 0.96f else 0.93f)
            g.line(c, cx + r1 * sin(a), cy - r1 * cos(a), cx + r2 * sin(a), cy - r2 * cos(a), g.dp(1.2f), if (d > 0) red else ink)
        }
        val lp = g.text(g.fonts.semi, 9.5f, ink)
        g.drawText(c, "VU", x + g.dp(7f), y + h - g.lineH(lp) - g.dp(3f), lp)
        g.drawTextRight(c, label, x + w - g.dp(7f), y + h - g.lineH(lp) - g.dp(3f), lp)
        // needle
        val a = angle(db)
        g.line(c, cx + rr * 0.2f * sin(a), cy - rr * 0.2f * cos(a), cx + rr * 0.98f * sin(a), cy - rr * 0.98f * cos(a), g.dp(1.6f), C.hex(0x141414))
        c.restore()
        g.rrectStroke(c, x, y, w, h, rad, g.dp(1.2f), C.hex(0x3C3830))
    }

    // ---------- LED spectrum ----------

    private fun fft() {
        // in-place radix-2
        var j = 0
        for (i in 1 until FFT) {
            var bit = FFT shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= FFT) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < FFT) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun drawSpectrum(c: Canvas, rate: Int, dt: Float, x: Float, y: Float, w: Float, h: Float): Boolean {
        val k60 = dt * 60f
        if (rate > 0) {
            for (i in 0 until FFT) { re[i] = (l[i] + r[i]) * 0.5f * window[i]; im[i] = 0f }
            fft()
            val fmin = 45f; val fmax = min(16000f, rate / 2f - 100f)
            for (b in 0 until BANDS) {
                val f0 = fmin * (fmax / fmin).pow(b / BANDS.toFloat())
                val f1 = fmin * (fmax / fmin).pow((b + 1) / BANDS.toFloat())
                val i0 = max(1, (f0 * FFT / rate).toInt()); val i1 = max(i0, (f1 * FFT / rate).toInt())
                var m = 0f
                for (i in i0..min(i1, FFT / 2 - 1)) { val v = re[i] * re[i] + im[i] * im[i]; if (v > m) m = v }
                val db = 10f * log10(m + 1e-12f) - 20f * log10(FFT / 4f)
                val v = ((db + 62f) / 56f).coerceIn(0f, 1f)
                bars[b] = if (v > bars[b]) bars[b] + (v - bars[b]) * min(1f, 0.7f * k60) else max(v, bars[b] - 0.045f * k60)
            }
        } else {
            for (b in 0 until BANDS) bars[b] = max(0f, bars[b] - 0.045f * k60)
        }
        var moving = rate > 0
        for (b in 0 until BANDS) {
            if (bars[b] >= peaks[b]) { peaks[b] = bars[b]; peakAge[b] = 0f }
            else { peakAge[b] += dt; if (peakAge[b] > 0.4f) peaks[b] = max(bars[b], peaks[b] - 0.03f * k60) }
            if (bars[b] > 0.001f || peaks[b] > 0.001f) moving = true
        }
        val seg = g.dp(3f); val gap = g.dp(1f)
        val nseg = ((h + gap) / (seg + gap)).toInt()
        val bw = w / BANDS
        val off = C.hex(0x1C1C20)
        for (b in 0 until BANDS) {
            val bx = x + b * bw + g.dp(1f)
            val lit = (bars[b] * nseg + 0.5f).toInt()
            val pk = (peaks[b] * nseg + 0.5f).toInt()
            for (s in 0 until nseg) {
                val sy = y + h - (s + 1) * (seg + gap) + gap
                val col = when {
                    s < lit -> {
                        val t = s / (nseg - 1f)
                        if (t > 0.55f) C.lerpColor(C.hex(0xEBEBF0), C.ACCENT, ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)) else C.hex(0xEBEBF0)
                    }
                    s == pk - 1 && pk > 0 -> C.ACCENT
                    else -> off
                }
                g.fill.color = col
                c.drawRect(bx, sy, bx + bw - g.dp(2.5f), sy + seg, g.fill)
            }
        }
        return moving
    }

    // ---------- oscilloscope ----------

    private fun drawScope(c: Canvas, rate: Int, x: Float, y: Float, w: Float, h: Float): Boolean {
        val mid = y + h / 2
        g.line(c, x, mid, x + w, mid, g.dp(0.8f), C.hex(0x28282C))
        if (rate <= 0) return false
        // ~16 ms of sound, started at a rising zero crossing so the trace stands still
        val span = min(FFT / 2, rate / 60)
        var st = FFT - span - 1
        for (i in FFT - span - 2 downTo FFT / 4) {
            val a = (l[i] + r[i]); val b = (l[i + 1] + r[i + 1])
            if (a < 0f && b >= 0f && i + span < FFT) { st = i; break }
        }
        var peak = 0.25f
        for (i in st until st + span) peak = max(peak, abs((l[i] + r[i]) * 0.5f))
        path.reset()
        val pts = min(span, (w / g.dp(1f)).toInt())
        for (k in 0..pts) {
            val i = st + k * span / pts
            val v = (l[min(i, FFT - 1)] + r[min(i, FFT - 1)]) * 0.5f / peak
            val px = x + w * k / pts; val py = mid - v * h * 0.45f
            if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        // glow, then the bright line
        g.stroke.color = C.withAlpha(C.ACCENT, 70); g.stroke.strokeWidth = g.dp(5f)
        c.drawPath(path, g.stroke)
        g.stroke.color = C.ACCENT2; g.stroke.strokeWidth = g.dp(1.5f)
        c.drawPath(path, g.stroke)
        return true
    }
}
