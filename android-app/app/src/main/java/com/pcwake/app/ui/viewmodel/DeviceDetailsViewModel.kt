package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.CommandRecord
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.PowerAction
import com.pcwake.app.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailsUiState(
    val device: DeviceWithConnections? = null,
    val records: List<CommandRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val check: AgentCheckUi = AgentCheckUi(DeviceStatus.CHECKING, "Checking…"),
    val bridgeDetail: String? = null,
    val bridgeOnline: Boolean? = null,
    val busy: String? = null,
) {
    val pendingCommand get() = check.agent?.pendingCommand
}

private data class DetailsLocalState(
    val check: AgentCheckUi = AgentCheckUi(DeviceStatus.CHECKING, "Checking…"),
    val bridgeDetail: String? = null,
    val bridgeOnline: Boolean? = null,
    val busy: String? = null,
)

class DeviceDetailsViewModel(
    private val deviceId: Long,
    private val container: AppContainer,
) : ViewModel() {
    private val local = MutableStateFlow(DetailsLocalState())
    private var refreshJob: Job? = null
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()
    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val deleted = _deleted.asSharedFlow()
    private val _duplicated = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val duplicated = _duplicated.asSharedFlow()

    val state = combine(
        container.deviceRepository.observeDevice(deviceId),
        container.historyRepository.observeForDevice(deviceId, 40),
        container.settingsRepository.settings,
        local,
    ) { device, records, settings, localState ->
        DetailsUiState(
            device = device,
            records = records,
            settings = settings,
            check = localState.check,
            bridgeDetail = localState.bridgeDetail,
            bridgeOnline = localState.bridgeOnline,
            busy = localState.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailsUiState())

    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            val device = container.deviceRepository.get(deviceId) ?: return@launch
            val settings = container.settingsRepository.settings.first()
            val check = container.monitor.checkAgent(device, settings.timeoutMs.toLong())
            val mapped = evaluateAgentCheck(device, check, container.historyRepository)
            if (mapped.status == DeviceStatus.ONLINE) {
                container.deviceRepository.markSeen(deviceId)
            }
            local.update { it.copy(check = mapped) }
            if (device.supportsRemoteWake) {
                val bridge = container.monitor.checkBridge(device, settings.timeoutMs.toLong())
                when (bridge) {
                    is com.pcwake.app.domain.DeviceMonitor.BridgeCheck.Online -> local.update {
                        it.copy(
                            bridgeOnline = true,
                            bridgeDetail = "Bridge ${bridge.status.name} reachable in ${bridge.latencyMs} ms · " +
                                "${bridge.status.targets.size} target(s)",
                        )
                    }
                    is com.pcwake.app.domain.DeviceMonitor.BridgeCheck.Unreachable -> local.update {
                        it.copy(bridgeOnline = false, bridgeDetail = bridge.reason)
                    }
                    is com.pcwake.app.domain.DeviceMonitor.BridgeCheck.NotConnected -> local.update {
                        it.copy(bridgeOnline = null, bridgeDetail = bridge.reason)
                    }
                }
            } else {
                local.update { it.copy(bridgeOnline = null, bridgeDetail = null) }
            }
        }
    }

    fun wake(viaBridge: Boolean) {
        val device = state.value.device ?: return
        if (local.value.busy != null) return
        local.update { it.copy(busy = "Sending wake packet…") }
        viewModelScope.launch {
            val result = if (viaBridge) {
                container.powerCoordinator.wakeRemote(device)
            } else {
                container.powerCoordinator.wakeLocal(device)
            }
            local.update { it.copy(busy = null) }
            result.fold(
                onSuccess = { outcome ->
                    val detail = if (outcome.viaBridge) {
                        "Wake Bridge accepted and sent ${outcome.datagrams}× magic packet."
                    } else {
                        "${outcome.datagrams}× magic packet sent to ${outcome.broadcast}:${outcome.port}."
                    }
                    emit(UiEvent("$detail Boot is not confirmed."))
                    if (device.supportsAgentControl) awaitOnline(device)
                },
                onFailure = { emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    fun requestPower(action: PowerAction, graceSeconds: Int) {
        val device = state.value.device ?: return
        if (local.value.busy != null) return
        local.update { it.copy(busy = "${action.label}…") }
        viewModelScope.launch {
            val result = container.powerCoordinator.power(device, action, graceSeconds)
            local.update { it.copy(busy = null) }
            result.fold(
                onSuccess = { command ->
                    emit(
                        UiEvent(
                            "${action.label} accepted by the agent. Windows will act in " +
                                "${command.graceSeconds}s — Windows shows its own visible warning.",
                        ),
                    )
                    delay((command.graceSeconds + 2) * 1_000L)
                    refresh()
                },
                onFailure = { emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    fun cancelPending() {
        val device = state.value.device ?: return
        if (local.value.busy != null) return
        local.update { it.copy(busy = "Cancelling…") }
        viewModelScope.launch {
            val result = container.powerCoordinator.cancelPending(device)
            local.update { it.copy(busy = null) }
            result.fold(
                onSuccess = { cancelled ->
                    emit(
                        UiEvent(
                            if (cancelled) "Pending shutdown/restart was cancelled."
                            else "There was no cancellable shutdown/restart.",
                        ),
                    )
                    refresh()
                },
                onFailure = { emit(UiEvent(ErrorText.humanize(it), isError = true)) },
            )
        }
    }

    fun duplicate() {
        viewModelScope.launch {
            val id = container.deviceRepository.duplicate(deviceId)
            if (id == null) emit(UiEvent("Could not duplicate this PC", isError = true))
            else _duplicated.emit(id)
        }
    }

    fun delete() {
        viewModelScope.launch {
            local.update { it.copy(busy = "Deleting…") }
            container.deviceRepository.delete(deviceId)
            local.update { it.copy(busy = null) }
            _deleted.emit(Unit)
        }
    }

    private fun awaitOnline(device: DeviceWithConnections) {
        viewModelScope.launch {
            val settings = container.settingsRepository.settings.first()
            repeat(18) {
                delay(5_000)
                val fresh = container.deviceRepository.get(deviceId) ?: return@launch
                if (!fresh.supportsAgentControl) return@launch
                val check = container.monitor.checkAgent(fresh, settings.timeoutMs.toLong())
                val mapped = evaluateAgentCheck(fresh, check, container.historyRepository)
                local.update { it.copy(check = mapped) }
                if (mapped.status == DeviceStatus.ONLINE) {
                    container.deviceRepository.markSeen(deviceId)
                    emit(UiEvent("${fresh.device.name} is online — the Windows agent replied."))
                    return@launch
                }
            }
        }
    }

    private fun emit(event: UiEvent) {
        viewModelScope.launch { _events.emit(event) }
    }
}
