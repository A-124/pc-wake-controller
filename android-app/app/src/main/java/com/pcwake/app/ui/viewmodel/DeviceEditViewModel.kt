package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Device
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.core.Validation
import com.pcwake.app.data.AppSettings
import com.pcwake.app.data.DeviceRepository
import com.pcwake.app.net.DiscoveredAgent
import com.pcwake.app.net.Endpoints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceEditUiState(
    val isNew: Boolean = true,
    val deviceId: Long = 0,
    val loading: Boolean = true,
    val name: String = "",
    val macAddress: String = "",
    val localIp: String = "",
    val broadcastAddress: String = "",
    val port: String = "9",
    val agentEndpoint: String? = null,
    val bridgeEndpoint: String? = null,
    val servers: List<ServerSummary> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val discovering: Boolean = false,
    val showDiscovery: Boolean = false,
    val discoveredAgents: List<DiscoveredAgent> = emptyList(),
) {
    val agentServers: List<ServerSummary> get() = servers.filter { it.kind == ConnectionMode.AGENT }
    val bridgeServers: List<ServerSummary> get() = servers.filter { it.kind == ConnectionMode.BRIDGE }
}

class DeviceEditViewModel(
    private val deviceId: Long,
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(DeviceEditUiState())
    val state = _state.asStateFlow()
    private val _saved = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()
    private var existing: Device? = null
    private var settings: AppSettings = AppSettings()

    init {
        viewModelScope.launch {
            settings = container.settingsRepository.settings.first()
            val servers = runCatching { container.secretVault.listServers() }.getOrDefault(emptyList())
            if (deviceId == 0L) {
                _state.value = DeviceEditUiState(
                    isNew = true,
                    loading = false,
                    port = settings.defaultPort.toString(),
                    broadcastAddress = settings.defaultBroadcast,
                    servers = servers,
                    agentEndpoint = servers.firstOrNull { it.kind == ConnectionMode.AGENT }?.endpoint,
                    bridgeEndpoint = servers.firstOrNull { it.kind == ConnectionMode.BRIDGE }?.endpoint,
                )
            } else {
                val loaded = container.deviceRepository.get(deviceId)
                existing = loaded?.device
                _state.value = DeviceEditUiState(
                    isNew = false,
                    deviceId = deviceId,
                    loading = loaded == null,
                    name = loaded?.device?.name.orEmpty(),
                    macAddress = loaded?.device?.macAddress.orEmpty(),
                    localIp = loaded?.device?.localIp.orEmpty(),
                    broadcastAddress = loaded?.device?.broadcastAddress.orEmpty(),
                    port = (loaded?.device?.port ?: settings.defaultPort).toString(),
                    agentEndpoint = loaded?.agentEndpoint,
                    bridgeEndpoint = loaded?.bridgeEndpoint,
                    servers = servers,
                )
            }
        }
    }

    fun update(block: DeviceEditUiState.() -> DeviceEditUiState) {
        _state.update { it.block().copy(errors = emptyMap()) }
    }

    fun detectLocalNetwork() {
        val snapshot = container.networkInfo.snapshot()
        val ip = snapshot.localIpv4
        if (ip == null) {
            _state.update {
                it.copy(
                    message = "No IPv4 LAN address detected. Connect to your home Wi-Fi.",
                    messageIsError = true,
                )
            }
            return
        }
        _state.update {
            it.copy(
                localIp = ip,
                broadcastAddress = snapshot.broadcast ?: it.broadcastAddress,
                message = if (snapshot.broadcast != null) {
                    "Detected ${snapshot.interfaceName ?: snapshot.transport}: $ip /${snapshot.prefixLength}, " +
                        "broadcast ${snapshot.broadcast}"
                } else {
                    "Detected $ip. Enter the LAN broadcast address manually."
                },
                messageIsError = false,
            )
        }
    }

    fun discoverAgents() {
        if (_state.value.discovering) return
        _state.update {
            it.copy(
                discovering = true,
                showDiscovery = true,
                discoveredAgents = emptyList(),
                message = null,
            )
        }
        viewModelScope.launch {
            val found = runCatching { container.discoveryClient.discover() }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    discovering = false,
                    discoveredAgents = found,
                    message = if (found.isEmpty()) {
                        "No agents replied on UDP 47100. Check that you are on the same LAN and discovery is enabled."
                    } else {
                        null
                    },
                    messageIsError = found.isEmpty(),
                )
            }
        }
    }

    fun dismissDiscovery() = _state.update { it.copy(showDiscovery = false) }

    fun selectDiscoveredAgent(agent: DiscoveredAgent) {
        val endpoint = Endpoints.build(agent.host, agent.port).getOrElse { error ->
            _state.update { it.copy(showDiscovery = false, message = error.message, messageIsError = true) }
            return
        }
        val paired = _state.value.servers.any {
            it.kind == ConnectionMode.AGENT && it.endpoint == endpoint
        }
        if (!paired) {
            _state.update {
                it.copy(
                    showDiscovery = false,
                    message = "Discovered ${agent.name} at ${agent.host}:${agent.port}, but this agent " +
                        "is not paired on this phone. Pair it first, then select it here.",
                    messageIsError = true,
                )
            }
            return
        }
        _state.update {
            it.copy(
                showDiscovery = false,
                agentEndpoint = endpoint,
                name = if (it.name.isBlank()) agent.name else it.name,
                message = "Selected ${agent.name} at ${agent.host}:${agent.port}",
                messageIsError = false,
            )
        }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val errors = linkedMapOf<String, String>()
        Validation.validateName(s.name)?.let { errors["name"] = it }
        val mac = MacAddress.parseOrNull(s.macAddress)
        if (mac == null) errors["mac"] = MacAddress.parse(s.macAddress).exceptionOrNull()?.message
            ?: "Invalid MAC address"
        Validation.validateLocalIp(s.localIp)?.let { errors["localIp"] = it }
        Validation.validateBroadcast(s.broadcastAddress)?.let { errors["broadcast"] = it }
        val port = s.port.trim().toIntOrNull()
        if (port == null || !Validation.isValidPort(port)) {
            errors["port"] = "UDP port must be between 1 and 65535"
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val agent = s.agentEndpoint?.takeIf { it.isNotBlank() }
        val bridge = s.bridgeEndpoint?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            if (agent != null && runCatching {
                    container.secretVault.has(ConnectionMode.AGENT, agent)
                }.getOrDefault(false).not()
            ) {
                _state.update {
                    it.copy(message = "That Windows agent is not paired. Pair it first.", messageIsError = true)
                }
                return@launch
            }
            if (bridge != null && runCatching {
                    container.secretVault.has(ConnectionMode.BRIDGE, bridge)
                }.getOrDefault(false).not()
            ) {
                _state.update {
                    it.copy(message = "That Wake Bridge is not paired. Pair it first.", messageIsError = true)
                }
                return@launch
            }
            _state.update { it.copy(saving = true) }
            runCatching {
                container.deviceRepository.save(
                    existing = existing,
                    input = DeviceRepository.DeviceInput(
                        name = s.name,
                        macAddress = mac!!.canonical,
                        localIp = s.localIp.takeIf(String::isNotBlank),
                        broadcastAddress = s.broadcastAddress,
                        port = port!!,
                        agentEndpoint = agent,
                        bridgeEndpoint = bridge,
                    ),
                )
            }.onSuccess { id ->
                _state.update { it.copy(saving = false) }
                _saved.emit(id)
            }.onFailure { t ->
                _state.update {
                    it.copy(
                        saving = false,
                        message = t.message ?: "Could not save this PC",
                        messageIsError = true,
                    )
                }
            }
        }
    }
}
