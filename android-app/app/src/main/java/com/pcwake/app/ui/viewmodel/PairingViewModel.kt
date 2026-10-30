package com.pcwake.app.ui.viewmodel

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.BuildConfig
import com.pcwake.app.core.AgentAdapter
import com.pcwake.app.core.BridgeTarget
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.MacAddress
import com.pcwake.app.core.Validation
import com.pcwake.app.data.AppSettings
import com.pcwake.app.data.DeviceRepository
import com.pcwake.app.net.AgentApi
import com.pcwake.app.net.BridgeApi
import com.pcwake.app.net.Endpoints
import com.pcwake.app.net.PairingPayloadParser
import com.pcwake.app.net.toModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairSuccessUi(
    val mode: ConnectionMode,
    val serverName: String,
    val endpoint: String,
    val fingerprint: String,
    val adapters: List<AgentAdapter>,
    val targets: List<BridgeTarget>,
)

data class PairingUiState(
    val mode: ConnectionMode = ConnectionMode.AGENT,
    val host: String = "",
    val port: String = Endpoints.AGENT_PORT.toString(),
    val code: String = "",
    val fingerprint: String = "",
    val payloadText: String = "",
    val deviceName: String = "",
    val pairing: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val showTofuWarning: Boolean = false,
    val result: PairSuccessUi? = null,
    val selectedIndex: Int = -1,
    val nickname: String = "",
    val broadcast: String = "",
    val udpPort: String = "9",
    val savingDevice: Boolean = false,
    val saveError: String? = null,
    val canScanQr: Boolean = true,
)

class PairingViewModel(
    private val container: AppContainer,
    initialMode: ConnectionMode,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PairingUiState(
            mode = initialMode,
            port = if (initialMode == ConnectionMode.BRIDGE) {
                Endpoints.BRIDGE_PORT.toString()
            } else {
                Endpoints.AGENT_PORT.toString()
            },
            deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { "Android phone" },
        ),
    )
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    private val _created = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val created = _created.asSharedFlow()

    private var settings = AppSettings()
    private var credential: com.pcwake.app.core.PairedServer? = null

    init {
        viewModelScope.launch {
            settings = container.settingsRepository.settings.first()
            _state.update {
                it.copy(
                    broadcast = settings.defaultBroadcast,
                    udpPort = settings.defaultPort.toString(),
                )
            }
        }
    }

    fun setMode(mode: ConnectionMode) {
        _state.update {
            it.copy(
                mode = mode,
                port = if (mode == ConnectionMode.BRIDGE) {
                    Endpoints.BRIDGE_PORT.toString()
                } else {
                    Endpoints.AGENT_PORT.toString()
                },
            )
        }
    }

    fun setHost(value: String) = _state.update { it.copy(host = value) }
    fun setPort(value: String) = _state.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
    fun setCode(value: String) = _state.update { it.copy(code = value.uppercase().take(9)) }
    fun setFingerprint(value: String) = _state.update {
        it.copy(fingerprint = value.lowercase().filter { c -> c in "0123456789abcdef" }.take(64))
    }
    fun setDeviceName(value: String) = _state.update { it.copy(deviceName = value.take(60)) }
    fun setNickname(value: String) = _state.update { it.copy(nickname = value.take(60)) }
    fun setBroadcast(value: String) = _state.update { it.copy(broadcast = value.take(30)) }
    fun setUdpPort(value: String) = _state.update { it.copy(udpPort = value.filter(Char::isDigit).take(5)) }

    fun applyPayload(raw: String) {
        _state.update { it.copy(payloadText = raw) }
        val parsed = PairingPayloadParser.parse(raw).getOrElse { error ->
            _state.update { it.copy(error = error.message) }
            return
        }
        val mode = parsed.mode ?: return
        val knownDefault = if (mode == ConnectionMode.BRIDGE) Endpoints.BRIDGE_PORT else Endpoints.AGENT_PORT
        _state.update {
            it.copy(
                mode = mode,
                host = parsed.host,
                port = parsed.port.toString(),
                code = parsed.code?.let { code ->
                    // Display grouped as XXXX-XXXX without modifying the wire value.
                    code.chunked(4).joinToString("-")
                } ?: it.code,
                fingerprint = parsed.fingerprint ?: "",
                error = null,
                info = parsed.name?.let { name -> "Payload loaded for $name (port $knownDefault is the default)" },
                showTofuWarning = false,
            )
        }
    }

    fun pairNow(allowTofu: Boolean) {
        val s = _state.value
        if (s.pairing || s.result != null) return
        val errors = mutableListOf<String>()
        Validation.validateHost(s.host)?.let(errors::add)
        val port = s.port.toIntOrNull()
        if (port == null || !Validation.isValidPort(port)) errors += "Port must be between 1 and 65535"
        if (Validation.validatePairingCode(s.code) != null) errors += "Enter the 8-character pairing code"
        if (Validation.validateName(s.deviceName) != null) errors += "Enter a device name"
        if (s.fingerprint.isNotBlank() && !Regex("^[0-9a-f]{64}$").matches(s.fingerprint)) {
            errors += "Fingerprint must be 64 hex characters"
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(error = errors.joinToString("\n")) }
            return
        }
        if (s.fingerprint.isBlank() && !allowTofu) {
            _state.update { it.copy(showTofuWarning = true) }
            return
        }
        val endpoint = Endpoints.build(s.host, port!!).getOrElse { error ->
            _state.update { it.copy(error = error.message) }
            return
        }
        val code = Validation.normalizePairingCode(s.code)
        _state.update { it.copy(pairing = true, error = null, showTofuWarning = false) }
        viewModelScope.launch {
            try {
                val timeout = settings.timeoutMs.toLong()
                val result = if (s.mode == ConnectionMode.AGENT) {
                    val pair: AgentApi.PairResult = container.agentApi.pair(
                        endpoint = endpoint,
                        expectedPin = s.fingerprint.takeIf { it.isNotBlank() },
                        code = code,
                        deviceName = s.deviceName,
                        deviceModel = Build.MODEL ?: "Android",
                        appVersion = BuildConfig.VERSION_NAME,
                        timeoutMs = timeout,
                    )
                    credential = pair.credential
                    PairSuccessUi(
                        mode = ConnectionMode.AGENT,
                        serverName = pair.server.name ?: pair.credential.name,
                        endpoint = endpoint,
                        fingerprint = pair.fingerprint,
                        adapters = pair.server.adapters.orEmpty()
                            .map { it.toModel() }
                            .filter { it.mac.isNotBlank() },
                        targets = emptyList(),
                    )
                } else {
                    val pair: BridgeApi.PairResult = container.bridgeApi.pair(
                        endpoint = endpoint,
                        expectedPin = s.fingerprint.takeIf { it.isNotBlank() },
                        code = code,
                        deviceName = s.deviceName,
                        deviceModel = Build.MODEL ?: "Android",
                        appVersion = BuildConfig.VERSION_NAME,
                        timeoutMs = timeout,
                    )
                    credential = pair.credential
                    PairSuccessUi(
                        mode = ConnectionMode.BRIDGE,
                        serverName = pair.server.name ?: pair.credential.name,
                        endpoint = endpoint,
                        fingerprint = pair.fingerprint,
                        adapters = emptyList(),
                        targets = pair.server.targets.orEmpty()
                            .map { it.toModel() }
                            .filter { it.mac.isNotBlank() },
                    )
                }
                container.secretVault.save(credential!!)
                val suggestedIndex = 0.takeIf {
                    val count = if (result.mode == ConnectionMode.AGENT) result.adapters.size
                    else result.targets.size
                    count > 0
                } ?: -1
                val chosenIp = result.adapters.getOrNull(suggestedIndex)?.ipv4?.firstOrNull()
                val suggestedBroadcast = when {
                    settings.defaultBroadcast.isNotBlank() -> settings.defaultBroadcast
                    chosenIp != null -> Validation.broadcastFor(chosenIp, 24).orEmpty()
                    else -> ""
                }
                _state.update {
                    it.copy(
                        pairing = false,
                        result = result,
                        selectedIndex = suggestedIndex,
                        nickname = if (result.mode == ConnectionMode.BRIDGE) {
                            result.targets.getOrNull(suggestedIndex)?.name ?: result.serverName
                        } else {
                            result.serverName
                        },
                        broadcast = suggestedBroadcast,
                        error = null,
                        info = "Paired successfully. Certificate pin: ${result.fingerprint.take(12)}…",
                    )
                }
                _events.emit(UiEvent("Paired with ${result.serverName}. Token stored in Android Keystore."))
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        pairing = false,
                        error = ErrorText.humanize(t),
                    )
                }
            }
        }
    }

    fun dismissTofuWarning() = _state.update { it.copy(showTofuWarning = false) }

    fun selectAdapter(index: Int) {
        val result = _state.value.result ?: return
        if (result.mode != ConnectionMode.AGENT) return
        val adapter = result.adapters.getOrNull(index) ?: return
        val ip = adapter.ipv4.firstOrNull()
        _state.update {
            it.copy(
                selectedIndex = index,
                nickname = if (it.nickname.isBlank()) result.serverName else it.nickname,
                broadcast = if (it.broadcast.isBlank() && ip != null) {
                    Validation.broadcastFor(ip, 24).orEmpty()
                } else {
                    it.broadcast
                },
            )
        }
    }

    fun selectTarget(index: Int) {
        val result = _state.value.result ?: return
        if (result.mode != ConnectionMode.BRIDGE) return
        val target = result.targets.getOrNull(index) ?: return
        _state.update {
            it.copy(
                selectedIndex = index,
                nickname = target.name,
            )
        }
    }

    fun saveDevice() {
        val s = _state.value
        val result = s.result ?: return
        if (s.savingDevice) return
        val macText = when {
            result.mode == ConnectionMode.AGENT -> result.adapters.getOrNull(s.selectedIndex)?.mac
            else -> result.targets.getOrNull(s.selectedIndex)?.mac
        }
        val mac = macText?.let(MacAddress::parseOrNull)
        if (mac == null) {
            _state.update { it.copy(saveError = "The server did not report a usable MAC. Add this PC manually.") }
            return
        }
        Validation.validateName(s.nickname)?.let {
            _state.update { st -> st.copy(saveError = it) }
            return
        }
        Validation.validateBroadcast(s.broadcast)?.let {
            _state.update { st -> st.copy(saveError = it) }
            return
        }
        val udpPort = s.udpPort.toIntOrNull()
        if (udpPort == null || !Validation.isValidPort(udpPort)) {
            _state.update { st -> st.copy(saveError = "UDP port must be 1..65535") }
            return
        }
        _state.update { it.copy(savingDevice = true, saveError = null) }
        viewModelScope.launch {
            try {
                val deviceId = container.deviceRepository.save(
                    existing = null,
                    input = DeviceRepository.DeviceInput(
                        name = s.nickname,
                        macAddress = mac.canonical,
                        localIp = result.adapters.getOrNull(s.selectedIndex)
                            ?.ipv4?.firstOrNull { Validation.isValidIpv4(it) },
                        broadcastAddress = s.broadcast,
                        port = udpPort,
                        agentEndpoint = if (result.mode == ConnectionMode.AGENT) result.endpoint else null,
                        bridgeEndpoint = if (result.mode == ConnectionMode.BRIDGE) result.endpoint else null,
                    ),
                )
                _state.update { it.copy(savingDevice = false) }
                _created.emit(deviceId)
            } catch (t: Throwable) {
                _state.update {
                    it.copy(savingDevice = false, saveError = t.message ?: "Could not save this PC")
                }
            }
        }
    }
}
