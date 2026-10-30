package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.Validation
import com.pcwake.app.data.AppSettings
import com.pcwake.app.data.DeviceRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WolConfigUiState(
    val loading: Boolean = true,
    val device: DeviceWithConnections? = null,
    val broadcast: String = "",
    val port: String = "9",
    val settings: AppSettings = AppSettings(),
    val testing: Boolean = false,
    val saving: Boolean = false,
    val errors: Map<String, String> = emptyMap(),
)

class WolConfigViewModel(
    private val deviceId: Long,
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(WolConfigUiState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    private val _saved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saved = _saved.asSharedFlow()

    init {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            val device = container.deviceRepository.get(deviceId)
            _state.value = WolConfigUiState(
                loading = false,
                device = device,
                broadcast = device?.device?.broadcastAddress.orEmpty(),
                port = (device?.device?.port ?: settings.defaultPort).toString(),
                settings = settings,
            )
        }
    }

    fun setBroadcast(value: String) = _state.update { it.copy(broadcast = value, errors = emptyMap()) }
    fun setPort(value: String) = _state.update {
        it.copy(port = value.filter(Char::isDigit).take(5), errors = emptyMap())
    }

    fun test() {
        val s = _state.value
        val device = s.device ?: return
        if (s.testing) return
        val broadcastError = Validation.validateBroadcast(s.broadcast)
        val port = s.port.toIntOrNull()
        val errors = buildMap {
            broadcastError?.let { put("broadcast", it) }
            if (port == null || !Validation.isValidPort(port)) put("port", "Port must be 1..65535")
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        _state.update { it.copy(testing = true) }
        viewModelScope.launch {
            val result = container.powerCoordinator.testWake(device, s.broadcast.trim(), port!!)
            _state.update { it.copy(testing = false) }
            result.fold(
                onSuccess = { r ->
                    _events.emit(
                        UiEvent(
                            "Test packet sent: ${r.datagrams}× ${r.packetBytes} bytes → " +
                                "${r.broadcast}:${r.port}. Delivery is not confirmed.",
                        ),
                    )
                },
                onFailure = { _events.emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    fun save() {
        val s = _state.value
        val device = s.device ?: return
        if (s.saving) return
        val errors = buildMap {
            Validation.validateBroadcast(s.broadcast)?.let { put("broadcast", it) }
            val port = s.port.toIntOrNull()
            if (port == null || !Validation.isValidPort(port)) put("port", "Port must be 1..65535")
        }
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            runCatching {
                container.deviceRepository.save(
                    existing = device.device,
                    input = DeviceRepository.DeviceInput(
                        name = device.device.name,
                        macAddress = device.device.macAddress,
                        localIp = device.device.localIp,
                        broadcastAddress = s.broadcast.trim(),
                        port = s.port.toInt(),
                        agentEndpoint = device.agentEndpoint,
                        bridgeEndpoint = device.bridgeEndpoint,
                    ),
                )
            }.onSuccess {
                _state.update { it.copy(saving = false) }
                _events.emit(UiEvent("Wake-on-LAN configuration saved."))
                _saved.emit(Unit)
            }.onFailure { t ->
                _state.update { it.copy(saving = false) }
                _events.emit(UiEvent(t.message ?: "Could not save configuration", isError = true))
            }
        }
    }
}
