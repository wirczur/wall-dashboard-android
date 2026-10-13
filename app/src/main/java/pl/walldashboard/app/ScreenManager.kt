package pl.walldashboard.app

import android.app.Activity
import android.graphics.Color
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
    private data class Cfg(val mode: String = "saver", val idleMin: Int = 0, val hours: List<Win> = emptyList(), val brightness: Int? = null, val dim: Int = 3)

    private val overlay = View(a).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
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
    }

    fun start() { h.removeCallbacks(tick); h.post(tick) }
    fun release() { h.removeCallbacks(tick); releaseWakeLock() }

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
                    dim = if (o.has("dimBrightness") && !o.isNull("dimBrightness")) o.optInt("dimBrightness") else 3
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
    fun forceDark(mode: String) { forced = mode; setDark(mode) }

    fun setBrightness(pct: Int) { userBrightness = pct.coerceIn(1, 100); if (!dark) applyBrightness() }

    private fun evaluate() {
        if (forced != null) return
        val now = SystemClock.elapsedRealtime()
        val idle = now - lastTouch
        val idleDark = cfg.idleMin > 0 && idle > cfg.idleMin * 60_000L
        val hold = (if (cfg.idleMin > 0) cfg.idleMin else 1) * 60_000L
        val winDark = inWindow() && idle > hold
        val want = idleDark || winDark
        if (want && !dark) setDark(cfg.mode)
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
            else -> { overlay.visibility = View.VISIBLE; window(0.01f) }
        }
        // keep the window "on" so the dashboard (WebSocket) stays alive; the OS screen timeout must not lock it
        a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION") // SCREEN_BRIGHT_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP: still the way to light the display from an app
    private fun setAwake() {
        dark = false
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
