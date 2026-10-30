package com.pcwake.app.domain

import com.pcwake.app.core.ApiException
import com.pcwake.app.core.CommandResult
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.HistoryType
import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.NotPairedException
import com.pcwake.app.core.PowerAction
import com.pcwake.app.core.WakeOutcome
import com.pcwake.app.core.WakeSendResult
import com.pcwake.app.core.AgentCommand
import com.pcwake.app.data.DeviceRepository
import com.pcwake.app.data.HistoryRepository
import com.pcwake.app.data.SecretVault
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.net.AgentApi
import com.pcwake.app.net.BridgeApi
import com.pcwake.app.net.WolSender
import kotlinx.coroutines.flow.first

/**
 * Executes real power operations and records honest results. A Wake-on-LAN
 * packet is only ever reported as "sent"; it does not prove the PC booted.
 * Shutdown/restart/sleep are only reported when the Windows agent accepts them.
 */
class PowerCoordinator(
    private val deviceRepository: DeviceRepository,
    private val historyRepository: HistoryRepository,
    private val vault: SecretVault,
    private val wolSender: WolSender,
    private val agentApi: AgentApi,
    private val bridgeApi: BridgeApi,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun wakeLocal(device: DeviceWithConnections): Result<WakeOutcome> {
        val settings = settingsRepository.settings.first()
        val mac = MacAddress.parseOrNull(device.device.macAddress)
        if (mac == null) {
            val message = "Invalid MAC address on this PC profile"
            logFailure(device, HistoryType.LOCAL_WAKE, message)
            return Result.failure(IllegalArgumentException(message))
        }
        return try {
            val result = wolSender.send(
                broadcast = device.device.broadcastAddress,
                port = device.device.port,
                mac = mac,
                repeats = settings.retries,
                intervalMs = settings.retryIntervalMs,
                interfaceName = settings.wakeInterface.takeIf { it.isNotBlank() },
            ).getOrThrow()
            deviceRepository.markWake(device.device.id)
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.LOCAL_WAKE,
                result = CommandResult.SENT,
                detail = "${result.datagrams}× 102-byte magic packet → ${result.broadcast}:${result.port}" +
                    (result.interfaceName?.let { " via $it" } ?: "") +
                    " (boot not confirmed)",
            )
            Result.success(
                WakeOutcome(
                    viaBridge = false,
                    targetName = null,
                    datagrams = result.datagrams,
                    port = result.port,
                    broadcast = result.broadcast,
                ),
            )
        } catch (t: Throwable) {
            logFailure(device, HistoryType.LOCAL_WAKE, ErrorText.humanize(t))
            Result.failure(t)
        }
    }

    suspend fun wakeRemote(device: DeviceWithConnections): Result<WakeOutcome> {
        val settings = settingsRepository.settings.first()
        val endpoint = device.bridgeEndpoint
            ?: return Result.failure(NotPairedException("No Wake Bridge is linked to this PC"))
        val mac = MacAddress.parseOrNull(device.device.macAddress)
            ?: return Result.failure(IllegalArgumentException("Invalid MAC address on this PC profile"))
        val credential = runCatching { vault.get(ConnectionMode.BRIDGE, endpoint) }.getOrNull()
        if (credential == null) {
            val message = "Wake Bridge is not paired on this phone"
            logFailure(device, HistoryType.BRIDGE_WAKE, message)
            return Result.failure(NotPairedException(message))
        }
        return try {
            val result = bridgeApi.wake(
                credential = credential,
                mac = mac,
                repeats = settings.retries,
                timeoutMs = settings.timeoutMs.toLong(),
            )
            deviceRepository.markWake(device.device.id)
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.BRIDGE_WAKE,
                result = CommandResult.SENT,
                detail = "Bridge sent ${result.sent}× magic packet to ${result.broadcast ?: "LAN"}:${result.port}" +
                    " (boot not confirmed)",
            )
            Result.success(
                WakeOutcome(
                    viaBridge = true,
                    targetName = result.targetName ?: credential.name,
                    datagrams = result.sent,
                    port = result.port,
                    broadcast = result.broadcast,
                ),
            )
        } catch (t: Throwable) {
            logFailure(device, HistoryType.BRIDGE_WAKE, ErrorText.humanize(t))
            Result.failure(t)
        }
    }

    suspend fun testWake(
        device: DeviceWithConnections,
        broadcast: String,
        port: Int,
    ): Result<WakeSendResult> {
        val mac = MacAddress.parseOrNull(device.device.macAddress)
            ?: return Result.failure(IllegalArgumentException("Invalid MAC address"))
        val settings = settingsRepository.settings.first()
        return try {
            val result = wolSender.send(
                broadcast = broadcast,
                port = port,
                mac = mac,
                repeats = 1,
                intervalMs = settings.retryIntervalMs,
                interfaceName = settings.wakeInterface.takeIf { it.isNotBlank() },
            ).getOrThrow()
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.TEST,
                result = CommandResult.SENT,
                detail = "WoL diagnostic: 1 magic packet → $broadcast:$port (delivery not confirmed)",
            )
            Result.success(result)
        } catch (t: Throwable) {
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.TEST,
                result = CommandResult.FAILED,
                errorMessage = ErrorText.humanize(t),
            )
            Result.failure(t)
        }
    }

    suspend fun power(
        device: DeviceWithConnections,
        action: PowerAction,
        graceSeconds: Int,
    ): Result<AgentCommand> {
        require(action != PowerAction.CANCEL)
        val endpoint = device.agentEndpoint
            ?: return Result.failure(NotPairedException("This PC has no Windows agent connection"))
        val credential = runCatching { vault.get(ConnectionMode.AGENT, endpoint) }.getOrNull()
            ?: return Result.failure(NotPairedException("Windows agent is not paired on this phone"))
        val settings = settingsRepository.settings.first()
        val type = when (action) {
            PowerAction.SHUTDOWN -> HistoryType.SHUTDOWN
            PowerAction.RESTART -> HistoryType.RESTART
            PowerAction.SLEEP -> HistoryType.SLEEP
            PowerAction.CANCEL -> HistoryType.CANCEL
        }
        return try {
            val command = agentApi.power(
                credential = credential,
                action = action,
                graceSeconds = graceSeconds,
                reason = "PC Wake remote ${action.wire} (${device.device.name})",
                timeoutMs = settings.timeoutMs.toLong(),
            )
            historyRepository.log(
                deviceId = device.device.id,
                type = type,
                result = CommandResult.ACCEPTED,
                detail = "${action.label} accepted by agent; executes in ${command.graceSeconds}s " +
                    "[${command.id.take(8)}]",
            )
            Result.success(command)
        } catch (t: Throwable) {
            val message = when (t) {
                is ApiException -> ErrorText.humanize(t)
                else -> ErrorText.humanize(t)
            }
            historyRepository.log(
                deviceId = device.device.id,
                type = type,
                result = CommandResult.FAILED,
                errorMessage = message,
            )
            Result.failure(t)
        }
    }

    suspend fun cancelPending(device: DeviceWithConnections): Result<Boolean> {
        val endpoint = device.agentEndpoint
            ?: return Result.failure(NotPairedException("This PC has no Windows agent connection"))
        val credential = runCatching { vault.get(ConnectionMode.AGENT, endpoint) }.getOrNull()
            ?: return Result.failure(NotPairedException("Windows agent is not paired on this phone"))
        val settings = settingsRepository.settings.first()
        return try {
            val cancelled = agentApi.cancel(credential, settings.timeoutMs.toLong())
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.CANCEL,
                result = if (cancelled) CommandResult.ACCEPTED else CommandResult.INFO,
                detail = if (cancelled) "Pending shutdown/restart cancelled" else "No cancellable command",
            )
            Result.success(cancelled)
        } catch (t: Throwable) {
            historyRepository.log(
                deviceId = device.device.id,
                type = HistoryType.CANCEL,
                result = CommandResult.FAILED,
                errorMessage = ErrorText.humanize(t),
            )
            Result.failure(t)
        }
    }

    private suspend fun logFailure(device: DeviceWithConnections, type: HistoryType, message: String) {
        historyRepository.log(
            deviceId = device.device.id,
            type = type,
            result = CommandResult.FAILED,
            errorMessage = message,
        )
    }
}
