package com.kasseta.player.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import com.kasseta.player.Album
import com.kasseta.player.App
import com.kasseta.player.Engine
import com.kasseta.player.Library
import com.kasseta.player.Track
import com.kasseta.player.fmtTime
import com.kasseta.player.plural
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The whole UI is one custom-drawn view, like the TrimUI version: black, orange accent,
 * library tabs, album/artist screens, and the cassette / CD "now playing" screen.
 */
@SuppressLint("ViewConstructor")
class KassetaView(ctx: Context, private val host: Host) : View(ctx), Engine.Listener {
    interface Host {
        fun requestStorage()
        fun finishApp()
        /** shows the system text field for search at the given place (px), or hides it */
        fun searchField(show: Boolean, x: Int, y: Int, w: Int, h: Int)
        fun setSearchText(text: String)
        /** applies App.landscape */
        fun applyOrientation()
        /** asks for "display over other apps" so the lock screen player can start; returns true if already allowed */
        fun ensureOverlayPermission(): Boolean
        fun hasOverlayPermission(): Boolean
    }

    private val g = Gfx(ctx)
    private val cassette = Cassette(g)
    private val cases = Cases(g)
    private val disc = Disc(g, cases)
    private val engine get() = App.engine
    private val lib: Library get() = App.library

    private var insetTop = 0f
    private var insetBottom = 0f
    private var insetLeft = 0f
    private var insetRight = 0f

    fun setInsets(top: Int, bottom: Int, left: Int, right: Int) {
        insetTop = top.toFloat(); insetBottom = bottom.toFloat(); insetLeft = left.toFloat(); insetRight = right.toFloat()
        invalidate()
    }

    // ================= screens =================

    private enum class Kind { LIBRARY, ARTIST, ALBUM, PLAYER, QUEUE, SEARCH }

    private class Scr(val kind: Kind, val key: String = "") {
        var scroll = 0f
        var maxScroll = 0f
    }

    private val stack = arrayListOf(Scr(Kind.LIBRARY))
    private val topScr get() = stack.last()

    private var tab = App.prefs.getInt("tab", 1)
    private val tabScroll = FloatArray(3)
    private val tabMax = FloatArray(3)
    private var tabAnim = tab.toFloat()

    // transition between screens
    private var trFrom: Scr? = null
    private var trPush = true
    private var trStart = 0L
    private val trDur = 240L
    private var trProgress = 1f

    private fun push(s: Scr) {
        trFrom = topScr
        trPush = true
        stack.add(s)
        trStart = SystemClock.uptimeMillis()
        trProgress = 0f
        scroller.forceFinished(true)
        if (s.kind == Kind.QUEUE) pendingQueueScroll = true
        invalidate()
    }

    /** handles the system back gesture; false = nothing left to close */
    fun back(): Boolean {
        if (aodStart >= 0) { closeAod(); return true }
        if (sheet != null) { closeSheet(); return true }
        if (stack.size > 1) {
            trFrom = stack.removeAt(stack.size - 1)
            trPush = false
            trStart = SystemClock.uptimeMillis()
            trProgress = 0f
            scroller.forceFinished(true)
            invalidate()
            return true
        }
        return false
    }

    fun openPlayer() {
        if (topScr.kind != Kind.PLAYER && engine.currentTrack() != null) push(Scr(Kind.PLAYER))
    }

    // ================= sheets (menu / context) & toast =================

    private class SheetItem(val label: String, val value: (() -> String)? = null, val action: () -> Unit)
    private class Sheet(val title: String, val sub: String, val items: List<SheetItem>, val closeOnTap: Boolean) {
        var scroll = 0f
        var maxScroll = 0f
    }

    private var sheet: Sheet? = null
    private var sheetAnim = 0f
    private var sheetClosing = false

    private fun openSheet(s: Sheet) {
        sheet = s; sheetClosing = false; sheetAnim = 0f; invalidate()
    }

    /** a newer version is on GitHub: offer to download and install it */
    fun offerUpdate(rel: com.kasseta.player.Updater.Release) {
        val note = rel.notes.lineSequence().map { it.trim().trimStart('-', '*', '•', '#', ' ') }.firstOrNull { it.isNotEmpty() } ?: ""
        val items = ArrayList<SheetItem>()
        items.add(SheetItem("Скачать и установить") {
            closeSheet()
            toast("Загружаю обновление…")
            com.kasseta.player.Updater.download(context, rel, { p -> toast("Загружаю обновление… $p%") }) { f ->
                when {
                    f == null -> toast("Не удалось скачать обновление")
                    !com.kasseta.player.Updater.install(context, f) -> toast("Не удалось открыть установщик")
                    else -> toast("Подтвердите установку")
                }
            }
        })
        items.add(SheetItem("Что нового") {
            try {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(rel.page)))
            } catch (_: Exception) {
                toast("Не удалось открыть браузер")
            }
        })
        items.add(SheetItem("Позже") { closeSheet() })
        items.add(SheetItem("Пропустить эту версию") { com.kasseta.player.Updater.skip(rel.version); closeSheet() })
        val cur = com.kasseta.player.Updater.currentVersion(context)
        openSheet(Sheet("Доступна версия ${rel.version}", if (note.isNotEmpty()) note else "Сейчас установлена $cur", items, closeOnTap = false))
    }

    private fun closeSheet() {
        if (sheet != null) { sheetClosing = true; invalidate() }
    }

    private var toastText = ""
    private var toastUntil = 0L

    fun toast(s: String) {
        toastText = s
        toastUntil = SystemClock.uptimeMillis() + 2600
        invalidate()
    }

    // ================= animation state =================

    private var t = 0f
    private var lastFrame = 0L
    private var reelL = 0f
    private var reelR = 0f
    private var discAngle = 0f
    private var discSpeed = 0f
    private var discOut = 0f
    private var discTrackId: String? = null
    private var discTrackChangedAt = -10f
    private var viewAnim = if (App.cdView) 1f else 0f
    private var seekDrag = -1f
    /** press-and-hold on next / previous: 1 forward, -1 rewind, 0 off */
    private var scanDir = 0
    private var scanPos = 0f
    private var scanTime = 0f
    private var scanWasPlaying = false
    private var scanFast = false
    private var pendingQueueScroll = false

    // ================= input =================

    private class Hit(
        val l: Float, val t: Float, val r: Float, val b: Float, val key: String,
        val tap: (() -> Unit)?, val long: (() -> Unit)? = null, val drag: ((Float, Float, Int) -> Unit)? = null,
        /** called when the finger lifts after a long press (press-and-hold actions) */
        val holdEnd: (() -> Unit)? = null,
    ) {
        fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
    }

    private var hits = ArrayList<Hit>()
    private var building = ArrayList<Hit>()
    private var collect = true
    private var pressedKey: String? = null

    private fun hit(l: Float, t: Float, r: Float, b: Float, key: String, long: (() -> Unit)? = null, holdEnd: (() -> Unit)? = null, tap: (() -> Unit)?) {
        if (collect) building.add(Hit(l, t, r, b, key, tap, long, null, holdEnd))
    }

    private fun dragHit(l: Float, t: Float, r: Float, b: Float, key: String, drag: (Float, Float, Int) -> Unit) {
        if (collect) building.add(Hit(l, t, r, b, key, null, null, drag))
    }

    private val scroller = OverScroller(ctx)
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat()
    private val maxFling = ViewConfiguration.get(ctx).scaledMaximumFlingVelocity.toFloat()
    private var vt: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var mode = 0 // 0 idle/pending, 1 vertical scroll, 2 horizontal swipe, 3 drag, 4 consumed (long press)
    private var downHit: Hit? = null
    private var downInContent = false
    private val longRunnable = Runnable {
        val h = downHit
        if (mode == 0 && h?.long != null) {
            mode = 4
            pressedKey = if (h.holdEnd != null) h.key else null
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            h.long.invoke()
            invalidate()
        }
    }

    private fun scrollTarget(): Pair<() -> Float, (Float) -> Unit>? {
        sheet?.let { s -> return Pair({ s.scroll }, { v: Float -> s.scroll = v.coerceIn(0f, s.maxScroll) }) }
        val s = topScr
        return when (s.kind) {
            Kind.PLAYER -> null
            Kind.LIBRARY -> Pair({ tabScroll[tab] }, { v: Float -> tabScroll[tab] = v.coerceIn(0f, tabMax[tab]) })
            else -> Pair({ s.scroll }, { v: Float -> s.scroll = v.coerceIn(0f, s.maxScroll) })
        }
    }

    private fun scrollMax(): Float = sheet?.maxScroll ?: when (topScr.kind) {
        Kind.LIBRARY -> tabMax[tab]
        Kind.PLAYER -> 0f
        else -> topScr.maxScroll
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (aodStart >= 0) { aodTouch(e); return true }
        if (trProgress < 1f || box != null) return true
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
                downX = x; downY = y; lastY = y
                scroller.forceFinished(true)
                mode = 0
                downHit = hits.lastOrNull { it.contains(x, y) }
                downInContent = y > insetTop + g.dp(56f)
                val h = downHit
                if (h?.drag != null) { mode = 3; h.drag.invoke(x, y, 0) }
                else pressedKey = h?.key
                if (h?.long != null) postDelayed(longRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                when (mode) {
                    3 -> downHit?.drag?.invoke(x, y, 1)
                    0 -> {
                        val dx = x - downX; val dy = y - downY
                        if (abs(dx) > slop || abs(dy) > slop) {
                            removeCallbacks(longRunnable)
                            pressedKey = null
                            mode = if (abs(dy) >= abs(dx)) 1 else 2
                            lastY = y
                        }
                    }
                    1 -> {
                        scrollTarget()?.let { (get, set) -> set(get() - (y - lastY)) }
                        lastY = y
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longRunnable)
                vt?.addMovement(e)
                vt?.computeCurrentVelocity(1000, maxFling)
                val vx = vt?.xVelocity ?: 0f
                val vy = vt?.yVelocity ?: 0f
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                when (mode) {
                    3 -> downHit?.drag?.invoke(x, y, if (cancel) 3 else 2)
                    0 -> if (!cancel) downHit?.tap?.invoke()
                    1 -> if (abs(vy) > minFling) scrollTarget()?.let { (get, _) ->
                        scroller.fling(0, get().toInt(), 0, (-vy).toInt(), 0, 0, 0, scrollMax().toInt())
                    }
                    2 -> if (!cancel) horizontalSwipe(x - downX, vx)
                    4 -> downHit?.holdEnd?.invoke()
                }
                pressedKey = null
                mode = 0
                vt?.recycle(); vt = null
                invalidate()
            }
        }
        return true
    }

    private fun horizontalSwipe(dx: Float, vx: Float) {
        val far = abs(dx) > width * 0.22f || (abs(vx) > 1200 && abs(dx) > slop * 2)
        if (!far || sheet != null) return
        when (topScr.kind) {
            Kind.LIBRARY -> if (downInContent) {
                val nt = (tab + if (dx < 0) 1 else -1).coerceIn(0, 2)
                if (nt != tab) setTab(nt)
            }
            Kind.PLAYER -> if (dx < 0) engine.next() else engine.prev()
            else -> if (dx > 0 && downX < width * 0.3f) back()
        }
    }

    private fun setTab(i: Int) {
        tab = i
        App.prefs.edit().putInt("tab", i).apply()
        scroller.forceFinished(true)
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTarget()?.let { (_, set) -> set(scroller.currY.toFloat()) }
            postInvalidateOnAnimation()
        }
    }

    // ================= lifecycle =================

    private val libListener: () -> Unit = { onLibraryChanged() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        engine.addListener(this)
        App.addLibraryListener(libListener)
        com.kasseta.player.Covers.onLoaded = { invalidate() }
    }

    override fun onDetachedFromWindow() {
        engine.removeListener(this)
        App.removeLibraryListener(libListener)
        super.onDetachedFromWindow()
    }

    override fun onEngineChanged() { invalidate() }

    private fun onLibraryChanged() {
        if (query.isNotBlank()) runSearch()
        // screens refer to albums/artists by key, drop the ones that disappeared
        while (stack.size > 1) {
            val s = topScr
            val gone = when (s.kind) {
                Kind.ALBUM -> lib.albumIndex(s.key) < 0
                Kind.ARTIST -> lib.artistIndex(s.key) < 0
                else -> false
            }
            if (gone) stack.removeAt(stack.size - 1) else break
        }
        invalidate()
    }

    // ================= drawing =================

    private val headerH get() = g.dp(56f)
    private val miniH get() = g.dp(64f)
    private fun hasMini() = engine.currentTrack() != null

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else min(0.1f, (now - lastFrame) / 1000f)
        lastFrame = now
        t += dt
        var animating = tick(dt)

        c.drawColor(C.BG)
        building = ArrayList()
        val from = trFrom
        if (trProgress < 1f && from != null) {
            trProgress = min(1f, (now - trStart).toFloat() / trDur)
            val e = ease(trProgress)
            collect = false
            val w = width.toFloat()
            val under = if (trPush) from else topScr
            val over = if (trPush) topScr else from
            val k = if (trPush) e else 1f - e
            val vertical = over.kind == Kind.PLAYER
            // screen below: slight parallax + dim
            c.save()
            if (!vertical) c.translate(-w * 0.25f * k, 0f)
            drawScreen(c, under)
            c.restore()
            g.fill.color = C.argb((140 * k).toInt(), 0x000000)
            c.drawRect(0f, 0f, w, height.toFloat(), g.fill)
            c.save()
            if (vertical) c.translate(0f, height * (1f - k)) else c.translate(w * (1f - k), 0f)
            g.fill.color = C.BG
            c.drawRect(0f, 0f, w, height.toFloat(), g.fill)
            drawScreen(c, over)
            c.restore()
            if (trProgress >= 1f) trFrom = null
            animating = true
            collect = true
        } else {
            collect = true
            drawScreen(c, topScr)
        }
        animating = drawBox(c, now) || animating
        animating = drawAod(c, now) || animating
        animating = drawToast(c, now) || animating
        animating = drawSheet(c, dt) || animating
        hits = building
        updateSearchField()

        if (animating) postInvalidateOnAnimation()
        else if (engine.isPlaying) postInvalidateDelayed(if (topScr.kind == Kind.PLAYER) 16 else 50)
        else if (App.scanning) postInvalidateDelayed(80)
    }

    private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

    // ---------- fast-forward / rewind ----------

    private fun scanStart(dir: Int) {
        if (engine.currentTrack() == null || engine.durationMs() <= 0) return
        scanDir = dir
        scanPos = engine.positionMs().toFloat()
        scanTime = 0f
        scanFast = false
        scanWasPlaying = engine.isPlaying
        if (scanWasPlaying) engine.pause()
        if (!App.cdView) Sfx.windStart(dir)
        invalidate()
    }

    private fun scanEnd() {
        if (scanDir == 0) return
        scanDir = 0
        Sfx.windStop(!App.cdView)
        engine.seekTo(scanPos.toLong())
        if (scanWasPlaying) engine.resume()
        invalidate()
    }

    /** playback speed of the scan: 12x, then 30x after a second and a half */
    private fun scanSpeed() = if (scanFast) 30f else 12f

    /** advances animations; returns true while something needs 60 fps */
    private fun tick(dt: Float): Boolean {
        var anim = false
        val playing = engine.isPlaying
        if (scanDir != 0) {
            val d = engine.durationMs().toFloat()
            scanTime += dt
            if (!scanFast && scanTime > 1.5f) { scanFast = true; if (!App.cdView) Sfx.windFaster() }
            scanPos = (scanPos + scanDir * scanSpeed() * dt * 1000f).coerceIn(0f, max(0f, d - 1000f))
            anim = true
        }
        val p = progress()
        val (rl, rr) = cassette.packs(p)
        if (scanDir != 0) {
            // tape winds fast; the reels turn the other way on rewind
            val k = -scanDir * min(scanSpeed(), 18f)
            reelL += k * 47.6f / rl * dt * 1.6f
            reelR += k * 47.6f / rr * dt * 1.6f
        } else if (playing) {
            reelL -= 47.6f / rl * dt * 1.6f
            reelR -= 47.6f / rr * dt * 1.6f
        }
        val target = if (scanDir != 0) 4.4f * scanDir * 3f else if (playing) 4.4f else 0f
        discSpeed += (target - discSpeed) * min(1f, dt * 3f)
        if (abs(discSpeed - target) < 0.005f) discSpeed = target
        discAngle = (discAngle + discSpeed * dt) % (2 * PI.toFloat())
        if (discAngle < 0f) discAngle += 2 * PI.toFloat()
        // the disc slides back into the case on a track change and comes out again
        val curId = engine.currentTrack()?.id
        if (curId != discTrackId) {
            if (discTrackId != null && discOut > 0.3f) discTrackChangedAt = t
            discTrackId = curId
        }
        val outTarget = if ((playing || scanDir != 0) && t - discTrackChangedAt > 0.45f) 1f else 0f
        val o = discOut + (outTarget - discOut) * min(1f, dt * 4.5f)
        if (abs(o - discOut) > 0.0005f) anim = topScr.kind == Kind.PLAYER
        discOut = o
        val vTarget = if (App.cdView) 1f else 0f
        if (viewAnim != vTarget) {
            viewAnim += (vTarget - viewAnim) * min(1f, dt * 8f)
            if (abs(viewAnim - vTarget) < 0.01f) viewAnim = vTarget
            anim = true
        }
        if (abs(tabAnim - tab) > 0.001f) {
            tabAnim += (tab - tabAnim) * min(1f, dt * 14f)
            if (abs(tabAnim - tab) < 0.01f) tabAnim = tab.toFloat()
            anim = true
        }
        if (topScr.kind == Kind.PLAYER && (playing || abs(discSpeed) > 0.01f)) anim = true
        // cover flow easing
        for ((_, st) in cfState) {
            if (cfDragKey != null) continue
            if (abs(st[1] - st[0]) > 0.001f) {
                st[0] += (st[1] - st[0]) * min(1f, dt * 9f)
                if (abs(st[1] - st[0]) < 0.002f) st[0] = st[1]
                anim = true
            }
        }
        return anim
    }

    private fun progress(): Float {
        val d = engine.durationMs()
        if (d <= 0) return 0f
        val pos = if (scanDir != 0) scanPos else if (seekDrag >= 0) seekDrag * d else engine.positionMs().toFloat()
        return (pos / d).coerceIn(0f, 1f)
    }

    private fun drawScreen(c: Canvas, s: Scr) {
        when (s.kind) {
            Kind.LIBRARY -> drawLibrary(c)
            Kind.ARTIST -> drawArtist(c, s)
            Kind.ALBUM -> drawAlbum(c, s)
            Kind.PLAYER -> drawPlayer(c)
            Kind.QUEUE -> drawQueue(c, s)
            Kind.SEARCH -> drawSearch(c, s)
        }
    }

    private fun contentTop() = insetTop + headerH
    private fun contentBottom() = height - insetBottom - if (hasMini()) miniH else 0f
    private val padL get() = insetLeft + g.dp(16f)
    private val padR get() = width - insetRight - g.dp(16f)

    // ---------- common pieces ----------

    private fun selectionBg(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        g.rrect(c, x, y, w, h, g.dp(12f), C.SEL)
        g.rrect(c, x, y + h * 0.22f, g.dp(3f), h * 0.56f, g.dp(1.5f), C.ACCENT)
    }

    private fun cover(c: Canvas, albumIdx: Int, x: Float, y: Float, size: Float, r: Float) {
        val l = lib
        if (albumIdx < 0 || albumIdx >= l.albums.size) { g.placeholder(c, "?", x, y, size, r); return }
        val b = com.kasseta.player.Covers.get(l, albumIdx, size.toInt())
        if (b != null) g.bitmapRounded(c, b, x, y, size, r)
        else if (com.kasseta.player.Covers.isMissing(l.albums[albumIdx])) g.placeholder(c, l.albums[albumIdx].name, x, y, size, r)
        else g.rrect(c, x, y, size, size, r, C.SURF2)
    }

    private fun scrollbar(c: Canvas, scroll: Float, max: Float, top: Float, bottom: Float) {
        if (max <= 0) return
        val vh = bottom - top
        val total = vh + max
        val th = max(g.dp(36f), vh * vh / total)
        val y = top + (vh - th) * (scroll / max)
        val x = width - insetRight - g.dp(5f)
        g.rrect(c, x, y, g.dp(3f), th, g.dp(1.5f), C.argb(90, 0xFFFFFF))
        // fast scroll: drag along the right edge
        if (max > vh * 3) {
            dragHit(width - insetRight - g.dp(28f), top, width.toFloat(), bottom, "fastscroll") { _, yy, phase ->
                if (phase <= 1) {
                    val f = ((yy - top - th / 2) / (vh - th)).coerceIn(0f, 1f)
                    scrollTarget()?.let { (_, set) -> set(f * max) }
                    invalidate()
                }
            }
        }
    }

    private fun backHeader(c: Canvas, title: String, sub: String?, titleAlpha: Int = 255) {
        val y0 = insetTop
        g.fill.color = C.BG
        c.drawRect(0f, 0f, width.toFloat(), y0 + headerH, g.fill)
        val cy = y0 + headerH / 2
        val pressed = pressedKey == "back"
        if (pressed) g.circle(c, insetLeft + g.dp(28f), cy, g.dp(20f), C.SEL)
        g.iconBack(c, insetLeft + g.dp(28f), cy, g.dp(22f), C.TEXT2)
        hit(0f, y0, insetLeft + g.dp(56f), y0 + headerH, "back") { back() }
        val tp = g.text(g.fonts.bold, 19f, C.withAlpha(C.TEXT, titleAlpha))
        val x = insetLeft + g.dp(56f)
        val right = padR - g.dp(36f)
        if (sub != null) {
            g.drawText(c, title, x, cy - g.lineH(tp) / 2 - g.dp(8f), tp, right - x)
            g.drawText(c, sub, x, cy + g.dp(5f), g.text(g.fonts.reg, 12.5f, C.withAlpha(C.TEXT2, titleAlpha)), right - x)
        } else {
            g.drawText(c, title, x, cy - g.lineH(tp) / 2, tp, right - x)
        }
        menuButton(c, cy)
    }

    private fun menuButton(c: Canvas, cy: Float) {
        val cx = width - insetRight - g.dp(28f)
        if (pressedKey == "menu") g.circle(c, cx, cy, g.dp(20f), C.SEL)
        g.iconMenu(c, cx, cy, g.dp(20f), C.TEXT2)
        hit(cx - g.dp(26f), cy - g.dp(26f), cx + g.dp(26f), cy + g.dp(26f), "menu") { openMenu() }
    }

    // ---------- library ----------

    private val tabNames = arrayOf("Исполнители", "Альбомы", "Треки")

    private fun drawLibrary(c: Canvas) {
        val vTop = contentTop(); val vBot = contentBottom()
        val l = lib
        when {
            !App.hasStoragePermission() -> drawPermission(c, vTop, vBot)
            l.tracks.isEmpty() && (!App.loaded || App.scanning || !App.everScanned) -> drawScanning(c, vTop, vBot)
            l.tracks.isEmpty() -> drawEmpty(c, vTop, vBot)
            else -> {
                c.save()
                c.clipRect(0f, vTop, width.toFloat(), vBot)
                val h = when (tab) {
                    0 -> drawArtistList(c, vTop, vBot)
                    1 -> drawAlbumsTab(c, l.albumsSorted, "lib", tabScroll[1], vTop, vBot, true)
                    else -> drawTrackList(c, vTop, vBot)
                }
                c.restore()
                tabMax[tab] = max(0f, h - (vBot - vTop))
                tabScroll[tab] = tabScroll[tab].coerceIn(0f, tabMax[tab])
                scrollbar(c, tabScroll[tab], tabMax[tab], vTop, vBot)
            }
        }
        drawTabs(c)
        drawMini(c)
    }

    private fun drawTabs(c: Canvas) {
        val y0 = insetTop
        g.fill.color = C.BG
        c.drawRect(0f, 0f, width.toFloat(), y0 + headerH, g.fill)
        // tabs shrink a little on narrow screens to leave room for the search and menu buttons
        val room = width - insetRight - g.dp(100f) - padL
        var fs = 18f
        var tp = g.text(g.fonts.semi, fs, C.TEXT)
        while (fs > 14f && tabNames.sumOf { tp.measureText(it).toDouble() } + g.dp(18f) * 2 > room) {
            fs -= 0.5f
            tp = g.text(g.fonts.semi, fs, C.TEXT)
        }
        val gap = g.dp(if (fs >= 18f) 22f else 18f)
        var x = padL
        val ty = y0 + (headerH - g.lineH(tp)) / 2 - g.dp(2f)
        val xs = FloatArray(3); val ws = FloatArray(3)
        for (i in 0 until 3) {
            val w = tp.measureText(tabNames[i])
            xs[i] = x; ws[i] = w
            val on = i == tab
            tp.color = if (on) C.TEXT else C.TEXT3
            c.drawText(tabNames[i], x, ty - tp.fontMetrics.ascent, tp)
            hit(x - gap / 2, y0, x + w + gap / 2, y0 + headerH, "tab$i") { if (tab == i) { scroller.forceFinished(true); tabScroll[i] = 0f; invalidate() } else setTab(i) }
            x += w + gap
        }
        // animated underline
        val ti = tabAnim.coerceIn(0f, 2f)
        val i0 = ti.toInt().coerceAtMost(1); val f = ti - i0
        val ux = g.lerp(xs[i0], xs[i0 + 1], f); val uw = g.lerp(ws[i0], ws[i0 + 1], f)
        g.rrect(c, ux, ty + g.lineH(tp) + g.dp(5f), uw, g.dp(3f), g.dp(1.5f), C.ACCENT)
        if (App.scanning && lib.tracks.isNotEmpty()) {
            val a = 0.5f + 0.5f * kotlin.math.sin(t * 4)
            g.circle(c, width - insetRight - g.dp(98f), y0 + headerH / 2, g.dp(3.5f), C.withAlpha(C.ACCENT, (80 + 150 * a).toInt()))
        }
        val sx = width - insetRight - g.dp(70f); val scy = y0 + headerH / 2
        if (pressedKey == "search") g.circle(c, sx, scy, g.dp(20f), C.SEL)
        g.iconSearch(c, sx, scy, g.dp(22f), C.TEXT2)
        hit(sx - g.dp(22f), y0, sx + g.dp(22f), y0 + headerH, "search") { openSearch() }
        menuButton(c, y0 + headerH / 2)
    }

    private fun currentAlbumIdx(): Int = App.albumOf(engine.currentTrack())

    private fun drawArtistList(c: Canvas, vTop: Float, vBot: Float): Float {
        val l = lib
        val rh = g.dp(70f)
        val s = tabScroll[0]
        val curArtist = currentAlbumIdx().let { if (it >= 0) l.albums[it].artistIdx else -1 }
        val top = qualityChips(c, vTop + g.dp(10f) - s, vTop, vBot)
        val off = if (top > 0) top + g.dp(6f) else 0f
        val first = max(0, ((s - off) / rh).toInt() - 1)
        val pName = g.text(g.fonts.semi, 16f, C.TEXT_DIM)
        val list = fArtists
        for (n in first until list.size) {
            val i = list[n]
            val y = vTop + g.dp(4f) + off + n * rh - s
            if (y > vBot) break
            val ar = l.artists[i]
            val key = "ar$i"
            if (pressedKey == key) selectionBg(c, insetLeft + g.dp(8f), y, width - insetLeft - insetRight - g.dp(16f), rh - g.dp(4f))
            val av = g.dp(50f)
            val ay = y + (rh - g.dp(4f) - av) / 2
            cover(c, ar.albums[0], padL, ay, av, av / 2)
            val tx = padL + av + g.dp(14f)
            val maxW = padR - tx - g.dp(30f)
            pName.color = if (i == curArtist) C.ACCENT else C.TEXT_DIM
            g.drawText(c, ar.name, tx, ay + g.dp(4f), pName, maxW)
            val sub = plural(ar.albums.size, "альбом", "альбома", "альбомов") + " · " + plural(ar.trackCount, "трек", "трека", "треков")
            g.drawText(c, sub, tx, ay + g.dp(28f), g.text(g.fonts.reg, 13f, C.TEXT2), maxW)
            if (i == curArtist) g.eq(c, padR - g.dp(16f), ay + av / 2 - g.dp(8f), g.dp(16f), C.ACCENT, engine.isPlaying, t)
            if (y + rh > vTop) hit(0f, max(y, vTop), width.toFloat(), min(y + rh, vBot), key,
                long = { artistMenu(ar.name) }) { push(Scr(Kind.ARTIST, ar.name)) }
        }
        return off + list.size * rh + g.dp(12f)
    }

    private fun gridCols(): Int {
        val avail = width - insetLeft - insetRight - g.dp(32f)
        return max(2, ((avail + g.dp(14f)) / (g.dp(150f) + g.dp(14f))).toInt())
    }

    // ---------- quality filter ----------

    private val qualityNames = arrayOf("Всё", "Lossy", "CD", "Hi-Res", "DSD")
    private var fLib: Library? = null
    private var fFilter = -1
    private var fArtists = IntArray(0)
    private var fAlbums = IntArray(0)
    private var fTracks = IntArray(0)
    private var fAlbumOk = BooleanArray(0)
    private val tierCount = IntArray(5)

    private fun ensureFilter() {
        val l = lib
        val q = App.qualityFilter
        if (fLib === l && fFilter == q) return
        fLib = l; fFilter = q
        java.util.Arrays.fill(tierCount, 0)
        for (t in l.tracks) tierCount[t.tier]++
        tierCount[0] = l.tracks.size
        fAlbumOk = BooleanArray(l.albums.size) { ai -> q == 0 || l.albums[ai].tracks.any { l.tracks[it].tier == q } }
        fAlbums = l.albumsSorted.filter { fAlbumOk[it] }.toIntArray()
        fArtists = l.artists.indices.filter { ar -> l.artists[ar].albums.any { fAlbumOk[it] } }.toIntArray()
        fTracks = if (q == 0) l.tracksByTitle else l.tracksByTitle.filter { l.tracks[it].tier == q }.toIntArray()
    }

    /** quality chips; returns their height (0 when the library has only one kind of files) */
    private fun qualityChips(c: Canvas, y: Float, vTop: Float, vBot: Float): Float {
        ensureFilter()
        val kinds = (1..4).count { tierCount[it] > 0 }
        if (kinds < 2 && App.qualityFilter == 0) return 0f
        val h = g.dp(32f)
        if (y + h < vTop || y > vBot) return h + g.dp(10f)
        val p = g.text(g.fonts.semi, 12.5f, C.TEXT)
        var x = padL
        val cur = App.qualityFilter
        for (i in 0..4) {
            if (i > 0 && tierCount[i] == 0 && cur != i) continue
            val name = qualityNames[i]
            val w = p.measureText(name) + g.dp(22f)
            val on = i == cur
            if (on) g.rrect(c, x, y, w, h, h / 2, C.withAlpha(C.ACCENT, 46))
            g.rrectStroke(c, x + 0.5f, y + 0.5f, w - 1f, h - 1f, h / 2, g.dp(1.2f), if (on) C.ACCENT else if (pressedKey == "q$i") C.TEXT3 else C.LINE)
            p.color = if (on) C.ACCENT2 else C.TEXT2
            g.drawText(c, name, x + g.dp(11f), y + (h - g.lineH(p)) / 2, p)
            hit(x, max(y, vTop), x + w, y + h, "q$i") {
                App.qualityFilter = if (cur == i) 0 else i
                tabScroll.fill(0f)
                cfState.clear()
                invalidate()
            }
            x += w + g.dp(7f)
        }
        return h + g.dp(10f)
    }

    private val themeNames = arrayOf("Обложки", "Диски", "Кассеты", "Cover Flow")

    /** theme chips above the albums; returns their height */
    private fun themeChips(c: Canvas, y: Float, vTop: Float, vBot: Float): Float {
        val h = g.dp(34f)
        if (y + h < vTop || y > vBot) return h + g.dp(14f)
        // fit all four chips into the width
        val avail = padR - padL - g.dp(8f) * (themeNames.size - 1)
        var fs = 13f
        var pad = g.dp(26f)
        var p = g.text(g.fonts.semi, fs, C.TEXT)
        while (themeNames.sumOf { (p.measureText(it) + pad).toDouble() } > avail && fs > 10.5f) {
            fs -= 0.5f; pad = max(g.dp(14f), pad - g.dp(2f))
            p = g.text(g.fonts.semi, fs, C.TEXT)
        }
        var x = padL
        val theme = App.albumTheme
        for ((i, name) in themeNames.withIndex()) {
            val w = p.measureText(name) + pad
            val on = i == theme
            g.rrect(c, x, y, w, h, h / 2, if (on) C.ACCENT else if (pressedKey == "th$i") C.SEL else C.CHIP)
            p.color = if (on) C.TEXT else C.TEXT2
            g.drawText(c, name, x + pad / 2, y + (h - g.lineH(p)) / 2, p)
            hit(x, max(y, vTop), x + w, y + h, "th$i") {
                App.albumTheme = i
                tabScroll[1] = 0f
                invalidate()
            }
            x += w + g.dp(8f)
        }
        return h + g.dp(14f)
    }

    /** albums in the chosen theme; returns content height */
    private fun drawAlbumsTab(c: Canvas, albums0: IntArray, key: String, scroll: Float, vTop: Float, vBot: Float, showArtist: Boolean): Float {
        ensureFilter()
        val albums = if (App.qualityFilter == 0) albums0 else albums0.filter { fAlbumOk.getOrElse(it) { true } }.toIntArray()
        if (App.albumTheme == 3) {
            val bh = if (key == "lib") aodBanner(c, vTop + g.dp(10f), vTop, vBot) else 0f
            val qh = qualityChips(c, vTop + g.dp(10f) + bh, vTop, vBot)
            val ch = themeChips(c, vTop + g.dp(10f) + bh + qh, vTop, vBot)
            drawCoverFlow(c, albums, key + "#" + App.qualityFilter, vTop + g.dp(10f) + bh + qh + ch, vBot)
            return vBot - vTop
        }
        val bh = if (key == "lib") aodBanner(c, vTop + g.dp(10f) - scroll, vTop, vBot) else 0f
        val qh = qualityChips(c, vTop + g.dp(10f) + bh - scroll, vTop, vBot)
        val ch = themeChips(c, vTop + g.dp(10f) + bh + qh - scroll, vTop, vBot)
        return drawAlbumGrid(c, albums, scroll, vTop, vBot, bh + qh + ch, showArtist)
    }

    private val stripColors = HashMap<String, Int>()

    /** a dark version of the cover's average colour, for the J-card */
    private fun stripColor(al: Album, b: android.graphics.Bitmap?): Int {
        stripColors[al.key]?.let { return it }
        if (b == null) return C.hex(0x26262B)
        val px = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val r = ((px shr 16) and 255) * 45 / 100; val gg = ((px shr 8) and 255) * 45 / 100; val bb = (px and 255) * 45 / 100
        val col = C.hex((r shl 16) or (gg shl 8) or bb)
        stripColors[al.key] = col
        return col
    }

    private fun tapeTag(al: Album): String {
        val t = lib.tracks[al.tracks[0]]
        return when (t.tier) {
            4 -> t.formatName + " CERAMIC"
            3 -> "TYPE IV METAL"
            2 -> "TYPE II CrO2"
            else -> "TYPE I NORMAL"
        }
    }

    /** album grid (covers, jewel cases or cassette boxes); returns content height */
    private fun drawAlbumGrid(c: Canvas, albums: IntArray, scroll: Float, vTop: Float, vBot: Float, topOffset: Float, showArtist: Boolean = true): Float {
        val l = lib
        val theme = App.albumTheme
        val cols = gridCols()
        val gap = g.dp(if (theme == 0) 14f else 18f)
        val x0 = insetLeft + g.dp(16f)
        val cw = (width - insetLeft - insetRight - g.dp(32f) - gap * (cols - 1)) / cols
        val imgH = when (theme) { 1 -> cw * Cases.JEWEL_RATIO; 2 -> cw * Cases.TAPE_RATIO; else -> cw }
        val capH = if (theme == 2) 0f else g.dp(50f)
        val ch = imgH + capH
        val rows = (albums.size + cols - 1) / cols
        val cur = currentAlbumIdx()
        val pt = g.text(g.fonts.semi, 14f, C.TEXT_DIM)
        val firstRow = max(0, ((scroll - topOffset) / (ch + gap)).toInt() - 1)
        for (r in firstRow until rows) {
            val y = vTop + topOffset + g.dp(4f) + r * (ch + gap) - scroll
            if (y > vBot) break
            for (col in 0 until cols) {
                val i = r * cols + col
                if (i >= albums.size) break
                val ai = albums[i]
                val al = l.albums[ai]
                val x = x0 + col * (cw + gap)
                val key = "al$ai"
                val pressed = pressedKey == key
                when (theme) {
                    1 -> {
                        val b = com.kasseta.player.Covers.get(l, ai, cw.toInt())
                        cases.jewel(c, x, y, cw, b, al.name, pressed)
                    }
                    2 -> {
                        val b = com.kasseta.player.Covers.get(l, ai, cw.toInt())
                        cases.tapeBox(c, x, y, cw, b, al.name, al.artist, if (al.year > 0) al.year.toString() else "",
                            tapeTag(al), stripColor(al, b), pressed)
                    }
                    else -> {
                        if (pressed) g.rrect(c, x - g.dp(6f), y - g.dp(6f), cw + g.dp(12f), ch + g.dp(6f), g.dp(14f), C.SEL)
                        cover(c, ai, x, y, cw, g.dp(10f))
                        if (pressed) g.rrectStroke(c, x - g.dp(2f), y - g.dp(2f), cw + g.dp(4f), cw + g.dp(4f), g.dp(11f), g.dp(2.5f), C.ACCENT)
                    }
                }
                if (ai == cur) {
                    val bx = x + cw - g.dp(20f); val byy = y + g.dp(20f)
                    g.circle(c, bx, byy, g.dp(14f), C.argb(200, 0x000000))
                    g.eq(c, bx - g.eqWidth(g.dp(14f)) / 2, byy - g.dp(7f), g.dp(14f), C.ACCENT, engine.isPlaying, t)
                }
                if (theme != 2) {
                    pt.color = if (ai == cur) C.ACCENT else C.TEXT_DIM
                    g.drawText(c, al.name, x, y + imgH + g.dp(8f), pt, cw)
                    val sub = if (showArtist) { if (al.year > 0) "${al.artist} · ${al.year}" else al.artist }
                    else listOf(if (al.year > 0) al.year.toString() else "", plural(al.tracks.size, "трек", "трека", "треков")).filter { it.isNotEmpty() }.joinToString(" · ")
                    g.drawText(c, sub, x, y + imgH + g.dp(28f), g.text(g.fonts.reg, 12f, C.TEXT2), cw)
                }
                if (y + ch > vTop) hit(x - gap / 2, max(y - gap / 2, vTop), x + cw + gap / 2, min(y + ch + gap / 2, vBot), key,
                    long = { albumMenu(ai) }) { if (theme == 2) openBox(ai, x, y, cw) else if (theme == 1) openBox(ai, x, y, cw, jewel = true) else push(Scr(Kind.ALBUM, al.key)) }
            }
        }
        return topOffset + g.dp(4f) + rows * (ch + gap) + g.dp(8f)
    }

    // ---------- cassette box opening ----------

    private class BoxAnim(val ai: Int, val key: String, val x: Float, val y: Float, val w: Float, val start: Long, val jewel: Boolean) {
        var fadeStart = -1L
    }
    private var box: BoxAnim? = null
    private var boxLid: android.graphics.Bitmap? = null
    private val boxCassette = Cassette(g)
    private val boxMatrix = android.graphics.Matrix()
    private val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val boxDur = 1100L

    private fun openBox(ai: Int, x: Float, y: Float, w: Float, jewel: Boolean = false) {
        val al = lib.albums[ai]
        box = BoxAnim(ai, al.key, x, y, w, SystemClock.uptimeMillis(), jewel)
        Sfx.boxOpen(jewel)
        boxLid = null
        invalidate()
    }

    private fun boxTarget(ratio: Float, maxW: Float): Triple<Float, Float, Float> {
        val tw = min(width * maxW, (height - insetTop - insetBottom) * 0.62f / ratio)
        val th = tw * ratio
        return Triple(width * 0.46f - tw / 2, insetTop + (height - insetTop - insetBottom - th) / 2, tw)
    }

    // ---------- album of the day ----------

    private val dayBox by lazy { DayBox(g, cases) }
    private var aodStart = -1L
    private var aodAlbum = -1
    private var aodChosen = 0
    private var aodSpines: List<DayBox.Spine> = emptyList()

    private fun iconDayBox(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, bg: Int = C.BG) {
        val w = s * 0.78f; val h = s * 0.48f
        val top = cy - h * 0.25f
        g.rrectStroke(c, cx - w / 2, top, w, h, s * 0.05f, s * 0.085f, color)
        // open flaps
        g.line(c, cx - w / 2, top, cx - w * 0.78f, top - s * 0.24f, s * 0.085f, color)
        g.line(c, cx + w / 2, top, cx + w * 0.78f, top - s * 0.24f, s * 0.085f, color)
        // the disc peeking out
        g.circle(c, cx, top - s * 0.04f, s * 0.2f, color)
        g.circle(c, cx, top - s * 0.04f, s * 0.06f, bg)
        g.fill.color = bg
        c.drawRect(cx - w / 2 + s * 0.06f, top + s * 0.05f, cx + w / 2 - s * 0.06f, top + h - s * 0.05f, g.fill)
    }

    private fun spineColor(ai: Int): Int {
        val b = com.kasseta.player.Covers.get(lib, ai, 64)
        if (b != null) {
            val px = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            return C.hex(px and 0xFFFFFF)
        }
        return C.hsv(((lib.albums[ai].name.hashCode() and 0x7FFFFFFF) % 360).toFloat(), 0.55f, 0.75f)
    }

    private fun openAod(reroll: Boolean = false) {
        val ai = if (reroll) App.rerollAlbumOfDay() else App.albumOfDay()
        if (ai < 0) return
        val l = lib
        val rnd = java.util.Random(java.time.LocalDate.now().toEpochDay())
        val others = l.albums.indices.filter { it != ai }.shuffled(rnd)
        aodChosen = 6 + (if (reroll) java.util.Random().nextInt(6) else rnd.nextInt(6))
        aodSpines = (0 until 16).map { i ->
            val idx = if (i == aodChosen || others.isEmpty()) ai else others[i % others.size]
            DayBox.Spine(l.albums[idx].name, spineColor(idx))
        }
        aodAlbum = ai
        aodStart = SystemClock.uptimeMillis()
        App.aodSeen = App.today()
        invalidate()
    }

    /** card at the top of the Albums tab; returns the height it takes */
    private fun aodBanner(c: Canvas, y: Float, vTop: Float, vBot: Float): Float {
        if (lib.albums.isEmpty()) return 0f
        val h = g.dp(60f)
        val x = padL; val w = padR - padL
        if (y + h > vTop && y < vBot) {
            val pressed = pressedKey == "aod"
            g.rrect(c, x, y, w, h, g.dp(14f), if (pressed) C.SEL else C.hex(0x17171B))
            g.rrectStroke(c, x + 0.5f, y + 0.5f, w - 1f, h - 1f, g.dp(14f), g.dp(1f), C.withAlpha(C.ACCENT, 70))
            val ix = x + g.dp(30f); val iy = y + h / 2 + g.dp(2f)
            iconDayBox(c, ix, iy, g.dp(26f), C.ACCENT2, if (pressed) C.SEL else C.hex(0x17171B))
            val fresh = App.aodSeen != App.today()
            val tp = g.text(g.fonts.bold, 15.5f, C.TEXT)
            val sp = g.text(g.fonts.reg, 12.5f, C.TEXT2)
            val tx = x + g.dp(58f)
            val tw = w - g.dp(58f) - g.dp(36f)
            val ty = y + (h - g.lineH(tp) - g.lineH(sp)) / 2
            g.drawText(c, "Альбом дня", tx, ty, tp)
            val sub = if (fresh) "Откройте коробку — сегодня новый"
            else lib.albums.getOrNull(App.albumOfDay())?.let { "${it.name} · ${it.artist}" } ?: ""
            g.drawText(c, sub, tx, ty + g.lineH(tp), sp, tw)
            if (fresh) g.circle(c, x + w - g.dp(22f), y + h / 2, g.dp(5f), C.ACCENT)
            else {
                val ax = x + w - g.dp(22f); val ay = y + h / 2; val k = g.dp(5f)
                g.line(c, ax - k / 2, ay - k, ax + k / 2, ay, g.dp(2f), C.TEXT3)
                g.line(c, ax + k / 2, ay, ax - k / 2, ay + k, g.dp(2f), C.TEXT3)
            }
            hit(x, max(y, vTop), x + w, min(y + h, vBot), "aod") { openAod() }
        }
        return h + g.dp(12f)
    }

    private fun closeAod() { aodStart = -1L; invalidate() }

    private fun drawAod(c: Canvas, now: Long): Boolean {
        if (aodStart < 0) return false
        val l = lib
        val ai = aodAlbum
        if (ai < 0 || ai >= l.albums.size) { aodStart = -1L; return false }
        val al = l.albums[ai]
        val t = (now - aodStart) / 1000f
        val cover = com.kasseta.player.Covers.get(l, ai, 600)
        val first = l.tracks[al.tracks[0]]
        val n = al.tracks.size
        val word = if (n % 10 == 1 && n % 100 != 11) "трек" else if (n % 10 in 2..4 && n % 100 !in 12..14) "трека" else "треков"
        val info = (if (al.year > 0) "${al.year} · " else "") + "$n $word · " + first.qualityLine
        val date = java.text.SimpleDateFormat("d MMMM", java.util.Locale("ru")).format(java.util.Date())
        dayBox.draw(c, width.toFloat(), height.toFloat(), insetTop, height - insetBottom, t, aodSpines, aodChosen, cover, al.name, al.artist, info, date)
        return t < dayBox.duration + 0.2f || cover == null
    }

    private fun aodTouch(e: MotionEvent) {
        if (e.actionMasked != MotionEvent.ACTION_UP) return
        val now = SystemClock.uptimeMillis()
        val t = (now - aodStart) / 1000f
        val x = e.x; val y = e.y
        fun inside(r: FloatArray) = x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
        if (t < dayBox.buttonsAt) {
            if (inside(dayBox.close) && t > 0.5f) { closeAod(); return }
            // tap skips the animation
            aodStart = now - (dayBox.duration * 1000).toLong()
            invalidate()
            return
        }
        val ai = aodAlbum
        when {
            inside(dayBox.close) -> closeAod()
            inside(dayBox.play) -> {
                closeAod()
                val l = lib
                if (ai in l.albums.indices) { playList(l.albums[ai].tracks.map { l.tracks[it] }, 0, false); openPlayer() }
            }
            inside(dayBox.again) && lib.albums.size > 1 -> openAod(reroll = true)
            inside(dayBox.open) -> {
                closeAod()
                val l = lib
                if (ai in l.albums.indices) push(Scr(Kind.ALBUM, l.albums[ai].key))
            }
        }
    }

    /** draws the opening animation on top of the screen; returns true while running */
    private fun drawBox(c: Canvas, now: Long): Boolean {
        val b = box ?: return false
        val tt = ((now - b.start).toFloat() / boxDur).coerceIn(0f, 1f)
        val l = lib
        if (b.ai >= l.albums.size || l.albums[b.ai].key != b.key) { box = null; return false }
        val al = l.albums[b.ai]
        // once open, the album screen slides in and the box fades away
        if (tt >= 1f && b.fadeStart < 0) { b.fadeStart = now; push(Scr(Kind.ALBUM, al.key)) }
        val fade = if (b.fadeStart < 0) 1f else 1f - (now - b.fadeStart) / 260f
        if (fade <= 0f) { box = null; boxLid = null; return false }
        if (fade < 1f) c.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), (fade * 255).toInt())
        val ratio = if (b.jewel) Cases.JEWEL_RATIO else Cases.TAPE_RATIO
        val (tx, ty, tw) = boxTarget(ratio, if (b.jewel) 0.74f else 0.60f)
        val mv = ease((tt / 0.42f).coerceIn(0f, 1f))
        val w = g.lerp(b.w, tw, mv)
        val h = w * ratio
        val x = g.lerp(b.x, tx, mv); val y = g.lerp(b.y, ty, mv)
        g.fill.color = C.argb((190 * min(1f, tt / 0.3f)).toInt(), 0x000000)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), g.fill)
        // the lid picture, rendered once at the final size
        var lid = boxLid
        if (lid == null) {
            val lw = tw.toInt().coerceAtLeast(16); val lh = (tw * ratio).toInt().coerceAtLeast(16)
            lid = android.graphics.Bitmap.createBitmap(lw, lh, android.graphics.Bitmap.Config.ARGB_8888)
            val cov = com.kasseta.player.Covers.get(l, b.ai, lw)
            if (b.jewel) cases.jewel(Canvas(lid), 0f, 0f, lw.toFloat(), cov, al.name)
            else cases.tapeBox(Canvas(lid), 0f, 0f, lw.toFloat(), cov, al.name, al.artist, if (al.year > 0) al.year.toString() else "", tapeTag(al), stripColor(al, cov))
            boxLid = lid
        }
        if (b.jewel) {
            // open jewel case: black tray with the disc in it
            val area = cases.jewelBase(c, x, y, w)
            val dr = area[2] * 0.48f
            val src = coverBitmap(b.ai, max(64, (dr * 2).toInt()))
            disc.disc(c, src, area[0] + area[2] / 2, area[1] + area[2] / 2, dr, 0f)
        } else {
            // open case: dark tray with the cassette lying in it
            val r = w * 0.035f
            g.rrect(c, x + w * 0.02f, y + w * 0.04f, w, h, r, C.argb(120, 0x000000))
            g.rrect(c, x, y, w, h, r, C.hex(0x121216))
            g.rrectStroke(c, x, y, w, h, r, maxOf(1f, w * 0.008f), C.argb(110, 0xFFFFFF))
            val t0 = l.tracks[al.tracks[0]]
            val (style, tag) = tapeFor(t0)
            val cLen = h * 0.88f
            val cHt = cLen * Cassette.CH / Cassette.CW
            val ccx = x + w / 2; val ccy = y + h / 2
            c.save()
            c.rotate(-90f, ccx, ccy)
            boxCassette.draw(c, ccx - cLen / 2, ccy - cHt / 2, cLen, 0f, al.name, al.artist, 0f, 0f, style, tag)
            c.restore()
        }
        // the lid swings open around the left hinge, towards the viewer
        val open = ease(((tt - 0.32f) / 0.5f).coerceIn(0f, 1f))
        val th = open * 104f * PI.toFloat() / 180f
        val f = w * 5f
        val z = -w * kotlin.math.sin(th)
        val sc = f / (f + z)
        val fx = x + w * kotlin.math.cos(th) * sc
        val q = floatArrayOf(x, y, fx, y + h / 2 - h / 2 * sc, fx, y + h / 2 + h / 2 * sc, x, y + h)
        if (kotlin.math.cos(th) >= 0f) {
            val src = floatArrayOf(0f, 0f, lid.width.toFloat(), 0f, lid.width.toFloat(), lid.height.toFloat(), 0f, lid.height.toFloat())
            boxMatrix.setPolyToPoly(src, 0, q, 0, 4)
            boxPaint.alpha = 255
            c.drawBitmap(lid, boxMatrix, boxPaint)
            // the lid darkens as it turns away from the light
            g.poly(c, C.argb((open * 120).toInt(), 0x000000), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])
        } else {
            // inside of the J-card / booklet
            g.poly(c, if (b.jewel) C.hex(0xEDEDEA) else C.hex(0xE9E3D6), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])
            g.poly(c, C.argb(40, 0x000000), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])
        }
        if (fade < 1f) c.restore()
        return true
    }

    // ---------- Cover Flow ----------

    /** per list: [position, target] in album indices */
    private val cfState = HashMap<String, FloatArray>()
    private var cfDragKey: String? = null
    private var cfDragX0 = 0f
    private var cfDragPos0 = 0f
    private var cfLastX = 0f
    private var cfLastT = 0L
    private var cfVel = 0f
    private val cfMatrix = android.graphics.Matrix()
    private val cfPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val phBitmaps = android.util.LruCache<String, android.graphics.Bitmap>(24)

    private fun coverBitmap(ai: Int, px: Int): android.graphics.Bitmap {
        com.kasseta.player.Covers.get(lib, ai, px)?.let { return it }
        val al = lib.albums[ai]
        val k = al.key + "@" + px
        phBitmaps.get(k)?.let { return it }
        val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        g.placeholder(Canvas(b), al.name, 0f, 0f, px.toFloat(), 0f)
        if (com.kasseta.player.Covers.isMissing(al)) phBitmaps.put(k, b)
        return b
    }

    private fun drawCoverFlow(c: Canvas, albums: IntArray, key: String, top: Float, bottom: Float) {
        val n = albums.size
        if (n == 0) return
        val st = cfState.getOrPut(key) {
            val cur = currentAlbumIdx()
            val i = albums.indexOf(cur).coerceAtLeast(0).toFloat()
            floatArrayOf(i, i)
        }
        st[0] = st[0].coerceIn(0f, (n - 1).toFloat()); st[1] = st[1].coerceIn(0f, (n - 1).toFloat())
        val pos = st[0]
        val w = width.toFloat()
        val s = min(w * 0.56f, (bottom - top) * 0.40f)
        val cx = w / 2
        val cy = top + max(s * 0.62f, (bottom - top - s * 2.05f) / 2 + s * 0.5f)
        val f = s * 3.2f
        val px = Covers_bucket(s)
        val first = max(0, kotlin.math.floor(pos).toInt() - 6)
        val last = min(n - 1, kotlin.math.ceil(pos).toInt() + 6)
        val order = (first..last).sortedByDescending { abs(it - pos) }

        fun quad(i: Int, reflect: Boolean): FloatArray {
            val d = i - pos
            val a = min(abs(d), 1f)
            val sg = if (d < 0) -1f else 1f
            val th = sg * a * 62f * PI.toFloat() / 180f
            val xoff = sg * (a * s * 0.80f + max(0f, abs(d) - 1f) * s * 0.20f)
            val zoff = a * s * 0.55f
            val ct = kotlin.math.cos(th); val stt = kotlin.math.sin(th)
            val ys = if (reflect) floatArrayOf(s * 1.5f, s * 1.5f, s * 0.5f, s * 0.5f) else floatArrayOf(-s * 0.5f, -s * 0.5f, s * 0.5f, s * 0.5f)
            val xs = floatArrayOf(-s * 0.5f, s * 0.5f, s * 0.5f, -s * 0.5f)
            val out = FloatArray(8)
            for (k in 0 until 4) {
                val xr = xs[k] * ct
                val zr = xs[k] * stt + zoff
                val sc = f / (f + zr)
                out[2 * k] = cx + (xoff + xr) * sc
                out[2 * k + 1] = cy + ys[k] * sc
            }
            return out
        }

        fun drawQuad(b: android.graphics.Bitmap, q: FloatArray, alpha: Int) {
            val src = floatArrayOf(0f, 0f, b.width.toFloat(), 0f, b.width.toFloat(), b.height.toFloat(), 0f, b.height.toFloat())
            cfMatrix.setPolyToPoly(src, 0, q, 0, 4)
            cfPaint.alpha = alpha
            c.drawBitmap(b, cfMatrix, cfPaint)
        }

        // reflections
        for (i in order) drawQuad(coverBitmap(albums[i], px), quad(i, true), 120)
        val reflTop = cy + s * 0.5f * (f / (f + s * 0.55f))
        g.fill.shader = android.graphics.LinearGradient(0f, reflTop, 0f, cy + s * 1.1f, C.argb(150, 0x000000), C.BG, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, reflTop, w, cy + s * 1.6f, g.fill)
        g.fill.shader = null
        // covers, the far ones darker
        for (i in order) {
            val q = quad(i, false)
            drawQuad(coverBitmap(albums[i], px), q, 255)
            val a = min(abs(i - pos), 1f)
            if (a > 0.02f) g.poly(c, C.argb((a * 110).toInt(), 0x000000), q[0], q[1], q[2], q[3], q[4], q[5], q[6], q[7])
            else g.poly(c, C.argb(18, 0xFFFFFF), q[0], q[1], q[0] + (q[2] - q[0]) * 0.55f, q[1], q[0], q[1] + (q[7] - q[1]) * 0.55f)
        }
        // caption
        val ci = kotlin.math.round(pos).toInt().coerceIn(0, n - 1)
        val al = lib.albums[albums[ci]]
        var ty = cy + s * 1.08f
        val tp = g.text(g.fonts.bold, 19f, if (albums[ci] == currentAlbumIdx()) C.ACCENT else C.TEXT)
        g.drawTextCenter(c, al.name, cx, ty, tp, w - g.dp(48f)); ty += g.lineH(tp)
        val ap = g.text(g.fonts.semi, 14.5f, C.ACCENT2)
        g.drawTextCenter(c, al.artist, cx, ty, ap, w - g.dp(48f)); ty += g.lineH(ap) + g.dp(2f)
        val meta = listOf(if (al.year > 0) al.year.toString() else "", plural(al.tracks.size, "трек", "трека", "треков")).filter { it.isNotEmpty() }.joinToString(" · ")
        g.drawTextCenter(c, meta, cx, ty, g.text(g.fonts.reg, 12.5f, C.TEXT2), w - g.dp(48f))
        // position dots
        val dots = min(n, 9)
        if (n > 1) {
            val dy = min(bottom - g.dp(14f), ty + g.dp(34f))
            val p0 = (ci - dots / 2).coerceIn(0, n - dots)
            for (k in 0 until dots) {
                val idx = p0 + k
                g.circle(c, cx + (k - (dots - 1) / 2f) * g.dp(11f), dy, if (idx == ci) g.dp(3.2f) else g.dp(2.2f), if (idx == ci) C.ACCENT else C.TEXT3)
            }
        }
        // input: drag to scroll, tap the middle to open, tap a side cover to bring it forward
        val spacing = s * 0.30f
        dragHit(0f, top, w, ty, "cf:$key") { x, _, phase ->
            val now = SystemClock.uptimeMillis()
            when (phase) {
                0 -> { cfDragKey = key; cfDragX0 = x; cfDragPos0 = st[0]; cfLastX = x; cfLastT = now; cfVel = 0f }
                1 -> {
                    st[0] = (cfDragPos0 - (x - cfDragX0) / spacing).coerceIn(-0.4f, n - 0.6f)
                    st[1] = st[0]
                    val dtm = (now - cfLastT).coerceAtLeast(1)
                    val v = -(x - cfLastX) / spacing / (dtm / 1000f)
                    cfVel = cfVel * 0.6f + v * 0.4f
                    cfLastX = x; cfLastT = now
                }
                else -> {
                    cfDragKey = null
                    if (abs(x - cfDragX0) < slop && phase == 2) {
                        val cur = kotlin.math.round(st[0]).toInt()
                        when {
                            abs(x - cx) < s * 0.5f -> push(Scr(Kind.ALBUM, lib.albums[albums[cur.coerceIn(0, n - 1)]].key))
                            x > cx -> st[1] = (cur + 1 + ((x - cx - s * 0.5f) / (s * 0.25f)).toInt()).coerceAtMost(n - 1).toFloat()
                            else -> st[1] = (cur - 1 - ((cx - x - s * 0.5f) / (s * 0.25f)).toInt()).coerceAtLeast(0).toFloat()
                        }
                    } else {
                        val fling = if (now - cfLastT > 120) 0f else cfVel * 0.22f
                        st[1] = kotlin.math.round(st[0] + fling).coerceIn(0f, (n - 1).toFloat())
                    }
                }
            }
            invalidate()
        }
    }

    private fun Covers_bucket(s: Float): Int = com.kasseta.player.Covers.bucket(s.toInt())

    private fun drawTrackList(c: Canvas, vTop: Float, vBot: Float): Float {
        val l = lib
        val rh = g.dp(62f)
        val s = tabScroll[2]
        val top = qualityChips(c, vTop + g.dp(10f) - s, vTop, vBot)
        val off = if (top > 0) top + g.dp(6f) else 0f
        val list = fTracks
        val first = max(0, ((s - off) / rh).toInt() - 1)
        for (i in first until list.size) {
            val y = vTop + g.dp(4f) + off + i * rh - s
            if (y > vBot) break
            val ti = list[i]
            trackRow(c, l.tracks[ti], "tr$i", y, rh, showCover = true, number = null, various = true, vTop = vTop, vBot = vBot,
                onTap = { playList(list.map { l.tracks[it] }, i) })
        }
        return off + list.size * rh + g.dp(12f)
    }

    private fun isCurrent(tr: Track) = engine.currentTrack()?.id == tr.id

    private fun trackRow(
        c: Canvas, tr: Track, key: String, y: Float, rh: Float, showCover: Boolean, number: String?, various: Boolean,
        vTop: Float, vBot: Float, x0: Float = padL, x1: Float = padR, onTap: () -> Unit,
    ) {
        val cur = isCurrent(tr)
        if (pressedKey == key) selectionBg(c, x0 - g.dp(8f), y, x1 - x0 + g.dp(16f), rh - g.dp(4f))
        var x = x0
        val cy = y + (rh - g.dp(4f)) / 2
        if (showCover) {
            val cs = g.dp(46f)
            cover(c, lib.indexOf(tr.id).let { if (it >= 0) lib.tracks[it].albumIdx else -1 }, x, cy - cs / 2, cs, g.dp(6f))
            if (cur) {
                g.rrect(c, x, cy - cs / 2, cs, cs, g.dp(6f), C.argb(150, 0x000000))
                g.eq(c, x + cs / 2 - g.eqWidth(g.dp(16f)) / 2, cy - g.dp(8f), g.dp(16f), C.ACCENT, engine.isPlaying, t)
            }
            x += cs + g.dp(14f)
        }
        if (number != null) {
            if (cur) g.eq(c, x + g.dp(4f), cy - g.dp(8f), g.dp(16f), C.ACCENT, engine.isPlaying, t)
            else {
                val pn = g.text(g.fonts.reg, 14f, C.TEXT3)
                g.drawTextRight(c, number, x + g.dp(22f), cy - g.lineH(pn) / 2, pn)
            }
            x += g.dp(36f)
        }
        val pd = g.text(g.fonts.reg, 12.5f, C.TEXT2)
        val dur = if (tr.durMs > 0) fmtTime(tr.durMs) else ""
        val dw = pd.measureText(dur)
        if (dur.isNotEmpty()) g.drawText(c, dur, x1 - dw, cy - g.lineH(pd) / 2, pd)
        val maxW = x1 - dw - g.dp(14f) - x
        val pt = g.text(g.fonts.semi, 15f, if (cur) C.ACCENT else C.TEXT_DIM)
        if (showCover || various) {
            val sub = if (showCover) "${tr.artist} · ${tr.album.ifBlank { Library.folderAlbumName(tr.dir) }}" else tr.artist
            val ps = g.text(g.fonts.reg, 12.5f, C.TEXT2)
            val th = g.lineH(pt) + g.lineH(ps)
            g.drawText(c, tr.title, x, cy - th / 2, pt, maxW)
            g.drawText(c, sub, x, cy - th / 2 + g.lineH(pt), ps, maxW)
        } else {
            g.drawText(c, tr.title, x, cy - g.lineH(pt) / 2, pt, maxW)
        }
        if (y + rh > vTop && y < vBot) hit(x0 - g.dp(8f), max(y, vTop), x1 + g.dp(8f), min(y + rh, vBot), key, long = { trackMenu(tr) }, tap = onTap)
    }

    private fun playList(tracks: List<Track>, index: Int, shuffle: Boolean = false) {
        engine.play(tracks, index, shuffle)
    }

    // ---------- artist ----------

    private fun drawArtist(c: Canvas, s: Scr) {
        val l = lib
        val ai = l.artistIndex(s.key)
        if (ai < 0) { backHeader(c, s.key, null); return }
        val ar = l.artists[ai]
        val vTop = contentTop(); val vBot = contentBottom()
        c.save(); c.clipRect(0f, vTop, width.toFloat(), vBot)
        val h = drawAlbumsTab(c, ar.albums, "ar:" + ar.name, s.scroll, vTop, vBot, false)
        c.restore()
        s.maxScroll = max(0f, h - (vBot - vTop))
        s.scroll = s.scroll.coerceIn(0f, s.maxScroll)
        val sub = plural(ar.albums.size, "альбом", "альбома", "альбомов") + " · " + plural(ar.trackCount, "трек", "трека", "треков")
        backHeader(c, ar.name, sub)
        drawMini(c)
    }

    // ---------- album ----------

    private fun albumTracks(al: Album): List<Track> = al.tracks.map { lib.tracks[it] }

    private fun drawAlbum(c: Canvas, s: Scr) {
        val l = lib
        val ai = l.albumIndex(s.key)
        if (ai < 0) { backHeader(c, "", null); return }
        val al = l.albums[ai]
        val tracks = albumTracks(al)
        val vTop = contentTop(); val vBot = contentBottom()
        val landscape = width > height * 1.15f
        val various = tracks.map { it.artist.lowercase() }.distinct().size > 1
        val multiDisc = tracks.map { it.disc }.distinct().size > 1

        c.save(); c.clipRect(0f, vTop, width.toFloat(), vBot)
        var y: Float
        val listX0: Float
        if (landscape) {
            // cover & info fixed on the left, track list scrolls on the right
            val cs = min((width - insetLeft - insetRight) * 0.36f, vBot - vTop - g.dp(120f)).coerceAtLeast(g.dp(120f))
            val lx = padL
            cover(c, ai, lx, vTop + g.dp(8f), cs, g.dp(12f))
            var iy = vTop + g.dp(8f) + cs + g.dp(14f)
            val tp = g.text(g.fonts.bold, 19f, C.TEXT)
            for (line in g.wrap(al.name, tp, cs, 2)) { g.drawText(c, line, lx, iy, tp); iy += g.lineH(tp) }
            val ap = g.text(g.fonts.semi, 14f, C.ACCENT)
            g.drawText(c, al.artist, lx, iy + g.dp(2f), ap, cs)
            hit(lx, iy, lx + cs, iy + g.lineH(ap) + g.dp(4f), "albart") { goArtist(al.artist) }
            iy += g.lineH(ap) + g.dp(6f)
            g.drawText(c, albumMeta(al, tracks), lx, iy, g.text(g.fonts.reg, 12.5f, C.TEXT2), cs)
            listX0 = lx + cs + g.dp(20f)
            y = vTop + g.dp(4f) - s.scroll
            y = albumButtons(c, tracks, listX0, padR, y, vTop, vBot)
        } else {
            val cs = min(min(width - insetLeft - insetRight - g.dp(96f), g.dp(320f)), (vBot - vTop) * 0.5f)
            y = vTop + g.dp(8f) - s.scroll
            val cx = (insetLeft + width - insetRight) / 2
            cover(c, ai, cx - cs / 2, y, cs, g.dp(12f))
            y += cs + g.dp(18f)
            val tp = g.text(g.fonts.bold, 22f, C.TEXT)
            for (line in g.wrap(al.name, tp, padR - padL, 2)) { g.drawTextCenter(c, line, cx, y, tp); y += g.lineH(tp) }
            val ap = g.text(g.fonts.semi, 15f, C.ACCENT)
            g.drawTextCenter(c, al.artist, cx, y + g.dp(4f), ap, padR - padL)
            val aw = min(ap.measureText(al.artist), padR - padL)
            if (y + g.lineH(ap) > vTop) hit(cx - aw / 2 - g.dp(8f), max(y, vTop), cx + aw / 2 + g.dp(8f), y + g.lineH(ap) + g.dp(8f), "albart") { goArtist(al.artist) }
            y += g.lineH(ap) + g.dp(10f)
            g.drawTextCenter(c, albumMeta(al, tracks), cx, y, g.text(g.fonts.reg, 13f, C.TEXT2), padR - padL)
            y += g.dp(20f)
            listX0 = padL
            y = albumButtons(c, tracks, padL, padR, y + g.dp(6f), vTop, vBot)
        }
        val headerEnd = y + s.scroll
        // tracks
        var disc = -1
        val rh = if (various) g.dp(62f) else g.dp(54f)
        for ((i, tr) in tracks.withIndex()) {
            if (multiDisc && tr.disc != disc) {
                disc = tr.disc
                if (y + g.dp(36f) > vTop && y < vBot) {
                    g.drawText(c, "Диск ${max(1, tr.disc)}", listX0 + g.dp(4f), y + g.dp(12f), g.text(g.fonts.semi, 13f, C.TEXT2))
                    g.fill.color = C.LINE
                    c.drawRect(listX0 + g.dp(4f), y + g.dp(34f), padR, y + g.dp(35f), g.fill)
                }
                y += g.dp(40f)
            }
            if (y + rh > vTop && y < vBot) {
                trackRow(c, tr, "at$i", y, rh, showCover = false, number = if (tr.trackNo > 0) tr.trackNo.toString() else "${i + 1}",
                    various = various, vTop = vTop, vBot = vBot, x0 = listX0, x1 = padR, onTap = { playList(tracks, i) })
            }
            y += rh
        }
        c.restore()
        val contentH = y + s.scroll - vTop + g.dp(16f)
        s.maxScroll = max(0f, contentH - (vBot - vTop))
        s.scroll = s.scroll.coerceIn(0f, s.maxScroll)
        val showTitle = !landscape && s.scroll > (headerEnd - vTop) * 0.45f
        backHeader(c, if (showTitle) al.name else "", if (showTitle) al.artist else null)
        drawMini(c)
    }

    private fun albumMeta(al: Album, tracks: List<Track>): String {
        val mins = ((al.durMs + 30000) / 60000).toInt()
        val dur = if (mins >= 60) "${mins / 60} ч ${mins % 60} мин" else "$mins мин"
        val fmts = tracks.map { it.formatName }.distinct()
        val q = if (fmts.size == 1) {
            val t0 = tracks[0]
            if (t0.dsdRate > 0) fmts[0] else "${fmts[0]} ${if (t0.bits > 0 && Track.isLossless(t0.codec)) "${t0.bits}/${Track.fmtKhz(t0.rate)}" else ""}".trim()
        } else "разные форматы"
        return listOf(if (al.year > 0) al.year.toString() else "", plural(tracks.size, "трек", "трека", "треков"), dur, q)
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** Play / Shuffle buttons; returns y below them */
    private fun albumButtons(c: Canvas, tracks: List<Track>, x0: Float, x1: Float, y: Float, vTop: Float, vBot: Float): Float {
        val bh = g.dp(44f)
        val gap = g.dp(12f)
        val bw = min((x1 - x0 - gap) / 2, g.dp(170f))
        val total = bw * 2 + gap
        val bx = x0 + (x1 - x0 - total) / 2
        val pp = g.text(g.fonts.semi, 15f, C.TEXT)
        // play
        val k1 = "albplay"
        g.rrect(c, bx, y, bw, bh, bh / 2, if (pressedKey == k1) C.ACCENT2 else C.ACCENT)
        val lw1 = pp.measureText("Слушать") + g.dp(26f)
        g.iconPlay(c, bx + bw / 2 - lw1 / 2 + g.dp(8f), y + bh / 2, g.dp(15f), false, C.TEXT)
        g.drawText(c, "Слушать", bx + bw / 2 - lw1 / 2 + g.dp(26f), y + (bh - g.lineH(pp)) / 2, pp)
        if (y + bh > vTop && y < vBot) hit(bx, max(y, vTop), bx + bw, y + bh, k1) { playList(tracks, 0, false) }
        // shuffle
        val k2 = "albshuf"
        val sx = bx + bw + gap
        g.rrect(c, sx, y, bw, bh, bh / 2, if (pressedKey == k2) C.SEL else C.CHIP)
        pp.color = C.TEXT
        val lw2 = pp.measureText("Перемешать") + g.dp(28f)
        g.iconShuffle(c, sx + bw / 2 - lw2 / 2 + g.dp(9f), y + bh / 2, g.dp(18f), C.TEXT)
        g.drawText(c, "Перемешать", sx + bw / 2 - lw2 / 2 + g.dp(28f), y + (bh - g.lineH(pp)) / 2, pp)
        if (y + bh > vTop && y < vBot) hit(sx, max(y, vTop), sx + bw, y + bh, k2) {
            playList(tracks, (tracks.indices).random(), true)
        }
        return y + bh + g.dp(18f)
    }

    private fun goArtist(name: String) {
        if (lib.artistIndex(name) >= 0) push(Scr(Kind.ARTIST, name))
    }

    // ---------- search ----------

    private var query = ""
    private var resArtists = IntArray(0)
    private var resAlbums = IntArray(0)
    private var resTracks = IntArray(0)
    private var resTotalTracks = 0
    private var normLib: Library? = null
    private var normTrack = arrayOf<String>()
    private var normTitle = arrayOf<String>()
    private var searchFieldShown = false
    private val fieldRect = IntArray(4)

    fun openSearch() {
        if (topScr.kind == Kind.SEARCH) return
        query = ""
        runSearch()
        host.setSearchText("")
        push(Scr(Kind.SEARCH))
    }

    private fun norm(s: String): String = s.lowercase().replace('ё', 'е')

    /** called by the activity when the text field changes */
    fun setQuery(q: String) {
        query = q
        runSearch()
        topScr.let { if (it.kind == Kind.SEARCH) it.scroll = 0f }
        invalidate()
    }

    private fun runSearch() {
        val l = lib
        if (normLib !== l) {
            normLib = l
            normTitle = Array(l.tracks.size) { norm(l.tracks[it].title) }
            normTrack = Array(l.tracks.size) { val t = l.tracks[it]; norm(t.title + " " + t.artist + " " + t.album + " " + t.albumArtist) }
        }
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) { resArtists = IntArray(0); resAlbums = IntArray(0); resTracks = IntArray(0); resTotalTracks = 0; return }
        fun matches(h: String) = words.all { h.contains(it) }
        val q0 = words.joinToString(" ")
        fun rank(name: String) = if (name.startsWith(q0)) 0 else if (name.contains(" $q0")) 1 else 2
        resArtists = l.artists.indices.filter { matches(norm(l.artists[it].name)) }
            .sortedBy { rank(norm(l.artists[it].name)) }.take(8).toIntArray()
        resAlbums = l.albums.indices.filter { matches(norm(l.albums[it].name + " " + l.albums[it].artist + " " + l.albums[it].year)) }
            .sortedBy { rank(norm(l.albums[it].name)) }.take(20).toIntArray()
        val tr = l.tracksByTitle.filter { matches(normTrack[it]) }
        resTotalTracks = tr.size
        resTracks = tr.sortedBy { rank(normTitle[it]) }.take(200).toIntArray()
    }

    private fun updateSearchField() {
        val want = topScr.kind == Kind.SEARCH && trProgress >= 1f && sheet == null
        val x = (insetLeft + g.dp(56f) + g.dp(34f)).toInt()
        val h = g.dp(44f).toInt()
        val y = (insetTop + (headerH - h) / 2).toInt()
        val w = (width - insetRight - g.dp(16f) - g.dp(40f) - x).toInt()
        if (want != searchFieldShown || (want && (fieldRect[0] != x || fieldRect[1] != y || fieldRect[2] != w))) {
            searchFieldShown = want
            fieldRect[0] = x; fieldRect[1] = y; fieldRect[2] = w; fieldRect[3] = h
            post { host.searchField(want, x, y, w, h) }
        }
    }

    private fun sectionHeader(c: Canvas, title: String, y: Float, vTop: Float, vBot: Float) {
        if (y + g.dp(36f) < vTop || y > vBot) return
        val p = g.text(g.fonts.semi, 12.5f, C.TEXT3)
        p.letterSpacing = 0.08f
        g.drawText(c, title.uppercase(), padL, y + g.dp(14f), p)
        p.letterSpacing = 0f
    }

    private fun drawSearch(c: Canvas, s: Scr) {
        val l = lib
        if (normLib !== l) runSearch()
        val vTop = contentTop(); val vBot = contentBottom()
        c.save(); c.clipRect(0f, vTop, width.toFloat(), vBot)
        var y = vTop + g.dp(4f) - s.scroll
        if (query.isBlank()) {
            val p = g.text(g.fonts.reg, 14.5f, C.TEXT2)
            g.drawTextCenter(c, "Ищите по названию трека, альбома или исполнителя", width / 2f, vTop + g.dp(60f), p, padR - padL)
        } else if (resArtists.isEmpty() && resAlbums.isEmpty() && resTracks.isEmpty()) {
            g.drawTextCenter(c, "Ничего не нашлось", width / 2f, vTop + g.dp(60f), g.text(g.fonts.semi, 16f, C.TEXT2), padR - padL)
        }
        if (resArtists.isNotEmpty()) {
            sectionHeader(c, "Исполнители", y, vTop, vBot); y += g.dp(40f)
            val rh = g.dp(64f)
            for (ai in resArtists) {
                if (y + rh > vTop && y < vBot) {
                    val ar = l.artists[ai]
                    val key = "sar$ai"
                    if (pressedKey == key) selectionBg(c, insetLeft + g.dp(8f), y, width - insetLeft - insetRight - g.dp(16f), rh - g.dp(4f))
                    val av = g.dp(46f)
                    val ay = y + (rh - g.dp(4f) - av) / 2
                    cover(c, ar.albums[0], padL, ay, av, av / 2)
                    val tx = padL + av + g.dp(14f)
                    g.drawText(c, ar.name, tx, ay + g.dp(2f), g.text(g.fonts.semi, 16f, C.TEXT_DIM), padR - tx)
                    g.drawText(c, plural(ar.albums.size, "альбом", "альбома", "альбомов") + " · " + plural(ar.trackCount, "трек", "трека", "треков"),
                        tx, ay + g.dp(25f), g.text(g.fonts.reg, 13f, C.TEXT2), padR - tx)
                    hit(0f, max(y, vTop), width.toFloat(), min(y + rh, vBot), key, long = { artistMenu(ar.name) }) { push(Scr(Kind.ARTIST, ar.name)) }
                }
                y += rh
            }
            y += g.dp(8f)
        }
        if (resAlbums.isNotEmpty()) {
            sectionHeader(c, "Альбомы", y, vTop, vBot); y += g.dp(40f)
            val rh = g.dp(68f)
            for (ai in resAlbums) {
                if (y + rh > vTop && y < vBot) {
                    val al = l.albums[ai]
                    val key = "sal$ai"
                    if (pressedKey == key) selectionBg(c, insetLeft + g.dp(8f), y, width - insetLeft - insetRight - g.dp(16f), rh - g.dp(4f))
                    val cs = g.dp(52f)
                    val cy0 = y + (rh - g.dp(4f) - cs) / 2
                    cover(c, ai, padL, cy0, cs, g.dp(6f))
                    val tx = padL + cs + g.dp(14f)
                    g.drawText(c, al.name, tx, cy0 + g.dp(4f), g.text(g.fonts.semi, 15.5f, if (ai == currentAlbumIdx()) C.ACCENT else C.TEXT_DIM), padR - tx)
                    val sub = if (al.year > 0) "${al.artist} · ${al.year}" else al.artist
                    g.drawText(c, sub, tx, cy0 + g.dp(27f), g.text(g.fonts.reg, 13f, C.TEXT2), padR - tx)
                    hit(0f, max(y, vTop), width.toFloat(), min(y + rh, vBot), key, long = { albumMenu(ai) }) { push(Scr(Kind.ALBUM, al.key)) }
                }
                y += rh
            }
            y += g.dp(8f)
        }
        if (resTracks.isNotEmpty()) {
            val title = if (resTotalTracks > resTracks.size) "Треки · ${resTracks.size} из $resTotalTracks" else "Треки · $resTotalTracks"
            sectionHeader(c, title, y, vTop, vBot); y += g.dp(40f)
            val rh = g.dp(62f)
            val list = resTracks
            for ((i, ti) in list.withIndex()) {
                if (y + rh > vTop && y < vBot) trackRow(c, l.tracks[ti], "str$i", y, rh, showCover = true, number = null, various = true,
                    vTop = vTop, vBot = vBot, onTap = { playList(list.map { l.tracks[it] }, i) })
                y += rh
            }
        }
        c.restore()
        val contentH = y + s.scroll - vTop + g.dp(16f)
        s.maxScroll = max(0f, contentH - (vBot - vTop))
        s.scroll = s.scroll.coerceIn(0f, s.maxScroll)
        // header with the field (the text itself is a real EditText placed on top by the activity)
        val y0 = insetTop
        g.fill.color = C.BG
        c.drawRect(0f, 0f, width.toFloat(), y0 + headerH, g.fill)
        val cy = y0 + headerH / 2
        if (pressedKey == "back") g.circle(c, insetLeft + g.dp(28f), cy, g.dp(20f), C.SEL)
        g.iconBack(c, insetLeft + g.dp(28f), cy, g.dp(22f), C.TEXT2)
        hit(0f, y0, insetLeft + g.dp(56f), y0 + headerH, "back") { back() }
        val fx0 = insetLeft + g.dp(56f); val fx1 = width - insetRight - g.dp(16f)
        g.rrect(c, fx0, cy - g.dp(21f), fx1 - fx0, g.dp(42f), g.dp(21f), C.SURF2)
        g.iconSearch(c, fx0 + g.dp(20f), cy, g.dp(18f), C.TEXT3)
        if (query.isNotEmpty()) {
            val bx = fx1 - g.dp(20f)
            g.iconClose(c, bx, cy, g.dp(16f), C.TEXT2)
            hit(bx - g.dp(20f), y0, fx1, y0 + headerH, "qclear") { host.setSearchText("") }
        }
        drawMini(c)
    }

    // ---------- queue ----------

    private fun drawQueue(c: Canvas, s: Scr) {
        val q = engine.queueTracks
        val ord = engine.playingOrder()
        val curPos = engine.currentQueuePos()
        val vTop = contentTop(); val vBot = contentBottom()
        val rh = g.dp(58f)
        if (pendingQueueScroll && curPos >= 0) {
            pendingQueueScroll = false
            s.maxScroll = max(0f, ord.size * rh + g.dp(12f) - (vBot - vTop))
            s.scroll = (curPos * rh - (vBot - vTop) / 3).coerceIn(0f, s.maxScroll)
        }
        c.save(); c.clipRect(0f, vTop, width.toFloat(), vBot)
        val first = max(0, (s.scroll / rh).toInt() - 1)
        for (p in first until ord.size) {
            val y = vTop + g.dp(4f) + p * rh - s.scroll
            if (y > vBot) break
            val tr = q.getOrNull(ord[p]) ?: continue
            val num = if (p < curPos) "·" else "${p + 1}"
            trackRow(c, tr, "q$p", y, rh, showCover = false, number = num, various = true, vTop = vTop, vBot = vBot,
                onTap = { engine.jump(p); if (!engine.isPlaying) engine.resume() })
        }
        c.restore()
        s.maxScroll = max(0f, ord.size * rh + g.dp(12f) - (vBot - vTop))
        s.scroll = s.scroll.coerceIn(0f, s.maxScroll)
        scrollbar(c, s.scroll, s.maxScroll, vTop, vBot)
        backHeader(c, "Очередь", plural(ord.size, "трек", "трека", "треков"))
        drawMini(c)
    }

    // ---------- mini player ----------

    private fun drawMini(c: Canvas) {
        val tr = engine.currentTrack() ?: return
        val y = height - insetBottom - miniH
        g.fill.color = C.hex(0x0C0C0E)
        c.drawRect(0f, y, width.toFloat(), height.toFloat(), g.fill)
        g.fill.color = C.LINE
        c.drawRect(0f, y, width.toFloat(), y + 1, g.fill)
        val p = progress()
        g.fill.color = C.ACCENT
        c.drawRect(0f, y, width * p, y + g.dp(2f), g.fill)
        val cs = g.dp(44f)
        val cx = padL
        val cy = y + (miniH - cs) / 2
        if (pressedKey == "mini") { g.fill.color = C.SEL; c.drawRect(0f, y + 2, width.toFloat(), height.toFloat(), g.fill) }
        cover(c, App.albumOf(tr), cx, cy, cs, g.dp(6f))
        val bx = width - insetRight - g.dp(36f)
        val px = bx - g.dp(52f)
        val tx = cx + cs + g.dp(12f)
        val maxW = px - g.dp(26f) - tx
        val pt = g.text(g.fonts.semi, 14.5f, C.TEXT)
        val ps = g.text(g.fonts.reg, 12.5f, C.TEXT2)
        val th = g.lineH(pt) + g.lineH(ps)
        val my = y + miniH / 2
        g.drawText(c, tr.title, tx, my - th / 2, pt, maxW)
        g.drawText(c, tr.artist, tx, my - th / 2 + g.lineH(pt), ps, maxW)
        hit(0f, y, px - g.dp(26f), height.toFloat(), "mini") { openPlayer() }
        // play/pause
        val playing = engine.isPlaying
        g.circle(c, px, my, g.dp(21f), if (pressedKey == "miniplay") C.ACCENT2 else if (playing) C.ACCENT else C.CHIP)
        g.iconPlay(c, px + if (playing) 0f else g.dp(1.5f), my, g.dp(17f), playing, C.TEXT)
        hit(px - g.dp(26f), y, px + g.dp(26f), height.toFloat(), "miniplay") { engine.toggle() }
        if (pressedKey == "mininext") g.circle(c, bx, my, g.dp(20f), C.SEL)
        g.iconSkip(c, bx, my, g.dp(17f), true, C.TEXT2)
        hit(bx - g.dp(26f), y, width.toFloat(), height.toFloat(), "mininext") { engine.next() }
    }

    // ---------- player ----------

    private fun drawPlayer(c: Canvas) {
        val tr = engine.currentTrack()
        val w = width.toFloat()
        val y0 = insetTop
        // header
        val cy = y0 + headerH / 2
        if (pressedKey == "back") g.circle(c, insetLeft + g.dp(28f), cy, g.dp(20f), C.SEL)
        g.iconDown(c, insetLeft + g.dp(28f), cy, g.dp(22f), C.TEXT2)
        hit(0f, y0, insetLeft + g.dp(56f), y0 + headerH, "back") { back() }
        val hp = g.text(g.fonts.semi, 11.5f, C.TEXT3)
        hp.letterSpacing = 0.12f
        g.drawTextCenter(c, "СЕЙЧАС ИГРАЕТ", w / 2, cy - g.lineH(hp) / 2, hp)
        hp.letterSpacing = 0f
        val rx = insetLeft + g.dp(74f)
        if (pressedKey == "rotate") g.circle(c, rx, cy, g.dp(20f), C.SEL)
        g.iconRotate(c, rx, cy, g.dp(22f), !App.landscape, C.TEXT2)
        hit(rx - g.dp(22f), y0, rx + g.dp(22f), y0 + headerH, "rotate") { App.landscape = !App.landscape; host.applyOrientation() }
        val qx = width - insetRight - g.dp(76f)
        if (pressedKey == "queue") g.circle(c, qx, cy, g.dp(20f), C.SEL)
        g.iconQueue(c, qx, cy, g.dp(20f), C.TEXT2)
        hit(qx - g.dp(24f), y0, qx + g.dp(24f), y0 + headerH, "queue") { push(Scr(Kind.QUEUE)) }
        menuButton(c, cy)

        if (tr == null) {
            g.drawTextCenter(c, "Ничего не играет", w / 2, height / 2f, g.text(g.fonts.semi, 18f, C.TEXT2))
            return
        }
        val landscape = width > height * 1.15f
        val bottom = height - insetBottom - g.dp(16f)
        val vx: Float; val vy: Float; val vw: Float; val vh: Float
        val ix0: Float; val ix1: Float; var iy: Float
        if (landscape) {
            vx = insetLeft + g.dp(16f); vy = y0 + headerH; vw = (w - insetLeft - insetRight) * 0.5f - g.dp(16f); vh = bottom - vy
            ix0 = vx + vw + g.dp(24f); ix1 = w - insetRight - g.dp(24f)
            iy = vy + max(0f, (vh - g.dp(250f)) / 2)
        } else {
            vx = insetLeft + g.dp(16f); vw = w - insetLeft - insetRight - g.dp(32f)
            vy = y0 + headerH + g.dp(8f)
            vh = min(vw * 0.68f, max(g.dp(150f), bottom - vy - g.dp(260f)))
            ix0 = padL; ix1 = padR
            iy = vy + vh + max(g.dp(18f), (bottom - vy - vh - g.dp(250f)) * 0.42f)
        }

        // visual: cassette ↔ CD cross-fade
        val albumIdx = App.albumOf(tr)
        val al = lib.albums.getOrNull(albumIdx)
        val title = tr.title
        val sub = "${tr.artist}  ·  ${tr.album.ifBlank { al?.name ?: "" }}"
        val prog = progress()
        val va = viewAnim
        if (va < 1f) {
            val cw = min(vh * Cassette.CW / Cassette.CH, vw)
            val chh = cw * Cassette.CH / Cassette.CW
            val sc = 1f - 0.06f * va
            c.save()
            c.scale(sc, sc, vx + vw / 2, vy + vh / 2)
            if (va > 0f) c.saveLayerAlpha(vx - g.dp(8f), vy - g.dp(8f), vx + vw + g.dp(8f), vy + vh + g.dp(8f), ((1f - va) * 255).toInt())
            val (style, tag) = tapeFor(tr)
            cassette.draw(c, vx + (vw - cw) / 2, vy + (vh - chh) / 2, cw, prog, title, sub, reelL, reelR, style, tag)
            if (va > 0f) c.restore()
            c.restore()
        }
        if (va > 0f) {
            val bmp = if (albumIdx >= 0) com.kasseta.player.Covers.get(lib, albumIdx, (min(vh * 0.94f, vw * 0.58f)).toInt()) else null
            val sc = 0.94f + 0.06f * va
            c.save()
            c.scale(sc, sc, vx + vw / 2, vy + vh / 2)
            if (va < 1f) c.saveLayerAlpha(vx - g.dp(8f), vy - g.dp(8f), vx + vw + g.dp(8f), vy + vh + g.dp(8f), (va * 255).toInt())
            disc.draw(c, vx, vy, vw, vh, bmp, al?.name ?: tr.album, discAngle, discOut)
            if (va < 1f) c.restore()
            c.restore()
        }
        hit(vx, vy, vx + vw, vy + vh, "visual") { App.cdView = !App.cdView; invalidate() }

        // titles
        val icx = (ix0 + ix1) / 2
        val iw = ix1 - ix0
        val tp = g.text(g.fonts.bold, 22f, C.TEXT)
        g.drawTextCenter(c, tr.title, icx, iy, tp, iw)
        iy += g.lineH(tp) + g.dp(2f)
        val sp = g.text(g.fonts.semi, 14.5f, C.ACCENT2)
        val subLine = "${tr.artist}  —  ${tr.album.ifBlank { al?.name ?: "" }}"
        g.drawTextCenter(c, subLine, icx, iy, sp, iw)
        if (al != null) hit(ix0, iy - g.dp(4f), ix1, iy + g.lineH(sp) + g.dp(4f), "pal") { push(Scr(Kind.ALBUM, al.key)) }
        iy += g.lineH(sp) + g.dp(10f)
        // format badge + output line
        val qp = g.text(g.fonts.semi, 11.5f, C.TEXT)
        val fmt = tr.formatName
        val qual = tr.qualityLine.removePrefix("$fmt · ")
        val bw = qp.measureText(fmt) + g.dp(14f)
        val qr = g.text(g.fonts.reg, 12.5f, C.TEXT2)
        val qw = bw + g.dp(8f) + qr.measureText(qual)
        val bx = icx - qw / 2
        val bh = g.dp(20f)
        g.rrect(c, bx, iy, bw, bh, g.dp(6f), if (tr.dsdRate > 0 || Track.isLossless(tr.codec)) C.hex(0x3A1220) else C.CHIP)
        qp.color = if (tr.dsdRate > 0 || Track.isLossless(tr.codec)) C.ACCENT2 else C.TEXT
        g.drawText(c, fmt, bx + g.dp(7f), iy + (bh - g.lineH(qp)) / 2, qp)
        g.drawText(c, qual, bx + bw + g.dp(8f), iy + (bh - g.lineH(qr)) / 2, qr)
        iy += bh + g.dp(6f)
        val out = engine.outputLine
        if (out.isNotEmpty()) g.drawTextCenter(c, out, icx, iy, g.text(g.fonts.reg, 11.5f, C.TEXT3), iw)
        iy += g.dp(26f)

        // progress
        val dur = tr.durMs
        val pos = if (scanDir != 0) scanPos.toLong() else if (seekDrag >= 0) (seekDrag * dur).toLong() else engine.positionMs()
        val px0 = ix0; val px1 = ix1
        val by = iy + g.dp(6f)
        g.rrect(c, px0, by - g.dp(2f), px1 - px0, g.dp(4f), g.dp(2f), C.hex(0x2A2A2F))
        val pw = (px1 - px0) * prog
        if (pw > 0) g.rrect(c, px0, by - g.dp(2f), max(pw, g.dp(4f)), g.dp(4f), g.dp(2f), C.ACCENT)
        g.circle(c, px0 + pw, by, if (seekDrag >= 0) g.dp(9f) else g.dp(7f), C.TEXT)
        val tpp = g.text(g.fonts.reg, 12f, C.TEXT2)
        g.drawText(c, fmtTime(pos), px0, by + g.dp(12f), tpp)
        g.drawTextRight(c, if (dur > 0) fmtTime(dur) else "–:––", px1, by + g.dp(12f), tpp)
        dragHit(px0 - g.dp(12f), by - g.dp(22f), px1 + g.dp(12f), by + g.dp(22f), "seek") { x, _, phase ->
            val f = ((x - px0) / (px1 - px0)).coerceIn(0f, 1f)
            when (phase) {
                0, 1 -> seekDrag = f
                2 -> { engine.seekTo((f * dur).toLong()); seekDrag = -1f }
                else -> seekDrag = -1f
            }
            invalidate()
        }
        iy = by + g.dp(46f)

        // controls
        val ccy = iy + g.dp(30f)
        val span = min(iw - g.dp(24f), g.dp(320f))
        val cx0 = icx - span / 2
        val step = span / 4
        val xs = FloatArray(5) { cx0 + it * step }
        // shuffle
        controlButton(c, xs[0], ccy, "shuf", g.dp(22f)) { engine.setShuffle(!engine.shuffle); toast(if (engine.shuffle) "Перемешивание включено" else "Перемешивание выключено") }
        g.iconShuffle(c, xs[0], ccy, g.dp(22f), if (engine.shuffle) C.ACCENT else C.TEXT2)
        if (engine.shuffle) g.circle(c, xs[0], ccy + g.dp(17f), g.dp(2f), C.ACCENT)
        controlButton(c, xs[1], ccy, "prev", g.dp(24f), hold = -1) { engine.prev() }
        g.iconSkip(c, xs[1], ccy, g.dp(26f), false, C.TEXT)
        // play
        val playing = engine.isPlaying || (scanDir != 0 && scanWasPlaying)
        g.circle(c, xs[2], ccy, g.dp(34f), if (pressedKey == "play") C.ACCENT2 else C.ACCENT)
        g.iconPlay(c, xs[2] + if (playing) 0f else g.dp(2f), ccy, g.dp(28f), playing, C.TEXT)
        hit(xs[2] - g.dp(38f), ccy - g.dp(38f), xs[2] + g.dp(38f), ccy + g.dp(38f), "play") { engine.toggle() }
        controlButton(c, xs[3], ccy, "next", g.dp(24f), hold = 1) { engine.next() }
        g.iconSkip(c, xs[3], ccy, g.dp(26f), true, C.TEXT)
        val rep = engine.repeat
        controlButton(c, xs[4], ccy, "rep", g.dp(22f)) {
            engine.cycleRepeat()
            toast(when (engine.repeat) { Engine.Repeat.OFF -> "Повтор выключен"; Engine.Repeat.ALL -> "Повтор очереди"; Engine.Repeat.ONE -> "Повтор трека" })
        }
        g.iconRepeat(c, xs[4], ccy, g.dp(22f), rep == Engine.Repeat.ONE, if (rep != Engine.Repeat.OFF) C.ACCENT else C.TEXT2)
        if (rep != Engine.Repeat.OFF) g.circle(c, xs[4], ccy + g.dp(17f), g.dp(2f), C.ACCENT)
    }

    /** cassette type by audio quality */
    private fun tapeFor(t: Track): Pair<TapeStyle, String> = when (t.tier) {
        4 -> TapeStyle.DSD to t.formatName.replace("DSD", "DSD ").replace("DST", "DST ")
        3 -> TapeStyle.METAL to (if (t.rate > 0) (t.rate / 1000).toString() else "IV")
        2 -> TapeStyle.CHROME to "TYPE II · CrO2"
        else -> TapeStyle.NORMAL to "TYPE I · C90"
    }

    /** [hold] != 0: press and hold winds the tape (1 forward, -1 back) */
    private fun controlButton(c: Canvas, cx: Float, cy: Float, key: String, r: Float, hold: Int = 0, tap: () -> Unit) {
        if (pressedKey == key) g.circle(c, cx, cy, r, if (scanDir != 0 && scanDir == hold) C.argb(90, 0xE5245E) else C.SEL)
        if (hold != 0) hit(cx - g.dp(28f), cy - g.dp(28f), cx + g.dp(28f), cy + g.dp(28f), key, long = { scanStart(hold) }, holdEnd = { scanEnd() }, tap = tap)
        else hit(cx - g.dp(28f), cy - g.dp(28f), cx + g.dp(28f), cy + g.dp(28f), key, tap = tap)
    }

    // ---------- special library states ----------

    private fun cassetteGlyph(c: Canvas, cx: Float, y: Float) {
        val w = g.dp(96f); val h = g.dp(60f)
        g.rrectStroke(c, cx - w / 2, y, w, h, g.dp(9f), g.dp(2.5f), C.TEXT3)
        g.ring(c, cx - w * 0.2f, y + h * 0.45f, g.dp(7f), g.dp(10f), C.TEXT3)
        g.ring(c, cx + w * 0.2f, y + h * 0.45f, g.dp(7f), g.dp(10f), C.TEXT3)
    }

    private fun drawPermission(c: Canvas, vTop: Float, vBot: Float) {
        val cx = width / 2f
        var y = (vTop + vBot) / 2 - g.dp(150f)
        cassetteGlyph(c, cx, y)
        y += g.dp(88f)
        val tp = g.text(g.fonts.bold, 21f, C.TEXT)
        for (l in g.wrap("Нужен доступ к музыке", tp, padR - padL, 2)) { g.drawTextCenter(c, l, cx, y, tp); y += g.lineH(tp) }
        y += g.dp(8f)
        val sp = g.text(g.fonts.reg, 14.5f, C.TEXT2)
        val msg = "Кассета сама найдёт все песни на устройстве и карте памяти, вместе с CUE-образами и DSD. Для этого нужно разрешение на чтение файлов."
        for (l in g.wrap(msg, sp, min(padR - padL, g.dp(340f)), 5)) { g.drawTextCenter(c, l, cx, y, sp); y += g.lineH(sp) }
        y += g.dp(22f)
        val bw = g.dp(220f); val bh = g.dp(48f)
        g.rrect(c, cx - bw / 2, y, bw, bh, bh / 2, if (pressedKey == "perm") C.ACCENT2 else C.ACCENT)
        g.drawTextCenter(c, "Разрешить доступ", cx, y + (bh - g.lineH(g.text(g.fonts.semi, 16f, C.TEXT))) / 2, g.text(g.fonts.semi, 16f, C.TEXT))
        hit(cx - bw / 2, y, cx + bw / 2, y + bh, "perm") { host.requestStorage() }
    }

    private fun drawScanning(c: Canvas, vTop: Float, vBot: Float) {
        val cx = width / 2f
        val cy = (vTop + vBot) / 2
        val rx = cx; val ry = cy - g.dp(90f); val r = g.dp(42f)
        g.circle(c, rx, ry, r, C.hex(0x1C1C20))
        g.ring(c, rx, ry, r * 0.55f, r * 0.95f, C.withAlpha(C.ACCENT, 60))
        g.circle(c, rx, ry, r * 0.42f, C.hex(0xDADADA))
        g.circle(c, rx, ry, r * 0.26f, C.BG)
        for (i in 0 until 6) {
            val a = t * 3 + i * PI.toFloat() / 3
            g.line(c, rx + kotlin.math.cos(a) * r * 0.14f, ry + kotlin.math.sin(a) * r * 0.14f, rx + kotlin.math.cos(a) * r * 0.27f, ry + kotlin.math.sin(a) * r * 0.27f, g.dp(3.5f), C.hex(0xDADADA))
        }
        val tp = g.text(g.fonts.bold, 21f, C.TEXT)
        g.drawTextCenter(c, if (App.loaded) "Ищу музыку на устройстве" else "Открываю библиотеку", cx, cy - g.dp(20f), tp, padR - padL)
        val pr = App.progress
        val line = if (pr.toProbe > 0) "Читаю теги: ${pr.probed.get()} из ${pr.toProbe}" else plural(pr.found, "файл найден", "файла найдено", "файлов найдено")
        g.drawTextCenter(c, line, cx, cy + g.dp(16f), g.text(g.fonts.reg, 14.5f, C.TEXT2), padR - padL)
        val cur = pr.current.substringAfter("/storage/").replace("emulated/0/", "")
        g.drawTextCenter(c, cur, cx, cy + g.dp(42f), g.text(g.fonts.reg, 12f, C.TEXT3), padR - padL)
    }

    private fun drawEmpty(c: Canvas, vTop: Float, vBot: Float) {
        val cx = width / 2f
        var y = (vTop + vBot) / 2 - g.dp(130f)
        cassetteGlyph(c, cx, y)
        y += g.dp(88f)
        g.drawTextCenter(c, "Музыка не найдена", cx, y, g.text(g.fonts.bold, 21f, C.TEXT), padR - padL)
        y += g.dp(36f)
        val sp = g.text(g.fonts.reg, 14.5f, C.TEXT2)
        for (l in g.wrap("Скопируйте музыку в любую папку на устройстве или карте памяти: FLAC, APE, WavPack, DSF/DFF, MP3, CUE-образы и другие.", sp, min(padR - padL, g.dp(340f)), 5)) {
            g.drawTextCenter(c, l, cx, y, sp); y += g.lineH(sp)
        }
        y += g.dp(22f)
        val bw = g.dp(200f); val bh = g.dp(46f)
        g.rrect(c, cx - bw / 2, y, bw, bh, bh / 2, if (pressedKey == "rescan") C.SEL else C.CHIP)
        val bp = g.text(g.fonts.semi, 15f, C.TEXT)
        g.drawTextCenter(c, "Обновить", cx, y + (bh - g.lineH(bp)) / 2, bp)
        hit(cx - bw / 2, y, cx + bw / 2, y + bh, "rescan") { App.rescan(false) }
    }

    // ---------- toast ----------

    private fun drawToast(c: Canvas, now: Long): Boolean {
        if (now > toastUntil || toastText.isEmpty()) return false
        val left = toastUntil - now
        val a = min(1f, left / 300f) * min(1f, (2600 - left) / 150f)
        val p = g.text(g.fonts.semi, 14f, C.withAlpha(C.TEXT, (255 * a).toInt()))
        val tw = min(p.measureText(toastText), width - g.dp(80f))
        val w = tw + g.dp(36f); val h = g.dp(40f)
        val x = (width - w) / 2
        val y = height - insetBottom - (if (hasMini() && topScr.kind != Kind.PLAYER) miniH else 0f) - h - g.dp(16f)
        g.rrect(c, x, y, w, h, h / 2, C.withAlpha(C.hex(0x26262B), (240 * a).toInt()))
        g.drawText(c, toastText, x + g.dp(18f), y + (h - g.lineH(p)) / 2, p, tw)
        return true
    }

    // ---------- sheets ----------

    private fun drawSheet(c: Canvas, dt: Float): Boolean {
        val s = sheet ?: return false
        val target = if (sheetClosing) 0f else 1f
        sheetAnim += (target - sheetAnim) * min(1f, dt * 14f)
        if (abs(sheetAnim - target) < 0.01f) sheetAnim = target
        if (sheetClosing && sheetAnim <= 0f) { sheet = null; return true }
        val a = sheetAnim
        g.fill.color = C.argb((160 * a).toInt(), 0x000000)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), g.fill)
        // tap outside closes (no input while the sheet is closing)
        val interactive = !sheetClosing
        if (interactive) hit(0f, 0f, width.toFloat(), height.toFloat(), "sheetbg") { closeSheet() }
        val rh = g.dp(52f)
        val w = min(width - insetLeft - insetRight.toFloat(), g.dp(520f))
        val x = (width - w) / 2
        val tp = g.text(g.fonts.bold, 18f, C.TEXT)
        val headH = g.dp(20f) + g.lineH(tp) + (if (s.sub.isNotEmpty()) g.dp(20f) else 0f) + g.dp(10f)
        val fullH = headH + s.items.size * rh + g.dp(12f) + insetBottom
        val maxH = height - insetTop - g.dp(40f)
        val h = min(fullH, maxH)
        s.maxScroll = max(0f, fullH - h)
        s.scroll = s.scroll.coerceIn(0f, s.maxScroll)
        val y = height - h * ease(a)
        g.rrect(c, x, y, w, h + g.dp(24f), g.dp(20f), C.hex(0x141417))
        g.rrectStroke(c, x, y, w, h + g.dp(24f), g.dp(20f), 1.2f, C.hex(0x2C2C32))
        if (interactive) hit(x, y, x + w, height.toFloat(), "sheet") { }
        g.rrect(c, x + w / 2 - g.dp(18f), y + g.dp(8f), g.dp(36f), g.dp(4f), g.dp(2f), C.hex(0x3A3A40))
        c.save()
        c.clipRect(x, y, x + w, height.toFloat())
        var yy = y + g.dp(20f) - s.scroll
        g.drawText(c, s.title, x + g.dp(24f), yy, tp, w - g.dp(48f))
        yy += g.lineH(tp)
        if (s.sub.isNotEmpty()) { g.drawText(c, s.sub, x + g.dp(24f), yy + g.dp(2f), g.text(g.fonts.reg, 13f, C.TEXT2), w - g.dp(48f)); yy += g.dp(20f) }
        yy += g.dp(10f)
        val lp = g.text(g.fonts.reg, 15.5f, C.TEXT)
        for ((i, it) in s.items.withIndex()) {
            val key = "si$i"
            if (pressedKey == key) g.rrect(c, x + g.dp(10f), yy + g.dp(2f), w - g.dp(20f), rh - g.dp(4f), g.dp(12f), C.SEL)
            val v = it.value?.invoke()
            val vp = g.text(g.fonts.semi, 14f, C.ACCENT2)
            val vw = if (v != null) vp.measureText(v) else 0f
            lp.color = C.TEXT
            g.drawText(c, it.label, x + g.dp(24f), yy + (rh - g.lineH(lp)) / 2, lp, w - g.dp(60f) - vw)
            if (v != null) g.drawTextRight(c, v, x + w - g.dp(24f), yy + (rh - g.lineH(vp)) / 2, vp)
            if (interactive && yy + rh > y && a > 0.9f) hit(x, max(yy, y), x + w, yy + rh, key) {
                it.action()
                if (s.closeOnTap) closeSheet()
                invalidate()
            }
            yy += rh
        }
        c.restore()
        return a < 1f || sheetClosing
    }

    private fun onOff(b: Boolean) = if (b) "вкл" else "выкл"

    private fun openMenu() {
        val items = ArrayList<SheetItem>()
        items.add(SheetItem("Вид плеера", { if (App.cdView) "CD" else "кассета" }) { App.cdView = !App.cdView })
        items.add(SheetItem("Ориентация экрана", { if (App.landscape) "горизонтальная" else "вертикальная" }) {
            App.landscape = !App.landscape; host.applyOrientation()
        })
        items.add(SheetItem("Звуки кассет и коробок", { onOff(App.sounds) }) { App.sounds = !App.sounds })
        items.add(SheetItem("Плеер на экране блокировки", {
            if (App.lockScreen && !host.hasOverlayPermission()) "нет разрешения" else onOff(App.lockScreen)
        }) {
            if (App.lockScreen && !host.hasOverlayPermission()) host.ensureOverlayPermission()
            else if (!App.lockScreen) {
                if (host.ensureOverlayPermission()) App.lockScreen = true
                else { App.lockScreen = true; toast("Разрешите «Поверх других приложений» и вернитесь") }
            } else App.lockScreen = false
        })
        items.add(SheetItem("Перемешивание", { onOff(engine.shuffle) }) { engine.setShuffle(!engine.shuffle) })
        items.add(SheetItem("Повтор", {
            when (engine.repeat) { Engine.Repeat.OFF -> "выкл"; Engine.Repeat.ALL -> "очередь"; Engine.Repeat.ONE -> "один трек" }
        }) { engine.cycleRepeat() })
        items.add(SheetItem("Перемешать всю музыку") {
            if (lib.tracks.isNotEmpty()) { engine.play(lib.tracks, lib.tracks.indices.random(), true); closeSheet(); openPlayer() }
        })
        items.add(SheetItem("Частота вывода", { "до ${App.maxRate / 1000} кГц" }) {
            App.maxRate = when (App.maxRate) { 192000 -> 96000; 96000 -> 48000; else -> 192000 }
        })
        items.add(SheetItem("Разрядность вывода", { if (App.output16) "16 бит" else "32 бит float" }) { App.output16 = !App.output16 })
        items.add(SheetItem("ReplayGain", { arrayOf("выкл", "трек", "альбом")[App.replayGain] }) { App.replayGain = (App.replayGain + 1) % 3 })
        items.add(SheetItem("DSD: +6 дБ громкости", { onOff(App.dsdBoost) }) { App.dsdBoost = !App.dsdBoost })
        items.add(SheetItem("DSD: фильтр ультразвука", { onOff(App.dsdFilter) }) { App.dsdFilter = !App.dsdFilter })
        items.add(SheetItem("Обновить библиотеку") {
            closeSheet()
            if (!App.hasStoragePermission()) host.requestStorage()
            else { toast("Обновляю библиотеку…"); App.rescan(false) { n -> if (n >= 0) toast("Готово: " + plural(n, "трек", "трека", "треков")) } }
        })
        items.add(SheetItem("Пересканировать всё заново") {
            closeSheet()
            if (!App.hasStoragePermission()) host.requestStorage()
            else { toast("Сканирую заново…"); App.rescan(true) { n -> if (n >= 0) toast("Готово: " + plural(n, "трек", "трека", "треков")) } }
        })
        val n = lib.tracks.size
        openSheet(Sheet("Меню", if (n > 0) "В библиотеке " + plural(n, "трек", "трека", "треков") else "", items, closeOnTap = false))
    }

    private fun trackMenu(tr: Track) {
        val items = ArrayList<SheetItem>()
        items.add(SheetItem("Играть следующим") { engine.enqueue(listOf(tr), true); toast("Будет следующим") })
        items.add(SheetItem("В конец очереди") { engine.enqueue(listOf(tr), false); toast("Добавлено в очередь") })
        val ai = lib.indexOf(tr.id).let { if (it >= 0) lib.tracks[it].albumIdx else -1 }
        if (ai >= 0) items.add(SheetItem("Открыть альбом") { push(Scr(Kind.ALBUM, lib.albums[ai].key)) })
        val artist = if (ai >= 0) lib.albums[ai].artist else tr.artist
        if (lib.artistIndex(artist) >= 0) items.add(SheetItem("Открыть исполнителя") { push(Scr(Kind.ARTIST, artist)) })
        val info = listOf(tr.formatName, tr.qualityLine.removePrefix(tr.formatName + " · "), fmtTime(tr.durMs)).joinToString(" · ")
        openSheet(Sheet(tr.title, "${tr.artist} · $info", items, closeOnTap = true))
    }

    private fun albumMenu(ai: Int) {
        val al = lib.albums[ai]
        val tracks = albumTracks(al)
        val items = ArrayList<SheetItem>()
        items.add(SheetItem("Слушать") { playList(tracks, 0) })
        items.add(SheetItem("Перемешать") { playList(tracks, tracks.indices.random(), true) })
        items.add(SheetItem("Играть следующим") { engine.enqueue(tracks, true); toast("Альбом будет следующим") })
        items.add(SheetItem("В конец очереди") { engine.enqueue(tracks, false); toast("Альбом добавлен в очередь") })
        if (lib.artistIndex(al.artist) >= 0) items.add(SheetItem("Открыть исполнителя") { push(Scr(Kind.ARTIST, al.artist)) })
        openSheet(Sheet(al.name, al.artist, items, closeOnTap = true))
    }

    private fun artistMenu(name: String) {
        val ai = lib.artistIndex(name)
        if (ai < 0) return
        val ar = lib.artists[ai]
        val tracks = ar.albums.flatMap { albumTracks(lib.albums[it]) }
        val items = ArrayList<SheetItem>()
        items.add(SheetItem("Слушать всё") { playList(tracks, 0) })
        items.add(SheetItem("Перемешать всё") { playList(tracks, tracks.indices.random(), true) })
        items.add(SheetItem("В конец очереди") { engine.enqueue(tracks, false); toast("Добавлено в очередь") })
        openSheet(Sheet(ar.name, plural(tracks.size, "трек", "трека", "треков"), items, closeOnTap = true))
    }
}
