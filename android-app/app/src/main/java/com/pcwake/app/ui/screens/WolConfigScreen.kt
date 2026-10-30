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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.MacAddress
import com.pcwake.app.net.MagicPacket
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.CheckRow
import com.pcwake.app.ui.components.CopyableCode
import com.pcwake.app.ui.components.ErrorBanner
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.LabeledField
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.WolConfigViewModel
import com.pcwake.app.ui.viewmodel.appViewModel

@Composable
fun WolConfigScreen(
    container: AppContainer,
    deviceId: Long,
    onBack: () -> Unit,
    onDiagnostics: (Long) -> Unit,
) {
    val viewModel = appViewModel(key = "wol-$deviceId") {
        WolConfigViewModel(deviceId, container)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(title = "Wake-on-LAN setup", onBack = onBack) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.saving && state.device != null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Save WoL configuration")
                }
            }
        },
    ) { padding ->
        val device = state.device
        if (state.loading || device == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        val packet = remember(device.device.macAddress) {
            MacAddress.parseOrNull(device.device.macAddress)?.let {
                MagicPacket.hexDump(MagicPacket.build(it))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                InfoBanner(
                    "The magic packet is 102 bytes: six 0xFF bytes followed by the MAC address " +
                        "repeated 16 times. It is broadcast on UDP port 9 (or 7).",
                )
            }
            item {
                PwCard {
                    SectionTitle("Packet target")
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "Broadcast address",
                        value = state.broadcast,
                        onValueChange = viewModel::setBroadcast,
                        placeholder = "192.168.1.255",
                        error = state.errors["broadcast"],
                        helper = "Use the directed broadcast of the PC's wired subnet.",
                        keyboardType = KeyboardType.Decimal,
                    )
                    Spacer(Modifier.height(12.dp))
                    LabeledField(
                        label = "UDP port",
                        value = state.port,
                        onValueChange = viewModel::setPort,
                        placeholder = "9",
                        error = state.errors["port"],
                        keyboardType = KeyboardType.Number,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Retries: ${state.settings.retries} packets, ${state.settings.retryIntervalMs} ms apart " +
                            "(configure in Settings). Interface: " +
                            (state.settings.wakeInterface.ifBlank { "automatic" }) + ".",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                PwCard {
                    SectionTitle("Packet preview")
                    Spacer(Modifier.height(8.dp))
                    packet?.let {
                        CopyableCode(it)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "102 bytes · ${device.device.macAddress}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } ?: Text("Invalid MAC address")
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = viewModel::test,
                        enabled = !state.testing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.testing) {
                            CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                        }
                        Text("Send one real test packet")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The test confirms a UDP datagram was written; it cannot confirm that the " +
                            "PC received or booted.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                PwCard {
                    SectionTitle("If wake does not work")
                    Spacer(Modifier.height(8.dp))
                    CheckRow("BIOS: “Wake on LAN / PCI-E wake” enabled (labels vary by board)", false)
                    CheckRow("Windows adapter: Wake on Magic Packet enabled, only allow magic packet", false)
                    CheckRow("Adapter supports standby power (check its LED while the PC is off)", false)
                    CheckRow("Use a directed broadcast on the wired subnet, not 255.255.255.255", false)
                    CheckRow("Router/client isolation is off; phone is on the same LAN", false)
                    CheckRow("Fast Startup can prevent wake from full shutdown — test Sleep first", false)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onDiagnostics(deviceId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("Run connection diagnostics")
                    }
                }
            }
            item {
                InfoBanner(
                    "A real Ethernet cable is required. Most USB adapters and desktops on Wi-Fi " +
                        "do not stay awake for Wake-on-LAN.",
                    warning = true,
                )
            }
            state.errors.values.firstOrNull()?.let { error ->
                item { ErrorBanner(error) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.WarningAmber,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Do not expose UDP 9 to the internet. Use the Wake Bridge instead.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
