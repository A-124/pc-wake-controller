package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.CommandRecord
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.PowerAction
import com.pcwake.app.core.ThemeMode
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val devices: List<DeviceWithConnections> = emptyList(),
    val recent: List<CommandRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val checks: Map<Long, AgentCheckUi> = emptyMap(),
    val busyDeviceId: Long? = null,
    val selectedId: Long? = null,
    val preferBridge: Boolean? = null,
) {
    val selectedDevice: DeviceWithConnections?
        get() = devices.firstOrNull { it.device.id == selectedId } ?: devices.firstOrNull()
}

private data class DashboardLocalState(
    val checks: Map<Long, AgentCheckUi> = emptyMap(),
    val busyDeviceId: Long? = null,
    val selectedId: Long? = null,
    val preferBridge: Boolean? = null,
)

class DashboardViewModel(private val container: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(DashboardLocalState())
    private var refreshJob: Job? = null
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val state = combine(
        container.deviceRepository.devices,
        container.historyRepository.observeRecent(6),
        container.settingsRepository.settings,
        local,
    ) { devices, recent, settings, localState ->
        DashboardUiState(
            devices = devices,
            recent = recent,
            settings = settings,
            checks = localState.checks,
            busyDeviceId = localState.busyDeviceId,
            selectedId = localState.selectedId,
            preferBridge = localState.preferBridge,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun selectDevice(id: Long) {
        local.update { it.copy(selectedId = id, preferBridge = null) }
    }

    fun setPreferBridge(value: Boolean) {
        local.update { it.copy(preferBridge = value) }
    }

    fun suggestBridge(device: DeviceWithConnections, settings: AppSettings): Boolean {
        val explicit = local.value.preferBridge
        if (explicit != null) return explicit && device.supportsRemoteWake
        if (!device.supportsRemoteWake) return false
        return settings.defaultRoute == WakeRoutePref.BRIDGE
    }

    fun refreshStatuses(force: Boolean = false) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val devices = container.deviceRepository.getAllWithConnections()
            val placeholders = linkedMapOf<Long, AgentCheckUi>()
            devices.forEach { device ->
                if (!device.supportsAgentControl) {
                    placeholders[device.device.id] = AgentCheckUi(
                        DeviceStatus.WAKE_ONLY,
                        if (device.supportsRemoteWake) {
                            "Wake-only profile with Wake Bridge. PC health needs the Windows agent."
                        } else {
                            "Wake-only profile. Link the Windows agent to control power."
                        },
                    )
                }
            }
            local.update { current ->
                val known = current.checks.filterKeys { key -> devices.any { it.device.id == key } }
                current.copy(checks = known + placeholders)
            }
            val settings = state.value.settings
            val results = coroutineScope {
                devices.filter { it.supportsAgentControl }.map { device ->
                    async {
                        val check = container.monitor.checkAgent(device, settings.timeoutMs.toLong())
                        val mapped = evaluateAgentCheck(device, check, container.historyRepository)
                        if (mapped.status == DeviceStatus.ONLINE) {
                            container.deviceRepository.markSeen(device.device.id)
                        }
                        device.device.id to mapped
                    }
                }.awaitAll()
            }.toMap()
            local.update { current ->
                current.copy(
                    checks = current.checks.filterKeys { id -> devices.any { it.device.id == id } } + results,
                )
            }
        }
    }

    fun wake(device: DeviceWithConnections, viaBridge: Boolean) {
        if (local.value.busyDeviceId != null) return
        local.update { it.copy(busyDeviceId = device.device.id) }
        viewModelScope.launch {
            val result = if (viaBridge) {
                container.powerCoordinator.wakeRemote(device)
            } else {
                container.powerCoordinator.wakeLocal(device)
            }
            local.update { it.copy(busyDeviceId = null) }
            result.fold(
                onSuccess = { outcome ->
                    val where = if (outcome.viaBridge) {
                        "via Wake Bridge: ${outcome.datagrams}× magic packet"
                    } else {
                        "${outcome.datagrams}× magic packet → ${outcome.broadcast}:${outcome.port}"
                    }
                    emit(UiEvent("Magic packet sent $where. Boot is not confirmed."))
                    if (device.supportsAgentControl) awaitOnline(device.device.id)
                },
                onFailure = { emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    fun requestPower(device: DeviceWithConnections, action: PowerAction, graceSeconds: Int) {
        if (local.value.busyDeviceId != null) return
        local.update { it.copy(busyDeviceId = device.device.id) }
        viewModelScope.launch {
            val result = container.powerCoordinator.power(device, action, graceSeconds)
            local.update { it.copy(busyDeviceId = null) }
            result.fold(
                onSuccess = { command ->
                    emit(
                        UiEvent(
                            "${action.label} accepted. Windows will act in ${command.graceSeconds}s; " +
                                "the PC control panel remains available until then.",
                        ),
                    )
                    delay((command.graceSeconds + 2) * 1_000L)
                    refreshStatuses(force = true)
                },
                onFailure = { emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    private fun awaitOnline(deviceId: Long) {
        viewModelScope.launch {
            val settings = state.value.settings
            repeat(18) {
                delay(5_000)
                val device = container.deviceRepository.get(deviceId) ?: return@launch
                if (!device.supportsAgentControl) return@launch
                val check = container.monitor.checkAgent(device, settings.timeoutMs.toLong())
                val mapped = evaluateAgentCheck(device, check, container.historyRepository)
                local.update { it.copy(checks = it.checks + (deviceId to mapped)) }
                if (mapped.status == DeviceStatus.ONLINE) {
                    container.deviceRepository.markSeen(deviceId)
                    emit(UiEvent("${device.device.name} is online — the Windows agent replied."))
                    return@launch
                }
            }
        }
    }

    private fun emit(event: UiEvent) {
        viewModelScope.launch { _events.emit(event) }
    }
}
