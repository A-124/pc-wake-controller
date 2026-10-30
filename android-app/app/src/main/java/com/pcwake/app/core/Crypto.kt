package com.pcwake.app.core

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** HMAC canonical-request signing shared with the Windows Agent and Wake Bridge. */
object ApiSigner {
    const val HEADER_DEVICE = "X-PCWake-Device"
    const val HEADER_TIMESTAMP = "X-PCWake-Timestamp"
    const val HEADER_NONCE = "X-PCWake-Nonce"
    const val HEADER_SIGNATURE = "X-PCWake-Signature"

    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val secureRandom = SecureRandom()

    fun newNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun bodyHash(body: ByteArray): String = sha256Hex(body)

    fun canonical(
        method: String,
        pathWithQuery: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): String = buildString {
        append(method.uppercase())
        append('\n')
        append(pathWithQuery)
        append('\n')
        append(timestamp)
        append('\n')
        append(nonce)
        append('\n')
        append(bodyHash(body))
    }

    fun sign(token: String, canonical: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
