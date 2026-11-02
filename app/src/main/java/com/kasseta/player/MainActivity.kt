package com.kasseta.player

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.kasseta.player.ui.KassetaView

class MainActivity : Activity(), KassetaView.Host {
    private lateinit var view: KassetaView
    private var backCallback: Any? = null
    private lateinit var search: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        applyOrientation()
        com.kasseta.player.ui.Sfx.init(applicationContext)
        view = KassetaView(this, this)
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                (v as KassetaView).setInsets(i.top, i.bottom, i.left, i.right)
            } else {
                @Suppress("DEPRECATION")
                (v as KassetaView).setInsets(insets.systemWindowInsetTop, insets.systemWindowInsetBottom, insets.systemWindowInsetLeft, insets.systemWindowInsetRight)
            }
            insets
        }
        search = EditText(this).apply {
            background = null
            setTextColor(0xFFF3F3F3.toInt())
            setHintTextColor(0xFF5D5D63.toInt())
            hint = "Трек, альбом, исполнитель"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            try { typeface = android.graphics.Typeface.createFromAsset(assets, "fonts/Golos-Regular.ttf") } catch (_: Exception) {}
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { view.setQuery(s?.toString() ?: "") }
            })
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
            }
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(search, FrameLayout.LayoutParams(0, 0))
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            val cb = OnBackInvokedCallback { if (!view.back()) finishApp() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (intent?.getBooleanExtra("player", false) == true) view.post { view.openPlayer() }
    }

    override fun onResume() {
        super.onResume()
        // once a day: is there a newer version on GitHub?
        Updater.check(this) { rel -> if (rel != null && !isFinishing) view.offerUpdate(rel) }
        // permission may have been granted in system settings
        if (App.loaded && App.hasStoragePermission() && App.library.tracks.isEmpty() && !App.scanning) App.rescan(false)
        view.invalidate()
    }

    override fun onStop() {
        App.savePlayback()
        super.onStop()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!view.back()) finishApp()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = App.engine
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { e.toggle(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { e.resume(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { e.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { e.next(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { e.prev(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            if (App.hasStoragePermission()) App.rescan(false)
            else if (!shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                // "don't ask again": send the user to the app settings
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            view.invalidate()
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    override fun searchField(show: Boolean, x: Int, y: Int, w: Int, h: Int) {
        if (show) {
            val lp = FrameLayout.LayoutParams(w, h)
            lp.leftMargin = x; lp.topMargin = y
            search.layoutParams = lp
            if (search.visibility != View.VISIBLE) {
                search.visibility = View.VISIBLE
                search.requestFocus()
                if (search.text.isEmpty()) search.post {
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        } else if (search.visibility == View.VISIBLE) {
            hideKeyboard()
            search.visibility = View.GONE
        }
    }

    override fun setSearchText(text: String) {
        if (search.text.toString() != text) search.setText(text)
        if (text.isEmpty() && search.visibility == View.VISIBLE) search.requestFocus()
    }

    override fun applyOrientation() {
        requestedOrientation = if (App.landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(this)

    override fun ensureOverlayPermission(): Boolean {
        if (Settings.canDrawOverlays(this)) return true
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
        }
        return false
    }

    override fun finishApp() {
        finish()
    }
}
