package com.pcwake.app.net

import com.pcwake.app.core.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class CertificatePin(
    val fingerprint: String,
    val subject: String,
    val notAfter: Long,
)

/**
 * Creates OkHttp clients that trust exactly one pinned server certificate
 * (SHA-256 of the DER leaf certificate). This replaces CA/hostname validation:
 * identity is established by the pin learned during QR pairing.
 */
class PinnedHttpClientFactory {

    private val pinnedClients = ConcurrentHashMap<String, OkHttpClient>()

    fun pinnedClient(pin: String, timeoutMs: Long): OkHttpClient {
        val normalized = normalizePin(pin)
        require(Regex("^[0-9a-f]{64}$").matches(normalized)) { "Invalid certificate pin" }
        return pinnedClients.getOrPut("$normalized:$timeoutMs") {
            buildClient(PinnedTrustManager(normalized), timeoutMs)
        }
    }

    /**
     * Trust-on-first-use capture used only when the user typed an address
     * without a QR fingerprint. Never used for authenticated requests.
     */
    suspend fun capturePin(endpoint: String, timeoutMs: Long): CertificatePin = withContext(Dispatchers.IO) {
        require(Endpoints.isCanonical(endpoint)) { "Invalid HTTPS endpoint" }
        val trustAll = TrustAllManager
        val client = buildClient(trustAll, timeoutMs)
        val request = Request.Builder().url("$endpoint/health").get().build()
        client.newCall(request).execute().use { response ->
            val certificate = response.handshake?.peerCertificates?.firstOrNull() as? X509Certificate
                ?: throw CertificateException("The server did not present a TLS certificate")
            CertificatePin(
                fingerprint = sha256Hex(certificate.encoded),
                subject = certificate.subjectDN.name,
                notAfter = certificate.notAfter.time,
            )
        }
    }

    fun fingerprintOf(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    fun normalizePin(pin: String): String =
        pin.lowercase().replace(":", "").replace(" ", "").trim()

    private fun buildClient(trustManager: X509TrustManager, timeoutMs: Long): OkHttpClient {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // The pinned certificate is the identity anchor. Hostname checks add
            // nothing for self-signed LAN agents and are intentionally replaced by pinning.
            .hostnameVerifier { _, _ -> true }
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 2_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Client certificates are not accepted")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull()
                ?: throw CertificateException("Certificate pin mismatch: server sent no certificate")
            val actual = sha256Hex(leaf.encoded)
            if (!MessageDigest.isEqual(
                    actual.toByteArray(Charsets.US_ASCII),
                    expected.toByteArray(Charsets.US_ASCII),
                )
            ) {
                throw CertificateException(
                    "Certificate pin mismatch: expected $expected, received $actual",
                )
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private object TrustAllManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
