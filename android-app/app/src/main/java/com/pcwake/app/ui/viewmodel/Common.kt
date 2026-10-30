package com.pcwake.app.ui.viewmodel

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pcwake.app.core.AgentStatus
import com.pcwake.app.core.CommandResult
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.HistoryType
import com.pcwake.app.data.HistoryRepository
import com.pcwake.app.domain.DeviceMonitor

data class UiEvent(val message: String, val isError: Boolean = false)

data class AgentCheckUi(
    val status: DeviceStatus = DeviceStatus.UNKNOWN,
    val detail: String? = null,
    val agent: AgentStatus? = null,
    val latencyMs: Long? = null,
)

class SimpleViewModelFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

@Composable
inline fun <reified T : ViewModel> appViewModel(
    key: String? = null,
    noinline create: () -> T,
): T = viewModel(key = key, factory = SimpleViewModelFactory(create))

/**
 * Converts a real agent check into an honest UI status. "Offline" is only used
 * when an accepted shutdown/sleep/restart just happened; otherwise an
 * unreachable agent is reported as Unknown, never as proof the PC is off.
 */
suspend fun evaluateAgentCheck(
    device: DeviceWithConnections,
    check: DeviceMonitor.AgentCheck,
    historyRepository: HistoryRepository,
): AgentCheckUi {
    if (!device.supportsAgentControl) {
        return AgentCheckUi(
            status = DeviceStatus.WAKE_ONLY,
            detail = if (device.supportsRemoteWake) {
                "Wake-only profile with Wake Bridge. PC health needs the Windows agent."
            } else {
                "Wake-only profile. Link the Windows agent to monitor health and control power."
            },
        )
    }
    return when (check) {
        is DeviceMonitor.AgentCheck.Online -> AgentCheckUi(
            status = DeviceStatus.ONLINE,
            detail = buildString {
                append("Agent replied in ${check.latencyMs} ms")
                if (check.status.os.isNotBlank()) append(" · ${check.status.os}")
            },
            agent = check.status,
            latencyMs = check.latencyMs,
        )
        is DeviceMonitor.AgentCheck.NotConnected -> AgentCheckUi(
            status = DeviceStatus.UNKNOWN,
            detail = check.reason,
        )
        is DeviceMonitor.AgentCheck.Unreachable -> {
            val latest = runCatching { historyRepository.latestForDevice(device.device.id) }.getOrNull()
            val age = latest?.let { System.currentTimeMillis() - it.timestamp } ?: Long.MAX_VALUE
            val recentOff = latest != null &&
                latest.result == CommandResult.ACCEPTED &&
                (
                    (latest.type in setOf(HistoryType.SHUTDOWN, HistoryType.SLEEP) &&
                        age in 0..10 * 60_000L) ||
                        (latest.type == HistoryType.RESTART && age in 0..2 * 60_000L)
                    )
            if (recentOff) {
                AgentCheckUi(
                    status = DeviceStatus.OFFLINE,
                    detail = "No agent response after ${latest!!.type.label.lowercase()} was accepted " +
                        "${Formatting.relativeTime(latest.timestamp)}. The PC is probably off or restarting.",
                )
            } else {
                AgentCheckUi(
                    status = DeviceStatus.UNKNOWN,
                    detail = "${check.reason} Unreachable does not necessarily mean the PC is powered off.",
                )
            }
        }
    }
}
