package pl.walldashboard.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/** Hidden settings (hold the top-left corner for 3 s), protected by the PIN set in the add-on. */
class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs
    private var tries = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.pin.isNotEmpty()) askPin() else menu()
    }

    private fun askPin() {
        val col = Ui.page(this)
        col.addView(Ui.text(this, "Ustawienia panelu", 22f, true))
        col.addView(Ui.text(this, "Podaj PIN (ustawiasz go w add-onie: Tablety → Ekran i aplikacja).", 14f, color = "#A7B5A9"))
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
        }
        col.addView(pin)
        col.addView(Ui.button(this, "OK", primary = true) {
            if (pin.text.toString() == prefs.pin) menu()
            else {
                tries++
                pin.setText("")
                Toast.makeText(this, "Zły PIN", Toast.LENGTH_SHORT).show()
                if (tries >= 3) finish()
            }
        })
        col.addView(Ui.button(this, "Anuluj") { finish() })
    }

    private fun menu() {
        val col = Ui.page(this)
        val owner = Kiosk.isOwner(this)
        col.addView(Ui.text(this, "Wall Dashboard ${BuildConfig.VERSION_NAME}", 22f, true))
        col.addView(Ui.text(this, "Tablet: ${prefs.tablet.ifEmpty { "–" }}\nAdd-on: ${prefs.base}\nTryb kiosku: ${if (owner) "aktywny (właściciel urządzenia)" else "nieaktywny – patrz niżej"}", 14f, color = "#A7B5A9"))
        col.addView(Ui.button(this, "Wróć do dashboardu", primary = true) { Kiosk.suspended = false; finish() })
        col.addView(Ui.button(this, "Przeładuj dashboard") { Kiosk.suspended = false; MainActivity.reloadRequested = true; finish() })
        col.addView(Ui.button(this, "Uruchom aplikację ponownie") { Kiosk.suspended = false; Kiosk.restart(this) })
        col.addView(Ui.button(this, "Wi-Fi") { leaveTo(Intent(Settings.ACTION_WIFI_SETTINGS)) })
        col.addView(Ui.button(this, "Ustawienia Androida") { leaveTo(Intent(Settings.ACTION_SETTINGS)) })
        col.addView(Ui.button(this, "Wybierz ekran główny (wyjście z kiosku)") { leaveTo(Intent(Settings.ACTION_HOME_SETTINGS)) })
        packageManager.getLaunchIntentForPackage("de.ozerov.fully")?.let { fully ->
            col.addView(Ui.button(this, "Otwórz Fully Kiosk") { leaveTo(fully) })
        }
        col.addView(Ui.button(this, "Sparuj ponownie / zmień adres add-onu") {
            AlertDialog.Builder(this)
                .setMessage("Panel zapomni klucz tabletu i pokaże nowy kod parowania.")
                .setPositiveButton("Sparuj ponownie") { _, _ ->
                    prefs.key = ""
                    prefs.certPin = ""
                    Kiosk.leave(this)
                    startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
                .setNegativeButton("Anuluj", null)
                .show()
        })
        col.addView(Ui.text(this, if (owner) "Blokada kiosku jest włączona. Przyciski Wi-Fi / Android / ekran główny wyłączają ją do „Wróć do dashboardu” lub restartu."
        else "Pełny tryb kiosku (bez paska systemowego, bez ekranu blokady, prawdziwe wyłączanie ekranu) – raz z komputera:\n\nadb shell dpm set-device-owner pl.walldashboard.app/.AdminReceiver\n\nNa panelu nie może być kont Google ani innego właściciela urządzenia (np. Fully).", 13f, color = "#A7B5A9"))
    }

    private fun leaveTo(i: Intent) {
        Kiosk.leave(this)
        try { startActivity(i) } catch (e: Exception) { Toast.makeText(this, e.message, Toast.LENGTH_LONG).show() }
    }
}
