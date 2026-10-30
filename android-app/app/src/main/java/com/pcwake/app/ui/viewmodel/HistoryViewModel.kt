package com.pcwake.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.CommandRecord
import com.pcwake.app.core.DeviceWithConnections
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HistoryUiState(
    val all: List<CommandRecord> = emptyList(),
    val devices: List<DeviceWithConnections> = emptyList(),
    val filterDeviceId: Long? = null,
    val busy: Boolean = false,
) {
    val records: List<CommandRecord>
        get() = filterDeviceId?.let { id -> all.filter { it.deviceId == id } } ?: all
}

class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow<Long?>(null)
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val state = combine(
        container.historyRepository.observeRecent(500),
        container.deviceRepository.devices,
        filter,
    ) { records, devices, filterId ->
        HistoryUiState(all = records, devices = devices, filterDeviceId = filterId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun setFilter(deviceId: Long?) {
        filter.value = deviceId
    }

    fun clearHistory() {
        viewModelScope.launch {
            runCatching { container.historyRepository.clearAll() }
                .onSuccess { _events.emit(UiEvent("Command history cleared.")) }
                .onFailure { _events.emit(UiEvent("Could not clear history", isError = true)) }
        }
    }

    fun exportCsv(uri: Uri) {
        viewModelScope.launch {
            runCatching { container.backupManager.exportHistoryCsv(uri) }
                .onSuccess { count -> _events.emit(UiEvent("Exported $count command(s) to CSV.")) }
                .onFailure { _events.emit(UiEvent(it.message ?: "Export failed", isError = true)) }
        }
    }
}
