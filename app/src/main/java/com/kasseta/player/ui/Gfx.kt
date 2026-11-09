package com.kasseta.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Kasseta palette — the same as on TrimUI. */
object C {
    const val BG = 0xFF000000.toInt()
    const val SURF = 0xFF101012.toInt()
    const val SURF2 = 0xFF19191C.toInt()
    const val SEL = 0xFF1D1D21.toInt()
    const val LINE = 0xFF26262B.toInt()
    const val TEXT = 0xFFF3F3F3.toInt()
    const val TEXT_DIM = 0xFFE2E2E2.toInt()
    const val TEXT2 = 0xFF9B9BA1.toInt()
    const val TEXT3 = 0xFF5D5D63.toInt()
    const val ACCENT = 0xFFE5245E.toInt()
    const val ACCENT2 = 0xFFFF7D9C.toInt()
    const val CHIP = 0xFF2B2B30.toInt()

    fun hex(rgb: Int) = 0xFF000000.toInt() or rgb
    fun argb(a: Int, rgb: Int) = (a shl 24) or (rgb and 0xFFFFFF)
    fun withAlpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
    fun lerpColor(a: Int, b: Int, t: Float): Int {
        fun ch(sh: Int) = (((a shr sh) and 0xFF) + (((b shr sh) and 0xFF) - ((a shr sh) and 0xFF)) * t).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun hsv(h: Float, s: Float, v: Float): Int = Color.HSVToColor(floatArrayOf(h, s, v))
}

class Fonts(private val ctx: Context) {
    private fun load(name: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(ctx.assets, "fonts/$name") } catch (e: Exception) { fallback }

    val reg = load("Golos-Regular.ttf", Typeface.DEFAULT)
    val med = load("Golos-Medium.ttf", Typeface.DEFAULT)
    val semi = load("Golos-SemiBold.ttf", Typeface.DEFAULT_BOLD)
    val bold = load("Golos-Bold.ttf", Typeface.DEFAULT_BOLD)
    val hand = load("Caveat-SemiBold.ttf", Typeface.DEFAULT)
    /** typewriter face for printed cassette labels */
    val mono = load("PTMono-Regular.ttf", Typeface.MONOSPACE)
}

/** Drawing helpers shared by all screens. Sizes are in px; `d` = density. */
class Gfx(ctx: Context) {
    val d = ctx.resources.displayMetrics.density
    val sd = ctx.resources.displayMetrics.scaledDensity
    val fonts = Fonts(ctx)
    fun dp(v: Float) = v * d
    fun dp(v: Int) = v * d
    fun sp(v: Float) = v * sd

    val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paints = object : LinkedHashMap<Long, TextPaint>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TextPaint>?) = size > 64
    }
    val rect = RectF()
    val path = Path()
    private val m = Matrix()

    // ---------- text ----------

    /** a TextPaint per (font, size); the colour is set on every call */
    fun text(face: Typeface, sizeSp: Float, color: Int): TextPaint {
        val px = sp(sizeSp)
        val key = (System.identityHashCode(face).toLong() shl 32) or (px * 64).toLong()
        val p = paints.getOrPut(key) {
            TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face; textSize = px }
        }
        p.color = color
        return p
    }

    private val ellCache = LruCache<String, String>(600)

    fun ellipsize(s: String, p: TextPaint, maxW: Float): String {
        if (maxW <= 0) return ""
        if (p.measureText(s) <= maxW) return s
        val key = "${p.typeface.hashCode()}|${p.textSize}|${maxW.toInt()}|$s"
        ellCache.get(key)?.let { return it }
        val r = TextUtils.ellipsize(s, p, maxW, TextUtils.TruncateAt.END).toString()
        ellCache.put(key, r)
        return r
    }

    /** draws text with its top at [top]; returns width drawn */
    fun drawText(c: Canvas, s: String, x: Float, top: Float, p: TextPaint, maxW: Float = 0f): Float {
        val t = if (maxW > 0) ellipsize(s, p, maxW) else s
        c.drawText(t, x, top - p.fontMetrics.ascent, p)
        return p.measureText(t)
    }

    fun drawTextCenter(c: Canvas, s: String, cx: Float, top: Float, p: TextPaint, maxW: Float = 0f) {
        val t = if (maxW > 0) ellipsize(s, p, maxW) else s
        c.drawText(t, cx - p.measureText(t) / 2, top - p.fontMetrics.ascent, p)
    }

    fun drawTextRight(c: Canvas, s: String, right: Float, top: Float, p: TextPaint) {
        c.drawText(s, right - p.measureText(s), top - p.fontMetrics.ascent, p)
    }

    fun lineH(p: Paint) = p.fontMetrics.let { it.descent - it.ascent }

    /** wraps into at most [maxLines] lines; returns lines */
    fun wrap(s: String, p: TextPaint, maxW: Float, maxLines: Int): List<String> {
        val words = s.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size && out.size < maxLines) {
            var line = words[i++]
            while (i < words.size && p.measureText(line + " " + words[i]) <= maxW) line += " " + words[i++]
            if (out.size == maxLines - 1 && i < words.size) {
                line += " " + words.subList(i, words.size).joinToString(" ")
                i = words.size
            }
            out.add(ellipsize(line, p, maxW))
        }
        return out
    }

    // ---------- shapes ----------

    fun rrect(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        fill.shader = null
        fill.color = color
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, fill)
    }

    fun rrectStroke(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, width: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = width
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, stroke)
    }

    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        fill.shader = null
        fill.color = color
        c.drawCircle(cx, cy, r, fill)
    }

    fun ring(c: Canvas, cx: Float, cy: Float, r0: Float, r1: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = r1 - r0
        c.drawCircle(cx, cy, (r0 + r1) / 2, stroke)
    }

    fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        stroke.color = color
        stroke.strokeWidth = w
        c.drawLine(x0, y0, x1, y1, stroke)
    }

    fun vgrad(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, c0: Int, c1: Int) {
        // the paint's own alpha scales a shader on Android: make it opaque so the gradient's colours count
        fill.color = 0xFF000000.toInt()
        fill.shader = LinearGradient(0f, y, 0f, y + h, c0, c1, Shader.TileMode.CLAMP)
        rect.set(x, y, x + w, y + h)
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
    }

    fun poly(c: Canvas, color: Int, vararg pts: Float) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        path.close()
        fill.shader = null
        fill.color = color
        c.drawPath(path, fill)
    }

    // ---------- bitmaps ----------

    private val shaders = WeakHashMap<Bitmap, BitmapShader>()

    fun bitmapRounded(c: Canvas, b: Bitmap, x: Float, y: Float, size: Float, r: Float, alpha: Int = 255) {
        val sh = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        m.reset()
        m.setScale(size / b.width, size / b.height)
        m.postTranslate(x, y)
        sh.setLocalMatrix(m)
        bmpPaint.shader = sh
        bmpPaint.alpha = alpha
        rect.set(x, y, x + size, y + size)
        c.drawRoundRect(rect, r, r, bmpPaint)
        bmpPaint.shader = null
    }

    fun shaderFor(b: Bitmap): BitmapShader = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }

    // ---------- album placeholder (no cover) ----------

    fun placeholder(c: Canvas, name: String, x: Float, y: Float, size: Float, r: Float) {
        val h = abs(name.hashCode()) % 360
        val c0 = C.hsv(h.toFloat(), 0.45f, 0.30f)
        val c1 = C.hsv(((h + 40) % 360).toFloat(), 0.5f, 0.12f)
        vgrad(c, x, y, size, size, r, c0, c1)
        val cx = x + size / 2; val cy = y + size / 2
        ring(c, cx, cy, size * 0.30f, size * 0.31f, C.argb(22, 0xFFFFFF))
        ring(c, cx, cy, size * 0.22f, size * 0.225f, C.argb(16, 0xFFFFFF))
        val letter = firstLetter(name)
        if (size >= dp(20)) {
            val p = text(fonts.bold, size * 0.36f / sd, C.argb(200, 0xFFFFFF))
            val w = p.measureText(letter)
            val fm = p.fontMetrics
            c.drawText(letter, cx - w / 2, cy - (fm.ascent + fm.descent) / 2, p)
        }
    }

    private fun firstLetter(s: String): String {
        for (ch in s) if (ch.isLetterOrDigit()) return ch.uppercaseChar().toString()
        return "♪"
    }

    // ---------- small animated equalizer ----------

    fun eq(c: Canvas, x: Float, y: Float, h: Float, color: Int, animate: Boolean, t: Float) {
        val bw = h * 0.22f; val gap = h * 0.14f
        for (i in 0 until 3) {
            val k = if (animate) 0.35f + 0.65f * abs(sin(t * (5.0f + i * 1.7f) + i * 1.3f)) else 0.35f + 0.2f * i
            val bh = h * k
            rrect(c, x + i * (bw + gap), y + h - bh, bw, bh, bw * 0.4f, color)
        }
    }

    fun eqWidth(h: Float) = h * 0.22f * 3 + h * 0.14f * 2

    // ---------- icons (drawn, centred at cx,cy with size s) ----------

    fun iconPlay(c: Canvas, cx: Float, cy: Float, s: Float, playing: Boolean, color: Int) {
        if (playing) {
            val w = s * 0.26f; val h = s * 0.84f
            rrect(c, cx - s * 0.34f, cy - h / 2, w, h, w * 0.3f, color)
            rrect(c, cx + s * 0.08f, cy - h / 2, w, h, w * 0.3f, color)
        } else {
            path.reset()
            val r = s * 0.06f
            path.moveTo(cx - s * 0.30f, cy - s * 0.42f + r)
            path.lineTo(cx + s * 0.46f, cy)
            path.lineTo(cx - s * 0.30f, cy + s * 0.42f - r)
            path.close()
            fill.shader = null
            fill.color = color
            fill.pathEffect = android.graphics.CornerPathEffect(s * 0.08f)
            c.drawPath(path, fill)
            fill.pathEffect = null
        }
    }

    fun iconSkip(c: Canvas, cx: Float, cy: Float, s: Float, forward: Boolean, color: Int) {
        val dir = if (forward) 1f else -1f
        fill.color = color
        fill.shader = null
        fill.pathEffect = android.graphics.CornerPathEffect(s * 0.05f)
        for (k in 0..1) {
            val ox = cx + dir * (k * s * 0.34f - s * 0.30f)
            path.reset()
            path.moveTo(ox, cy - s * 0.32f)
            path.lineTo(ox + dir * s * 0.40f, cy)
            path.lineTo(ox, cy + s * 0.32f)
            path.close()
            c.drawPath(path, fill)
        }
        fill.pathEffect = null
        val bx = cx + dir * s * 0.46f
        rrect(c, bx - s * 0.05f, cy - s * 0.32f, s * 0.1f, s * 0.64f, s * 0.04f, color)
    }

    fun iconShuffle(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = s * 0.09f
        val x0 = cx - s * 0.42f; val x1 = cx + s * 0.36f; val y0 = cy - s * 0.26f; val y1 = cy + s * 0.26f
        path.reset(); path.moveTo(x0, y0); path.cubicTo(cx, y0, cx, y1, x1, y1); c.drawPath(path, stroke)
        path.reset(); path.moveTo(x0, y1); path.cubicTo(cx, y1, cx, y0, x1, y0); c.drawPath(path, stroke)
        arrowHead(c, x1, y0, s, color); arrowHead(c, x1, y1, s, color)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        poly(c, color, x + s * 0.14f, y, x - s * 0.04f, y - s * 0.13f, x - s * 0.04f, y + s * 0.13f)
    }

    fun iconRepeat(c: Canvas, cx: Float, cy: Float, s: Float, one: Boolean, color: Int) {
        stroke.color = color; stroke.strokeWidth = s * 0.09f
        rect.set(cx - s * 0.40f, cy - s * 0.26f, cx + s * 0.40f, cy + s * 0.26f)
        path.reset()
        path.addRoundRect(rect, s * 0.18f, s * 0.18f, Path.Direction.CW)
        c.drawPath(path, stroke)
        // gaps with arrow heads
        fill.color = C.BG
        poly(c, color, cx + s * 0.14f, cy - s * 0.26f, cx - s * 0.02f, cy - s * 0.39f, cx - s * 0.02f, cy - s * 0.13f)
        poly(c, color, cx - s * 0.14f, cy + s * 0.26f, cx + s * 0.02f, cy + s * 0.13f, cx + s * 0.02f, cy + s * 0.39f)
        if (one) {
            circle(c, cx + s * 0.40f, cy - s * 0.30f, s * 0.20f, color)
            val p = text(fonts.bold, s * 0.26f / sd, C.BG)
            val w = p.measureText("1")
            val fm = p.fontMetrics
            c.drawText("1", cx + s * 0.40f - w / 2, cy - s * 0.30f - (fm.ascent + fm.descent) / 2, p)
        }
    }

    fun iconBack(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(c, cx + s * 0.16f, cy - s * 0.34f, cx - s * 0.18f, cy, s * 0.11f, color)
        line(c, cx - s * 0.18f, cy, cx + s * 0.16f, cy + s * 0.34f, s * 0.11f, color)
    }

    fun iconDown(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(c, cx - s * 0.34f, cy - s * 0.14f, cx, cy + s * 0.18f, s * 0.11f, color)
        line(c, cx, cy + s * 0.18f, cx + s * 0.34f, cy - s * 0.14f, s * 0.11f, color)
    }

    fun iconSearch(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = s * 0.11f
        c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.28f, stroke)
        line(c, cx + s * 0.13f, cy + s * 0.13f, cx + s * 0.38f, cy + s * 0.38f, s * 0.12f, color)
    }

    fun iconClose(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(c, cx - s * 0.3f, cy - s * 0.3f, cx + s * 0.3f, cy + s * 0.3f, s * 0.1f, color)
        line(c, cx + s * 0.3f, cy - s * 0.3f, cx - s * 0.3f, cy + s * 0.3f, s * 0.1f, color)
    }

    /** phone outline with a turning arrow; [land] draws the phone lying on its side */
    fun iconRotate(c: Canvas, cx: Float, cy: Float, s: Float, land: Boolean, color: Int) {
        val w = if (land) s * 0.78f else s * 0.48f
        val h = if (land) s * 0.48f else s * 0.78f
        rrectStroke(c, cx - w / 2, cy - h / 2, w, h, s * 0.08f, s * 0.09f, color)
        stroke.color = color; stroke.strokeWidth = s * 0.08f
        rect.set(cx - s * 0.52f, cy - s * 0.52f, cx + s * 0.52f, cy + s * 0.52f)
        c.drawArc(rect, -60f, 50f, false, stroke)
        val a = (-10f) * Math.PI.toFloat() / 180f
        val ax = cx + kotlin.math.cos(a) * s * 0.52f; val ay = cy + kotlin.math.sin(a) * s * 0.52f
        poly(c, color, ax - s * 0.12f, ay - s * 0.02f, ax + s * 0.10f, ay - s * 0.04f, ax - s * 0.02f, ay + s * 0.16f)
    }

    fun iconMenu(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        for (i in -1..1) circle(c, cx, cy + i * s * 0.30f, s * 0.085f, color)
    }

    /** speech bubble with lines of text */
    fun iconLyrics(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val w = s * 0.86f; val h = s * 0.64f
        val x0 = cx - w / 2; val y0 = cy - h / 2 - s * 0.06f
        rrectStroke(c, x0, y0, w, h, s * 0.16f, s * 0.085f, color)
        poly(c, color, x0 + w * 0.22f, y0 + h - s * 0.02f, x0 + w * 0.46f, y0 + h - s * 0.02f, x0 + w * 0.18f, y0 + h + s * 0.2f)
        line(c, x0 + w * 0.24f, y0 + h * 0.36f, x0 + w * 0.76f, y0 + h * 0.36f, s * 0.08f, color)
        line(c, x0 + w * 0.24f, y0 + h * 0.64f, x0 + w * 0.6f, y0 + h * 0.64f, s * 0.08f, color)
    }

    fun iconQueue(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        for (i in 0..2) line(c, cx - s * 0.38f, cy - s * 0.28f + i * s * 0.28f, cx + (if (i == 2) s * 0.05f else s * 0.38f), cy - s * 0.28f + i * s * 0.28f, s * 0.09f, color)
        poly(c, color, cx + s * 0.18f, cy + s * 0.14f, cx + s * 0.42f, cy + s * 0.28f, cx + s * 0.18f, cy + s * 0.42f)
    }

    fun iconCheck(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        line(c, cx - s * 0.32f, cy, cx - s * 0.08f, cy + s * 0.24f, s * 0.12f, color)
        line(c, cx - s * 0.08f, cy + s * 0.24f, cx + s * 0.36f, cy - s * 0.24f, s * 0.12f, color)
    }

    // tiny utilities
    fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
    @Suppress("unused") fun polar(cx: Float, r: Float, a: Float) = cx + cos(a) * r
}
