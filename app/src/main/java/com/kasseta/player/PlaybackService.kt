package com.kasseta.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent

/** Keeps playback alive in the background: foreground notification + media session (hardware buttons). */
class PlaybackService : Service(), Engine.Listener {
    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "com.kasseta.player.TOGGLE"
        const val ACTION_NEXT = "com.kasseta.player.NEXT"
        const val ACTION_PREV = "com.kasseta.player.PREV"
        const val ACTION_STOP = "com.kasseta.player.STOP"
        private const val LOCK_CHANNEL = "lockscreen"
        const val LOCK_NOTIF_ID = 2
    }

    private lateinit var session: MediaSession
    private lateinit var nm: NotificationManager
    private val engine get() = App.engine
    private val main = Handler(Looper.getMainLooper())
    private var foreground = false
    private var artKey: String? = null
    private var art: Bitmap? = null
    private var lastTrackId: String? = null
    private val tick = object : Runnable {
        override fun run() {
            updateState()
            if (engine.isPlaying) main.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        session = MediaSession(this, "Kasseta")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = engine.resume()
            override fun onPause() = engine.pause()
            override fun onSkipToNext() = engine.next()
            override fun onSkipToPrevious() = engine.prev()
            override fun onSeekTo(pos: Long) = engine.seekTo(pos)
            override fun onStop() = engine.pause()
            override fun onMediaButtonEvent(i: Intent): Boolean {
                @Suppress("DEPRECATION")
                val ev = i.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (ev != null && ev.action == KeyEvent.ACTION_DOWN && ev.repeatCount == 0) {
                    when (ev.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { engine.toggle(); return true }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> { engine.resume(); return true }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> { engine.pause(); return true }
                        KeyEvent.KEYCODE_MEDIA_NEXT -> { engine.next(); return true }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { engine.prev(); return true }
                    }
                }
                return super.onMediaButtonEvent(i)
            }
        })
        session.setSessionActivity(contentIntent())
        session.isActive = true
        engine.addListener(this)
        val f = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
        f.addAction(Intent.ACTION_SCREEN_ON)
        f.addAction(Intent.ACTION_USER_PRESENT)
        registerReceiver(screenReceiver, f)
        App.serviceRunning = true
        startFg()
        onEngineChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        when (intent?.action) {
            ACTION_TOGGLE -> engine.toggle()
            ACTION_NEXT -> engine.next()
            ACTION_PREV -> engine.prev()
            ACTION_STOP -> { engine.pause(); stopFg(true); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!engine.isPlaying) { stopFg(true); stopSelf() }
    }

    /**
     * Screen went off while music plays: put our player in front of the lock screen.
     * Two routes, because hi-fi player firmwares differ: a direct start (allowed with the
     * "draw over other apps" permission) and a full-screen notification, which the system
     * itself turns into an activity while the screen is off (the way alarm and call apps do it).
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> showLock()
                Intent.ACTION_SCREEN_ON -> {
                    // fallback: if the system lock screen is up and our screen is not, start it now
                    val km = getSystemService(android.app.KeyguardManager::class.java)
                    if (wantLock() && km.isKeyguardLocked && !LockActivity.visible) startLock()
                    main.postDelayed({ nm.cancel(LOCK_NOTIF_ID) }, 4000)
                }
                Intent.ACTION_USER_PRESENT -> nm.cancel(LOCK_NOTIF_ID)
            }
        }
    }

    private fun wantLock() = App.lockScreen && engine.currentTrack() != null && engine.isPlaying

    private fun lockIntent() = Intent(this, LockActivity::class.java).addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
    )

    private fun startLock() {
        try { startActivity(lockIntent()) } catch (e: Exception) { android.util.Log.w("Kasseta", "lock start failed", e) }
    }

    private fun showLock() {
        if (!wantLock() || LockActivity.visible) return
        startLock()
        // full-screen notification route
        try {
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(LOCK_CHANNEL) == null) {
                val ch = NotificationChannel(LOCK_CHANNEL, "Экран блокировки", NotificationManager.IMPORTANCE_HIGH)
                ch.setSound(null, null)
                ch.enableVibration(false)
                ch.enableLights(false)
                ch.setShowBadge(false)
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
            val pi = PendingIntent.getActivity(this, 7, lockIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, LOCK_CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
            b.setSmallIcon(R.drawable.ic_stat_kasseta)
                .setContentTitle(engine.currentTrack()?.title ?: "Кассета")
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setShowWhen(false)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MAX).setSound(null)
            if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(15000)
            nm.notify(LOCK_NOTIF_ID, b.build())
        } catch (e: Exception) {
            android.util.Log.w("Kasseta", "lock notification failed", e)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        engine.removeListener(this)
        main.removeCallbacks(tick)
        App.savePlayback()
        session.isActive = false
        session.release()
        App.serviceRunning = false
        super.onDestroy()
    }

    override fun onEngineChanged() {
        val t = engine.currentTrack()
        if (t?.id != lastTrackId) {
            lastTrackId = t?.id
            updateMetadata(t)
        }
        updateState()
        val n = buildNotification(t)
        if (engine.isPlaying) {
            startFg(n)
            main.removeCallbacks(tick)
            main.postDelayed(tick, 1000)
        } else {
            if (foreground) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_DETACH) else @Suppress("DEPRECATION") stopForeground(false)
                foreground = false
            }
            if (t != null) nm.notify(NOTIF_ID, n)
        }
    }

    private fun startFg(n: Notification = buildNotification(engine.currentTrack())) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIF_ID, n)
        foreground = true
    }

    private fun stopFg(remove: Boolean) {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(if (remove) Service.STOP_FOREGROUND_REMOVE else Service.STOP_FOREGROUND_DETACH)
        else @Suppress("DEPRECATION") stopForeground(remove)
        foreground = false
        if (remove) nm.cancel(NOTIF_ID)
    }

    private fun updateMetadata(t: Track?) {
        if (t == null) { session.setMetadata(null); return }
        val album = App.albumOf(t)
        val key = "$album"
        if (key != artKey) {
            artKey = key
            art = null
            Thread({
                val b = try { Covers.getBlocking(App.library, album, 512) } catch (e: Throwable) { null }
                main.post {
                    if (artKey == key) {
                        art = b
                        lastTrackId?.let { id -> if (id == engine.currentTrack()?.id) { setMeta(engine.currentTrack()); nm.notify(NOTIF_ID, buildNotification(engine.currentTrack())) } }
                    }
                }
            }, "kasseta-art").start()
        }
        setMeta(t)
    }

    private fun setMeta(t: Track?) {
        if (t == null) return
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, t.albumArtist.ifBlank { t.artist })
            .putLong(MediaMetadata.METADATA_KEY_DURATION, t.durMs)
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, t.trackNo.toLong())
        art?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(b.build())
    }

    private fun updateState() {
        val st = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
            )
            .setState(if (engine.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, engine.positionMs(), if (engine.isPlaying) 1f else 0f)
            .build()
        session.setPlaybackState(st)
    }

    private fun contentIntent(): PendingIntent =
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun action(act: String, req: Int): PendingIntent =
        PendingIntent.getService(this, req, Intent(this, PlaybackService::class.java).setAction(act), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(t: Track?): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        val playing = engine.isPlaying
        b.setSmallIcon(R.drawable.ic_stat_kasseta)
            .setContentTitle(t?.title ?: "Кассета")
            .setContentText(t?.let { "${it.artist} — ${it.album}" } ?: "")
            .setContentIntent(contentIntent())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(playing)
            .setShowWhen(false)
            .setColor(0xFFE5245E.toInt())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_prev), "Назад", action(ACTION_PREV, 1)).build())
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    if (playing) "Пауза" else "Играть", action(ACTION_TOGGLE, 2)
                ).build()
            )
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_next), "Вперёд", action(ACTION_NEXT, 3)).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
        art?.let { b.setLargeIcon(it) }
        return b.build()
    }
}
