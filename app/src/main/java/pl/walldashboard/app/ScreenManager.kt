package pl.walldashboard.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.Calendar

/**
 * Screen saver / dimming / screen off, from the add-on settings (Tablety → Ekran i aplikacja):
 * after N minutes without touch and/or in time windows. A touch, HA, a pop-up or a call wakes it.
 */
class ScreenManager(private val a: Activity, root: FrameLayout) {
    private data class Win(val from: Int, val to: Int, val days: Set<Int>?)
    private data class Clock(val night: Boolean = false, val idle: Boolean = false, val date: Boolean = false, val brightness: Int = 8)
    private data class Cfg(val mode: String = "saver", val idleMin: Int = 0, val hours: List<Win> = emptyList(), val brightness: Int? = null, val dim: Int = 3, val clock: Clock = Clock())

    private val overlay = FrameLayout(a).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
    }
    // ---- clock on the black screen (night windows / idle), values pushed by the dashboard
    private val timeView = TextView(a).apply { setTextColor(Color.argb(215, 255, 255, 255)); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL); gravity = Gravity.CENTER; includeFontPadding = false }
    private val dateView = TextView(a).apply { setTextColor(Color.argb(150, 255, 255, 255)); gravity = Gravity.CENTER }
    private val infoView = TextView(a).apply { setTextColor(Color.argb(160, 255, 255, 255)); gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f) }
    private val warnView = TextView(a).apply { setTextColor(Color.argb(200, 255, 170, 90)); gravity = Gravity.CENTER }
    private val clockBox = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(timeView); addView(dateView); addView(infoView); addView(warnView)
        visibility = View.GONE
    }
    private var darkReason = "idle"
    private var clockOn = false
    private val timeFmt = SimpleDateFormat("HH:mm", Locale("pl", "PL"))
    private val dateFmt = SimpleDateFormat("EEEE, d MMMM", Locale("pl", "PL"))
    private var lastMove = 0L
    private val clockTick = object : Runnable {
        override fun run() { if (clockOn) { drawClock(); h.postDelayed(this, 15_000) } }
    }
    private val h = Handler(Looper.getMainLooper())
    private var cfg = Cfg()
    private var lastTouch = SystemClock.elapsedRealtime()
    private var dark = false
    /** HA / admin forced "saver" or "screen-off": stays dark until a touch or a wake */
    private var forced: String? = null
    private var swallow = false
    private var userBrightness: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() { evaluate(); h.postDelayed(this, 10_000) }
    }

    init {
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay.addView(clockBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** [{"icon":"🌡","text":"6,4 °C","warn":false}] from the dashboard (window.wallApp.setSaverInfo) */
    fun setInfo(json: String) {
        try {
            val arr = JSONArray(json)
            val normal = mutableListOf<String>(); val warn = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = (o.optString("icon") + " " + o.optString("text")).trim()
                if (o.optBoolean("warn")) warn.add(t) else normal.add(t)
            }
            infoView.text = normal.joinToString("     ")
            infoView.visibility = if (normal.isEmpty()) View.GONE else View.VISIBLE
            warnView.text = warn.joinToString("     ")
            warnView.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE
        } catch (e: Exception) { Log.w(TAG, "zegar – dane: ${e.message}") }
    }

    private fun drawClock() {
        val now = java.util.Date()
        val m = Math.min(overlay.width, overlay.height).takeIf { it > 0 } ?: Math.min(a.resources.displayMetrics.widthPixels, a.resources.displayMetrics.heightPixels)
        timeView.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.27f)
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.05f)
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.045f)
        warnView.setTextSize(TypedValue.COMPLEX_UNIT_PX, m * 0.045f)
        timeView.text = timeFmt.format(now)
        dateView.text = dateFmt.format(now)
        dateView.visibility = if (cfg.clock.date) View.VISIBLE else View.GONE
        // move a little every minute so nothing burns into the panel
        if (SystemClock.elapsedRealtime() - lastMove > 60_000) {
            lastMove = SystemClock.elapsedRealtime()
            val dx = overlay.width * 0.10f; val dy = overlay.height * 0.10f
            clockBox.translationX = Random.nextFloat() * 2 * dx - dx
            clockBox.translationY = Random.nextFloat() * 2 * dy - dy
        }
    }

    private fun showClock(on: Boolean) {
        clockOn = on
        clockBox.visibility = if (on) View.VISIBLE else View.GONE
        h.removeCallbacks(clockTick)
        if (on) { lastMove = 0; h.post(clockTick) }
    }

    fun start() { h.removeCallbacks(tick); h.post(tick) }
    fun release() { h.removeCallbacks(tick); h.removeCallbacks(clockTick); releaseWakeLock() }

    /** {"mode":"saver|dim|off","idle":10,"hours":[{"from":"23:00","to":"06:30","days":[1,2]}],"brightness":60,"dimBrightness":3} */
    fun configure(json: String) {
        cfg = try {
            if (json.isBlank()) Cfg() else {
                val o = JSONObject(json)
                val wins = mutableListOf<Win>()
                val hs = o.optJSONArray("hours")
                if (hs != null) for (i in 0 until hs.length()) {
                    val w = hs.optJSONObject(i) ?: continue
                    val f = minutes(w.optString("from")) ?: continue
                    val t = minutes(w.optString("to")) ?: continue
                    val d = w.optJSONArray("days")
                    val days = if (d == null || d.length() == 0) null else (0 until d.length()).map { d.optInt(it) }.toSet()
                    wins.add(Win(f, t, days))
                }
                Cfg(
                    mode = o.optString("mode").ifEmpty { "saver" },
                    idleMin = o.optInt("idle", 0),
                    hours = wins,
                    brightness = if (o.has("brightness") && !o.isNull("brightness")) o.optInt("brightness") else null,
                    dim = if (o.has("dimBrightness") && !o.isNull("dimBrightness")) o.optInt("dimBrightness") else 3,
                    clock = o.optJSONObject("clock")?.let { c -> Clock(c.optBoolean("night"), c.optBoolean("idle"), c.optBoolean("date"), c.optInt("brightness", 8).coerceIn(1, 60)) } ?: Clock()
                )
            }
        } catch (e: Exception) { Log.w(TAG, "ustawienia ekranu: ${e.message}"); Cfg() }
        if (!dark) applyBrightness()
        evaluate()
    }

    /** every touch goes through here; true = swallowed (it only woke the screen) */
    fun onTouch(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        if (swallow) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
            return true
        }
        if (dark) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) { swallow = true; wake() }
            return true
        }
        return false
    }

    val isDark: Boolean get() = dark

    /** touch, HA "screen-on", pop-up, call: light up (in a dark window: for the idle time, at least 1 minute) */
    fun wake() {
        forced = null
        lastTouch = SystemClock.elapsedRealtime()
        if (dark || !isInteractive()) setAwake()
    }

    /** HA / admin: "saver" or "off" right now */
    fun forceDark(mode: String) { forced = mode; darkReason = "idle"; setDark(mode) }

    fun setBrightness(pct: Int) { userBrightness = pct.coerceIn(1, 100); if (!dark) applyBrightness() }

    private fun evaluate() {
        if (forced != null) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastTouch
        val idleDark = cfg.idleMin > 0 && idle > cfg.idleMin * 60_000L
        val hold = (if (cfg.idleMin > 0) cfg.idleMin else 1) * 60_000L
        val winDark = inWindow() && idle > hold
        val want = idleDark || winDark
        val reason = if (winDark) "night" else "idle"
        if (want && dark && reason != darkReason) { darkReason = reason; setDark(cfg.mode) } // e.g. idle → night: maybe the clock now
        if (want && !dark) { darkReason = reason; setDark(cfg.mode) }
        else if (!want && dark) setAwake()
    }

    private fun setDark(mode: String) {
        dark = true
        when (mode) {
            "dim" -> { overlay.visibility = View.GONE; window(cfg.dim.coerceIn(0, 100) / 100f) }
            "off" -> {
                overlay.visibility = View.VISIBLE
                window(0.01f)
                if (!Kiosk.lockNow(a)) Log.i(TAG, "wyłączenie ekranu wymaga trybu właściciela urządzenia – czarny ekran zamiast tego")
            }
            else -> {
                overlay.visibility = View.VISIBLE
                val clock = (darkReason == "night" && cfg.clock.night) || (darkReason == "idle" && cfg.clock.idle)
                showClock(clock)
                window(if (clock) cfg.clock.brightness / 100f else 0.01f)
            }
        }
        if (mode != "saver") showClock(false)
        // keep the window "on" so the dashboard (WebSocket) stays alive; the OS screen timeout must not lock it
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP: still the way to light the display from an app
    private fun setAwake() {
        dark = false
        showClock(false)
        overlay.visibility = View.GONE
        applyBrightness()
        if (!isInteractive()) {
            // display was really off (mode "off"): wake it
            try {
                val pm = a.getSystemService(PowerManager::class.java)
                releaseWakeLock()
                wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "walldash:wake").apply { acquire(5_000) }
            } catch (e: Exception) { Log.w(TAG, "budzenie ekranu: ${e.message}") }
        }
    }

    private fun applyBrightness() {
        val pct = userBrightness ?: cfg.brightness
        window(if (pct == null) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else pct.coerceIn(1, 100) / 100f)
    }

    private fun window(v: Float) {
        val lp = a.window.attributes
        lp.screenBrightness = v
        a.window.attributes = lp
    }

    private fun isInteractive(): Boolean = try { a.getSystemService(PowerManager::class.java).isInteractive } catch (_: Exception) { true }
    private fun releaseWakeLock() { try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) { }; wakeLock = null }

    private fun inWindow(): Boolean {
        if (cfg.hours.isEmpty()) return false
        val c = Calendar.getInstance()
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val today = c.get(Calendar.DAY_OF_WEEK) - 1          // 0 = Sunday, like the add-on
        val yesterday = (today + 6) % 7
        return cfg.hours.any { w ->
            if (w.from <= w.to) m in w.from until w.to && (w.days == null || today in w.days)
            // crosses midnight: the evening part belongs to today, the morning part to the day the window started
            else (m >= w.from && (w.days == null || today in w.days)) || (m < w.to && (w.days == null || yesterday in w.days))
        }
    }

    private fun minutes(s: String): Int? {
        val p = s.split(":")
        if (p.size != 2) return null
        val hh = p[0].toIntOrNull() ?: return null
        val mm = p[1].toIntOrNull() ?: return null
        return hh * 60 + mm
    }

    companion object { const val TAG = "WallDashboard" }
}
