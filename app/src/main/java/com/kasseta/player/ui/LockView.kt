package com.kasseta.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.kasseta.player.App
import com.kasseta.player.Covers
import com.kasseta.player.Engine
import com.kasseta.player.Track
import com.kasseta.player.fmtTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Lock screen player: clock, cassette or CD, track info, controls, swipe up to unlock. */
@SuppressLint("ViewConstructor")
class LockView(ctx: Context, private val onUnlock: () -> Unit) : View(ctx), Engine.Listener {
    private val g = Gfx(ctx)
    private val cassette = Cassette(g)
    private val disc = Disc(g, Cases(g))
    private val engine get() = App.engine
    private var insetTop = 0f
    private var insetBottom = 0f
    private var lastFrame = 0L
    private var t = 0f
    private var reelL = 0f
    private var reelR = 0f
    private var discAngle = 0f
    private var discSpeed = 0f
    private var discOut = 0f
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    // touch
    private var downY = 0f
    private var downX = 0f
    private var drag = 0f
    private var dragging = false
    private var unlocking = -1L
    private val buttons = ArrayList<Pair<FloatArray, () -> Unit>>()

    fun setInsets(top: Int, bottom: Int) { insetTop = top.toFloat(); insetBottom = bottom.toFloat(); invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); engine.addListener(this) }
    override fun onDetachedFromWindow() { engine.removeListener(this); super.onDetachedFromWindow() }
    override fun onEngineChanged() { invalidate() }

    private fun tapeFor(t: Track): Pair<TapeStyle, String> = when (t.tier) {
        4 -> TapeStyle.DSD to t.formatName.replace("DSD", "DSD ").replace("DST", "DST ")
        3 -> TapeStyle.METAL to (if (t.rate > 0) (t.rate / 1000).toString() else "IV")
        2 -> TapeStyle.CHROME to "TYPE II · CrO2"
        else -> TapeStyle.NORMAL to "TYPE I · C90"
    }

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        t += dt
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(C.BG)
        // slide away while unlocking
        val shift = if (unlocking >= 0) -h * ease(min(1f, (now - unlocking) / 260f)) else min(0f, drag)
        if (unlocking >= 0 && now - unlocking > 280) { unlocking = -1; drag = 0f; onUnlock() }
        c.save()
        c.translate(0f, shift)
        buttons.clear()

        val tr = engine.currentTrack()
        val playing = engine.isPlaying
        val dur = engine.durationMs()
        val pos = engine.positionMs()
        val prog = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val landscape = w > h * 1.15f
        val pad = g.dp(24f)

        // clock
        val tp = g.text(g.fonts.reg, if (landscape) 46f else 64f, C.TEXT)
        val time = timeFmt.format(Date())
        var y = insetTop + g.dp(if (landscape) 10f else 28f)
        val clockX = if (landscape) pad else w / 2
        if (landscape) g.drawText(c, time, clockX, y, tp) else g.drawTextCenter(c, time, clockX, y, tp)
        y += g.lineH(tp)
        val dp = g.text(g.fonts.reg, 15f, C.TEXT2)
        val date = dateFmt.format(Date()).replaceFirstChar { it.uppercase() }
        if (landscape) g.drawText(c, date, clockX, y, dp) else g.drawTextCenter(c, date, clockX, y, dp)
        y += g.lineH(dp) + g.dp(18f)

        // visual
        val vx: Float; val vy: Float; val vw: Float; val vh: Float
        if (landscape) {
            vx = w * 0.42f; vy = insetTop + g.dp(16f); vw = w * 0.55f; vh = h - insetTop - insetBottom - g.dp(84f)
        } else {
            vx = pad; vy = y; vw = w - 2 * pad; vh = min(vw * 0.66f, h * 0.32f)
        }
        if (tr != null) {
            if (playing) {
                val (rl, rr) = cassette.packs(prog)
                reelL -= 47.6f / rl * dt * 1.6f
                reelR -= 47.6f / rr * dt * 1.6f
            }
            val target = if (playing) 4.4f else 0f
            discSpeed += (target - discSpeed) * min(1f, dt * 3f)
            discAngle = (discAngle + discSpeed * dt) % (2 * PI.toFloat())
            discOut += ((if (playing) 1f else 0f) - discOut) * min(1f, dt * 4.5f)
            if (App.cdView) {
                val lib = App.library
                val ai = App.albumOf(tr)
                val bmp = if (ai >= 0) Covers.get(lib, ai, min(vh * 0.94f, vw * 0.6f).toInt()) else null
                disc.draw(c, vx, vy, vw, vh, bmp, tr.album, discAngle, discOut)
            } else {
                val cw = min(vh * Cassette.CW / Cassette.CH, vw)
                val ch = cw * Cassette.CH / Cassette.CW
                val (st, tag) = tapeFor(tr)
                cassette.draw(c, vx + (vw - cw) / 2, vy + (vh - ch) / 2, cw, prog, tr.title, "${tr.artist}  ·  ${tr.album}", reelL, reelR, st, tag)
            }
        }

        // title, progress, controls
        val ix0: Float; val ix1: Float
        var iy: Float
        if (landscape) { ix0 = pad; ix1 = w * 0.40f; iy = y + g.dp(6f) } else { ix0 = pad; ix1 = w - pad; iy = vy + vh + g.dp(22f) }
        val icx = (ix0 + ix1) / 2
        if (tr != null) {
            val ttl = g.text(g.fonts.bold, 20f, C.TEXT)
            g.drawTextCenter(c, tr.title, icx, iy, ttl, ix1 - ix0); iy += g.lineH(ttl)
            val ap = g.text(g.fonts.semi, 14f, C.ACCENT2)
            g.drawTextCenter(c, "${tr.artist}  —  ${tr.album}", icx, iy, ap, ix1 - ix0); iy += g.lineH(ap) + g.dp(4f)
            g.drawTextCenter(c, tr.qualityLine, icx, iy, g.text(g.fonts.reg, 12f, C.TEXT3), ix1 - ix0); iy += g.dp(26f)
            g.rrect(c, ix0, iy, ix1 - ix0, g.dp(3f), g.dp(1.5f), C.hex(0x2A2A2F))
            g.rrect(c, ix0, iy, max(g.dp(3f), (ix1 - ix0) * prog), g.dp(3f), g.dp(1.5f), C.ACCENT)
            val tpp = g.text(g.fonts.reg, 11.5f, C.TEXT3)
            g.drawText(c, fmtTime(pos), ix0, iy + g.dp(8f), tpp)
            g.drawTextRight(c, fmtTime(dur), ix1, iy + g.dp(8f), tpp)
            iy += g.dp(46f)
            val ccy = iy + g.dp(26f)
            val span = min(ix1 - ix0, g.dp(260f))
            val xs = floatArrayOf(icx - span / 2.6f, icx, icx + span / 2.6f)
            g.iconSkip(c, xs[0], ccy, g.dp(26f), false, C.TEXT)
            g.circle(c, xs[1], ccy, g.dp(32f), C.ACCENT)
            g.iconPlay(c, xs[1] + if (playing) 0f else g.dp(2f), ccy, g.dp(26f), playing, C.TEXT)
            g.iconSkip(c, xs[2], ccy, g.dp(26f), true, C.TEXT)
            val r = g.dp(36f)
            buttons.add(floatArrayOf(xs[0] - r, ccy - r + shift, xs[0] + r, ccy + r + shift) to { engine.prev() })
            buttons.add(floatArrayOf(xs[1] - r, ccy - r + shift, xs[1] + r, ccy + r + shift) to { engine.toggle() })
            buttons.add(floatArrayOf(xs[2] - r, ccy - r + shift, xs[2] + r, ccy + r + shift) to { engine.next() })
        }

        // unlock hint
        val hy = h - insetBottom - g.dp(40f)
        val bounce = abs(sin(t * 2.4f)) * g.dp(5f)
        val hc = C.withAlpha(C.TEXT2, 200)
        g.line(c, w / 2 - g.dp(9f), hy - bounce + g.dp(4f), w / 2, hy - bounce - g.dp(4f), g.dp(2.2f), hc)
        g.line(c, w / 2, hy - bounce - g.dp(4f), w / 2 + g.dp(9f), hy - bounce + g.dp(4f), g.dp(2.2f), hc)
        g.drawTextCenter(c, "Проведите вверх, чтобы разблокировать", w / 2, hy + g.dp(10f), g.text(g.fonts.reg, 13f, C.TEXT3))
        c.restore()

        // keep animating while visible (clock, reels, hint)
        if (playing || discSpeed > 0.01f || unlocking >= 0 || dragging) postInvalidateOnAnimation() else postInvalidateDelayed(500)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; drag = 0f; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (abs(dy) > g.dp(12f)) dragging = true
                if (dragging) { drag = min(0f, dy); invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    if (-drag > height * 0.18f) unlocking = SystemClock.uptimeMillis() - ((-drag / height) * 260).toLong()
                    else drag = 0f
                } else {
                    buttons.firstOrNull { (r, _) -> e.x >= r[0] && e.x <= r[2] && e.y >= r[1] && e.y <= r[3] }?.second?.invoke()
                }
                dragging = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; drag = 0f; invalidate() }
        }
        return true
    }
}
