package com.pcwake.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ConfirmDialog
import com.pcwake.app.ui.components.EmptyStateCard
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.BridgeSetupViewModel
import com.pcwake.app.ui.viewmodel.appViewModel

@Composable
fun BridgeSetupScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onPair: () -> Unit,
) {
    val viewModel = appViewModel { BridgeSetupViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)
    var revokeTarget by remember { mutableStateOf<ServerSummary?>(null) }
    val bridges = state.servers.filter { it.kind == ConnectionMode.BRIDGE }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = "Wake Bridge",
                onBack = onBack,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Rounded.Refresh, "Refresh")
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
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Icon(
                            Icons.Rounded.WifiTethering,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("Wake from mobile data", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A phone on 4G cannot broadcast into your home LAN. An always-on Wake " +
                                "Bridge (Raspberry Pi, router, or small Linux box) receives a signed " +
                                "request over HTTPS and sends the magic packet locally.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = onPair, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Pair a Wake Bridge")
                        }
                    }
                }
            }

            item { SectionTitle("Paired bridges") }
            if (bridges.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.Cloud,
                        title = "No bridge paired",
                        message = "Local wake still works at home. Pair a bridge for remote wake and " +
                            "an authenticated status endpoint.",
                        actionLabel = "Pair a Wake Bridge",
                        onAction = onPair,
                    )
                }
            } else {
                bridges.forEach { server ->
                    val check = state.checks[server.endpoint]
                    item(key = server.endpoint) {
                    PwCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Cloud,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(server.name, style = MaterialTheme.typography.titleLarge)
                                Text(
                                    Formatting.endpointPretty(server.endpoint),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { revokeTarget = server }) {
                                Icon(Icons.Rounded.Delete, "Revoke bridge")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Pinned ${Formatting.shortFingerprint(server.fingerprint)} · paired " +
                                Formatting.relativeTime(server.pairedAt),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { viewModel.test(server) },
                                enabled = state.busyEndpoint == null,
                            ) {
                                if (state.busyEndpoint == server.endpoint) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(7.dp))
                                }
                                Text("Test connection")
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                when (check?.online) {
                                    true -> "Online"
                                    false -> "Unreachable"
                                    null -> "Not tested"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = when (check?.online) {
                                    true -> MaterialTheme.colorScheme.secondary
                                    false -> MaterialTheme.colorScheme.error
                                    null -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        check?.detail?.let {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!check?.targets.isNullOrEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            check!!.targets.forEach { target ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        target.name.ifBlank { target.mac },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        target.mac,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }

            item {
                PwCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Memory, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("Deploy on a Raspberry Pi", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use the zero-dependency Node.js bridge from wake-bridge/. It requires an " +
                            "always-on device with HTTPS, a target allow-list and a systemd service. " +
                            "Expose only its HTTPS port — through Tailscale, or a reverse proxy with a " +
                            "real certificate. Never port-forward UDP Wake-on-LAN.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Lock, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Full guide: documentation/WAKE_BRIDGE.md",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                InfoBanner(
                    "A bridge proves only that it accepted and sent the packet. PC health still " +
                        "comes from the Windows agent, never from the bridge.",
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lan, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onPair) { Text("Pair another bridge") }
                }
            }
        }
    }

    revokeTarget?.let { server ->
        ConfirmDialog(
            title = "Revoke ${server.name}?",
            message = "This removes the bridge token from this phone and asks the bridge to revoke " +
                "it. Wake via this bridge stops immediately.",
            confirmLabel = "Revoke",
            destructive = true,
            onConfirm = { viewModel.revoke(server); revokeTarget = null },
            onDismiss = { revokeTarget = null },
        )
    }
}
