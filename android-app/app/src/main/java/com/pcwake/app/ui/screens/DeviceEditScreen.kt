package com.pcwake.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ErrorBanner
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.LabeledField
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.DeviceEditUiState
import com.pcwake.app.ui.viewmodel.DeviceEditViewModel
import com.pcwake.app.ui.viewmodel.appViewModel

@Composable
fun DeviceEditScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
    onSaved: (Long) -> Unit,
) {
    val viewModel = appViewModel(key = "edit-$deviceId") {
        DeviceEditViewModel(deviceId, container)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.saved.collect { id -> onSaved(id) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (state.isNew) "Add PC" else "Edit PC",
                onBack = onBack,
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving && !state.loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.isNew) "Add PC" else "Save changes")
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 6.dp,
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                PwCard {
                    SectionTitle("Identity")
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "Nickname",
                        value = state.name,
                        onValueChange = { value -> viewModel.update { copy(name = value) } },
                        placeholder = "Gaming PC",
                        error = state.errors["name"],
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "MAC address",
                        value = state.macAddress,
                        onValueChange = { value -> viewModel.update { copy(macAddress = value) } },
                        placeholder = "AA:BB:CC:DD:EE:FF",
                        error = state.errors["mac"],
                        helper = "Find it with ipconfig /all or the agent pairing details.",
                        keyboardType = KeyboardType.Ascii,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = viewModel::detectLocalNetwork) {
                        Icon(Icons.Rounded.WifiTethering, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Detect this phone's LAN")
                    }
                }
            }

            item {
                PwCard {
                    SectionTitle("Local Wake-on-LAN")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Magic packets are sent on your LAN. When you are away, the optional Wake " +
                            "Bridge sends the packet inside your home network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "PC IPv4 (optional)",
                        value = state.localIp,
                        onValueChange = { value -> viewModel.update { copy(localIp = value) } },
                        placeholder = "192.168.1.50",
                        error = state.errors["localIp"],
                        keyboardType = KeyboardType.Decimal,
                        leadingIcon = Icons.Rounded.Lan,
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "Broadcast address",
                        value = state.broadcastAddress,
                        onValueChange = { value -> viewModel.update { copy(broadcastAddress = value) } },
                        placeholder = "192.168.1.255",
                        error = state.errors["broadcast"],
                        helper = "Directed broadcast of the PC's subnet. Routers usually use /24.",
                        keyboardType = KeyboardType.Decimal,
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "UDP port",
                        value = state.port,
                        onValueChange = { value ->
                            viewModel.update { copy(port = value.filter(Char::isDigit).take(5)) }
                        },
                        placeholder = "9",
                        error = state.errors["port"],
                        helper = "Port 9 is standard. Some setups use 7.",
                        keyboardType = KeyboardType.Number,
                    )
                }
            }

            item {
                PwCard {
                    SectionTitle("Connections")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Local wake works with just the fields above. Pair a Windows agent for " +
                            "shutdown/restart/sleep and real health, and/or a Wake Bridge for remote wake.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    ServerSelector(
                        label = "Windows agent",
                        icon = Icons.Rounded.DesktopWindows,
                        selected = state.agentEndpoint,
                        servers = state.agentServers,
                        emptyHint = "No agent paired",
                        onSelect = { endpoint -> viewModel.update { copy(agentEndpoint = endpoint) } },
                    )
                    Spacer(Modifier.height(12.dp))
                    ServerSelector(
                        label = "Wake Bridge",
                        icon = Icons.Rounded.WifiTethering,
                        selected = state.bridgeEndpoint,
                        servers = state.bridgeServers,
                        emptyHint = "No bridge paired",
                        onSelect = { endpoint -> viewModel.update { copy(bridgeEndpoint = endpoint) } },
                    )
                    if (state.agentServers.isEmpty() && state.bridgeServers.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        InfoBanner(
                            "Pair servers in Device pairing, then they appear in these selectors.",
                            warning = false,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::discoverAgents,
                        enabled = !state.discovering,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.discovering) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(7.dp))
                        } else {
                            Icon(Icons.Rounded.Search, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(if (state.discovering) "Scanning LAN…" else "Discover agents on this LAN")
                    }
                }
            }

            item {
                InfoBanner(
                    "Multiple PCs can share one agent or Wake Bridge. Deleting a profile here does " +
                        "not revoke its server pairing.",
                )
            }

            state.message?.let { message ->
                item {
                    if (state.messageIsError) {
                        ErrorBanner(message)
                    } else {
                        InfoBanner(message)
                    }
                }
            }
        }
    }

    if (state.showDiscovery) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDiscovery,
            title = { Text("Agents on this LAN") },
            text = {
                Column {
                    if (state.discovering) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(9.dp))
                            Text("Broadcasting discovery probe…")
                        }
                    } else if (state.discoveredAgents.isEmpty()) {
                        Text(
                            "No agents replied. Discovery uses UDP 47100 on the local LAN; it may be " +
                                "blocked by the network or disabled in the agent.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.discoveredAgents.forEach { agent ->
                            val endpoint = com.pcwake.app.net.Endpoints.build(agent.host, agent.port).getOrNull()
                            val locallyPaired = state.servers.any {
                                it.kind == ConnectionMode.AGENT && it.endpoint == endpoint
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectDiscoveredAgent(agent) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.DesktopWindows,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(agent.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "${agent.host}:${agent.port}",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    if (locallyPaired) "Paired" else if (agent.paired) "Other phone" else "Unpaired",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (locallyPaired) MaterialTheme.colorScheme.secondary
                                    else MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::dismissDiscovery) { Text("Close") } },
        )
    }
}

@Composable
private fun ServerSelector(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: String?,
    servers: List<ServerSummary>,
    emptyHint: String,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = servers.firstOrNull { it.endpoint == selected }
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(icon, null, Modifier.size(19.dp))
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        summary?.name ?: emptyHint,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                    )
                    val endpoint = selected
                    if (endpoint != null) {
                        Text(
                            Formatting.endpointPretty(endpoint),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(Icons.Rounded.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text("None — local wake only") },
                    onClick = { onSelect(null); expanded = false },
                )
                servers.forEach { server ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(server.name)
                                Text(
                                    Formatting.endpointPretty(server.endpoint),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { onSelect(server.endpoint); expanded = false },
                    )
                }
            }
        }
    }
}
