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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.Formatting
import com.pcwake.app.net.NetworkSnapshot
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.CopyableCode
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.KeyValueRow
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.DiagItem
import com.pcwake.app.ui.viewmodel.DiagState
import com.pcwake.app.ui.viewmodel.DiagnosticsViewModel
import com.pcwake.app.ui.viewmodel.appViewModel

@Composable
fun DiagnosticsScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
) {
    val viewModel = appViewModel(key = "diag-$deviceId") {
        DiagnosticsViewModel(deviceId, container)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = "Diagnostics",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refreshSnapshot(); viewModel.runAll() }) {
                        Icon(Icons.Rounded.Refresh, "Run again")
                    }
                },
            )
        },
    ) { padding ->
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
                    SectionTitle("Network snapshot")
                    Spacer(Modifier.height(8.dp))
                    state.snapshot?.let { NetworkDetails(it) } ?: Text("No network information")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = viewModel::refreshSnapshot,
                        enabled = !state.running,
                    ) { Text("Refresh network") }
                }
            }

            item {
                PwCard {
                    SectionTitle("Connection checks")
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (state.device == null) {
                            "Open this screen from a PC profile to test its agent, bridge and WoL packet."
                        } else {
                            "Tests use real network calls. The WoL check sends one real UDP packet."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.running) {
                        Spacer(Modifier.height(10.dp))
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    Spacer(Modifier.height(10.dp))
                    state.items.forEach { item -> DiagRow(item) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::runAll,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (state.running) "Running…" else "Run all checks again") }
                }
            }

            state.packetHex?.let { hex ->
                item {
                    PwCard {
                        SectionTitle("Magic packet")
                        Spacer(Modifier.height(8.dp))
                        CopyableCode(hex)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "6 × FF + MAC × 16 = 102 bytes",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                PwCard {
                    SectionTitle("Common Wake-on-LAN problems")
                    Spacer(Modifier.height(8.dp))
                    TroubleItem(
                        "Incorrect MAC or broadcast",
                        "Use the wired adapter's own MAC (no Wi-Fi virtual adapter) and the subnet's " +
                            "directed broadcast, e.g. 192.168.1.255.",
                    )
                    TroubleItem(
                        "BIOS / UEFI wake disabled",
                        "Enable Wake on LAN / PCI-E Wake in the BIOS power-management section " +
                            "(labels vary by board). Save and fully power-cycle.",
                    )
                    TroubleItem(
                        "Windows adapter not armed",
                        "Device Manager → wired NIC → Power Management: allow this device to wake " +
                            "the computer, only allow a magic packet.",
                    )
                    TroubleItem(
                        "No standby power",
                        "While shut down, the NIC LED and motherboard Ethernet LEDs should stay lit. " +
                            "Otherwise ErP/EuP or a power strip is cutting standby power.",
                    )
                    TroubleItem(
                        "Router or AP isolation",
                        "Guest Wi-Fi, client isolation or VLAN separation can block broadcasts. " +
                            "Join the same trusted LAN as the PC.",
                    )
                    TroubleItem(
                        "Wake from full shutdown unsupported",
                        "Many boards only wake from Sleep/S5 with specific BIOS settings. Test Sleep " +
                            "first; Fast Startup can also interfere with S5 wake.",
                    )
                }
            }

            item {
                InfoBanner(
                    "Diagnostics never claim a boot. A sent datagram proves only that Android wrote " +
                        "the packet; a successful agent check is the only online confirmation.",
                )
            }
            state.device?.let {
                item {
                    Text(
                        "Target: ${it.device.name} · ${it.device.macAddress} · " +
                            "${it.device.broadcastAddress}:${it.device.port}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkDetails(snapshot: NetworkSnapshot) {
    KeyValueRow("Transport", snapshot.transport)
    snapshot.interfaceName?.let { KeyValueRow("Interface", it) }
    KeyValueRow("Phone IPv4", snapshot.localIpv4 ?: "None")
    snapshot.prefixLength?.let { KeyValueRow("Prefix", "/$it") }
    snapshot.broadcast?.let { KeyValueRow("Subnet broadcast", it, monospace = true) }
    KeyValueRow("VPN", if (snapshot.isVpn) "Active" else "No")
    KeyValueRow("Internet capability", if (snapshot.hasInternet) "Yes" else "No")
    KeyValueRow("Metered", if (snapshot.metered) "Yes" else "No")
}

@Composable
private fun DiagRow(item: DiagItem) {
    val colors = MaterialTheme.colorScheme
    val (icon, tint) = when (item.state) {
        DiagState.PASS -> Icons.Rounded.CheckCircle to colors.secondary
        DiagState.WARN -> Icons.Rounded.WarningAmber to colors.tertiary
        DiagState.FAIL -> Icons.Rounded.ErrorOutline to colors.error
        DiagState.SKIPPED -> Icons.Rounded.RemoveCircleOutline to colors.outline
        DiagState.PENDING -> Icons.Rounded.Info to colors.outline
        DiagState.RUNNING -> Icons.Rounded.Info to colors.primary
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (item.state == DiagState.RUNNING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = tint)
            } else {
                Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            item.detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TroubleItem(title: String, body: String) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
