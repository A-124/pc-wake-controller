package com.pcwake.app

import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.Validation
import com.pcwake.app.net.MagicPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeOnLanTest {

    @Test
    fun `magic packet has sync stream and sixteen MAC repetitions`() {
        val mac = MacAddress.parse("AA:BB:CC:DD:EE:FF").getOrThrow()
        val packet = MagicPacket.build(mac)
        assertEquals(102, packet.size)
        assertEquals(102, MagicPacket.PACKET_SIZE)
        repeat(6) { index ->
            assertEquals(0xFF.toByte(), packet[index])
        }
        val macBytes = byteArrayOf(
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        repeat(16) { repetition ->
            val slice = packet.copyOfRange(6 + repetition * 6, 6 + repetition * 6 + 6)
            assertArrayEquals(macBytes, slice)
        }
    }

    @Test
    fun `MAC parser accepts common formats and normalizes`() {
        val variants = listOf(
            "aa:bb:cc:dd:ee:ff",
            "AA-BB-CC-DD-EE-FF",
            "aabbccddeeff",
            "AABB.CCDD.EEFF",
            " aa:bb:cc:dd:ee:ff ",
        )
        variants.forEach { input ->
            val parsed = MacAddress.parse(input)
            assertTrue("expected valid: $input", parsed.isSuccess)
            assertEquals("AA:BB:CC:DD:EE:FF", parsed.getOrThrow().canonical)
        }
    }

    @Test
    fun `MAC parser rejects invalid multicast zero and short addresses`() {
        assertTrue(MacAddress.parse("01:00:5E:00:00:01").isFailure) // multicast
        assertTrue(MacAddress.parse("00:00:00:00:00:00").isFailure)
        assertTrue(MacAddress.parse("AA:BB:CC:DD:EE").isFailure)
        assertTrue(MacAddress.parse("GG:BB:CC:DD:EE:FF").isFailure)
        assertTrue(MacAddress.parse("").isFailure)
    }

    @Test
    fun `broadcast calculation respects prefix length`() {
        assertEquals("192.168.1.255", Validation.broadcastFor("192.168.1.50", 24))
        assertEquals("192.168.127.255", Validation.broadcastFor("192.168.100.7", 17))
        assertEquals("10.255.255.255", Validation.broadcastFor("10.4.5.6", 8))
        assertEquals("172.16.15.255", Validation.broadcastFor("172.16.9.3", 20))
        assertNull(Validation.broadcastFor("bad", 24))
    }

    @Test
    fun `broadcast and port validation reject unsafe values`() {
        assertNotNull(Validation.validateBroadcast(""))
        assertNotNull(Validation.validateBroadcast("999.1.1.1"))
        assertNotNull(Validation.validateBroadcast("0.0.0.0"))
        assertNotNull(Validation.validateBroadcast("224.0.0.1"))
        assertNull(Validation.validateBroadcast("192.168.1.255"))
        assertNull(Validation.validateBroadcast("255.255.255.255"))
        assertTrue(Validation.isValidPort(9))
        assertTrue(!Validation.isValidPort(0))
        assertTrue(!Validation.isValidPort(65536))
    }

    @Test
    fun `pairing code normalizes crockford ambiguities`() {
        assertEquals("K7Q2M4XP", Validation.normalizePairingCode("k7q2-m4xp"))
        assertEquals("K1Q2M4XP", Validation.normalizePairingCode("KIQ2 M4XP"))
        assertEquals("K0Q2M4XP", Validation.normalizePairingCode("KOQ2-M4XP"))
        assertEquals("KVQ2M4XP", Validation.normalizePairingCode("KUQ2-M4XP"))
        assertNull(Validation.validatePairingCode("K7Q2-M4XP"))
        assertNotNull(Validation.validatePairingCode("short"))
    }
}
