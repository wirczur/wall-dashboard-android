package pl.walldashboard.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors

/** Sounds, speech and volume for the Fully-compatible bridge (doorbell melodies, wash finished, TTS). */
class SoundPlayer(private val ctx: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var mp: MediaPlayer? = null
    @Volatile private var gen = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val ttsQueue = mutableListOf<String>()
    private val audio = ctx.getSystemService(AudioManager::class.java)

    fun play(url: String, loop: Boolean) {
        val my = ++gen
        io.execute {
            try {
                val f = fetch(url)
                main.post {
                    if (my != gen) return@post
                    stopPlayer()
                    val p = MediaPlayer()
                    p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    p.setDataSource(f.absolutePath)
                    p.isLooping = loop
                    p.setOnCompletionListener { done -> if (!loop) { done.release(); if (mp === done) mp = null } }
                    p.prepare()
                    p.start()
                    mp = p
                }
            } catch (e: Exception) { Log.w(TAG, "dźwięk $url: ${e.message}") }
        }
    }

    fun stop() { gen++; main.post { stopPlayer() } }

    private fun stopPlayer() {
        try { mp?.stop() } catch (_: Exception) { }
        try { mp?.release() } catch (_: Exception) { }
        mp = null
    }

    /** add-on sounds are small: cached for a day, so the doorbell plays instantly */
    private fun fetch(url: String): File {
        val dir = File(ctx.cacheDir, "snd").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val f = File(dir, name)
        if (f.exists() && f.length() > 0 && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return f
        val c = Net.open(prefs, url)
        if (c.responseCode >= 400) throw IllegalStateException("HTTP ${c.responseCode}")
        val tmp = File(dir, "$name.part")
        c.inputStream.use { inp -> tmp.outputStream().use { inp.copyTo(it) } }
        c.disconnect()
        tmp.renameTo(f)
        return f
    }

    fun speak(text: String) {
        main.post {
            if (tts == null) {
                tts = TextToSpeech(ctx) { status ->
                    ttsReady = status == TextToSpeech.SUCCESS
                    if (ttsReady) {
                        tts?.language = Locale("pl", "PL")
                        ttsQueue.forEach { say(it) }
                    }
                    ttsQueue.clear()
                }
            }
            if (ttsReady) say(text) else ttsQueue.add(text)
        }
    }

    private fun say(t: String) { tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "wd-" + System.nanoTime()) }

    /** Fully semantics: level 0–100, stream = Android stream number (0 voice call, 3 music) */
    fun setVolume(level: Int, stream: Int) {
        try {
            val max = audio.getStreamMaxVolume(stream)
            audio.setStreamVolume(stream, Math.round(level.coerceIn(0, 100) / 100f * max), 0)
        } catch (e: Exception) { Log.w(TAG, "głośność: ${e.message}") }
    }

    private var callOn = false
    private var savedMode = AudioManager.MODE_NORMAL

    /** SIP call in the WebView: communication mode with the sound on the built-in speaker
     *  (otherwise some panels route the call to a missing earpiece – very quiet); back to normal afterwards. */
    fun callAudio(on: Boolean) {
        if (on == callOn) return
        callOn = on
        try {
            if (on) {
                savedMode = audio.mode
                audio.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { audio.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audio.isSpeakerphoneOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
                else { @Suppress("DEPRECATION") audio.isSpeakerphoneOn = false }
                audio.mode = savedMode
            }
        } catch (e: Exception) { Log.w(TAG, "audio rozmowy: ${e.message}") }
    }

    fun getVolume(stream: Int): Int = try {
        val max = audio.getStreamMaxVolume(stream)
        if (max > 0) Math.round(audio.getStreamVolume(stream) * 100f / max) else 0
    } catch (_: Exception) { -1 }

    fun release() {
        stop()
        try { tts?.shutdown() } catch (_: Exception) { }
        io.shutdownNow()
    }

    companion object { const val TAG = "WallDashboard" }
}
