package com.kasseta.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Cassette look by audio quality, like real tape types:
 * lossy → Type I "normal", CD-quality lossless → Type II chrome,
 * hi-res lossless → Type IV metal, DSD → clear premium shell.
 */
class TapeStyle(
    val name: String,
    val shellTop: Int, val shellBottom: Int, val shellEdge: Int, val trapezoid: Int,
    val label: Int, val band: Int, val bandLight: Int, val bandDark: Int, val ruled: Int,
    val ink: Int, val sub: Int, val tag: Int, val badgeBg: Int, val badgeFg: Int,
    val screw: Int, val screwIn: Int, val tape: Int, val tapeEdge: Int,
    val clear: Boolean = false,
) {
    companion object {
        val NORMAL = TapeStyle("normal",
            C.hex(0x34343A), C.hex(0x1F1F23), C.hex(0x4A4A52), C.hex(0x26262B),
            C.hex(0xECE4D2), C.hex(0xFF8A3D), C.hex(0xFFD0A8), C.hex(0xC85A1E), C.hex(0xC9C1B4),
            C.hex(0x1E2A66), C.hex(0x5B5345), C.hex(0x7C3A12), C.hex(0x2A2A30), C.hex(0xECE4D2),
            C.hex(0x55555C), C.hex(0x3E3E44), C.hex(0x3B2618), C.hex(0x55392A))
        val CHROME = TapeStyle("chrome",
            C.hex(0x262A33), C.hex(0x111318), C.hex(0x3F4552), C.hex(0x1A1D23),
            C.hex(0xF3F3F1), C.hex(0x2F5BD3), C.hex(0x9DB8FF), C.hex(0x1B3A96), C.hex(0xCFD3DA),
            C.hex(0x14181F), C.hex(0x4A505C), C.hex(0x1B3A96), C.hex(0x1B3A96), C.hex(0xF3F3F1),
            C.hex(0x5A606C), C.hex(0x3A3F49), C.hex(0x2B1C14), C.hex(0x47332A))
        val METAL = TapeStyle("metal",
            C.hex(0x2E2E33), C.hex(0x101012), C.hex(0x5C5C63), C.hex(0x1C1C20),
            C.hex(0xDCDEE2), C.hex(0xB01E28), C.hex(0xE8767B), C.hex(0x6A0E14), C.hex(0x9EA2A9),
            C.hex(0x14161B), C.hex(0x3A3C42), C.hex(0xB01E28), C.hex(0xB01E28), C.hex(0xFFFFFF),
            C.hex(0xCDD0D6), C.hex(0x8C9097), C.hex(0x121212), C.hex(0x2C2C2C))
        /** DSD: white ceramic shell, black label band with gold lines */
        val DSD = TapeStyle("ceramic",
            C.hex(0xF6F4F0), C.hex(0xD8D4CD), C.hex(0xFFFFFF), C.hex(0xC9C5BE),
            C.hex(0xFBFAF7), C.hex(0x17171B), C.hex(0xD9B65E), C.hex(0x8A6A22), C.hex(0xD6D1C8),
            C.hex(0x1E2A66), C.hex(0x6B655A), C.hex(0x8A6A22), C.hex(0x17171B), C.hex(0xF6F4F0),
            C.hex(0xE2E4E8), C.hex(0xA9ADB5), C.hex(0x3B2618), C.hex(0x55392A))
    }
}

/** The cassette from the TrimUI version, ported to Canvas. Geometry is in "u" units (100.4 × 63.8). */
class Cassette(private val g: Gfx) {
    companion object {
        /** mock-ups only: overrides [printStyle] when >= 0 */
        @JvmStatic var printStyleAll = -1
        const val CW = 100.4f
        const val CH = 63.8f
        private const val REEL_L = 29.2f
        private const val REEL_R = 71.2f
        private const val REEL_Y = 28.4f
        private const val HOLE_R = 6.0f
        private const val HUB_R = 4.1f
        const val PACK_MIN = 4.6f
        const val PACK_MAX = 15.5f
        private const val WIN_X0 = 37.6f
        private const val WIN_X1 = 62.8f
        private const val WIN_Y0 = 21.2f
        private const val WIN_Y1 = 35.6f
    }

    private var layer: Bitmap? = null
    private var layerKey = ""

    /** tape pack radii (u) for a progress 0..1 */
    fun packs(progress: Float): Pair<Float, Float> {
        val p = progress.coerceIn(0f, 1f)
        val a2 = PACK_MIN * PACK_MIN; val b2 = PACK_MAX * PACK_MAX
        return sqrt(a2 + (1 - p) * (b2 - a2)) to sqrt(a2 + p * (b2 - a2))
    }

    /** Type IV (hi-res): grey shell with a smoked window band and gold print, in the spirit of late-80s premium tapes */
    private val gold = C.hex(0xCBAA62)

    /** true: the title is typed on the label (lossless and DSD), false: written by hand (MP3) */
    private var printed = false
    /** variant for the mock-ups: 0 typewriter, 1 typeset */
    var printStyle = 0
        get() = if (printStyleAll >= 0) printStyleAll else field

    /** writes the title: by hand, or typed when [printed]; shrinks long titles, then cuts them */
    private fun title(c: Canvas, u: Float, t: String, x: Float, y: Float, maxW: Float, size0: Float, minSize: Float, ink: Int) {
        if (!printed) {
            var size = size0
            var ph = g.text(g.fonts.hand, size / g.sd, ink)
            while (ph.measureText(t) > maxW && size > minSize) { size *= 0.94f; ph = g.text(g.fonts.hand, size / g.sd, ink) }
            c.drawText(g.ellipsize(t, ph, maxW), x, y, ph)
            return
        }
        // typed: smaller and slightly lower, so it sits on the line like a label strip
        val face = if (printStyle == 0) g.fonts.mono else g.fonts.bold
        var size = size0 * (if (printStyle == 0) 0.74f else 0.66f)
        val min2 = minSize * 0.5f
        var ph = g.text(face, size / g.sd, ink)
        if (printStyle == 1) ph.letterSpacing = 0.02f
        while (ph.measureText(t) > maxW && size > min2) { size *= 0.95f; ph = g.text(face, size / g.sd, ink); if (printStyle == 1) ph.letterSpacing = 0.02f }
        if (printStyle == 0) {
            // ink of a typewriter ribbon: a touch uneven
            val txt = g.ellipsize(t, ph, maxW)
            c.drawText(txt, x, y - 0.4f * u, ph)
            ph.alpha = 70
            c.drawText(txt, x + 0.12f * u, y - 0.32f * u, ph)
            ph.alpha = 255
        } else {
            c.drawText(g.ellipsize(t, ph, maxW), x, y - 0.4f * u, ph)
            ph.letterSpacing = 0f
        }
    }

    private fun drawSmoked(c: Canvas, u: Float, title: String, sub: String, tag: String) {
        val w = CW * u; val h = CH * u
        g.vgrad(c, 0f, 0f, w, h, 3.2f * u, C.hex(0x6A6B70), C.hex(0x56575B))
        g.rrectStroke(c, 0.5f, 0.5f, w - 1, h - 1, 3.2f * u, maxOf(1f, 0.25f * u), C.hex(0x3E3F42))
        // writing area on top (slightly recessed)
        g.rrect(c, 4.0f * u, 3.4f * u, w - 8.0f * u, 11.8f * u, 1.2f * u, C.hex(0x626368))
        g.line(c, 4.6f * u, 15.0f * u, w - 4.6f * u, 15.0f * u, maxOf(1f, 0.2f * u), C.argb(70, 0xFFFFFF))
        g.line(c, 4.6f * u, 3.5f * u, w - 4.6f * u, 3.5f * u, maxOf(1f, 0.2f * u), C.argb(60, 0x000000))
        // handwritten title in a silver marker
        title(c, u, title, 9f * u, 12.4f * u, w - 22f * u, 6.4f * u, 4.6f * u, C.hex(0xF2F2F0))
        // smoked window band
        val bx0 = 3.0f * u; val by0 = 16.4f * u; val bx1 = w - 3.0f * u; val by1 = 44.2f * u
        g.vgrad(c, bx0, by0, bx1 - bx0, by1 - by0, 0.8f * u, C.hex(0x1D1A17), C.hex(0x120F0D))
        g.rrectStroke(c, bx0, by0, bx1 - bx0, by1 - by0, 0.8f * u, maxOf(1f, 0.2f * u), C.argb(110, 0x000000))
        g.poly(c, C.argb(12, 0xFFFFFF), bx0 + 28f * u, by0, bx0 + 40f * u, by0, bx0 + 30f * u, by1, bx0 + 18f * u, by1)
        // gold print
        val pb = g.text(g.fonts.bold, 6.2f * u / g.sd, gold)
        pb.letterSpacing = -0.02f
        c.drawText("HR-M", 4.8f * u, 22.6f * u, pb)
        pb.letterSpacing = 0f
        val pl = g.text(g.fonts.semi, 1.9f * u / g.sd, gold)
        c.drawText("TYPE IV (METAL) POSITION  HIGH BIAS 70µs EQ", 25.6f * u, 19.4f * u, pl)
        val ps = g.text(g.fonts.semi, 2.4f * u / g.sd, C.withAlpha(gold, 210))
        c.drawText(g.ellipsize(sub, ps, 50f * u), 25.6f * u, 41.6f * u, ps)
        val pk = g.text(g.fonts.bold, 3.2f * u / g.sd, gold)
        pk.letterSpacing = 0.12f
        c.drawText("KASSETA", 5.2f * u, 41.8f * u, pk)
        pk.letterSpacing = 0f
        val pn = g.text(g.fonts.semi, 6.0f * u / g.sd, gold)
        g.drawTextRight(c, tag, bx1 - 2.2f * u, 36.4f * u, pn)
        // grey rings around the reel holes
        for (rx in floatArrayOf(REEL_L, REEL_R)) g.circle(c, rx * u, REEL_Y * u, (HOLE_R + 1.4f) * u, C.hex(0x3C3C40))
        // dots between the reels
        for (k in 0 until 5) g.circle(c, (43.6f + k * 3.3f) * u, REEL_Y * u, 0.45f * u, gold)
        // "A" and the lower part
        val pa = g.text(g.fonts.reg, 4.2f * u / g.sd, C.hex(0x8E8F94))
        c.drawText("A", 6.0f * u, 52.6f * u, pa)
        val tr = floatArrayOf(19.5f * u, h - 0.3f * u, 22.5f * u, 47.0f * u, 77.9f * u, 47.0f * u, 80.9f * u, h - 0.3f * u)
        g.poly(c, C.hex(0x5B5C60), *tr)
        for (k in 0 until 26) {
            val xx = (23.5f + k * 2.1f) * u
            g.line(c, xx, 47.8f * u, xx, h - 1.0f * u, maxOf(1f, 0.15f * u), C.argb(30, 0x000000))
        }
        g.line(c, tr[2], tr[3], tr[4], tr[5], maxOf(1f, 0.2f * u), C.argb(60, 0xFFFFFF))
        g.circle(c, 31.0f * u, 57.0f * u, 1.7f * u, C.hex(0x2A2A2D))
        g.circle(c, 69.4f * u, 57.0f * u, 1.7f * u, C.hex(0x2A2A2D))
        g.rrect(c, 39.6f * u, 54.8f * u, 3.0f * u, 3.0f * u, 0.6f * u, C.hex(0x2A2A2D))
        g.rrect(c, 57.8f * u, 54.8f * u, 3.0f * u, 3.0f * u, 0.6f * u, C.hex(0x2A2A2D))
        val sx = floatArrayOf(3.4f, CW - 3.4f, 3.4f, CW - 3.4f, CW / 2)
        val sy = floatArrayOf(3.4f, 3.4f, CH - 3.4f, CH - 3.4f, 50.6f)
        for (i in 0 until 5) {
            g.circle(c, sx[i] * u, sy[i] * u, 1.5f * u, C.hex(0x1E1E21))
            g.line(c, (sx[i] - 0.7f) * u, sy[i] * u, (sx[i] + 0.7f) * u, sy[i] * u, 0.3f * u, C.hex(0x47474B))
            g.line(c, sx[i] * u, (sy[i] - 0.7f) * u, sx[i] * u, (sy[i] + 0.7f) * u, 0.3f * u, C.hex(0x47474B))
        }
    }

    /** Type II: retro design — charcoal shell, cream label, striped band, dark pill with cream reels */
    private val retroStripes = intArrayOf(0x1F3F8F, 0x2F6FD6, 0x8EC3F2, 0x2F6FD6, 0x1F3F8F)
    private val retroDark = C.hex(0x3A3A3D)
    private val retroCream = C.hex(0xE9E0CF)
    private val retroTan = C.hex(0xB69B6E)

    private fun drawRetro(c: Canvas, u: Float, title: String, sub: String, tag: String) {
        val w = CW * u; val h = CH * u
        g.vgrad(c, 0f, 0f, w, h, 3.4f * u, C.hex(0x414145), C.hex(0x333336))
        g.rrectStroke(c, 0.5f, 0.5f, w - 1, h - 1, 3.4f * u, maxOf(1f, 0.25f * u), C.hex(0x2A2A2C))
        // bottom guide area outlined
        val tr = floatArrayOf(20.5f * u, h - 0.3f * u, 25.5f * u, 47.6f * u, 74.9f * u, 47.6f * u, 79.9f * u, h - 0.3f * u)
        g.poly(c, C.hex(0x37373A), *tr)
        g.line(c, tr[0], tr[1], tr[2], tr[3], maxOf(1f, 0.25f * u), C.hex(0x55555A))
        g.line(c, tr[2], tr[3], tr[4], tr[5], maxOf(1f, 0.25f * u), C.hex(0x55555A))
        g.line(c, tr[4], tr[5], tr[6], tr[7], maxOf(1f, 0.25f * u), C.hex(0x55555A))
        g.circle(c, 31.0f * u, 57.0f * u, 1.8f * u, retroCream)
        g.circle(c, 69.4f * u, 57.0f * u, 1.8f * u, retroCream)
        g.rrect(c, 39.6f * u, 54.6f * u, 3.2f * u, 3.2f * u, 0.8f * u, retroCream)
        g.rrect(c, 57.6f * u, 54.6f * u, 3.2f * u, 3.2f * u, 0.8f * u, retroCream)
        // screws
        val sx = floatArrayOf(4.2f, CW - 4.2f, 4.2f, CW - 4.2f, CW / 2)
        val sy = floatArrayOf(4.2f, 4.2f, CH - 4.2f, CH - 4.2f, 50.8f)
        for (i in 0 until 5) {
            g.circle(c, sx[i] * u, sy[i] * u, 1.6f * u, C.hex(0xA3A3A6))
            g.line(c, (sx[i] - 0.8f) * u, (sy[i] - 0.8f) * u, (sx[i] + 0.8f) * u, (sy[i] + 0.8f) * u, 0.35f * u, C.hex(0x2C2C2E))
            g.line(c, (sx[i] + 0.8f) * u, (sy[i] - 0.8f) * u, (sx[i] - 0.8f) * u, (sy[i] + 0.8f) * u, 0.35f * u, C.hex(0x2C2C2E))
        }
        // cream label
        val lx0 = 7.4f * u; val ly0 = 4.6f * u; val lx1 = (CW - 7.4f) * u; val ly1 = 44.0f * u
        g.rrect(c, lx0, ly0, lx1 - lx0, ly1 - ly0, 0.8f * u, retroCream)
        // writing lines
        g.fill.shader = null
        g.fill.color = retroTan
        c.drawRect(9.8f * u, 9.4f * u, lx1 - 2.4f * u, 9.4f * u + maxOf(1f, 0.3f * u), g.fill)
        c.drawRect(9.8f * u, 15.6f * u, lx1 - 2.4f * u, 15.6f * u + maxOf(1f, 0.3f * u), g.fill)
        // striped band
        val sy0 = 20.6f * u; val sh = 3.0f * u
        for ((i, col) in retroStripes.withIndex()) {
            g.fill.color = C.hex(col)
            c.drawRect(lx0, sy0 + i * sh, lx1, sy0 + (i + 1) * sh + 0.5f, g.fill)
        }
        // dark pill around reels and window
        g.rrect(c, (REEL_L - 8.0f) * u, (REEL_Y - 8.0f) * u, (REEL_R - REEL_L + 16f) * u, 16f * u, 8f * u, retroDark)
        // handwritten title on the lines
        title(c, u, title, 11f * u, 15.1f * u, lx1 - 12f * u - 11f * u, 6.6f * u, 4.8f * u, C.hex(0x1E2A66))
        // tag
        val pt = g.text(g.fonts.bold, 2.3f * u / g.sd, C.hex(0x8C7550))
        pt.letterSpacing = 0.08f
        g.drawTextRight(c, tag, lx1 - 2.2f * u, 5.6f * u, pt)
        pt.letterSpacing = 0f
        // side A box and artist line
        g.fill.color = C.hex(0x111113)
        c.drawRect(10.2f * u, 37.0f * u, 15.0f * u, 41.8f * u, g.fill)
        val pa = g.text(g.fonts.bold, 3.8f * u / g.sd, retroCream)
        val fm = pa.fontMetrics
        c.drawText("A", 12.6f * u - pa.measureText("A") / 2, 39.4f * u - (fm.ascent + fm.descent) / 2, pa)
        val pb = g.text(g.fonts.semi, 2.6f * u / g.sd, C.hex(0x6E624E))
        g.drawText(c, sub, 17.5f * u, 38.2f * u, pb, lx1 - 19.5f * u - 17.5f * u)
    }

    private fun retroHub(c: Canvas, cx: Float, cy: Float, u: Float, angle: Float) {
        g.circle(c, cx, cy, 5.6f * u, retroCream)
        g.ring(c, cx, cy, 3.5f * u, 3.95f * u, retroTan)
        for (i in 0 until 6) {
            val a = angle + i * PI.toFloat() / 3
            val ca = cos(a); val sa = sin(a)
            val r0 = 4.4f * u; val r1 = 5.6f * u; val hw = 0.55f * u
            g.poly(c, retroTan,
                cx + ca * r0 - sa * hw, cy + sa * r0 + ca * hw,
                cx + ca * r1 - sa * hw, cy + sa * r1 + ca * hw,
                cx + ca * r1 + sa * hw, cy + sa * r1 - ca * hw,
                cx + ca * r0 + sa * hw, cy + sa * r0 - ca * hw)
        }
        g.ring(c, cx, cy, 5.4f * u, 5.75f * u, C.argb(70, 0x000000))
    }

    /** retro window: light background, dark tape packs and a little scale */
    private fun retroWindow(c: Canvas, x: Float, y: Float, u: Float, rl: Float, rr: Float) {
        val wx0 = x + 40.0f * u; val wy0 = y + 22.0f * u; val wx1 = x + 60.4f * u; val wy1 = y + 34.8f * u
        g.rrect(c, wx0, wy0, wx1 - wx0, wy1 - wy0, 0.6f * u, C.hex(0xF1EEE7))
        c.save()
        g.rect.set(wx0, wy0, wx1, wy1)
        c.clipRect(g.rect)
        g.circle(c, x + REEL_L * u, y + REEL_Y * u, rl * u, C.hex(0x1E1E20))
        g.circle(c, x + REEL_R * u, y + REEL_Y * u, rr * u, C.hex(0x1E1E20))
        for (k in 1..4) {
            g.ring(c, x + REEL_R * u, y + REEL_Y * u, (rr - k * 1.6f) * u, (rr - k * 1.6f + 0.15f) * u, C.argb(60, 0xFFFFFF))
            g.ring(c, x + REEL_L * u, y + REEL_Y * u, (rl - k * 1.6f) * u, (rl - k * 1.6f + 0.15f) * u, C.argb(60, 0xFFFFFF))
        }
        // scale
        val my = y + REEL_Y * u
        g.line(c, wx0 + 1.5f * u, my, wx1 - 1.5f * u, my, maxOf(1f, 0.25f * u), retroTan)
        var i = 0
        var xx = wx0 + 2.0f * u
        while (xx < wx1 - 1.5f * u) {
            val big = i % 4 == 0
            g.line(c, xx, my - (if (big) 1.4f else 0.7f) * u, xx, my + (if (big) 1.4f else 0.7f) * u, maxOf(1f, 0.2f * u), retroTan)
            xx += 1.2f * u; i++
        }
        c.restore()
        g.rrectStroke(c, wx0, wy0, wx1 - wx0, wy1 - wy0, 0.6f * u, maxOf(1f, 0.25f * u), C.hex(0x2A2A2C))
    }

    /**
     * DSD: all-white ceramic shell without a paper label (in the spirit of the 80s ceramic
     * metal tapes): text printed straight on the body, black screws, a narrow tape slot.
     */
    private fun drawCeramic(c: Canvas, u: Float, title: String, sub: String, tag: String) {
        val w = CW * u; val h = CH * u
        val gray = C.hex(0x8C8C8E)
        // body: soft white with shaded edges
        g.vgrad(c, 0f, 0f, w, h, 3.6f * u, C.hex(0xFBFBF9), C.hex(0xE4E3DF))
        g.rrectStroke(c, 0.5f, 0.5f, w - 1, h - 1, 3.6f * u, maxOf(1f, 0.3f * u), C.hex(0xB9B8B3))
        g.rrectStroke(c, 1.2f * u, 1.2f * u, w - 2.4f * u, h - 2.4f * u, 2.8f * u, maxOf(1f, 0.15f * u), C.argb(160, 0xFFFFFF))
        // lower guide area, slightly raised
        g.poly(c, C.hex(0xF0EFEC), 20.5f * u, h - 0.3f * u, 25.5f * u, 47.6f * u, 74.9f * u, 47.6f * u, 79.9f * u, h - 0.3f * u)
        g.line(c, 25.5f * u, 47.6f * u, 74.9f * u, 47.6f * u, maxOf(1f, 0.2f * u), C.hex(0xC9C8C3))
        g.line(c, 20.5f * u, h - 0.4f * u, 25.5f * u, 47.6f * u, maxOf(1f, 0.2f * u), C.hex(0xD3D2CD))
        g.line(c, 79.9f * u, h - 0.4f * u, 74.9f * u, 47.6f * u, maxOf(1f, 0.2f * u), C.hex(0xC2C1BC))
        g.circle(c, 31.0f * u, 56.6f * u, 2.0f * u, C.hex(0x141416))
        g.circle(c, 69.4f * u, 56.6f * u, 2.0f * u, C.hex(0x141416))
        g.rrect(c, 40.0f * u, 55.0f * u, 3.0f * u, 3.0f * u, 0.5f * u, C.hex(0x141416))
        g.rrect(c, 57.4f * u, 55.0f * u, 3.0f * u, 3.0f * u, 0.5f * u, C.hex(0x141416))
        g.rrect(c, 46.6f * u, 54.0f * u, 7.2f * u, 4.6f * u, 0.8f * u, C.hex(0x1A1A1D))
        // big black screws
        val sx = floatArrayOf(4.4f, CW - 4.4f, 4.4f, CW - 4.4f, CW / 2)
        val sy = floatArrayOf(4.4f, 4.4f, CH - 4.4f, CH - 4.4f, 51.2f)
        for (i in 0 until 5) {
            g.circle(c, sx[i] * u, sy[i] * u, 2.2f * u, C.hex(0x1B1B1E))
            g.circle(c, (sx[i] - 0.5f) * u, (sy[i] - 0.5f) * u, 0.7f * u, C.argb(60, 0xFFFFFF))
        }
        // printed markings
        val pa = g.text(g.fonts.reg, 7.0f * u / g.sd, gray)
        c.drawText("A", 9.6f * u, 13.6f * u, pa)
        val pt = g.text(g.fonts.reg, 5.0f * u / g.sd, C.hex(0x7E7E80))
        g.drawTextRight(c, tag, CW * u - 9.0f * u, 6.6f * u, pt)
        val ps = g.text(g.fonts.reg, 1.9f * u / g.sd, gray)
        ps.letterSpacing = 0.06f
        g.drawTextRight(c, "TYPE DSD · CERAMIC POSITION", CW * u - 9.0f * u, 12.6f * u, ps)
        ps.letterSpacing = 0f
        g.line(c, 53.5f * u, 15.6f * u, CW * u - 9.0f * u, 15.6f * u, maxOf(1f, 0.15f * u), C.hex(0xA5A5A7))
        // the title written on the shell by hand
        title(c, u, title, 16.5f * u, 20.4f * u, 52f * u, 6.2f * u, 4.6f * u, C.hex(0x1E2A66))
        val pc = g.text(g.fonts.reg, 2.0f * u / g.sd, gray)
        pc.letterSpacing = 0.08f
        g.drawTextCenter(c, "CERAMIC COMPOSITE", w / 2, 33.6f * u, pc)
        pc.letterSpacing = 0f
        val pb = g.text(g.fonts.reg, 2.4f * u / g.sd, C.hex(0x9A9A9C))
        g.drawTextCenter(c, sub, w / 2, 41.2f * u, pb, 60f * u)
        // reel holes with a slight bevel
        for (rx in floatArrayOf(REEL_L, REEL_R)) {
            g.ring(c, rx * u, REEL_Y * u, HOLE_R * u, (HOLE_R + 0.6f) * u, C.hex(0xD2D1CC))
        }
    }

    private fun drawStatic(c: Canvas, u: Float, title: String, sub: String, st: TapeStyle, tag: String) {
        if (st.name == "ceramic") { drawCeramic(c, u, title, sub, tag); return }
        if (st.name == "chrome") { drawRetro(c, u, title, sub, tag); return }
        if (st.name == "metal") { drawSmoked(c, u, title, sub, tag); return }
        val w = CW * u; val h = CH * u
        // shell
        g.vgrad(c, 0f, 0f, w, h, 3.6f * u, st.shellTop, st.shellBottom)
        g.rrectStroke(c, 0.5f, 0.5f, w - 1, h - 1, 3.6f * u, 1.2f * g.d / 1.5f, st.shellEdge)
        g.rrectStroke(c, 1.6f * u, 1.6f * u, w - 3.2f * u, h - 3.2f * u, 2.4f * u, 1f, C.argb(if (st.clear) 50 else 14, 0xFFFFFF))
        if (st.name == "ceramic") {
            // glossy glaze
            g.poly(c, C.argb(70, 0xFFFFFF), 0f, 0f, w * 0.55f, 0f, 0f, h * 0.7f)
            g.rrectStroke(c, 0.5f, 0.5f, w - 1, h - 1, 3.6f * u, maxOf(1f, 0.35f * u), C.hex(0xA8A39A))
        }
        if (st.clear) {
            // inner parts seen through the clear plastic
            g.ring(c, REEL_L * u, REEL_Y * u, 16.2f * u, 16.6f * u, C.argb(40, 0xFFFFFF))
            g.ring(c, REEL_R * u, REEL_Y * u, 16.2f * u, 16.6f * u, C.argb(40, 0xFFFFFF))
            g.circle(c, 9.5f * u, 49f * u, 1.6f * u, C.argb(70, 0xFFFFFF))
            g.circle(c, CW * u - 9.5f * u, 49f * u, 1.6f * u, C.argb(70, 0xFFFFFF))
            // glare on the plastic
            g.poly(c, C.argb(16, 0xFFFFFF), 58f * u, 0f, 74f * u, 0f, 40f * u, h, 24f * u, h)
            g.poly(c, C.argb(10, 0xFFFFFF), 78f * u, 0f, 82f * u, 0f, 48f * u, h, 44f * u, h)
        }
        // bottom trapezoid
        g.poly(c, st.trapezoid, 20.5f * u, h - 0.2f * u, 25.5f * u, 47.6f * u, 74.9f * u, 47.6f * u, 79.9f * u, h - 0.2f * u)
        g.line(c, 25.5f * u, 47.6f * u, 74.9f * u, 47.6f * u, 1.2f, C.argb(26, 0xFFFFFF))
        g.line(c, 20.5f * u, h - 0.4f * u, 25.5f * u, 47.6f * u, 1f, C.argb(14, 0xFFFFFF))
        g.line(c, 79.9f * u, h - 0.4f * u, 74.9f * u, 47.6f * u, 1f, C.argb(90, 0x000000))
        g.circle(c, 31.0f * u, 56.6f * u, 2.0f * u, C.hex(0x0C0C0E))
        g.circle(c, 69.4f * u, 56.6f * u, 2.0f * u, C.hex(0x0C0C0E))
        g.rrect(c, 40.0f * u, 55.0f * u, 3.0f * u, 3.0f * u, 0.5f * u, C.hex(0x0C0C0E))
        g.rrect(c, 57.4f * u, 55.0f * u, 3.0f * u, 3.0f * u, 0.5f * u, C.hex(0x0C0C0E))
        g.rrect(c, 46.2f * u, 53.6f * u, 8.0f * u, 5.6f * u, 0.8f * u, C.hex(0x141417))
        // screws
        val sx = floatArrayOf(4.2f, CW - 4.2f, 4.2f, CW - 4.2f, CW / 2)
        val sy = floatArrayOf(4.2f, 4.2f, CH - 4.2f, CH - 4.2f, 50.6f)
        for (i in 0 until 5) {
            g.circle(c, sx[i] * u, sy[i] * u, 1.7f * u, st.screw)
            g.circle(c, sx[i] * u, sy[i] * u, 1.2f * u, st.screwIn)
            g.line(c, (sx[i] - 0.9f) * u, (sy[i] - 0.5f) * u, (sx[i] + 0.9f) * u, (sy[i] + 0.5f) * u, 0.45f * u, C.hex(0x1A1A1D))
        }
        // label
        val lx0 = 7.4f * u; val ly0 = 4.0f * u; val lx1 = (CW - 7.4f) * u; val ly1 = 44.6f * u
        if (st.clear) {
            // two paper strips; the middle stays transparent so the tape is visible
            g.rrect(c, lx0, ly0, lx1 - lx0, 13.6f * u, 1.6f * u, st.label)
            g.rrect(c, lx0, 38.6f * u, lx1 - lx0, ly1 - 38.6f * u, 1.2f * u, st.label)
            g.fill.color = st.band; g.fill.shader = null
            c.drawRect(lx0, 16.4f * u, lx1, 17.0f * u, g.fill)
            c.drawRect(lx0, 37.4f * u, lx1, 38.0f * u, g.fill)
        } else if (st.name == "metal") {
            // brushed aluminium label
            g.vgrad(c, lx0, ly0, lx1 - lx0, ly1 - ly0, 1.6f * u, C.hex(0xF1F2F4), C.hex(0xA6AAB2))
            c.save()
            g.rect.set(lx0, ly0, lx1, ly1)
            c.clipRect(g.rect)
            var yy = ly0
            var k = 0
            while (yy < ly1) {
                g.line(c, lx0, yy, lx1, yy, maxOf(1f, 0.1f * u), C.argb(if (k % 3 == 0) 26 else 14, if (k % 2 == 0) 0x000000 else 0xFFFFFF))
                yy += 0.55f * u; k++
            }
            val by0 = 17.2f * u; val by1 = 39.4f * u
            g.fill.color = st.band; g.fill.shader = null
            c.drawRect(lx0, by0, lx1, by1, g.fill)
            g.fill.color = st.bandLight
            c.drawRect(lx0, by0 + 1.2f * u, lx1, by0 + 1.6f * u, g.fill)
            c.drawRect(lx0, by1 - 1.6f * u, lx1, by1 - 1.2f * u, g.fill)
            // fine gold pinstripes
            g.fill.color = C.hex(0xD9B65E)
            c.drawRect(lx0, by0 - 0.5f * u, lx1, by0 - 0.2f * u, g.fill)
            c.drawRect(lx0, by1 + 0.2f * u, lx1, by1 + 0.5f * u, g.fill)
            c.restore()
            g.rrectStroke(c, lx0, ly0, lx1 - lx0, ly1 - ly0, 1.6f * u, 1f, C.argb(90, 0xFFFFFF))
        } else {
            g.rrect(c, lx0, ly0, lx1 - lx0, ly1 - ly0, 1.6f * u, st.label)
            val by0 = 17.2f * u; val by1 = 39.4f * u
            c.save()
            g.rect.set(lx0, ly0, lx1, ly1)
            c.clipRect(g.rect)
            g.fill.color = st.band; g.fill.shader = null
            c.drawRect(lx0, by0, lx1, by1, g.fill)
            g.fill.color = st.bandLight
            c.drawRect(lx0, by0 + 1.4f * u, lx1, by0 + 2.0f * u, g.fill)
            g.fill.color = st.bandDark
            c.drawRect(lx0, by1 - 2.0f * u, lx1, by1 - 1.4f * u, g.fill)
            c.restore()
        }
        // ruled lines
        g.fill.color = st.ruled
        for (i in 0 until 2) {
            val yy = (11.0f + i * 4.4f) * u
            c.drawRect(16.5f * u, yy, lx1 - 18.5f * u, yy + maxOf(1f, 0.25f * u), g.fill)
        }
        // side A badge
        g.circle(c, 11.6f * u, 10.0f * u, 3.0f * u, st.badgeBg)
        val pa = g.text(g.fonts.bold, 4.0f * u / g.sd, st.badgeFg)
        val fm = pa.fontMetrics
        c.drawText("A", 11.6f * u - pa.measureText("A") / 2, 10.0f * u - (fm.ascent + fm.descent) / 2, pa)
        val ps = g.text(g.fonts.bold, 2.6f * u / g.sd, st.tag)
        ps.letterSpacing = 0.08f
        g.drawTextRight(c, tag, lx1 - 2.0f * u, 5.6f * u, ps)
        ps.letterSpacing = 0f
        // handwritten title
        // handwriting gets a bit smaller for long titles before it is cut
        title(c, u, title, 16.8f * u, 15.2f * u, lx1 - 22.0f * u - 16.8f * u, 7.4f * u, 5.4f * u, st.ink)
        // bottom strip
        val pb = g.text(g.fonts.semi, 2.7f * u / g.sd, st.sub)
        g.drawTextCenter(c, sub, w / 2, 40.4f * u, pb, lx1 - lx0 - 8 * u)
        // holes & window frames
        if (st.clear) {
            g.ring(c, REEL_L * u, REEL_Y * u, HOLE_R * u, (HOLE_R + 0.5f) * u, st.band)
            g.ring(c, REEL_R * u, REEL_Y * u, HOLE_R * u, (HOLE_R + 0.5f) * u, st.band)
        } else {
            g.circle(c, REEL_L * u, REEL_Y * u, (HOLE_R + 0.7f) * u, st.bandDark)
            g.circle(c, REEL_R * u, REEL_Y * u, (HOLE_R + 0.7f) * u, st.bandDark)
            g.rrect(c, (WIN_X0 - 0.7f) * u, (WIN_Y0 - 0.7f) * u, (WIN_X1 - WIN_X0 + 1.4f) * u, (WIN_Y1 - WIN_Y0 + 1.4f) * u, 1.8f * u, st.bandDark)
        }
    }

    private fun hub(c: Canvas, cx: Float, cy: Float, u: Float, angle: Float, packR: Float, st: TapeStyle) {
        if (!st.clear) g.circle(c, cx, cy, HOLE_R * u, C.hex(0x121214))
        val pr = min(packR, HOLE_R - 0.25f)
        if (pr > HUB_R && !st.clear && st.name != "ceramic" && st.name != "metal") {
            g.circle(c, cx, cy, pr * u, st.tape)
            g.ring(c, cx, cy, (pr - 0.35f) * u, pr * u, st.tapeEdge)
        }
        g.circle(c, cx, cy, HUB_R * u, C.hex(0xE9E9E6))
        g.circle(c, cx, cy, 2.75f * u, C.hex(0x121214))
        for (i in 0 until 6) {
            val a = angle + i * PI.toFloat() / 3
            val ca = cos(a); val sa = sin(a)
            val r0 = 1.55f * u; val r1 = 2.9f * u; val hw = 0.45f * u
            g.poly(c, C.hex(0xE9E9E6),
                cx + ca * r0 - sa * hw, cy + sa * r0 + ca * hw,
                cx + ca * r1 - sa * hw, cy + sa * r1 + ca * hw,
                cx + ca * r1 + sa * hw, cy + sa * r1 - ca * hw,
                cx + ca * r0 + sa * hw, cy + sa * r0 - ca * hw)
        }
        for (i in 0 until 3) {
            val a = angle + i * 2 * PI.toFloat() / 3 + 0.5f
            g.circle(c, cx + cos(a) * 3.45f * u, cy + sin(a) * 3.45f * u, 0.32f * u, C.hex(0xA9A9A4))
        }
        if (!st.clear) g.ring(c, cx, cy, (HOLE_R - 0.25f) * u, HOLE_R * u, C.argb(160, 0x000000))
    }

    fun draw(
        c: Canvas, x: Float, y: Float, w: Float, progress: Float, title: String, sub: String, reelL: Float, reelR: Float,
        st: TapeStyle = TapeStyle.NORMAL, tag: String = "C·90", typed: Boolean = false,
    ) {
        val u = w / CW
        val key = "$title|$sub|${w.toInt()}|${st.name}|$tag|$typed|$printStyle"
        var lay = layer
        if (lay == null || key != layerKey) {
            lay = Bitmap.createBitmap(ceil(CW * u).toInt() + 1, ceil(CH * u).toInt() + 1, Bitmap.Config.ARGB_8888)
            printed = typed
            drawStatic(Canvas(lay), u, title, sub, st, tag)
            layer = lay; layerKey = key
        }
        val (rl, rr) = packs(progress)
        // soft shadow
        g.rrect(c, x + 2 * g.d, y + 6 * g.d, w, CH * u, 3.6f * u, C.argb(if (st.clear) 70 else 120, 0x000000))
        if (st.clear) {
            // whole tape packs are visible through the clear shell
            g.rrect(c, x, y, w, CH * u, 3.6f * u, C.hex(0x0E0F12))
            for ((cx, r) in listOf(REEL_L to rl, REEL_R to rr)) {
                g.circle(c, x + cx * u, y + REEL_Y * u, r * u, st.tape)
                g.ring(c, x + cx * u, y + REEL_Y * u, (r - 0.4f) * u, r * u, st.tapeEdge)
                for (k in 1..3) g.ring(c, x + cx * u, y + REEL_Y * u, (HUB_R + (r - HUB_R) * k / 4f) * u, (HUB_R + (r - HUB_R) * k / 4f + 0.12f) * u, C.argb(30, 0x000000))
            }
            // tape running between the reels along the bottom guides
            g.line(c, x + (REEL_L - rl * 0.2f) * u, y + (REEL_Y + rl) * u, x + 22f * u, y + 46.5f * u, 0.45f * u, st.tape)
            g.line(c, x + (REEL_R + rr * 0.2f) * u, y + (REEL_Y + rr) * u, x + 78.4f * u, y + 46.5f * u, 0.45f * u, st.tape)
            g.line(c, x + 22f * u, y + 46.5f * u, x + 78.4f * u, y + 46.5f * u, 0.45f * u, st.tape)
        }
        c.drawBitmap(lay, x, y, null)
        if (st.clear) {
            hub(c, x + REEL_L * u, y + REEL_Y * u, u, reelL, rl, st)
            hub(c, x + REEL_R * u, y + REEL_Y * u, u, reelR, rr, st)
            return
        }
        if (st.name == "chrome") {
            retroWindow(c, x, y, u, rl, rr)
            retroHub(c, x + REEL_L * u, y + REEL_Y * u, u, reelL)
            retroHub(c, x + REEL_R * u, y + REEL_Y * u, u, reelR)
            return
        }
        if (st.name == "metal") {
            c.save()
            g.rect.set(x + 3.2f * u, y + 16.6f * u, x + (CW - 3.2f) * u, y + 44.0f * u)
            c.clipRect(g.rect)
            for ((cx, r) in listOf(REEL_L to rl, REEL_R to rr)) {
                if (r > HOLE_R + 1.4f) g.ring(c, x + cx * u, y + REEL_Y * u, (HOLE_R + 1.4f) * u, r * u, C.argb(60, 0x3A2A1E))
            }
            c.restore()
            hub(c, x + REEL_L * u, y + REEL_Y * u, u, reelL, rl, st)
            hub(c, x + REEL_R * u, y + REEL_Y * u, u, reelR, rr, st)
            return
        }
        val ceramic = st.name == "ceramic"
        val wx0 = x + (if (ceramic) 41.6f else WIN_X0) * u; val wy0 = y + (if (ceramic) 26.6f else WIN_Y0) * u
        val wx1 = x + (if (ceramic) 58.8f else WIN_X1) * u; val wy1 = y + (if (ceramic) 30.4f else WIN_Y1) * u
        g.rrect(c, wx0, wy0, wx1 - wx0, wy1 - wy0, 1.4f * u, C.hex(0x121214))
        c.save()
        g.rect.set(wx0 + 1, wy0 + 1, wx1 - 1, wy1 - 1)
        c.clipRect(g.rect)
        g.circle(c, x + REEL_L * u, y + REEL_Y * u, rl * u, st.tape)
        g.circle(c, x + REEL_R * u, y + REEL_Y * u, rr * u, st.tape)
        g.ring(c, x + REEL_L * u, y + REEL_Y * u, (rl - 0.4f) * u, rl * u, st.tapeEdge)
        g.ring(c, x + REEL_R * u, y + REEL_Y * u, (rr - 0.4f) * u, rr * u, st.tapeEdge)
        g.line(c, wx0, wy1 - 1.2f * u, wx1, wy1 - 1.2f * u, 0.5f * u, C.hex(0x2A1A10))
        g.poly(c, C.argb(14, 0xFFFFFF), wx0 + 3 * u, wy0, wx0 + 7 * u, wy0, wx0 + 3 * u, wy1, wx0 - 1 * u, wy1)
        c.restore()
        g.rrectStroke(c, wx0, wy0, wx1 - wx0, wy1 - wy0, 1.4f * u, 1.2f, C.argb(140, 0x000000))
        hub(c, x + REEL_L * u, y + REEL_Y * u, u, reelL, rl, st)
        hub(c, x + REEL_R * u, y + REEL_Y * u, u, reelR, rr, st)
    }

    fun release() { layer = null; layerKey = "" }
}
