package com.pcwake.app.net

import com.pcwake.app.core.Validation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Serializable
private data class DiscoveryReply(
    val v: Int = 1,
    val kind: String = "",
    val name: String = "",
    val port: Int = 0,
    val paired: Boolean = false,
)

data class DiscoveredAgent(
    val name: String,
    val host: String,
    val port: Int,
    val paired: Boolean,
)

/**
 * LAN discovery using the documented UDP 47100 probe. Discovery is advisory:
 * a discovered agent still has to be paired (or already be paired) to control it.
 */
class DiscoveryClient {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun discover(timeout: Duration = 3.seconds): List<DiscoveredAgent> =
        withContext(Dispatchers.IO) {
            val socket = DatagramSocket()
            val deadline = TimeSource.Monotonic.markNow() + timeout
            val discovered = LinkedHashMap<String, DiscoveredAgent>()
            try {
                socket.broadcast = true
                socket.soTimeout = 300
                socket.reuseAddress = true
                val probe = PROBE.toByteArray(Charsets.US_ASCII)
                localBroadcasts().forEach { broadcast ->
                    runCatching {
                        socket.send(
                            DatagramPacket(
                                probe,
                                probe.size,
                                InetAddress.getByName(broadcast),
                                PORT,
                            ),
                        )
                    }
                }
                val buffer = ByteArray(2048)
                while (deadline.hasNotPassedNow()) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    val reply = runCatching { json.decodeFromString<DiscoveryReply>(text) }.getOrNull()
                        ?: continue
                    if (reply.v != 1 || reply.kind != "agent") continue
                    val host = (packet.address as? Inet4Address)?.hostAddress ?: continue
                    val port = reply.port.takeIf { it in 1..65535 } ?: Endpoints.AGENT_PORT
                    val key = "$host:$port"
                    discovered.putIfAbsent(
                        key,
                        DiscoveredAgent(
                            name = reply.name.ifBlank { host },
                            host = host,
                            port = port,
                            paired = reply.paired,
                        ),
                    )
                }
            } finally {
                socket.close()
            }
            discovered.values.sortedBy { it.name.lowercase() }
        }

    private fun localBroadcasts(): List<String> {
        val broadcasts = linkedSetOf("255.255.255.255")
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast?.hostAddress }
                .filter { Validation.isValidIpv4(it) }
                .forEach(broadcasts::add)
        }
        return broadcasts.toList()
    }

    private companion object {
        const val PROBE = "PCWAKE_DISCOVER_V1"
        const val PORT = 47100
    }
}
