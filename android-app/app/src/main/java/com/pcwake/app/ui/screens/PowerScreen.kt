package com.pcwake.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.PowerAction
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ConfirmPowerDialog
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.StatusPill
import com.pcwake.app.ui.viewmodel.DeviceDetailsViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import kotlinx.coroutines.delay

@Composable
fun PowerScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "power-$deviceId") {
        DeviceDetailsViewModel(deviceId, container)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)
    var pendingAction by remember { mutableStateOf<PowerAction?>(null) }

    LaunchedEffect(state.settings.refreshSeconds, deviceId) {
        while (true) {
            viewModel.refresh()
            delay(state.settings.refreshSeconds * 1_000L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(title = "Power controls", onBack = onBack) },
    ) { padding ->
        val device = state.device
        if (device == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        var preferBridge by rememberSaveable(deviceId) {
            mutableStateOf(
                device.supportsRemoteWake && state.settings.defaultRoute == WakeRoutePref.BRIDGE,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PwCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.device.name, style = MaterialTheme.typography.headlineSmall)
                            Spacer(Modifier.height(3.dp))
                            Text(
                                state.check.detail ?: "Waiting for the agent…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(state.check.status)
                    }
                }
            }
            item {
                PowerControlsCard(
                    device = device,
                    state = state,
                    preferBridge = preferBridge,
                    onPreferBridge = { preferBridge = it },
                    onWakeLocal = { viewModel.wake(false) },
                    onWakeBridge = { viewModel.wake(true) },
                    onAction = { pendingAction = it },
                    onCancelPending = viewModel::cancelPending,
                )
            }
            item {
                PwCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Shield,
                            null,
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("What the agent can execute", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Only three fixed actions: shutdown, restart and sleep. There is no remote " +
                            "shell or arbitrary command execution. A 15-second native Windows warning " +
                            "(3 seconds for sleep) is shown and can be cancelled from the tray or this app " +
                            "while the command is still pending.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                InfoBanner(
                    "A power command is reported as accepted only after the signed agent replies. " +
                        "PC health is never inferred from an ICMP ping.",
                )
            }
        }
    }

    pendingAction?.let { action ->
        if (state.device?.supportsAgentControl == true) {
            ConfirmPowerDialog(
                action = action,
                deviceName = state.device!!.device.name,
                graceSeconds = if (action == PowerAction.SLEEP) 3 else 15,
                onConfirm = {
                    viewModel.requestPower(action, if (action == PowerAction.SLEEP) 3 else 15)
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
        } else {
            pendingAction = null
        }
    }
}
