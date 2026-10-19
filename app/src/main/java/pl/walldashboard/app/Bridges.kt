package pl.walldashboard.app

import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * `window.fully` – the subset of Fully Kiosk's JavaScript interface the Wall Dashboard add-on uses,
 * so the dashboard works unchanged (doorbell sounds, wake, brightness, speech, call volume).
 */
class FullyBridge(private val m: MainActivity) {
    private fun ui(f: () -> Unit) = m.runOnUiThread(f)

    @JavascriptInterface fun turnScreenOn() = ui { m.screen.wake() }
    @JavascriptInterface fun stopScreensaver() = ui { m.screen.wake() }
    @JavascriptInterface fun turnScreenOff() = ui { m.screen.forceDark("off") }
    @JavascriptInterface fun turnScreenOff(@Suppress("UNUSED_PARAMETER") keepAlive: Boolean) = ui { m.screen.forceDark("off") }
    @JavascriptInterface fun startScreensaver() = ui { m.screen.forceDark("saver") }
    @JavascriptInterface fun bringToForeground() = ui {
        m.startActivity(Intent(m, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    /** Fully scale 0–255 */
    @JavascriptInterface fun setScreenBrightness(v: Double) = ui { m.screen.setBrightness(Math.round(v / 255.0 * 100).toInt()) }
    @JavascriptInterface fun playSound(url: String, loop: Boolean) = m.sound.play(url, loop)
    @JavascriptInterface fun stopSound() = m.sound.stop()
    @JavascriptInterface fun textToSpeech(text: String) = m.sound.speak(text)
    @JavascriptInterface fun setAudioVolume(level: Int, stream: Int) = m.sound.setVolume(level, stream)
    @JavascriptInterface fun getAudioVolume(stream: Int): Int = m.sound.getVolume(stream)
    @JavascriptInterface fun restartApp() = ui { Kiosk.restart(m) }
    @JavascriptInterface fun loadStartUrl() = ui { m.reloadDashboard() }
}

/** `window.wallApp` – app-specific: screen settings from the add-on and commands from HA. */
class AppBridge(private val m: MainActivity) {
    private fun ui(f: () -> Unit) = m.runOnUiThread(f)

    @JavascriptInterface fun setConfig(json: String) = ui { m.applyConfig(json) }
    @JavascriptInterface fun reload() = ui { m.reloadDashboard() }
    @JavascriptInterface fun restart() = ui { Kiosk.restart(m) }
    @JavascriptInterface fun screenOn() = ui { m.screen.wake() }
    @JavascriptInterface fun screenOff() = ui { m.screen.forceDark("off") }
    @JavascriptInterface fun saver() = ui { m.screen.forceDark("saver") }
    @JavascriptInterface fun setBrightness(pct: Int) = ui { m.screen.setBrightness(pct) }
    @JavascriptInterface fun version(): String = BuildConfig.VERSION_NAME
    @JavascriptInterface fun setSaverInfo(json: String) = ui { m.screen.setInfo(json) }
    /** SIP call started / ended in the dashboard: speaker + communication mode */
    @JavascriptInterface fun callAudio(on: Boolean) = ui { m.sound.callAudio(on) }
}
