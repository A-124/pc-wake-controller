package com.pcwake.app.net

import com.pcwake.app.core.AgentAdapter
import com.pcwake.app.core.AgentCommand
import com.pcwake.app.core.AgentStatus
import com.pcwake.app.core.BridgeStatus
import com.pcwake.app.core.BridgeTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PairRequestDto(
    val code: String,
    val deviceName: String,
    val deviceModel: String,
    val appVersion: String,
)

@Serializable
data class PairResponseDto(
    val ok: Boolean = false,
    val error: String? = null,
    val deviceId: String? = null,
    val token: String? = null,
    val server: ServerInfoDto? = null,
)

@Serializable
data class ServerInfoDto(
    val kind: String? = null,
    val name: String? = null,
    val version: String? = null,
    val agentVersion: String? = null,
    val hostName: String? = null,
    val adapters: List<AdapterDto>? = null,
    val targets: List<TargetDto>? = null,
)

@Serializable
data class AdapterDto(
    val name: String = "Ethernet",
    val mac: String = "",
    val ipv4: List<String> = emptyList(),
    val ipv6: List<String> = emptyList(),
)

@Serializable
data class TargetDto(
    val id: String = "",
    val name: String = "",
    val mac: String = "",
    val broadcast: String? = null,
    val port: Int? = null,
)

@Serializable
data class ApiErrorDto(val ok: Boolean = false, val error: String = "unknown_error")

@Serializable
data class CommandDto(
    val id: String = "",
    val action: String = "",
    val requestedAt: Long = 0,
    val executeAt: Long = 0,
    val status: String = "",
    val graceSeconds: Int = 0,
    val error: String? = null,
)

@Serializable
data class AgentStatusDto(
    val ok: Boolean = false,
    val error: String? = null,
    val name: String? = null,
    val hostName: String? = null,
    val os: String? = null,
    val agentVersion: String? = null,
    val online: Boolean = false,
    val uptimeSeconds: Long? = null,
    val adapters: List<AdapterDto>? = null,
    val capabilities: List<String>? = null,
    val pendingCommand: CommandDto? = null,
    val lastCommand: CommandDto? = null,
)

@Serializable
data class PowerRequestDto(
    val graceSeconds: Int,
    val reason: String? = null,
)

@Serializable
data class PowerResponseDto(
    val ok: Boolean = false,
    val error: String? = null,
    val command: CommandDto? = null,
    val cancelled: Boolean? = null,
)

@Serializable
data class BridgeStatusDto(
    val ok: Boolean = false,
    val error: String? = null,
    val name: String? = null,
    val kind: String? = null,
    val version: String? = null,
    val online: Boolean = false,
    val time: Long? = null,
    val targets: List<TargetDto>? = null,
)

@Serializable
data class WakeRequestDto(
    val mac: String,
    val repeats: Int,
)

@Serializable
data class WakeResponseDto(
    val ok: Boolean = false,
    val error: String? = null,
    val target: String? = null,
    val sent: Int = 0,
    val port: Int? = null,
    val broadcast: String? = null,
    val at: Long? = null,
)

@Serializable
data class RevokeResponseDto(
    val ok: Boolean = false,
    val error: String? = null,
)

@Serializable
data class EmptyBody(val unused: Boolean = false)

fun AdapterDto.toModel(): AgentAdapter = AgentAdapter(name, mac, ipv4)
fun TargetDto.toModel(): BridgeTarget = BridgeTarget(id, name, mac, broadcast, port)
fun CommandDto.toModel(): AgentCommand =
    AgentCommand(id, action, requestedAt, executeAt, status, graceSeconds, error)

fun AgentStatusDto.toModel(): AgentStatus = AgentStatus(
    name = name ?: "Windows PC",
    hostName = hostName ?: "",
    os = os ?: "Windows",
    agentVersion = agentVersion ?: "",
    online = online,
    uptimeSeconds = uptimeSeconds ?: 0,
    adapters = adapters.orEmpty().map { it.toModel() },
    capabilities = capabilities.orEmpty(),
    pendingCommand = pendingCommand?.toModel(),
    lastCommand = lastCommand?.toModel(),
)

fun BridgeStatusDto.toModel(): BridgeStatus = BridgeStatus(
    name = name ?: "Wake Bridge",
    version = version ?: "",
    online = online,
    serverTime = time ?: 0,
    targets = targets.orEmpty().map { it.toModel() },
)
