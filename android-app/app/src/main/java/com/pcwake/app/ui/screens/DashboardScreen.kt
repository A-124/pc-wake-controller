package com.pcwake.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.PowerAction
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.ui.components.ConfirmPowerDialog
import com.pcwake.app.ui.components.DeviceCard
import com.pcwake.app.ui.components.EmptyStateCard
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.HistoryRow
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.PowerCircleButton
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.QuickAction
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.components.StatusPill
import com.pcwake.app.ui.components.statusColor
import com.pcwake.app.ui.components.statusLabel
import com.pcwake.app.ui.viewmodel.DashboardUiState
import com.pcwake.app.ui.viewmodel.DashboardViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    container: AppContainer,
    onAddPc: () -> Unit,
    onOpenDevice: (Long) -> Unit,
    onOpenHistory: () -> Unit,
    onPair: () -> Unit,
    onOpenBridgeSetup: () -> Unit,
) {
    val viewModel = appViewModel { DashboardViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)
    var confirmation by remember { mutableStateOf<Pair<PowerAction, DeviceWithConnections>?>(null) }

    LaunchedEffect(state.settings.refreshSeconds) {
        while (true) {
            viewModel.refreshStatuses()
            delay(state.settings.refreshSeconds * 1_000L)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DashboardHeader(
                    pcCount = state.devices.size,
                    onlineCount = state.checks.values.count { it.status == DeviceStatus.ONLINE },
                    onAddPc = onAddPc,
                )
            }

            val selected = state.selectedDevice
            if (selected == null) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.DesktopWindows,
                        title = "No PCs yet",
                        message = "Add your Windows PC and pair the companion agent. Local Wake-on-LAN " +
                            "works with just a MAC address.",
                        actionLabel = "Add your first PC",
                        onAction = onAddPc,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(onClick = onPair) { Text("Pair a server") }
                        TextButton(onClick = onOpenBridgeSetup) { Text("Wake Bridge setup") }
                    }
                }
            } else {
                item {
                    SelectedDeviceHero(
                        device = selected,
                        state = state,
                        onWakeLocal = { viewModel.wake(selected, false) },
                        onWakeBridge = { viewModel.wake(selected, true) },
                        onSelectRoute = viewModel::setPreferBridge,
                        onOpenDevice = { onOpenDevice(selected.device.id) },
                        onPower = { action -> confirmation = action to selected },
                    )
                }
                item {
                    SectionTitle(
                        title = "Your PCs",
                        actionLabel = "Add",
                        onAction = onAddPc,
                    )
                }
                items(state.devices, key = { it.device.id }) { device ->
                    val check = state.checks[device.device.id]
                    DeviceCard(
                        device = device,
                        check = check ?: com.pcwake.app.ui.viewmodel.AgentCheckUi(
                            DeviceStatus.CHECKING,
                            "Checking…",
                        ),
                        onClick = { onOpenDevice(device.device.id) },
                        onWake = {
                            val viaBridge = device.supportsRemoteWake &&
                                state.settings.defaultRoute == WakeRoutePref.BRIDGE
                            viewModel.wake(device, viaBridge)
                        },
                        waking = state.busyDeviceId == device.device.id,
                    )
                }
            }

            item {
                SectionTitle(
                    title = "Recent commands",
                    actionLabel = "History",
                    onAction = onOpenHistory,
                )
            }
            if (state.recent.isEmpty()) {
                item {
                    PwCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.History,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(19.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Wake packets and power commands you send will be recorded here.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                item {
                    PwCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        state.recent.take(4).forEachIndexed { index, record ->
                            HistoryRow(record)
                            if (index < minOf(state.recent.size, 4) - 1) {
                                androidx.compose.material3.HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                InfoBanner(
                    "Online means the Windows agent answered an authenticated request. An unreachable " +
                        "PC is shown as Unknown — it may be off, asleep, or on another network.",
                )
            }
            item {
                Text(
                    "Wake-on-LAN never confirms a boot by itself. PC Wake waits for the agent before " +
                        "saying a PC is online.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    confirmation?.let { (action, device) ->
        ConfirmPowerDialog(
            action = action,
            deviceName = device.device.name,
            graceSeconds = if (action == PowerAction.SLEEP) 3 else 15,
            onConfirm = {
                viewModel.requestPower(
                    device,
                    action,
                    if (action == PowerAction.SLEEP) 3 else 15,
                )
                confirmation = null
            },
            onDismiss = { confirmation = null },
        )
    }
}

@Composable
private fun DashboardHeader(pcCount: Int, onlineCount: Int, onAddPc: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF5E9BFF), Color(0xFF35D0A0)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.PowerSettingsNew,
                null,
                tint = Color(0xFF07121F),
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("PC Wake", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (pcCount == 0) "Local + remote power control"
                else "$pcCount ${if (pcCount == 1) "PC" else "PCs"} · $onlineCount online",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        IconButton(onClick = onAddPc) {
            Icon(Icons.Rounded.Add, "Add PC")
        }
    }
}

@Composable
private fun SelectedDeviceHero(
    device: DeviceWithConnections,
    state: DashboardUiState,
    onWakeLocal: () -> Unit,
    onWakeBridge: () -> Unit,
    onSelectRoute: (Boolean) -> Unit,
    onOpenDevice: () -> Unit,
    onPower: (PowerAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val check = state.checks[device.device.id]
    val viaBridge = if (device.supportsRemoteWake) {
        state.preferBridge ?: (state.settings.defaultRoute == WakeRoutePref.BRIDGE)
    } else {
        false
    }
    val busy = state.busyDeviceId == device.device.id
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            colors.outlineVariant.copy(alpha = 0.7f),
        ),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(
                        colors.primary.copy(alpha = 0.11f),
                        colors.secondary.copy(alpha = 0.05f),
                        Color.Transparent,
                    ),
                ),
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "SELECTED PC",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            device.device.name,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            device.device.macAddress,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    StatusPill(check?.status ?: DeviceStatus.CHECKING)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PowerCircleButton(
                        onClick = if (device.supportsRemoteWake && viaBridge) onWakeBridge else onWakeLocal,
                        loading = busy,
                        label = if (viaBridge) "Wake via bridge" else "Power on · LAN",
                        enabled = !busy,
                    )
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            check?.detail ?: "Checking agent status…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (check?.status == DeviceStatus.ONLINE) {
                                statusColor(DeviceStatus.ONLINE)
                            } else {
                                colors.onSurfaceVariant
                            },
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (device.supportsRemoteWake) {
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = !viaBridge,
                                    onClick = { if (!busy) onSelectRoute(false) },
                                    label = { Text("LAN") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Lan, null, Modifier.size(15.dp))
                                    },
                                    enabled = !busy,
                                )
                                FilterChip(
                                    selected = viaBridge,
                                    onClick = { if (!busy) onSelectRoute(true) },
                                    label = { Text("Bridge") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.WifiTethering, null, Modifier.size(15.dp))
                                    },
                                    enabled = !busy,
                                )
                            }
                        }
                        TextButton(onClick = onOpenDevice) {
                            Text("Open ${device.device.name}")
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickAction(
                        icon = Icons.Rounded.PowerSettingsNew,
                        label = "Shut down",
                        onClick = { onPower(PowerAction.SHUTDOWN) },
                        enabled = device.supportsAgentControl && !busy,
                        destructive = true,
                        modifier = Modifier.weight(1f),
                    )
                    QuickAction(
                        icon = Icons.Rounded.RestartAlt,
                        label = "Restart",
                        onClick = { onPower(PowerAction.RESTART) },
                        enabled = device.supportsAgentControl && !busy,
                        modifier = Modifier.weight(1f),
                    )
                    QuickAction(
                        icon = Icons.Rounded.Bedtime,
                        label = "Sleep",
                        onClick = { onPower(PowerAction.SLEEP) },
                        enabled = device.supportsAgentControl && !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (!device.supportsAgentControl) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Shield,
                            null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Power actions need the paired Windows agent.",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
