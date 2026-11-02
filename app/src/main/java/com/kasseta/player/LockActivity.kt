package com.kasseta.player

import android.app.Activity
import android.app.KeyguardManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.kasseta.player.ui.LockView

/** Our player shown instead of the system lock screen (like on FiiO players). */
class LockActivity : Activity() {
    private lateinit var view: LockView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        requestedOrientation = if (App.landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view = LockView(this) { unlock() }
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (v as LockView).setInsets(i.top, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                (v as LockView).setInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(view)
        current = this
    }

    override fun onResume() {
        super.onResume()
        visible = true
        getSystemService(android.app.NotificationManager::class.java).cancel(PlaybackService.LOCK_NOTIF_ID)
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** swipe up: close our screen and let the system ask for the PIN if there is one */
    private fun unlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { finish() }
                override fun onDismissCancelled() {}
                override fun onDismissError() { finish() }
            })
        } else finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // the lock screen is not closed with Back
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = App.engine
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { e.toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { e.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { e.prev(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        @Volatile var current: LockActivity? = null
        /** our lock screen is on top right now */
        @Volatile var visible = false
    }
}
