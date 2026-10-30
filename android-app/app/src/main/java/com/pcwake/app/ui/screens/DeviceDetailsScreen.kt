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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.pcwake.app.core.AgentCommand
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.PowerAction
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ConfirmDialog
import com.pcwake.app.ui.components.ConfirmPowerDialog
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.HistoryRow
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.KeyValueRow
import com.pcwake.app.ui.components.PowerCircleButton
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.QuickAction
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.components.StatusPill
import com.pcwake.app.ui.viewmodel.DetailsUiState
import com.pcwake.app.ui.viewmodel.DeviceDetailsViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import kotlinx.coroutines.delay

@Composable
fun DeviceDetailsScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onWolConfig: (Long) -> Unit,
    onDiagnostics: (Long) -> Unit,
    onPowerControls: (Long) -> Unit,
    onOpenDevice: (Long) -> Unit,
    onHistory: () -> Unit,
) {
    val viewModel = appViewModel(key = "details-$deviceId") {
        DeviceDetailsViewModel(deviceId, container)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)
    var menuOpen by remember { mutableStateOf(false) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<PowerAction?>(null) }

    LaunchedEffect(state.settings.refreshSeconds, deviceId) {
        while (true) {
            viewModel.refresh()
            delay(state.settings.refreshSeconds * 1_000L)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.deleted.collect { onBack() }
    }
    LaunchedEffect(Unit) {
        viewModel.duplicated.collect { id -> onOpenDevice(id) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = state.device?.device?.name ?: "PC details",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Rounded.Refresh, "Refresh status")
                    }
                    IconButton(onClick = { onEdit(deviceId) }) {
                        Icon(Icons.Rounded.Edit, "Edit")
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Duplicate profile") },
                                onClick = { menuOpen = false; viewModel.duplicate() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete PC") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                                onClick = { menuOpen = false; deleteConfirm = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val device = state.device
        if (device == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val check = state.check
        var preferBridge by rememberSaveable(deviceId) {
            mutableStateOf(
                device.supportsRemoteWake &&
                    (state.settings.defaultRoute == WakeRoutePref.BRIDGE),
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
                StatusHero(device, state)
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
                TextButton(
                    onClick = { onPowerControls(deviceId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open full power controls")
                }
            }

            item {
                PwCard {
                    SectionTitle("Network & connections")
                    Spacer(Modifier.height(8.dp))
                    KeyValueRow("MAC address", device.device.macAddress, monospace = true)
                    KeyValueRow("IPv4", device.device.localIp ?: "Not set")
                    KeyValueRow("Broadcast", device.device.broadcastAddress, monospace = true)
                    KeyValueRow("UDP port", device.device.port.toString())
                    KeyValueRow("Last seen", Formatting.relativeTime(device.device.lastSeenAt))
                    KeyValueRow("Last wake sent", Formatting.relativeTime(device.device.lastWakeAt))
                    HorizontalDivider(
                        Modifier.padding(vertical = 9.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    )
                    KeyValueRow(
                        "Windows agent",
                        device.agentEndpoint?.let(Formatting::endpointPretty) ?: "Not paired",
                        monospace = device.agentEndpoint != null,
                    )
                    KeyValueRow(
                        "Wake Bridge",
                        device.bridgeEndpoint?.let(Formatting::endpointPretty) ?: "Not paired",
                        monospace = device.bridgeEndpoint != null,
                    )
                    state.bridgeDetail?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Bridge: $it",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.bridgeOnline == true) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onWolConfig(deviceId) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Tune, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("WoL")
                        }
                        OutlinedButton(onClick = { onDiagnostics(deviceId) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Diagnose")
                        }
                    }
                }
            }

            item {
                SectionTitle(
                    title = "Recent commands",
                    actionLabel = "All history",
                    onAction = onHistory,
                )
            }
            if (state.records.isEmpty()) {
                item {
                    PwCard {
                        Text(
                            "No commands yet. Power actions and wake packets appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                item {
                    PwCard(padding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                        state.records.take(6).forEachIndexed { index, record ->
                            HistoryRow(record, showDevice = false)
                            if (index < minOf(state.records.size, 6) - 1) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
            }

            item {
                InfoBanner(
                    "Unknown is not the same as offline. A missing agent may mean the PC is off, " +
                        "asleep, on another network, or the pin changed. Only an authenticated agent " +
                        "response proves the PC is online.",
                )
            }
        }
    }

    pendingAction?.let { action ->
        val device = state.device
        if (device != null && device.supportsAgentControl) {
            ConfirmPowerDialog(
                action = action,
                deviceName = device.device.name,
                graceSeconds = if (action == PowerAction.SLEEP) 3 else 15,
                onConfirm = {
                    viewModel.requestPower(action, if (action == PowerAction.SLEEP) 3 else 15)
                    pendingAction = null
                },
                onDismiss = { pendingAction = null },
            )
        }
    }
    if (deleteConfirm) {
        ConfirmDialog(
            title = "Delete ${state.device?.device?.name}?",
            message = "This removes the PC profile and its command history from this phone. " +
                "The Windows agent/bridge pairing stays until you revoke it in Settings.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = { deleteConfirm = false; viewModel.delete() },
            onDismiss = { deleteConfirm = false },
        )
    }
}

@Composable
private fun StatusHero(device: DeviceWithConnections, state: DetailsUiState) {
    val colors = MaterialTheme.colorScheme
    val check = state.check
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(
            Modifier.background(
                Brush.linearGradient(
                    listOf(colors.primary.copy(alpha = 0.10f), Color.Transparent),
                ),
            ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(15.dp))
                            .background(colors.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (device.supportsAgentControl) Icons.Rounded.PowerSettingsNew
                            else Icons.Rounded.Bolt,
                            null,
                            tint = colors.primary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.device.name,
                            style = MaterialTheme.typography.headlineSmall,
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
                    StatusPill(check.status)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    check.detail ?: "Waiting for status…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (check.status == DeviceStatus.ONLINE) colors.secondary
                    else colors.onSurfaceVariant,
                )
                check.agent?.let { agent ->
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (agent.adapters.isNotEmpty()) {
                            AgentChip(
                                "Uptime ${Formatting.duration(agent.uptimeSeconds)}",
                                Icons.Rounded.PowerSettingsNew,
                            )
                        }
                        AgentChip("Agent ${agent.agentVersion}", Icons.Rounded.Lan)
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PowerControlsCard(
    device: DeviceWithConnections,
    state: DetailsUiState,
    preferBridge: Boolean,
    onPreferBridge: (Boolean) -> Unit,
    onWakeLocal: () -> Unit,
    onWakeBridge: () -> Unit,
    onAction: (PowerAction) -> Unit,
    onCancelPending: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val busy = state.busy
    PwCard {
        SectionTitle("Power controls")
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PowerCircleButton(
                onClick = if (device.supportsRemoteWake && preferBridge) onWakeBridge else onWakeLocal,
                loading = busy == "Sending wake packet…",
                enabled = busy == null,
                label = if (device.supportsRemoteWake && preferBridge) "Wake · bridge" else "Power on · LAN",
                size = 92.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (device.supportsRemoteWake) {
                    Text(
                        "Wake route",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !preferBridge,
                            onClick = { if (busy == null) onPreferBridge(false) },
                            label = { Text("LAN") },
                            leadingIcon = { Icon(Icons.Rounded.Lan, null, Modifier.size(15.dp)) },
                            enabled = busy == null,
                        )
                        FilterChip(
                            selected = preferBridge,
                            onClick = { if (busy == null) onPreferBridge(true) },
                            label = { Text("Bridge") },
                            leadingIcon = { Icon(Icons.Rounded.WifiTethering, null, Modifier.size(15.dp)) },
                            enabled = busy == null,
                        )
                    }
                } else {
                    Text(
                        "Local Wake-on-LAN",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Add a Wake Bridge to wake this PC from mobile data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Broadcast ${device.device.broadcastAddress}:${device.device.port}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (busy != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(busy, style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction(
                icon = Icons.Rounded.PowerSettingsNew,
                label = "Shut down",
                onClick = { onAction(PowerAction.SHUTDOWN) },
                enabled = device.supportsAgentControl && busy == null,
                destructive = true,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Rounded.RestartAlt,
                label = "Restart",
                onClick = { onAction(PowerAction.RESTART) },
                enabled = device.supportsAgentControl && busy == null,
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Rounded.Bedtime,
                label = "Sleep",
                onClick = { onAction(PowerAction.SLEEP) },
                enabled = device.supportsAgentControl && busy == null,
                modifier = Modifier.weight(1f),
            )
        }
        if (!device.supportsAgentControl) {
            Spacer(Modifier.height(10.dp))
            InfoBanner("Shutdown, restart and sleep require the signed Windows agent.")
        }
        state.pendingCommand?.let { command ->
            Spacer(Modifier.height(12.dp))
            PendingCommandRow(command, busy, onCancelPending)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Wake packets are reported as sent only. Online is confirmed only when the agent answers.",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun PendingCommandRow(
    command: AgentCommand,
    busy: String?,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val label = when (command.action) {
        "shutdown" -> "Shutdown"
        "restart" -> "Restart"
        "sleep" -> "Sleep"
        else -> command.action
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.tertiaryContainer.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bedtime, null, tint = colors.onTertiaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "$label pending (${command.status})",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onTertiaryContainer,
                )
                Text(
                    "Requested ${Formatting.relativeTime(command.requestedAt * 1000)} · grace ${command.graceSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            if (command.action != "sleep" && command.status == "pending") {
                Button(
                    onClick = onCancel,
                    enabled = busy == null,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = colors.onTertiaryContainer,
                        contentColor = colors.tertiaryContainer,
                    ),
                ) { Text("Cancel") }
            }
        }
    }
}
