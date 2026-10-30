package com.pcwake.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.core.ThemeMode
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.data.AppSettings
import com.pcwake.app.net.WakeInterface
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class SettingsLocalState(
    val servers: List<ServerSummary> = emptyList(),
    val interfaces: List<WakeInterface> = emptyList(),
    val busy: String? = null,
)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val servers: List<ServerSummary> = emptyList(),
    val interfaces: List<WakeInterface> = emptyList(),
    val busy: String? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(SettingsLocalState())
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    val state = combine(container.settingsRepository.settings, local) { settings, l ->
        SettingsUiState(
            settings = settings,
            servers = l.servers,
            interfaces = l.interfaces,
            busy = l.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    init {
        refreshSecurity()
    }

    fun setTheme(mode: ThemeMode) = launchSetting { container.settingsRepository.setTheme(mode) }
    fun setRoute(route: WakeRoutePref) = launchSetting { container.settingsRepository.setDefaultRoute(route) }
    fun setDefaultPort(port: Int) = launchSetting { container.settingsRepository.setDefaultPort(port) }
    fun setDefaultBroadcast(value: String) = launchSetting { container.settingsRepository.setDefaultBroadcast(value) }
    fun setRetries(value: Int) = launchSetting { container.settingsRepository.setRetries(value) }
    fun setRetryInterval(value: Int) = launchSetting { container.settingsRepository.setRetryInterval(value) }
    fun setTimeout(value: Int) = launchSetting { container.settingsRepository.setTimeout(value) }
    fun setRefreshSeconds(value: Int) = launchSetting { container.settingsRepository.setRefreshSeconds(value) }
    fun setWakeInterface(name: String) = launchSetting { container.settingsRepository.setWakeInterface(name) }

    private fun launchSetting(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                _events.emit(UiEvent(it.message ?: "Could not save setting", isError = true))
            }
        }
    }

    fun refreshSecurity() {
        viewModelScope.launch {
            val servers = runCatching { container.secretVault.listServers() }.getOrDefault(emptyList())
            val interfaces = runCatching { container.wolSender.listInterfaces() }.getOrDefault(emptyList())
            local.update { it.copy(servers = servers, interfaces = interfaces) }
        }
    }

    fun revokeServer(server: ServerSummary) {
        if (local.value.busy != null) return
        local.update { it.copy(busy = server.name) }
        viewModelScope.launch {
            var remoteError: String? = null
            try {
                val credential = container.secretVault.get(server.kind, server.endpoint)
                if (credential != null) {
                    val timeout = container.settingsRepository.settings.first().timeoutMs.toLong()
                    when (server.kind) {
                        ConnectionMode.AGENT -> container.agentApi.revoke(credential, timeout)
                        ConnectionMode.BRIDGE -> container.bridgeApi.revoke(credential, timeout)
                    }
                }
            } catch (t: Throwable) {
                remoteError = ErrorText.humanize(t)
            }
            runCatching { container.secretVault.delete(server.kind, server.endpoint) }
            val servers = runCatching { container.secretVault.listServers() }.getOrDefault(emptyList())
            local.update { it.copy(busy = null, servers = servers) }
            _events.emit(
                UiEvent(
                    if (remoteError == null) {
                        "Revoked ${server.name}. Its token was removed here and on the server."
                    } else {
                        "Removed ${server.name} from this phone. The server could not be reached to revoke: $remoteError"
                    },
                    isError = remoteError != null,
                ),
            )
        }
    }

    fun exportConfig(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            local.update { it.copy(busy = "Exporting configuration…") }
            runCatching {
                container.backupManager.exportConfig(uri, passphrase.toCharArray())
            }.onSuccess { count ->
                _events.emit(UiEvent("Exported $count PC profile(s). Credentials are not included."))
            }.onFailure {
                _events.emit(UiEvent(it.message ?: "Export failed", isError = true))
            }
            local.update { it.copy(busy = null) }
        }
    }

    fun importConfig(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            local.update { it.copy(busy = "Importing configuration…") }
            runCatching {
                container.backupManager.importConfig(uri, passphrase.toCharArray())
            }.onSuccess { result ->
                _events.emit(
                    UiEvent(
                        "Imported ${result.imported} PC profile(s)" +
                            if (result.skipped > 0) "; skipped ${result.skipped} invalid entry(s)." else ".",
                    ),
                )
            }.onFailure {
                _events.emit(
                    UiEvent(
                        if (it is javax.crypto.AEADBadTagException) "Wrong passphrase or corrupted backup."
                        else it.message ?: "Import failed",
                        isError = true,
                    ),
                )
            }
            local.update { it.copy(busy = null) }
        }
    }
}
