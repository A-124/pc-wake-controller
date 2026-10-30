package com.pcwake.app

import com.pcwake.app.data.BackupCrypto
import com.pcwake.app.data.BackupEnvelope
import com.pcwake.app.net.PairingPayloadParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class PairingAndBackupTest {

    @Test
    fun `parses a valid agent pairing payload`() {
        val payload = """
            {"v":1,"kind":"agent","name":"DESKTOP-7F3A","host":"192.168.1.50",
             "port":47101,"fingerprint":"${"ab".repeat(32)}","code":"K7Q2-M4XP"}
        """.trimIndent()
        val parsed = PairingPayloadParser.parse(payload).getOrThrow()
        assertEquals("agent", parsed.kind)
        assertEquals("192.168.1.50", parsed.host)
        assertEquals(47101, parsed.port)
        assertEquals("ab".repeat(32), parsed.fingerprint)
        assertNotNull(parsed.mode)
    }

    @Test
    fun `parses bridge payload without code or fingerprint`() {
        val parsed = PairingPayloadParser.parse(
            """{"v":1,"kind":"bridge","host":"bridge.example.net","port":8443}""",
        ).getOrThrow()
        assertEquals("bridge", parsed.kind)
        assertEquals(8443, parsed.port)
        assertTrue(parsed.code == null)
    }

    @Test
    fun `rejects invalid payloads`() {
        assertTrue(PairingPayloadParser.parse("not json").isFailure)
        assertTrue(PairingPayloadParser.parse("""{"v":2,"kind":"agent","host":"x","port":1}""").isFailure)
        assertTrue(PairingPayloadParser.parse("""{"v":1,"kind":"router","host":"x","port":1}""").isFailure)
        assertTrue(PairingPayloadParser.parse("""{"v":1,"kind":"agent","host":"x","port":0}""").isFailure)
        assertTrue(
            PairingPayloadParser.parse(
                """{"v":1,"kind":"agent","host":"x","port":1,"fingerprint":"zz"}""",
            ).isFailure,
        )
    }

    @Test
    fun `backup encryption round trips and rejects wrong passphrase`() {
        val plaintext = """{"devices":[{"name":"Gaming PC","macAddress":"AA:BB:CC:DD:EE:FF",
            |"broadcastAddress":"192.168.1.255","port":9}]}""".trimMargin().toByteArray()
        val passphrase = "correct horse battery".toCharArray()
        val envelope = BackupCrypto.encrypt(plaintext, passphrase)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(envelope, passphrase))

        val json = Json
        val serialized = json.encodeToString(BackupEnvelope.serializer(), envelope)
        val restored = json.decodeFromString(BackupEnvelope.serializer(), serialized)
        assertArrayEquals(plaintext, BackupCrypto.decrypt(restored, passphrase))

        val wrong = runCatching { BackupCrypto.decrypt(envelope, "wrong passphrase".toCharArray()) }
        assertTrue(wrong.isFailure)
        assertTrue(wrong.exceptionOrNull() is AEADBadTagException)
    }

    @Test
    fun `backup encryption uses fresh salt and iv`() {
        val plaintext = "hello".toByteArray()
        val pass = "passphrase-123".toCharArray()
        val a = BackupCrypto.encrypt(plaintext, pass)
        val b = BackupCrypto.encrypt(plaintext, pass)
        assertTrue(a.salt != b.salt)
        assertTrue(a.iv != b.iv)
        assertTrue(a.ciphertext != b.ciphertext)
    }

    @Test
    fun `backup requires a passphrase of at least eight characters`() {
        val result = runCatching { BackupCrypto.encrypt("x".toByteArray(), "short".toCharArray()) }
        assertTrue(result.isFailure)
    }
}
