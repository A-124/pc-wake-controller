package com.pcwake.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.BuildConfig
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.ServerSummary
import com.pcwake.app.core.ThemeMode
import com.pcwake.app.core.WakeRoutePref
import com.pcwake.app.net.WakeInterface
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ConfirmDialog
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.SettingsUiState
import com.pcwake.app.ui.viewmodel.SettingsViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    container: AppContainer,
    onAbout: () -> Unit,
    onBridgeSetup: () -> Unit,
    onPairAgent: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val viewModel = appViewModel { SettingsViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)

    var numberDialog by remember { mutableStateOf<NumberDialog?>(null) }
    var broadcastDialog by remember { mutableStateOf(false) }
    var interfaceDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<ServerSummary?>(null) }
    var passphrasePurpose by remember { mutableStateOf<PassphrasePurpose?>(null) }
    var pendingPassphrase by remember { mutableStateOf<String?>(null) }

    val createBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && passphrase != null) viewModel.exportConfig(uri, passphrase)
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && passphrase != null) viewModel.importConfig(uri, passphrase)
    }

    LaunchedEffect(Unit) { viewModel.refreshSecurity() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(title = "Settings") },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.busy?.let { busy ->
                item {
                    PwCard {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text(
                            busy,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SettingsCard(icon = Icons.Rounded.Palette, title = "Appearance") {
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.settings.theme == ThemeMode.SYSTEM,
                            onClick = { viewModel.setTheme(ThemeMode.SYSTEM) },
                            label = { Text("System") },
                        )
                        FilterChip(
                            selected = state.settings.theme == ThemeMode.LIGHT,
                            onClick = { viewModel.setTheme(ThemeMode.LIGHT) },
                            label = { Text("Light") },
                        )
                        FilterChip(
                            selected = state.settings.theme == ThemeMode.DARK,
                            onClick = { viewModel.setTheme(ThemeMode.DARK) },
                            label = { Text("Dark") },
                        )
                    }
                }
            }

            item {
                PwCard(padding = PaddingValues(0.dp)) {
                    GroupHeader(Icons.Rounded.Bolt, "Wake defaults")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Text(
                            "Default wake route",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(7.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = state.settings.defaultRoute == WakeRoutePref.ASK,
                                onClick = { viewModel.setRoute(WakeRoutePref.ASK) },
                                label = { Text("Ask / LAN") },
                            )
                            FilterChip(
                                selected = state.settings.defaultRoute == WakeRoutePref.LOCAL,
                                onClick = { viewModel.setRoute(WakeRoutePref.LOCAL) },
                                label = { Text("Always LAN") },
                            )
                            FilterChip(
                                selected = state.settings.defaultRoute == WakeRoutePref.BRIDGE,
                                onClick = { viewModel.setRoute(WakeRoutePref.BRIDGE) },
                                label = { Text("Prefer bridge") },
                            )
                        }
                    }
                    SettingsDivider()
                    SettingRow(
                        title = "UDP port",
                        subtitle = "Default for new PCs",
                        value = state.settings.defaultPort.toString(),
                        onClick = {
                            numberDialog = NumberDialog(
                                title = "Default UDP port",
                                value = state.settings.defaultPort,
                                min = 1,
                                max = 65535,
                            ) { viewModel.setDefaultPort(it) }
                        },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Broadcast address",
                        subtitle = "Default for new PCs; detected LAN otherwise",
                        value = state.settings.defaultBroadcast.ifBlank { "Not set" },
                        onClick = { broadcastDialog = true },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Packet retries",
                        subtitle = "Magic packets per wake",
                        value = "${state.settings.retries}×",
                        onClick = {
                            numberDialog = NumberDialog(
                                title = "Magic packets per wake",
                                value = state.settings.retries,
                                min = 1,
                                max = 20,
                            ) { viewModel.setRetries(it) }
                        },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Retry interval",
                        subtitle = "Delay between packets",
                        value = "${state.settings.retryIntervalMs} ms",
                        onClick = {
                            numberDialog = NumberDialog(
                                title = "Retry interval (ms)",
                                value = state.settings.retryIntervalMs,
                                min = 50,
                                max = 5_000,
                            ) { viewModel.setRetryInterval(it) }
                        },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Connection timeout",
                        subtitle = "Agent and bridge HTTPS requests",
                        value = "${state.settings.timeoutMs} ms",
                        onClick = {
                            numberDialog = NumberDialog(
                                title = "Connection timeout (ms)",
                                value = state.settings.timeoutMs,
                                min = 1_000,
                                max = 60_000,
                            ) { viewModel.setTimeout(it) }
                        },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Auto-refresh",
                        subtitle = "Status polling while a screen is open",
                        value = "${state.settings.refreshSeconds} s",
                        onClick = {
                            numberDialog = NumberDialog(
                                title = "Auto-refresh interval (s)",
                                value = state.settings.refreshSeconds,
                                min = 5,
                                max = 300,
                            ) { viewModel.setRefreshSeconds(it) }
                        },
                    )
                    SettingsDivider()
                    SettingRow(
                        title = "Wake interface",
                        subtitle = "Bind magic packets to one adapter",
                        value = state.settings.wakeInterface.ifBlank { "Automatic" },
                        onClick = { interfaceDialog = true },
                    )
                }
            }

            item {
                PwCard(padding = PaddingValues(0.dp)) {
                    GroupHeader(Icons.Rounded.Security, "Security & servers")
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp)) {
                        Text(
                            "Pairing tokens are encrypted by Android Keystore. Every command is " +
                                "signed, rate-limited and replay-protected. Revoking removes the token " +
                                "from this phone and asks the server to revoke it.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.servers.isEmpty()) {
                        Text(
                            "No servers paired yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        state.servers.forEachIndexed { index, server ->
                            ServerRow(server, onRevoke = { revokeTarget = server })
                            if (index < state.servers.size - 1) SettingsDivider()
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        OutlinedButton(
                            onClick = onPairAgent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.DesktopWindows, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Pair a Windows agent")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onBridgeSetup,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Cloud, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(7.dp))
                            Text("Wake Bridge setup")
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Configuration backup",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "Exports PC profiles and network settings as an AES-256-GCM encrypted " +
                                "file. Tokens and pins are deliberately excluded — re-pair after import.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(9.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { passphrasePurpose = PassphrasePurpose.EXPORT },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Storage, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Export")
                            }
                            OutlinedButton(
                                onClick = { passphrasePurpose = PassphrasePurpose.IMPORT },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.DataObject, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import")
                            }
                        }
                    }
                }
            }

            item {
                PwCard(padding = PaddingValues(0.dp)) {
                    GroupHeader(Icons.Rounded.Search, "Tools")
                    SettingRow(
                        icon = Icons.Rounded.SettingsEthernet,
                        title = "Connection diagnostics",
                        subtitle = "Network snapshot, TLS/auth checks, WoL test",
                        onClick = onDiagnostics,
                    )
                    SettingsDivider()
                    SettingRow(
                        icon = Icons.Rounded.WifiTethering,
                        title = "Wake Bridge",
                        subtitle = "Paired bridges, targets, deployment notes",
                        onClick = onBridgeSetup,
                    )
                    SettingsDivider()
                    SettingRow(
                        icon = Icons.Rounded.Info,
                        title = "About PC Wake",
                        subtitle = "Version ${BuildConfig.VERSION_NAME}, licenses, limitations",
                        onClick = onAbout,
                    )
                }
            }

            item {
                InfoBanner(
                    "PC Wake sends only fixed power actions. It never exposes a remote shell, " +
                        "never stores plaintext passwords, and does not collect personal data.",
                )
            }
            item {
                Text(
                    "PC Wake ${BuildConfig.VERSION_NAME} · No accounts, no ads, no subscription",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    numberDialog?.let { dialog ->
        NumberSettingDialog(dialog = dialog, onDismiss = { numberDialog = null })
    }
    if (broadcastDialog) {
        TextSettingDialog(
            title = "Default broadcast",
            message = "Used when creating new PCs. Leave empty to detect from the active network.",
            initial = state.settings.defaultBroadcast,
            placeholder = "192.168.1.255",
            onSave = {
                viewModel.setDefaultBroadcast(it)
                broadcastDialog = false
            },
            onDismiss = { broadcastDialog = false },
        )
    }
    if (interfaceDialog) {
        WakeInterfaceDialog(
            interfaces = state.interfaces,
            selected = state.settings.wakeInterface,
            onSelect = {
                viewModel.setWakeInterface(it)
                interfaceDialog = false
            },
            onDismiss = { interfaceDialog = false },
        )
    }
    revokeTarget?.let { server ->
        ConfirmDialog(
            title = "Revoke ${server.name}?",
            message = "The token is removed from this phone and the server is asked to revoke it. " +
                "PCs that use it lose that connection until re-paired.",
            confirmLabel = "Revoke",
            destructive = true,
            onConfirm = { viewModel.revokeServer(server); revokeTarget = null },
            onDismiss = { revokeTarget = null },
        )
    }
    passphrasePurpose?.let { purpose ->
        PassphraseDialog(
            purpose = purpose,
            onDismiss = { passphrasePurpose = null },
            onConfirm = { passphrase ->
                pendingPassphrase = passphrase
                passphrasePurpose = null
                if (purpose == PassphrasePurpose.EXPORT) {
                    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                    createBackup.launch("pcwake-backup-$stamp.pcwake")
                } else {
                    openBackup.launch(arrayOf("*/*"))
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    PwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        Column(content = content)
    }
}

@Composable
private fun GroupHeader(icon: ImageVector, title: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        value?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.Rounded.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ServerRow(server: ServerSummary, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (server.kind == ConnectionMode.AGENT) Icons.Rounded.DesktopWindows else Icons.Rounded.Cloud,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                Formatting.endpointPretty(server.endpoint),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Pinned ${Formatting.shortFingerprint(server.fingerprint)} · " +
                    Formatting.relativeTime(server.pairedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRevoke) {
            Icon(Icons.Rounded.Delete, "Revoke", tint = MaterialTheme.colorScheme.error)
        }
    }
}

private data class NumberDialog(
    val title: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val onSave: (Int) -> Unit,
)

@Composable
private fun NumberSettingDialog(dialog: NumberDialog, onDismiss: () -> Unit) {
    var text by remember(dialog) { mutableStateOf(dialog.value.toString()) }
    val parsed = text.toIntOrNull()
    val error = when {
        parsed == null -> "Enter a number"
        parsed !in dialog.min..dialog.max -> "Range: ${dialog.min}..${dialog.max}"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    singleLine = true,
                    isError = error != null,
                    supportingText = { Text(error ?: "${dialog.min}–${dialog.max}") },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(dialog.onSave); onDismiss() },
                enabled = error == null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TextSettingDialog(
    title: String,
    message: String,
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(30) },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WakeInterfaceDialog(
    interfaces: List<WakeInterface>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wake interface") },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onSelect("") }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected.isBlank(), onClick = { onSelect("") })
                    Text("Automatic (route to broadcast)")
                }
                interfaces.forEach { iface ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(iface.name) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == iface.name,
                            onClick = { onSelect(iface.name) },
                        )
                        Column {
                            Text(iface.displayName.ifBlank { iface.name })
                            Text(
                                iface.ipv4.joinToString(", "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (interfaces.isEmpty()) {
                    Text(
                        "No active IPv4 interfaces found.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private enum class PassphrasePurpose { EXPORT, IMPORT }

@Composable
private fun PassphraseDialog(
    purpose: PassphrasePurpose,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val mismatch = purpose == PassphrasePurpose.EXPORT && confirm.isNotEmpty() && confirm != passphrase
    val tooShort = passphrase.isNotEmpty() && passphrase.length < 8
    val valid = passphrase.length >= 8 && (purpose != PassphrasePurpose.EXPORT || passphrase == confirm)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(if (purpose == PassphrasePurpose.EXPORT) "Encrypt backup" else "Decrypt backup")
        },
        text = {
            Column {
                Text(
                    if (purpose == PassphrasePurpose.EXPORT) {
                        "Choose a passphrase of at least 8 characters. Without it the backup cannot " +
                            "be restored. PC profiles are encrypted with PBKDF2 + AES-256-GCM; pairing " +
                            "tokens are never exported."
                    } else {
                        "Enter the passphrase used when this backup was created. Imported PCs must " +
                            "re-pair their agent or bridge."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = if (visible) {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                )
                if (purpose == PassphrasePurpose.EXPORT) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text("Confirm passphrase") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = if (visible) {
                            androidx.compose.ui.text.input.VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                        ),
                        supportingText = if (mismatch) {
                            { Text("Passphrases do not match") }
                        } else {
                            null
                        },
                    )
                }
                if (tooShort) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "At least 8 characters.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(onClick = { visible = !visible }) {
                    Text(if (visible) "Hide" else "Show")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(passphrase); onDismiss() }, enabled = valid) {
                Text(if (purpose == PassphrasePurpose.EXPORT) "Choose file" else "Choose backup")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
