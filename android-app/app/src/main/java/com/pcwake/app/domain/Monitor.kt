package com.pcwake.app.domain

import com.pcwake.app.core.AgentStatus
import com.pcwake.app.core.BridgeStatus
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.isCertProblem
import com.pcwake.app.data.SecretVault
import com.pcwake.app.net.AgentApi
import com.pcwake.app.net.BridgeApi
import android.os.SystemClock

class DeviceMonitor(
    private val agentApi: AgentApi,
    private val bridgeApi: BridgeApi,
    private val vault: SecretVault,
) {
    sealed interface AgentCheck {
        data class Online(val status: AgentStatus, val latencyMs: Long) : AgentCheck
        data class Unreachable(val reason: String, val certProblem: Boolean) : AgentCheck
        data class NotConnected(val reason: String) : AgentCheck
    }

    sealed interface BridgeCheck {
        data class Online(val status: BridgeStatus, val latencyMs: Long) : BridgeCheck
        data class Unreachable(val reason: String, val certProblem: Boolean) : BridgeCheck
        data class NotConnected(val reason: String) : BridgeCheck
    }

    suspend fun checkAgent(device: DeviceWithConnections, timeoutMs: Long): AgentCheck {
        val endpoint = device.agentEndpoint
            ?: return AgentCheck.NotConnected("No Windows agent is linked to this PC")
        val credential = runCatching { vault.get(ConnectionMode.AGENT, endpoint) }.getOrNull()
            ?: return AgentCheck.NotConnected("Windows agent is not paired on this phone")
        val started = SystemClock.elapsedRealtime()
        return try {
            val status = agentApi.status(credential, timeoutMs)
            AgentCheck.Online(status, SystemClock.elapsedRealtime() - started)
        } catch (t: Throwable) {
            AgentCheck.Unreachable(ErrorText.humanize(t), t.isCertProblem())
        }
    }

    suspend fun checkBridge(device: DeviceWithConnections, timeoutMs: Long): BridgeCheck {
        val endpoint = device.bridgeEndpoint
            ?: return BridgeCheck.NotConnected("No Wake Bridge is linked to this PC")
        val credential = runCatching { vault.get(ConnectionMode.BRIDGE, endpoint) }.getOrNull()
            ?: return BridgeCheck.NotConnected("Wake Bridge is not paired on this phone")
        val started = SystemClock.elapsedRealtime()
        return try {
            val status = bridgeApi.status(credential, timeoutMs)
            BridgeCheck.Online(status, SystemClock.elapsedRealtime() - started)
        } catch (t: Throwable) {
            BridgeCheck.Unreachable(ErrorText.humanize(t), t.isCertProblem())
        }
    }
}
