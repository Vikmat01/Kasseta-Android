package com.kasseta.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** CD view: a jewel case; the disc (printed with the cover) slides out of it and spins. */
class Disc(private val g: Gfx, private val cases: Cases) {
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenKey = ""
    private val m = Matrix()
    private var phBitmap: Bitmap? = null
    private var phKey = ""

    fun placeholderBitmap(name: String, size: Int): Bitmap {
        val key = "$name@$size"
        phBitmap?.let { if (phKey == key) return it }
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        g.placeholder(Canvas(b), name, 0f, 0f, size.toFloat(), 0f)
        phBitmap = b; phKey = key
        return b
    }

    private fun sheenShader(cx: Float, cy: Float): SweepGradient {
        val n = 73
        val colors = IntArray(n)
        val pos = FloatArray(n)
        for (i in 0 until n) {
            val phi = (i.toFloat() / (n - 1)) * 2 * PI.toFloat()
            val lobe = max(0f, cos(2 * (phi + 0.75f))).pow(10f)
            val lobe2 = max(0f, cos(2 * (phi - 0.35f))).pow(24f) * 0.5f
            colors[i] = C.argb(((lobe + lobe2) * 46f).toInt().coerceIn(0, 255), 0xFFFFFF)
            pos[i] = i.toFloat() / (n - 1)
        }
        return SweepGradient(cx, cy, colors, pos)
    }

    fun disc(c: Canvas, src: Bitmap, dcx: Float, dcy: Float, r: Float, angle: Float) {
        val d = r * 2
        val sh = g.shaderFor(src)
        m.reset()
        m.setScale(d / src.width, d / src.height)
        m.postTranslate(dcx - r, dcy - r)
        m.postRotate(angle * 180f / PI.toFloat(), dcx, dcy)
        sh.setLocalMatrix(m)
        discPaint.shader = sh
        c.drawCircle(dcx, dcy, r, discPaint)
        discPaint.shader = null
        g.circle(c, dcx, dcy, r * 0.30f, C.argb(200, 0xC4C4CA))
        g.ring(c, dcx, dcy, r * 0.255f, r * 0.27f, C.hex(0x8D8D94))
        g.ring(c, dcx, dcy, r * 0.982f, r, C.hex(0xA3A3AA))
        val key = "$dcx|$dcy|$r"
        if (key != sheenKey) { sheen.shader = sheenShader(dcx, dcy); sheenKey = key }
        c.drawCircle(dcx, dcy, r, sheen)
        g.circle(c, dcx, dcy, r * 0.125f, C.BG)
        g.ring(c, dcx, dcy, r * 0.125f, r * 0.135f, C.argb(60, 0xFFFFFF))
    }

    /** [compact] (portrait): bigger case, the disc slides out a shorter way so it all fits the width */
    private fun caseWidth(vw: Float, vh: Float, compact: Boolean): Float =
        if (compact) min(vw * 0.76f, vh * 0.96f / Cases.JEWEL_RATIO) else min(vw * 0.64f, vh * 0.96f / Cases.JEWEL_RATIO)

    /** height the picture takes in a box */
    fun height(vw: Float, vh: Float, compact: Boolean): Float = caseWidth(vw, vh, compact) * Cases.JEWEL_RATIO

    /** [out] 0 = disc inside the case, 1 = slid out */
    fun draw(c: Canvas, vx: Float, vy: Float, vw: Float, vh: Float, cover: Bitmap?, name: String, angle: Float, out: Float, compact: Boolean = false) {
        val o = out.coerceIn(0f, 1f)
        val caseW = caseWidth(vw, vh, compact)
        val caseH = caseW * Cases.JEWEL_RATIO
        val r = caseH * 0.43f
        val travel = r * (if (compact) 0.84f else 1.25f)
        val groupW = caseW + travel * o
        val cx0 = vx + (vw - groupW) / 2
        val cy0 = vy + (vh - caseH) / 2
        // the disc sits under the booklet and slides out to the right
        val bookletCx = cx0 + caseW - caseH * 0.955f / 2 - caseW * 0.012f
        val dcx = bookletCx + travel * o
        val dcy = cy0 + caseH / 2
        val src = cover ?: placeholderBitmap(name, max(64, (r * 2).toInt()))
        if (o > 0.01f) disc(c, src, dcx, dcy, r, angle)
        cases.jewel(c, cx0, cy0, caseW, cover, name)
    }
}
