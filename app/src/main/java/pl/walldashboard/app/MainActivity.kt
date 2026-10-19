package pl.walldashboard.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** The kiosk: the add-on dashboard in a full-screen WebView + native screen / sound handling. */
class MainActivity : Activity() {
    lateinit var prefs: Prefs
    lateinit var screen: ScreenManager
    lateinit var sound: SoundPlayer
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var errorView: LinearLayout? = null
    private var errorText: TextView? = null
    private val io = Executors.newSingleThreadExecutor()
    private var loadFailed = false
    private var pendingPermission: PermissionRequest? = null
    private val h = Handler(Looper.getMainLooper())
    private val retry = Runnable { reloadDashboard() }
    private val openSettings = Runnable { startActivity(Intent(this, SettingsActivity::class.java)) }
    private var cornerX = 0f
    private var cornerY = 0f

    private var cpuLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.paired) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = WebView(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sound = SoundPlayer(this, prefs)
        // wall panel on mains power: the CPU never sleeps, so a doorbell (SIP / WebSocket) is handled even with the screen off
        try {
            cpuLock = getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "walldash:cpu").apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { android.util.Log.w("WallDashboard", "wake lock: ${e.message}") }
        screen = ScreenManager(this, root)   // its black overlay sits above the WebView
        setContentView(root)

        setupWebView()
        screen.configure(prefs.screenJson)
        screen.start()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        }
        web.loadUrl(prefs.pageUrl())
        checkKey()
    }

    /**
     * The tablet was deleted in the add-on or got a new key: go back to pairing instead of showing
     * "Nieprawidłowy klucz" forever. Network errors are ignored here (the error screen handles them).
     */
    private fun checkKey() {
        if (!prefs.paired) return
        io.execute {
            val code = try { Net.get(prefs, "${prefs.base}/api/kiosk?k=${prefs.key}").code } catch (_: Exception) { -1 }
            if (code == 401 || code == 404) runOnUiThread { repair("Ten panel został usunięty w add-onie albo dostał nowy klucz. Sparuj go ponownie.") }
        }
    }

    fun repair(reason: String) {
        prefs.key = ""
        Kiosk.leave(this)
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_REASON, reason)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true) // chrome://inspect from a PC over ADB
        // the dashboard keeps its priority with the screen off / covered: the SIP phone and the WebSocket answer at once
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        web.setBackgroundColor(Color.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString = "$userAgentString WallDashboardApp/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(FullyBridge(this), "fully")
        web.addJavascriptInterface(AppBridge(this), "wallApp")

        web.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // the add-on's self-signed certificate: only the one pinned when pairing, only for the add-on host
                val x = error.certificate?.x509Certificate
                val sameHost = Uri.parse(error.url).host == prefs.host()
                if (sameHost && x != null && prefs.certPin.isNotEmpty() && Net.sha256(x) == prefs.certPin) handler.proceed()
                else {
                    handler.cancel()
                    if (Uri.parse(error.url).host == prefs.host()) showError("Certyfikat add-onu jest inny niż przy parowaniu.\nOtwórz ustawienia (przytrzymaj lewy górny róg) → Sparuj ponownie.")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != prefs.host() // stay on the dashboard, no external pages

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { loadFailed = false }

            override fun onPageFinished(view: WebView, url: String?) { if (!loadFailed) { hideError(); checkKey() } }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadFailed = true
                showError("Brak połączenia z add-onem\n${prefs.base}\n\n${error.description}\n\nPonawiam za 10 s…")
                h.removeCallbacks(retry)
                h.postDelayed(retry, 10_000)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "WebView padł (crash=${detail.didCrash()}) – uruchamiam ponownie")
                recreate()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wanted = request.resources.filter { it == PermissionRequest.RESOURCE_AUDIO_CAPTURE }.toTypedArray()
                    if (wanted.isEmpty()) { request.deny(); return@runOnUiThread }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(wanted)
                    else { pendingPermission = request; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) }
                }
            }

            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) Log.w(TAG, "JS: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val p = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) p.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else p.deny()
    }

    /** screen settings + PIN from the add-on (window.wallApp.setConfig) */
    fun applyConfig(json: String) {
        try {
            val o = JSONObject(json)
            prefs.pin = o.optString("pin", "")
            prefs.tablet = o.optString("tablet", prefs.tablet)
            val s = o.optJSONObject("screen")?.toString() ?: ""
            if (s != prefs.screenJson) { prefs.screenJson = s; screen.configure(s) }
        } catch (e: Exception) { Log.w(TAG, "konfiguracja: ${e.message}") }
    }

    fun reloadDashboard() {
        h.removeCallbacks(retry)
        if (::web.isInitialized) web.loadUrl(prefs.pageUrl())
    }

    /** connection / certificate problem: message + buttons (no hidden gesture needed to get out) */
    private fun showError(text: String) {
        val v = errorView ?: LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(40, 40, 40, 40)
            isClickable = true
            val t = TextView(this@MainActivity).apply {
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }
            errorText = t
            addView(t)
            addView(Ui.button(this@MainActivity, "Ponów teraz", primary = true) { reloadDashboard() })
            addView(Ui.button(this@MainActivity, "Ustawienia / sparuj ponownie") { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) })
            root.addView(this, 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            errorView = this
        }
        errorText?.text = text
        v.visibility = View.VISIBLE
    }

    private fun hideError() { errorView?.visibility = View.GONE }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::screen.isInitialized) return super.dispatchTouchEvent(ev)
        if (screen.onTouch(ev)) return true
        // hidden settings: hold the top-left corner for 3 s
        val corner = 64 * resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (ev.x < corner && ev.y < corner) { cornerX = ev.x; cornerY = ev.y; h.postDelayed(openSettings, 3000) }
            MotionEvent.ACTION_MOVE -> if (Math.abs(ev.x - cornerX) > 40 || Math.abs(ev.y - cornerY) > 40) h.removeCallbacks(openSettings)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> h.removeCallbacks(openSettings)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (!::web.isInitialized) return
        hideSystemBars()
        Kiosk.enter(this)
        if (reloadRequested) { reloadRequested = false; reloadDashboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::web.isInitialized) hideSystemBars()
    }

    // the WebView is never paused: the dashboard keeps its connection (wake from HA, doorbell) with the screen dark

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack()
        // otherwise ignore: a kiosk does not close
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    override fun onDestroy() {
        try { cpuLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }
        if (::sound.isInitialized) sound.callAudio(false)
        h.removeCallbacksAndMessages(null)
        io.shutdownNow()
        if (::screen.isInitialized) screen.release()
        if (::sound.isInitialized) sound.release()
        if (::web.isInitialized) { root.removeView(web); web.destroy() }
        super.onDestroy()
    }

    companion object {
        const val TAG = "WallDashboard"
        const val REQ_MIC = 7
        /** set by the settings screen */
        @Volatile var reloadRequested = false
    }
}
