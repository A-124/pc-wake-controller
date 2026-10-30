package com.pcwake.app

import com.pcwake.app.core.ApiSigner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Normative vectors from documentation/PROTOCOL.md §8. All three components
 * (Android, Windows agent, Wake Bridge) assert these exact values.
 */
class ProtocolVectorsTest {

    private val token = "test-token-0123456789"
    private val timestamp = "1720000000"
    private val nonce = "AAECAwQFBgcICQoLDA0ODw"

    @Test
    fun `POST power shutdown signature matches protocol vector`() {
        val body = "{\"graceSeconds\":15}".toByteArray(Charsets.UTF_8)
        val canonical = ApiSigner.canonical(
            method = "POST",
            pathWithQuery = "/api/v1/power/shutdown",
            timestamp = timestamp,
            nonce = nonce,
            body = body,
        )
        assertEquals(
            "POST\n/api/v1/power/shutdown\n$timestamp\n$nonce\n" +
                "41d72c15ac4723b83324e5ff7a275bc1a6bbe34fa3de3a7c27d249c1b97eacb5",
            canonical,
        )
        assertEquals("qEPDkh0eZIiTzT1ZGTXR4z398xh3XNG3iL6buuhenkQ", ApiSigner.sign(token, canonical))
    }

    @Test
    fun `GET status signature matches protocol vector`() {
        val canonical = ApiSigner.canonical(
            method = "GET",
            pathWithQuery = "/api/v1/status",
            timestamp = timestamp,
            nonce = nonce,
            body = ByteArray(0),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApiSigner.bodyHash(ByteArray(0)),
        )
        assertEquals("6S_CySELEsBTeWGBaBgzKQqWjAkz3CMQTwxPMNKyVUI", ApiSigner.sign(token, canonical))
    }

    @Test
    fun `tampered path produces a different signature`() {
        val canonical = ApiSigner.canonical(
            "GET", "/api/v1/status", timestamp, nonce, ByteArray(0),
        )
        val tampered = ApiSigner.canonical(
            "GET", "/api/v1/power/shutdown", timestamp, nonce, ByteArray(0),
        )
        assertEquals(false, ApiSigner.constantTimeEquals(
            ApiSigner.sign(token, canonical),
            ApiSigner.sign(token, tampered),
        ))
    }

    @Test
    fun `nonce is base64url and unique`() {
        val a = ApiSigner.newNonce()
        val b = ApiSigner.newNonce()
        assertEquals(22, a.length)
        assertEquals(false, a.contains("="))
        assertEquals(false, a == b)
    }
}
