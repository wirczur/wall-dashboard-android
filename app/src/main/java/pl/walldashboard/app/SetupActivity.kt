package pl.walldashboard.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * First start: type the add-on address once, the panel shows a 6-digit code,
 * confirm it in Home Assistant (Wall Dashboard → Tablety → parowanie). No camera / QR needed.
 */
class SetupActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var addr: EditText
    private lateinit var status: TextView
    private lateinit var codeView: TextView
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var token = ""
    private var base = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = Ui.page(this)
        col.addView(Ui.text(this, "Wall Dashboard", 24f, true))
        col.addView(Ui.text(this, "Adres add-onu w Home Assistant (port kiosku). Wystarczy IP – aplikacja sama sprawdzi HTTPS i HTTP, domyślny port 8100.", 14f, color = "#A7B5A9"))
        addr = EditText(this).apply {
            setText(prefs.base.ifEmpty { "192.168.5.4:8100" })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE)
            setSingleLine()
        }
        col.addView(addr)
        col.addView(Ui.button(this, "Połącz i pokaż kod", primary = true) { connect() })
        codeView = Ui.text(this, "", 56f, true).apply { gravity = Gravity.CENTER; visibility = View.GONE; letterSpacing = 0.12f }
        col.addView(codeView)
        status = Ui.text(this, "", 15f)
        col.addView(status)
        col.addView(Ui.button(this, "Wi-Fi") { open(Settings.ACTION_WIFI_SETTINGS) })
        col.addView(Ui.button(this, "Ustawienia Androida") { open(Settings.ACTION_SETTINGS) })
    }

    private fun open(action: String) { try { startActivity(Intent(action)) } catch (_: Exception) { } }

    private fun candidates(input: String): List<String> {
        val s = input.trim().trimEnd('/')
        if (s.startsWith("http://") || s.startsWith("https://")) return listOf(s)
        val hostPort = if (Regex(":\\d+").containsMatchIn(s.substringBefore('/'))) s else s.replaceFirst(Regex("^([^/]+)"), "$1:8100")
        return listOf("https://$hostPort", "http://$hostPort")
    }

    private fun connect() {
        val list = candidates(addr.text.toString())
        if (addr.text.isBlank()) return
        h.removeCallbacksAndMessages(null)
        codeView.visibility = View.GONE
        status.text = "Łączenie…"
        val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        io.execute {
            var last = ""
            prefs.certPin = ""
            for (b in list) {
                try {
                    val r = Net.postJson(prefs, "$b/api/pair/start", JSONObject().put("device", device).toString(), learn = b.startsWith("https"))
                    if (r.code == 200) {
                        val o = JSONObject(r.body)
                        runOnUiThread {
                            base = b
                            token = o.getString("token")
                            val c = o.getString("code")
                            codeView.text = "${c.substring(0, 3)} ${c.substring(3)}"
                            codeView.visibility = View.VISIBLE
                            status.text = "Połączono z $b\n\nW Home Assistant otwórz Wall Dashboard → Tablety i wpisz ten kod w „Parowanie”. Kod jest ważny 15 minut."
                            poll()
                        }
                        return@execute
                    }
                    last = "$b → " + (try { JSONObject(r.body).optString("error") } catch (_: Exception) { "" }).ifEmpty { "HTTP ${r.code} (to na pewno port kiosku add-onu?)" }
                } catch (e: Exception) {
                    last = "$b → ${e.message}"
                    if (!b.startsWith("https")) continue
                }
            }
            runOnUiThread { status.text = "Nie udało się połączyć.\n$last" }
        }
    }

    private fun poll() {
        h.postDelayed({
            io.execute {
                try {
                    val r = Net.get(prefs, "$base/api/pair/poll?token=$token")
                    val o = try { JSONObject(r.body) } catch (_: Exception) { JSONObject() }
                    when (o.optString("status")) {
                        "ok" -> runOnUiThread {
                            prefs.base = base
                            prefs.key = o.getString("key")
                            prefs.tablet = o.optString("tablet")
                            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                            finish()
                        }
                        "pending" -> runOnUiThread { poll() }
                        else -> runOnUiThread { codeView.visibility = View.GONE; status.text = "Kod wygasł – naciśnij „Połącz i pokaż kod” jeszcze raz." }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Czekam na potwierdzenie… (brak odpowiedzi: ${e.message})"; poll() }
                }
            }
        }, 2000)
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }
}
