package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.MacAddress
import com.pcwake.app.domain.DeviceMonitor
import com.pcwake.app.net.MagicPacket
import com.pcwake.app.net.NetworkSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiagState { PENDING, RUNNING, PASS, WARN, FAIL, SKIPPED }

data class DiagItem(
    val key: String,
    val title: String,
    val state: DiagState = DiagState.PENDING,
    val detail: String? = null,
)

data class DiagnosticsUiState(
    val device: DeviceWithConnections? = null,
    val snapshot: NetworkSnapshot? = null,
    val packetHex: String? = null,
    val running: Boolean = false,
    val items: List<DiagItem> = defaultItems(),
) {
    companion object {
        fun defaultItems() = listOf(
            DiagItem("network", "Network and interface"),
            DiagItem("wol", "Wake-on-LAN packet"),
            DiagItem("agent", "Windows agent · TLS + authentication"),
            DiagItem("bridge", "Wake Bridge · TLS + authentication"),
            DiagItem("security", "Certificate pins and credentials"),
        )
    }
}

class DiagnosticsViewModel(
    private val deviceId: Long,
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val device = if (deviceId > 0) container.deviceRepository.get(deviceId) else null
            val packet = device?.device?.macAddress
                ?.let(MacAddress::parseOrNull)
                ?.let { MagicPacket.hexDump(MagicPacket.build(it)) }
            _state.update {
                it.copy(
                    device = device,
                    snapshot = container.networkInfo.snapshot(),
                    packetHex = packet,
                )
            }
            runAll()
        }
    }

    fun refreshSnapshot() {
        _state.update { it.copy(snapshot = container.networkInfo.snapshot()) }
    }

    fun runAll() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update {
                it.copy(
                    running = true,
                    items = DiagnosticsUiState.defaultItems().map { item -> item.copy(state = DiagState.PENDING) },
                )
            }
            val device = _state.value.device
            val snapshot = container.networkInfo.snapshot()
            _state.update { it.copy(snapshot = snapshot) }

            // 1. Network
            update("network", DiagState.RUNNING, null)
            val networkDetail = buildString {
                append("${snapshot.transport}")
                snapshot.interfaceName?.let { append(" · $it") }
                snapshot.localIpv4?.let { append(" · $it") } ?: append(" · no IPv4 address")
                snapshot.prefixLength?.let { append("/$it") }
                snapshot.broadcast?.let { append(" · broadcast $it") }
                if (snapshot.isVpn) append(" · VPN active")
                if (snapshot.metered) append(" · metered network")
            }
            update(
                "network",
                if (snapshot.localIpv4 != null) DiagState.PASS else DiagState.WARN,
                networkDetail,
            )

            // 2. Real Wake-on-LAN test (sends one packet; not delivery confirmation)
            if (device == null) {
                update("wol", DiagState.SKIPPED, "Open diagnostics from a PC profile to test WoL")
            } else {
                update("wol", DiagState.RUNNING, null)
                val result = container.powerCoordinator.testWake(
                    device,
                    device.device.broadcastAddress,
                    device.device.port,
                )
                result.fold(
                    onSuccess = { r ->
                        update(
                            "wol",
                            DiagState.PASS,
                            "Sent ${r.datagrams}× ${r.packetBytes}-byte magic packet to " +
                                "${r.broadcast}:${r.port} via ${r.interfaceName ?: "default interface"}. " +
                                "This confirms the datagram was written, not that the PC booted.",
                        )
                    },
                    onFailure = { update("wol", DiagState.FAIL, ErrorText.humanize(it)) },
                )
            }

            // 3. Windows agent
            if (device == null || !device.supportsAgentControl) {
                update("agent", DiagState.SKIPPED, "No Windows agent linked to this PC")
            } else {
                update("agent", DiagState.RUNNING, null)
                val settings = container.settingsRepository.settings.first()
                val check = container.monitor.checkAgent(device, settings.timeoutMs.toLong())
                when (check) {
                    is DeviceMonitor.AgentCheck.Online -> {
                        container.deviceRepository.markSeen(device.device.id)
                        update(
                            "agent",
                            DiagState.PASS,
                            "Authenticated in ${check.latencyMs} ms · ${check.status.os} · agent " +
                                "${check.status.agentVersion} · ${check.status.adapters.size} adapter(s)",
                        )
                    }
                    is DeviceMonitor.AgentCheck.NotConnected ->
                        update("agent", DiagState.WARN, check.reason)
                    is DeviceMonitor.AgentCheck.Unreachable ->
                        update(
                            "agent",
                            DiagState.FAIL,
                            "${check.reason} This does not prove the PC is powered off.",
                        )
                }
            }

            // 4. Wake Bridge
            if (device == null || !device.supportsRemoteWake) {
                update("bridge", DiagState.SKIPPED, "No Wake Bridge linked to this PC")
            } else {
                update("bridge", DiagState.RUNNING, null)
                val settings = container.settingsRepository.settings.first()
                val check = container.monitor.checkBridge(device, settings.timeoutMs.toLong())
                when (check) {
                    is DeviceMonitor.BridgeCheck.Online -> update(
                        "bridge",
                        DiagState.PASS,
                        "${check.status.name} v${check.status.version} replied in ${check.latencyMs} ms · " +
                            "${check.status.targets.size} allow-listed target(s)",
                    )
                    is DeviceMonitor.BridgeCheck.NotConnected ->
                        update("bridge", DiagState.WARN, check.reason)
                    is DeviceMonitor.BridgeCheck.Unreachable ->
                        update("bridge", DiagState.FAIL, check.reason)
                }
            }

            // 5. Credentials / pins
            update("security", DiagState.RUNNING, null)
            val servers = runCatching { container.secretVault.listServers() }.getOrDefault(emptyList())
            if (servers.isEmpty()) {
                update("security", DiagState.WARN, "No servers are paired on this phone")
            } else {
                val relevant = buildList {
                    device?.agentEndpoint?.let { add(ConnectionMode.AGENT to it) }
                    device?.bridgeEndpoint?.let { add(ConnectionMode.BRIDGE to it) }
                }
                val missing = relevant.filterNot { (mode, endpoint) ->
                    servers.any { it.kind == mode && it.endpoint == endpoint }
                }
                val pinDetail = buildString {
                    append("${servers.size} paired server(s); tokens live in Android Keystore. ")
                    append("Pins: ")
                    append(
                        servers.joinToString(" · ") {
                            "${it.name} ${Formatting.shortFingerprint(it.fingerprint)}"
                        },
                    )
                }
                update(
                    "security",
                    if (missing.isEmpty()) DiagState.PASS else DiagState.WARN,
                    if (missing.isEmpty()) pinDetail else "Missing credential for ${missing.size} linked server(s). Re-pair.",
                )
            }
            _state.update { it.copy(running = false) }
        }
    }

    private fun update(key: String, state: DiagState, detail: String?) {
        _state.update { current ->
            current.copy(
                items = current.items.map {
                    if (it.key == key) it.copy(state = state, detail = detail) else it
                },
            )
        }
    }
}
