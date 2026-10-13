package pl.walldashboard.app

import android.content.Context

/** Everything the app remembers: add-on address, tablet key, pinned certificate, settings PIN, screen settings. */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("wall", Context.MODE_PRIVATE)

    private fun str(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) { p.edit().putString(k, v).apply() }

    /** e.g. https://192.168.5.4:8100 (no trailing slash) */
    var base: String
        get() = str("base")
        set(v) = put("base", v.trimEnd('/'))
    var key: String
        get() = str("key")
        set(v) = put("key", v)
    /** SHA-256 of the add-on's self-signed certificate, learned when pairing */
    var certPin: String
        get() = str("certPin")
        set(v) = put("certPin", v)
    var pin: String
        get() = str("pin")
        set(v) = put("pin", v)
    var tablet: String
        get() = str("tablet")
        set(v) = put("tablet", v)
    /** last screen settings from the add-on (used until the dashboard loads again) */
    var screenJson: String
        get() = str("screen")
        set(v) = put("screen", v)

    val paired: Boolean get() = base.isNotEmpty() && key.isNotEmpty()
    fun pageUrl(): String = "$base/?k=$key"
    fun host(): String = android.net.Uri.parse(base).host ?: ""
}
