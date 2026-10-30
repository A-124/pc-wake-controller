package com.pcwake.app.core

import java.security.MessageDigest

/** Parsed MAC address. Always unicast, 6 bytes. */
class MacAddress private constructor(
    val canonical: String,
    val bytes: ByteArray,
) {
    val hex: String get() = bytes.joinToString("") { "%02X".format(it) }

    override fun equals(other: Any?): Boolean =
        other is MacAddress && canonical == other.canonical

    override fun hashCode(): Int = canonical.hashCode()

    override fun toString(): String = canonical

    companion object {
        fun parse(input: String): Result<MacAddress> {
            val compact = input.trim()
                .replace(":", "")
                .replace("-", "")
                .replace(".", "")
                .replace(" ", "")
            if (compact.length != 12 || compact.any { it.digitToIntOrNull(16) == null }) {
                return Result.failure(IllegalArgumentException("Enter 12 hexadecimal digits, e.g. AA:BB:CC:DD:EE:FF"))
            }
            val bytes = ByteArray(6) { i -> compact.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            if (bytes.all { it == 0.toByte() }) {
                return Result.failure(IllegalArgumentException("MAC address cannot be all zeros"))
            }
            if (bytes[0].toInt() and 0x01 != 0) {
                return Result.failure(IllegalArgumentException("This is a multicast/broadcast MAC. Use the adapter's own unicast MAC."))
            }
            val canonical = bytes.joinToString(":") { "%02X".format(it) }
            return Result.success(MacAddress(canonical, bytes))
        }

        fun parseOrNull(input: String): MacAddress? = parse(input).getOrNull()
    }
}

enum class ConnectionMode(val wire: String, val label: String) {
    AGENT("agent", "Windows agent"),
    BRIDGE("bridge", "Wake Bridge"),
    ;

    companion object {
        fun fromWire(value: String?): ConnectionMode? = entries.firstOrNull { it.wire == value }
    }
}

enum class PowerAction(
    val wire: String,
    val label: String,
    val shortLabel: String,
    val pastTense: String,
) {
    SHUTDOWN("shutdown", "Shut down", "Shutdown", "shut down"),
    RESTART("restart", "Restart", "Restart", "restarted"),
    SLEEP("sleep", "Sleep", "Sleep", "put to sleep"),
    CANCEL("cancel", "Cancel pending action", "Cancel", "cancelled"),
}

enum class HistoryType(val label: String) {
    LOCAL_WAKE("Local wake"),
    BRIDGE_WAKE("Remote wake"),
    SHUTDOWN("Shutdown"),
    RESTART("Restart"),
    SLEEP("Sleep"),
    CANCEL("Cancel"),
    PAIR("Pairing"),
    TEST("Diagnostic"),
}

enum class CommandResult(val label: String) {
    SENT("Sent"),
    ACCEPTED("Accepted"),
    INFO("Info"),
    FAILED("Failed"),
}

enum class DeviceStatus { ONLINE, OFFLINE, UNKNOWN, WAKE_ONLY, CHECKING }

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class WakeRoutePref { ASK, LOCAL, BRIDGE }

data class Device(
    val id: Long,
    val name: String,
    val macAddress: String,
    val localIp: String?,
    val broadcastAddress: String,
    val port: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSeenAt: Long?,
    val lastWakeAt: Long?,
)

data class DeviceConnection(
    val mode: ConnectionMode,
    val endpoint: String,
)

data class DeviceWithConnections(
    val device: Device,
    val connections: List<DeviceConnection>,
) {
    fun connection(mode: ConnectionMode): DeviceConnection? = connections.firstOrNull { it.mode == mode }
    val agentEndpoint: String? get() = connection(ConnectionMode.AGENT)?.endpoint
    val bridgeEndpoint: String? get() = connection(ConnectionMode.BRIDGE)?.endpoint
    val supportsAgentControl: Boolean get() = agentEndpoint != null
    val supportsRemoteWake: Boolean get() = bridgeEndpoint != null
}

data class CommandRecord(
    val id: Long,
    val deviceId: Long,
    val deviceName: String,
    val type: HistoryType,
    val timestamp: Long,
    val result: CommandResult,
    val errorMessage: String?,
    val detail: String?,
)

data class AgentCommand(
    val id: String,
    val action: String,
    val requestedAt: Long,
    val executeAt: Long,
    val status: String,
    val graceSeconds: Int,
    val error: String?,
)

data class AgentAdapter(
    val name: String,
    val mac: String,
    val ipv4: List<String>,
)

data class AgentStatus(
    val name: String,
    val hostName: String,
    val os: String,
    val agentVersion: String,
    val online: Boolean,
    val uptimeSeconds: Long,
    val adapters: List<AgentAdapter>,
    val capabilities: List<String>,
    val pendingCommand: AgentCommand?,
    val lastCommand: AgentCommand?,
)

data class BridgeTarget(
    val id: String,
    val name: String,
    val mac: String,
    val broadcast: String?,
    val port: Int?,
)

data class BridgeStatus(
    val name: String,
    val version: String,
    val online: Boolean,
    val serverTime: Long,
    val targets: List<BridgeTarget>,
)

data class PairedServer(
    val kind: ConnectionMode,
    val name: String,
    val endpoint: String,
    val deviceId: String,
    val pin: String,
    val token: String,
    val pairedAt: Long,
)

data class ServerSummary(
    val kind: ConnectionMode,
    val name: String,
    val endpoint: String,
    val pairedAt: Long,
    val fingerprint: String,
)

data class WakeSendResult(
    val datagrams: Int,
    val packetBytes: Int,
    val port: Int,
    val broadcast: String,
    val interfaceName: String?,
)

data class WakeOutcome(
    val viaBridge: Boolean,
    val targetName: String?,
    val datagrams: Int,
    val port: Int,
    val broadcast: String?,
)

data class PendingPowerResult(
    val command: AgentCommand,
)

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))
