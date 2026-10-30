package com.pcwake.app.net

import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.Validation
import com.pcwake.app.core.WakeSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

object MagicPacket {
    const val PACKET_SIZE = 102

    /** 6 × 0xFF followed by the target MAC repeated 16 times. */
    fun build(mac: MacAddress): ByteArray {
        val packet = ByteArray(PACKET_SIZE)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        var offset = 6
        repeat(16) {
            mac.bytes.copyInto(packet, offset)
            offset += 6
        }
        return packet
    }

    fun hexDump(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it) }
}

data class WakeInterface(
    val name: String,
    val displayName: String,
    val ipv4: List<String>,
)

class WolSender {

    suspend fun send(
        broadcast: String,
        port: Int,
        mac: MacAddress,
        repeats: Int,
        intervalMs: Int,
        interfaceName: String? = null,
    ): Result<WakeSendResult> = withContext(Dispatchers.IO) {
        Validation.validateBroadcast(broadcast)?.let {
            return@withContext Result.failure(IllegalArgumentException(it))
        }
        if (!Validation.isValidPort(port)) {
            return@withContext Result.failure(IllegalArgumentException("UDP port must be 1..65535"))
        }
        val count = repeats.coerceIn(1, 20)
        val address = runCatching { InetAddress.getByName(broadcast.trim()) }.getOrElse {
            return@withContext Result.failure(IllegalArgumentException("Cannot resolve broadcast address"))
        }
        if (address !is Inet4Address) {
            return@withContext Result.failure(IllegalArgumentException("Wake-on-LAN needs an IPv4 broadcast address"))
        }
        val localAddress = interfaceName?.takeIf { it.isNotBlank() }?.let { name ->
            runCatching { NetworkInterface.getByName(name) }.getOrNull()
                ?.inetAddresses?.toList()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
        }
        if (!interfaceName.isNullOrBlank() && localAddress == null) {
            return@withContext Result.failure(IllegalArgumentException("Wake interface '$interfaceName' has no IPv4 address"))
        }

        val packet = MagicPacket.build(mac)
        val socket = DatagramSocket(null)
        var sent = 0
        try {
            socket.broadcast = true
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localAddress, 0))
            repeat(count) { index ->
                socket.send(DatagramPacket(packet, packet.size, address, port))
                sent++
                if (index < count - 1) delay(intervalMs.coerceIn(50, 5_000).toLong())
            }
            Result.success(
                WakeSendResult(
                    datagrams = sent,
                    packetBytes = packet.size,
                    port = port,
                    broadcast = broadcast.trim(),
                    interfaceName = interfaceName?.takeIf { it.isNotBlank() },
                ),
            )
        } catch (e: IOException) {
            Result.failure(
                IOException("Sent $sent of $count magic packets, then failed: ${e.message}", e),
            )
        } finally {
            socket.close()
        }
    }

    fun listInterfaces(): List<WakeInterface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .mapNotNull { nif ->
                val addresses = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                    .filter { Validation.isValidIpv4(it) }
                if (addresses.isEmpty()) null
                else WakeInterface(nif.name, nif.displayName ?: nif.name, addresses)
            }
    }.getOrDefault(emptyList())
}
