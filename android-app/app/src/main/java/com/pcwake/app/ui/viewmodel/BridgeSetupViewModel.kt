package com.pcwake.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pcwake.app.AppContainer
import com.pcwake.app.core.BridgeTarget
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.ErrorText
import com.pcwake.app.core.ServerSummary
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerCheck(
    val online: Boolean? = null,
    val detail: String? = null,
    val targets: List<BridgeTarget> = emptyList(),
)

data class BridgeSetupUiState(
    val servers: List<ServerSummary> = emptyList(),
    val checks: Map<String, ServerCheck> = emptyMap(),
    val busyEndpoint: String? = null,
)

class BridgeSetupViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BridgeSetupUiState())
    val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val servers = runCatching { container.secretVault.listServers() }.getOrDefault(emptyList())
            _state.update { it.copy(servers = servers) }
        }
    }

    fun test(server: ServerSummary) {
        if (server.kind != ConnectionMode.BRIDGE) return
        if (_state.value.busyEndpoint != null) return
        _state.update { it.copy(busyEndpoint = server.endpoint) }
        viewModelScope.launch {
            try {
                val credential = container.secretVault.get(ConnectionMode.BRIDGE, server.endpoint)
                    ?: error("Bridge is not paired on this phone")
                val timeout = container.settingsRepository.settings.first().timeoutMs.toLong()
                val status = container.bridgeApi.status(credential, timeout)
                _state.update {
                    it.copy(
                        busyEndpoint = null,
                        checks = it.checks + (
                            server.endpoint to ServerCheck(
                                online = true,
                                detail = "${status.name} v${status.version} · ${status.targets.size} target(s) · " +
                                    "server time ${com.pcwake.app.core.Formatting.date(status.serverTime)}",
                                targets = status.targets,
                            )
                            ),
                    )
                }
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        busyEndpoint = null,
                        checks = it.checks + (server.endpoint to ServerCheck(online = false, detail = ErrorText.humanize(t))),
                    )
                }
            }
        }
    }

    fun revoke(server: ServerSummary) {
        if (_state.value.busyEndpoint != null) return
        _state.update { it.copy(busyEndpoint = server.endpoint) }
        viewModelScope.launch {
            var remoteError: String? = null
            try {
                val credential = container.secretVault.get(server.kind, server.endpoint)
                if (credential != null) {
                    val timeout = container.settingsRepository.settings.first().timeoutMs.toLong()
                    container.bridgeApi.revoke(credential, timeout)
                }
            } catch (t: Throwable) {
                remoteError = ErrorText.humanize(t)
            }
            runCatching { container.secretVault.delete(server.kind, server.endpoint) }
            _state.update {
                it.copy(
                    busyEndpoint = null,
                    servers = it.servers.filterNot { s ->
                        s.kind == server.kind && s.endpoint == server.endpoint
                    },
                    checks = it.checks - server.endpoint,
                )
            }
            _events.emit(
                UiEvent(
                    if (remoteError == null) "Revoked ${server.name} on both the bridge and this phone."
                    else "Removed ${server.name} from this phone. Remote revocation failed: $remoteError",
                    isError = remoteError != null,
                ),
            )
        }
    }
}
