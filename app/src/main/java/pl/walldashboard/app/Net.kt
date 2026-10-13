package pl.walldashboard.app

import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * HTTP(S) to the add-on. Certificates signed by a public CA are checked normally; the add-on's own
 * self-signed certificate is accepted only if it matches the fingerprint remembered when pairing (trust on first use).
 */
object Net {
    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private val system: X509TrustManager by lazy {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class PinTrust(private val prefs: Prefs, private val learn: Boolean) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Brak certyfikatu")
            try { system.checkServerTrusted(chain, authType); return } catch (_: Exception) { }
            val fp = sha256(chain[0])
            val pin = prefs.certPin
            if (pin.isEmpty() && learn) { prefs.certPin = fp; return }
            if (pin != fp) throw CertificateException("Certyfikat add-onu jest inny niż przy parowaniu – sparuj aplikację ponownie")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    fun open(prefs: Prefs, url: String, learn: Boolean = false): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        if (c is HttpsURLConnection) {
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf<TrustManager>(PinTrust(prefs, learn)), SecureRandom())
            c.sslSocketFactory = ctx.socketFactory
            val def = HttpsURLConnection.getDefaultHostnameVerifier()
            // self-signed certificates for an IP address rarely name that IP – the pinned fingerprint is what counts
            c.hostnameVerifier = HostnameVerifier { host, session ->
                def.verify(host, session) || try {
                    val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                    leaf != null && prefs.certPin.isNotEmpty() && sha256(leaf) == prefs.certPin
                } catch (_: Exception) { false }
            }
        }
        c.connectTimeout = 6000
        c.readTimeout = 15000
        return c
    }

    class Response(val code: Int, val body: String)

    private fun read(c: HttpURLConnection): Response {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Response(code, body)
    }

    fun get(prefs: Prefs, url: String): Response = read(open(prefs, url))

    fun postJson(prefs: Prefs, url: String, json: String, learn: Boolean = false): Response {
        val c = open(prefs, url, learn)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return read(c)
    }
}
