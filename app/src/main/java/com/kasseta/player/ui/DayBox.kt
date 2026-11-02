package com.kasseta.player.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * "Album of the day": a cardboard box of CDs opens, the cases riffle,
 * one jewel case rises out, turns to the viewer and becomes the day's pick.
 */
class DayBox(private val g: Gfx, private val cases: Cases) {
    class Spine(val name: String, val color: Int)

    /** total length of the animation, seconds */
    val duration = 4.4f
    /** moment from which the buttons are active */
    val buttonsAt = 3.9f

    // box in world units: x right, y up, z towards the viewer; origin at the centre of the floor
    private val n = 16
    private val thick = 0.04f
    private val bw = n * thick + 0.05f
    private val bd = 0.60f
    private val bh = 0.50f
    private val cd = 0.54f          // case width (along z)
    private val ch = 0.47f          // case height
    private val pitch = 30f * PI.toFloat() / 180f
    private val f = 3.2f

    private var sx = 0f; private var sy = 0f; private var scale = 1f
    private val m = Matrix()
    private val bp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labels = HashMap<String, Bitmap>()
    private var front: Bitmap? = null
    private var jewelBmp: Bitmap? = null
    private var jewelKey = ""

    // matte graphite
    private val kraft = C.hex(0x47484D)
    private val kraftDark = C.hex(0x2B2C30)
    private val kraftInside = C.hex(0x18181B)

    /** rects of the final buttons: play, open album, close (l, t, r, b each) */
    val play = FloatArray(4); val open = FloatArray(4); val close = FloatArray(4); val again = FloatArray(4)

    private fun proj(x: Float, y: Float, z: Float, out: FloatArray, o: Int) {
        val yc = y * cos(pitch) - z * sin(pitch)
        val zc = y * sin(pitch) + z * cos(pitch)
        val s = f / (f - zc)
        out[o] = sx + x * s * scale
        out[o + 1] = sy - yc * s * scale
    }

    private val q = FloatArray(8)
    private fun quad(p: FloatArray): FloatArray {
        for (i in 0 until 4) proj(p[i * 3], p[i * 3 + 1], p[i * 3 + 2], q, i * 2)
        return q
    }

    private fun fillQuad(c: Canvas, color: Int, vararg p: Float) {
        val s = quad(p)
        g.poly(c, color, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
    }

    /** signed area of the projected quad: > 0 when it faces the viewer (clockwise on screen) */
    private fun facing(s: FloatArray): Float {
        var a = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; a += s[i * 2] * s[j * 2 + 1] - s[j * 2] * s[i * 2 + 1] }
        return a
    }

    private fun bitmapQuad(c: Canvas, b: Bitmap, s: FloatArray, alpha: Int = 255) {
        val src = floatArrayOf(0f, 0f, b.width.toFloat(), 0f, b.width.toFloat(), b.height.toFloat(), 0f, b.height.toFloat())
        m.setPolyToPoly(src, 0, s, 0, 4)
        bp.alpha = alpha
        c.drawBitmap(b, m, bp)
    }

    private fun spineLabel(sp: Spine): Bitmap = labels.getOrPut(sp.name + sp.color) {
        val w = 320; val h = 26
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(C.hex(0x17171B))
        g.fill.color = sp.color
        c.drawRect(0f, 0f, w * 0.10f, h.toFloat(), g.fill)
        g.fill.color = C.argb(60, 0xFFFFFF)
        c.drawRect(0f, 0f, w.toFloat(), 2f, g.fill)
        val p = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = g.fonts.semi
        p.textSize = h * 0.62f
        p.color = C.hex(0xD8D8DC)
        val txt = g.ellipsize(sp.name, p, w * 0.84f)
        c.drawText(txt, w * 0.14f, h * 0.72f, p)
        b
    }

    private fun frontLabel(): Bitmap = front ?: run {
        val w = 512; val h = 360
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        // soft light from above and a fine matte grain
        g.vgrad(c, 0f, 0f, w.toFloat(), h.toFloat(), 0f, C.hex(0x46474C), C.hex(0x2E2F33))
        val rnd = java.util.Random(7)
        for (i in 0 until 2600) {
            g.fill.color = if (rnd.nextBoolean()) C.argb(10, 0xFFFFFF) else C.argb(14, 0x000000)
            val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h
            c.drawRect(x, y, x + 2f, y + 2f, g.fill)
        }
        front = b
        b
    }

    private fun jewelBitmap(cover: Bitmap?, name: String, w: Int): Bitmap {
        val k = "$name@$w@${cover?.hashCode()}"
        jewelBmp?.let { if (jewelKey == k) return it }
        val h = (w * Cases.JEWEL_RATIO).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cases.jewel(Canvas(b), 0f, 0f, w.toFloat(), cover, name)
        jewelBmp = b; jewelKey = k
        return b
    }

    /** thin edge around a flap so the closed lid reads as separate panels */
    private fun edges(c: Canvas, s: FloatArray) {
        for (i in 0 until 4) { val j = (i + 1) % 4; g.line(c, s[i * 2], s[i * 2 + 1], s[j * 2], s[j * 2 + 1], g.dp(1f), C.argb(90, 0x000000)) }
    }

    private fun ph(t: Float, a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
    private fun easeOut(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)
    private fun easeInOut(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2

    /**
     * Draws the whole scene at time [t] (seconds). [chosen] is the index in [spines] of the day's album.
     */
    fun draw(
        c: Canvas, w: Float, h: Float, top: Float, bottom: Float, t: Float,
        spines: List<Spine>, chosen: Int, cover: Bitmap?, title: String, artist: String, info: String, date: String,
    ) {
        val landscape = w > h * 1.15f
        // dim the screen
        g.fill.color = C.argb((225 * ph(t, 0f, 0.35f)).toInt(), 0x050506)
        c.drawRect(0f, 0f, w, h, g.fill)

        // header
        val ha = ph(t, 0.1f, 0.5f)
        val hp = g.text(g.fonts.bold, 13f, C.withAlpha(C.ACCENT, (255 * ha).toInt()))
        g.drawTextCenter(c, "А Л Ь Б О М   Д Н Я", w / 2, top + g.dp(18f), hp)
        g.drawTextCenter(c, date, w / 2, top + g.dp(18f) + g.lineH(hp), g.text(g.fonts.reg, 13f, C.withAlpha(C.TEXT2, (255 * ha).toInt())))
        // close button
        val cx = w - g.dp(32f); val cy = top + g.dp(30f)
        g.iconClose(c, cx, cy, g.dp(20f), C.withAlpha(C.TEXT2, (255 * ha).toInt()))
        close[0] = cx - g.dp(26f); close[1] = cy - g.dp(26f); close[2] = cx + g.dp(26f); close[3] = cy + g.dp(26f)

        // ---------- the box ----------
        val boxOut = ph(t, 3.0f, 3.6f)
        val rise = easeOut(ph(t, 0f, 0.5f))
        scale = if (landscape) min(w * 0.34f / bw, h * 0.50f / bh) else min(w * 0.50f / bw, h * 0.28f / bh)
        sx = if (landscape) w * 0.5f else w / 2
        val sy0 = (if (landscape) h * 0.80f else h * 0.70f) + (1f - rise) * h * 0.5f
        sy = sy0 + easeInOut(boxOut) * h * 0.55f
        val boxAlpha = 1f - boxOut
        if (boxAlpha > 0f) {
            if (boxAlpha < 1f) c.saveLayerAlpha(0f, 0f, w, h, (boxAlpha * 255).toInt())
            drawBox(c, t, spines, chosen)
            if (boxAlpha < 1f) c.restore()
        }

        // ---------- the chosen case ----------
        // while it is still coming out, the front wall of the box hides its lower part
        val rim = FloatArray(2)
        proj(0f, bh, bd / 2, rim, 0)
        sy = sy0  // the case does not leave with the box
        val clip = t < 2.95f && boxAlpha > 0f
        if (clip) { c.save(); c.clipRect(0f, 0f, w, rim[1]) }
        drawChosen(c, w, h, top, bottom, t, spines, chosen, cover, title, landscape)
        if (clip) c.restore()

        // ---------- text and buttons ----------
        val ta = ph(t, 3.8f, 4.3f)
        if (ta > 0f) {
            val a = (255 * ta).toInt()
            val (tx, ty, tw) = textArea(w, h, top, bottom, landscape)
            val dy = (1f - easeOut(ta)) * g.dp(16f)
            var y = ty + dy
            val tp = g.text(g.fonts.bold, 24f, C.withAlpha(C.TEXT, a))
            for (l in g.wrap(title, tp, tw, 2)) { g.drawTextCenter(c, l, tx + tw / 2, y, tp); y += g.lineH(tp) }
            val ap = g.text(g.fonts.semi, 16f, C.withAlpha(C.ACCENT2, a))
            g.drawTextCenter(c, artist, tx + tw / 2, y, ap, tw); y += g.lineH(ap) + g.dp(6f)
            val ip = g.text(g.fonts.reg, 13f, C.withAlpha(C.TEXT3, a))
            g.drawTextCenter(c, info, tx + tw / 2, y, ip, tw); y += g.lineH(ip) + g.dp(22f)
            // buttons
            val bh2 = g.dp(48f)
            val bw2 = min(tw, g.dp(300f))
            val bx = tx + (tw - bw2) / 2
            g.rrect(c, bx, y, bw2, bh2, bh2 / 2, C.withAlpha(C.ACCENT, a))
            val pp = g.text(g.fonts.bold, 16f, C.withAlpha(C.TEXT, a))
            val lbl = "Слушать"
            val lw = pp.measureText(lbl) + g.dp(26f)
            g.iconPlay(c, tx + tw / 2 - lw / 2 + g.dp(8f), y + bh2 / 2, g.dp(18f), false, C.withAlpha(C.TEXT, a))
            g.drawText(c, lbl, tx + tw / 2 - lw / 2 + g.dp(26f), y + (bh2 - g.lineH(pp)) / 2, pp)
            play[0] = bx; play[1] = y; play[2] = bx + bw2; play[3] = y + bh2
            y += bh2 + g.dp(10f)
            g.rrect(c, bx, y, bw2, bh2, bh2 / 2, C.withAlpha(C.CHIP, a))
            g.drawTextCenter(c, "Открыть альбом", tx + tw / 2, y + (bh2 - g.lineH(pp)) / 2, g.text(g.fonts.semi, 16f, C.withAlpha(C.TEXT, a)))
            open[0] = bx; open[1] = y; open[2] = bx + bw2; open[3] = y + bh2
            // pick another one
            y += bh2 + g.dp(8f)
            val rp = g.text(g.fonts.semi, 14.5f, C.withAlpha(C.TEXT2, a))
            val rl = "Выбрать другой"
            val rw = rp.measureText(rl) + g.dp(26f)
            val rx = tx + tw / 2 - rw / 2
            g.iconShuffle(c, rx + g.dp(9f), y + g.dp(20f), g.dp(17f), C.withAlpha(C.TEXT2, a))
            g.drawText(c, rl, rx + g.dp(26f), y + g.dp(20f) - g.lineH(rp) / 2, rp)
            again[0] = rx - g.dp(12f); again[1] = y; again[2] = rx + rw + g.dp(12f); again[3] = y + g.dp(40f)
        }
    }

    /** final place of the jewel case: x, y, width */
    private fun finalCase(w: Float, h: Float, top: Float, bottom: Float, landscape: Boolean): Triple<Float, Float, Float> {
        return if (landscape) {
            val cw = min(w * 0.40f, (bottom - top - g.dp(110f)) / Cases.JEWEL_RATIO)
            Triple(w * 0.27f - cw / 2, top + g.dp(70f) + (bottom - top - g.dp(90f) - cw * Cases.JEWEL_RATIO) / 2, cw)
        } else {
            val cw = min(w * 0.76f, (bottom - top) * 0.40f / Cases.JEWEL_RATIO)
            Triple((w - cw) / 2, top + g.dp(76f), cw)
        }
    }

    private fun textArea(w: Float, h: Float, top: Float, bottom: Float, landscape: Boolean): Triple<Float, Float, Float> {
        val (fx, fy, fw) = finalCase(w, h, top, bottom, landscape)
        return if (landscape) Triple(w * 0.52f, top + g.dp(90f), w * 0.42f)
        else Triple(g.dp(28f), fy + fw * Cases.JEWEL_RATIO + g.dp(28f), w - g.dp(56f))
    }

    private fun caseX(i: Int) = -n * thick / 2 + i * thick

    /** lift of case i during the riffle */
    private fun riffle(t: Float, i: Int, chosen: Int): Float {
        val u = ph(t, 1.2f, 2.35f)
        if (u <= 0f || u >= 1f) return 0f
        val s = -1.5f + (chosen + 1.5f) * easeOut(u)
        val d = (i - s) / 0.9f
        val fade = min(1f, (1f - u) * 6f)
        return 0.045f * exp(-d * d) * fade
    }

    private fun drawBox(c: Canvas, t: Float, spines: List<Spine>, chosen: Int) {
        val x0 = -bw / 2; val x1 = bw / 2; val z0 = -bd / 2; val z1 = bd / 2
        // flap angles (degrees → radians): front/back first, then the sides
        val fb = easeOut(ph(t, 0.45f, 0.95f))
        val sd = easeOut(ph(t, 0.70f, 1.20f))
        val aFront = (fb * 192f) * PI.toFloat() / 180f
        val aBack = (fb * 112f) * PI.toFloat() / 180f
        val aSide = (sd * 112f) * PI.toFloat() / 180f
        // shadow on the floor
        fillQuad(c, C.argb(120, 0x000000), x0 - 0.03f, 0f, z0 - 0.02f, x1 + 0.03f, 0f, z0 - 0.02f, x1 + 0.06f, 0f, z1 + 0.10f, x0 - 0.06f, 0f, z1 + 0.10f)

        // back flap standing behind the box once it is past vertical
        if (aBack > PI.toFloat() / 2) flapBack(c, aBack)
        // inside: back wall and side walls
        fillQuad(c, kraftInside, x0, 0f, z0, x1, 0f, z0, x1, bh, z0, x0, bh, z0)
        fillQuad(c, C.hex(0x111113), x0, bh, z0, x0, bh, z1, x0, 0f, z1, x0, 0f, z0)
        fillQuad(c, C.hex(0x111113), x1, bh, z0, x1, 0f, z0, x1, 0f, z1, x1, bh, z1)
        // inner shadow line under the rim
        fillQuad(c, C.argb(90, 0x000000), x0, bh, z0, x1, bh, z0, x1, bh - 0.05f, z0, x0, bh - 0.05f, z0)

        // the CD cases, outer ones first so the inner ones overlap them
        val order = (0 until n).sortedByDescending { abs(caseX(it) + thick / 2) }
        val zc0 = -cd / 2; val zc1 = cd / 2
        for (i in order) {
            if (i == chosen && t > 2.3f) continue
            val sp = spines[i % spines.size]
            val lift = riffle(t, i, chosen) + if (i == chosen) chosenLift(t) else 0f
            val xa = caseX(i) + 0.002f; val xb = xa + thick - 0.004f
            val yb = 0.01f + lift; val yt = yb + ch
            // side face towards the centre
            if (xb < 0f) fillQuad(c, C.hex(0x101013), xb, yt, zc0, xb, yt, zc1, xb, yb, zc1, xb, yb, zc0)
            else fillQuad(c, C.hex(0x101013), xa, yt, zc1, xa, yt, zc0, xa, yb, zc0, xa, yb, zc1)
            // end facing the viewer (visible above the front wall when lifted)
            fillQuad(c, C.hex(0x1E1E23), xa, yt, zc1, xb, yt, zc1, xb, yb, zc1, xa, yb, zc1)
            fillQuad(c, sp.color, xa, yt, zc1, xb, yt, zc1, xb, yt - 0.06f, zc1, xa, yt - 0.06f, zc1)
            // top: the printed spine, text running from the back to the front
            val s = quad(floatArrayOf(xa, yt, zc0, xa, yt, zc1, xb, yt, zc1, xb, yt, zc0))
            // map the label: its left edge at the back, its top edge on the left side
            val dst = floatArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
            val lb = spineLabel(sp)
            val src = floatArrayOf(0f, 0f, lb.width.toFloat(), 0f, lb.width.toFloat(), lb.height.toFloat(), 0f, lb.height.toFloat())
            m.setPolyToPoly(src, 0, dst, 0, 4)
            bp.alpha = 255
            c.drawBitmap(lb, m, bp)
            if (i == chosen && t > 2.0f) {
                // the pick glows a little before it rises
                val a = (min(1f, (t - 2.0f) / 0.3f) * 140).toInt()
                g.poly(c, C.argb(a, 0xE5245E), s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
            }
        }

        // side flaps
        flapSide(c, aSide, true)
        flapSide(c, aSide, false)
        // back flap while it still lies over the opening
        if (aBack <= PI.toFloat() / 2) flapBack(c, aBack)

        // front wall with its label
        val fs = quad(floatArrayOf(x0, bh, z1, x1, bh, z1, x1, 0f, z1, x0, 0f, z1))
        bitmapQuad(c, frontLabel(), fs.copyOf())
        g.line(c, fs[0], fs[1], fs[2], fs[3], g.dp(1.5f), C.argb(45, 0xFFFFFF))
        // front flap
        flapFront(c, aFront)
    }

    private fun chosenLift(t: Float) = 0.62f * easeInOut(ph(t, 2.3f, 2.85f))

    private fun flapFront(c: Canvas, a: Float) {
        val x0 = -bw / 2; val x1 = bw / 2; val z1 = bd / 2; val l = bd / 2
        val dy = sin(a) * l; val dz = -cos(a) * l
        val s = quad(floatArrayOf(x0, bh, z1, x1, bh, z1, x1, bh + dy, z1 + dz, x0, bh + dy, z1 + dz))
        val col = if (facing(s) > 0) kraftDark else kraft
        g.poly(c, col, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
        edges(c, s)
    }

    private fun flapBack(c: Canvas, a: Float) {
        val x0 = -bw / 2; val x1 = bw / 2; val z0 = -bd / 2; val l = bd / 2
        val dy = sin(a) * l; val dz = cos(a) * l
        val s = quad(floatArrayOf(x0, bh, z0, x1, bh, z0, x1, bh + dy, z0 + dz, x0, bh + dy, z0 + dz))
        val col = if (a > PI.toFloat() / 2) kraftInside else kraft
        g.poly(c, col, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
        edges(c, s)
    }

    private fun flapSide(c: Canvas, a: Float, left: Boolean) {
        val xh = if (left) -bw / 2 else bw / 2
        val sg = if (left) 1f else -1f
        val l = bw / 2 * 0.98f
        val dx = sg * cos(a) * l; val dy = sin(a) * l
        val z0 = -bd / 2; val z1 = bd / 2
        val s = quad(floatArrayOf(xh, bh, z0, xh, bh, z1, xh + dx, bh + dy, z1, xh + dx, bh + dy, z0))
        val col = if (a > PI.toFloat() / 2) C.hex(0x36373B) else kraft
        g.poly(c, col, s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
        edges(c, s)
    }

    /** the picked case: rises from the box, turns towards the viewer and settles at the top */
    private fun drawChosen(
        c: Canvas, w: Float, h: Float, top: Float, bottom: Float, t: Float,
        spines: List<Spine>, chosen: Int, cover: Bitmap?, title: String, landscape: Boolean,
    ) {
        if (t <= 2.3f) return
        val (fx, fy, fw) = finalCase(w, h, top, bottom, landscape)
        val jb = jewelBitmap(cover, title, fw.toInt().coerceAtLeast(64))
        val sp = spines[chosen % spines.size]
        val lift = chosenLift(t)
        val turn = easeInOut(ph(t, 2.85f, 3.5f))
        val fwd = easeInOut(ph(t, 2.85f, 3.5f))
        val morph = easeInOut(ph(t, 3.25f, 3.95f))
        // centre of the case in the world
        val cxw = caseX(chosen) + thick / 2
        val cyw = 0.01f + lift + ch / 2 + fwd * 0.22f
        val czw = fwd * 0.22f
        val ang = -turn * PI.toFloat() / 2  // rotation about the vertical axis
        fun pt(lx: Float, ly: Float, lz: Float, out: FloatArray, o: Int) {
            val rx = lx * cos(ang) + lz * sin(ang)
            val rz = -lx * sin(ang) + lz * cos(ang)
            proj(cxw * (1f - fwd) + rx, cyw + ly, czw + rz, out, o)
        }
        val ht = thick / 2; val hc = cd / 2; val hh = ch / 2
        // the big face with the cover (normal +x before the turn); bitmap left edge = front (z+)
        val face = FloatArray(8)
        pt(ht, hh, hc, face, 0); pt(ht, hh, -hc, face, 2); pt(ht, -hh, -hc, face, 4); pt(ht, -hh, hc, face, 6)
        // morph the projected face into its final flat rectangle
        val fin = floatArrayOf(fx, fy, fx + fw, fy, fx + fw, fy + fw * Cases.JEWEL_RATIO, fx, fy + fw * Cases.JEWEL_RATIO)
        val qd = FloatArray(8) { g.lerp(face[it], fin[it], morph) }
        // the spine end and the top, visible until the case has turned
        if (morph < 1f) {
            val e = FloatArray(8)
            pt(-ht, hh, hc, e, 0); pt(ht, hh, hc, e, 2); pt(ht, -hh, hc, e, 4); pt(-ht, -hh, hc, e, 6)
            val ea = ((1f - morph) * 255).toInt()
            if (facing(e) > 0) {
                g.poly(c, C.withAlpha(C.hex(0x1E1E23), ea), e[0], e[1], e[2], e[3], e[4], e[5], e[6], e[7])
            }
            val tp = FloatArray(8)
            pt(-ht, hh, -hc, tp, 0); pt(-ht, hh, hc, tp, 2); pt(ht, hh, hc, tp, 4); pt(ht, hh, -hc, tp, 6)
            if (facing(tp) > 0) bitmapQuad(c, spineLabel(sp), tp, ea)
        }
        if (facing(qd) > 0 || morph > 0.5f) {
            // soft accent glow behind the case once it is in place
            if (morph > 0f) {
                val ga = (morph * 40).toInt()
                g.circle(c, fx + fw / 2, fy + fw * Cases.JEWEL_RATIO / 2, fw * 0.62f, C.argb(ga / 3, 0xE5245E))
                g.circle(c, fx + fw / 2, fy + fw * Cases.JEWEL_RATIO / 2, fw * 0.50f, C.argb(ga / 3, 0xE5245E))
            }
            bitmapQuad(c, jb, qd)
        }
    }
}
