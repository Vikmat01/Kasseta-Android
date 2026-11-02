package com.kasseta.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas

/** Album packaging: CD jewel case and cassette box with a J-card. */
class Cases(private val g: Gfx) {
    companion object {
        /** jewel case 142 × 125 mm */
        const val JEWEL_RATIO = 125f / 142f
        /** cassette box seen from the front (J-card panel) */
        const val TAPE_RATIO = 1.56f
    }

    private fun cover(c: Canvas, cover: Bitmap?, name: String, x: Float, y: Float, s: Float, r: Float) {
        if (cover != null) g.bitmapRounded(c, cover, x, y, s, r) else g.placeholder(c, name, x, y, s, r)
    }

    /** clear CD jewel case of width [w], the booklet filling the front; returns its height */
    fun jewel(c: Canvas, x: Float, y: Float, w: Float, cover: Bitmap?, name: String, selected: Boolean = false): Float {
        val h = w * JEWEL_RATIO
        val r = w * 0.012f
        // shadow
        g.rrect(c, x + w * 0.01f, y + w * 0.025f, w, h, r, C.argb(110, 0x000000))
        // clear plastic
        g.rrect(c, x, y, w, h, r, C.argb(70, 0xD9DCE0))
        // booklet
        val bs = h * 0.966f
        val bx = x + w - bs - w * 0.011f
        val by = y + (h - bs) / 2
        cover(c, cover, name, bx, by, bs, w * 0.002f)
        // spine (hinge side): frosted strip with two moulded lines
        val sp = w * 0.064f
        g.vgrad(c, x, y, sp, h, r, C.argb(165, 0xF2F3F5), C.argb(120, 0xC7CBD0))
        g.line(c, x + w * 0.022f, y + r, x + w * 0.022f, y + h - r, maxOf(1f, w * 0.004f), C.argb(170, 0xFFFFFF))
        g.line(c, x + w * 0.05f, y + r, x + w * 0.05f, y + h - r, maxOf(1f, w * 0.003f), C.argb(60, 0x000000))
        g.line(c, x + sp, y + r, x + sp, y + h - r, maxOf(1f, w * 0.003f), C.argb(120, 0xFFFFFF))
        // rim and highlights
        g.rrectStroke(c, x, y, w, h, r, maxOf(1f, w * 0.006f), C.argb(210, 0xE9EBEE))
        g.line(c, x + r, y + w * 0.005f, x + w - r, y + w * 0.005f, maxOf(1f, w * 0.003f), C.argb(150, 0xFFFFFF))
        g.line(c, x + w - w * 0.005f, y + r, x + w - w * 0.005f, y + h - r, maxOf(1f, w * 0.003f), C.argb(110, 0xFFFFFF))
        g.poly(c, C.argb(22, 0xFFFFFF), x + sp, y, x + w * 0.46f, y, x + sp, y + h * 0.55f)
        if (selected) g.rrectStroke(c, x - g.dp(3f), y - g.dp(3f), w + g.dp(6f), h + g.dp(6f), r + g.dp(3f), g.dp(2.5f), C.ACCENT)
        return h
    }

    /** open jewel case base: spine, black tray; returns the booklet area (x, y, size) */
    fun jewelBase(c: Canvas, x: Float, y: Float, w: Float): FloatArray {
        val h = w * JEWEL_RATIO
        val r = w * 0.012f
        g.rrect(c, x + w * 0.01f, y + w * 0.025f, w, h, r, C.argb(110, 0x000000))
        g.rrect(c, x, y, w, h, r, C.hex(0x0E0E11))
        val bs = h * 0.966f
        val bx = x + w - bs - w * 0.011f
        val by = y + (h - bs) / 2
        // tray ribs and the centre hub with teeth
        g.rrectStroke(c, bx + bs * 0.02f, by + bs * 0.02f, bs * 0.96f, bs * 0.96f, bs * 0.02f, maxOf(1f, w * 0.003f), C.argb(30, 0xFFFFFF))
        val sp = w * 0.064f
        g.vgrad(c, x, y, sp, h, r, C.argb(200, 0xE6E8EB), C.argb(170, 0xB9BDC2))
        g.line(c, x + w * 0.022f, y + r, x + w * 0.022f, y + h - r, maxOf(1f, w * 0.004f), C.argb(170, 0xFFFFFF))
        g.rrectStroke(c, x, y, w, h, r, maxOf(1f, w * 0.006f), C.argb(170, 0xE9EBEE))
        return floatArrayOf(bx, by, bs)
    }

    /** cassette box (clear Norelco case, J-card with cover and printed title); returns height */
    fun tapeBox(
        c: Canvas, x: Float, y: Float, w: Float, cover: Bitmap?, name: String, artist: String, info: String,
        tag: String, strip: Int, selected: Boolean = false,
    ): Float {
        val h = w * TAPE_RATIO
        val r = w * 0.035f
        g.rrect(c, x + w * 0.015f, y + w * 0.035f, w, h, r, C.argb(110, 0x000000))
        // J-card
        val m = w * 0.045f
        val jx = x + m; val jy = y + m; val jw = w - 2 * m; val jh = h - 2 * m
        g.rrect(c, jx, jy, jw, jh, w * 0.01f, strip)
        cover(c, cover, name, jx, jy, jw, w * 0.006f)
        // printed text on the J-card
        var ty = jy + jw + jw * 0.07f
        val tp = g.text(g.fonts.bold, jw * 0.105f / g.sd, C.hex(0xF6F2EA))
        for (line in g.wrap(name, tp, jw * 0.86f, 2)) {
            g.drawText(c, line, jx + jw * 0.07f, ty, tp)
            ty += g.lineH(tp) * 0.92f
        }
        val ap = g.text(g.fonts.semi, jw * 0.075f / g.sd, C.withAlpha(C.hex(0xF6F2EA), 200))
        g.drawText(c, artist, jx + jw * 0.07f, ty + jw * 0.01f, ap, jw * 0.86f)
        val ip = g.text(g.fonts.semi, jw * 0.055f / g.sd, C.withAlpha(C.hex(0xF6F2EA), 150))
        ip.letterSpacing = 0.1f
        g.drawText(c, tag, jx + jw * 0.07f, jy + jh - jw * 0.07f - g.lineH(ip), ip, jw * 0.55f)
        ip.letterSpacing = 0f
        g.drawTextRight(c, info, jx + jw * 0.93f, jy + jh - jw * 0.07f - g.lineH(ip), ip)
        // clear case: rim, the two hinge pins and the flap edge
        g.rrect(c, x, y, w, h, r, C.argb(26, 0xFFFFFF))
        g.rrectStroke(c, x, y, w, h, r, maxOf(1f, w * 0.008f), C.argb(110, 0xFFFFFF))
        g.rrectStroke(c, x + m * 0.45f, y + m * 0.45f, w - m * 0.9f, h - m * 0.9f, r * 0.7f, 1f, C.argb(40, 0xFFFFFF))
        g.circle(c, x + m * 0.5f, y + h * 0.06f, m * 0.28f, C.argb(120, 0xFFFFFF))
        g.circle(c, x + m * 0.5f, y + h * 0.94f, m * 0.28f, C.argb(120, 0xFFFFFF))
        g.poly(c, C.argb(28, 0xFFFFFF), x + w * 0.15f, y, x + w * 0.62f, y, x + w * 0.15f, y + h * 0.42f)
        if (selected) g.rrectStroke(c, x - g.dp(3f), y - g.dp(3f), w + g.dp(6f), h + g.dp(6f), r + g.dp(3f), g.dp(2.5f), C.ACCENT)
        return h
    }
}
