package com.pcwake.app.ui.screens

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pcwake.app.AppContainer
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Formatting
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.CopyableCode
import com.pcwake.app.ui.components.ErrorBanner
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.LabeledField
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle
import com.pcwake.app.ui.viewmodel.PairingUiState
import com.pcwake.app.ui.viewmodel.PairingViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import kotlinx.coroutines.launch

@Composable
fun PairingScreen(
    container: AppContainer,
    initialMode: ConnectionMode,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val viewModel = appViewModel(key = "pairing-${initialMode.wire}") {
        PairingViewModel(container, initialMode)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    EventSnackbar(viewModel.events, snackbar)
    var payloadInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.created.collect { id -> onCreated(id) }
    }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            scope.launch { snackbar.showSnackbar("No QR code captured.") }
        } else {
            payloadInput = contents
            viewModel.applyPayload(contents)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { AppTopBar(title = "Device pairing", onBack = onBack) },
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
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.mode == ConnectionMode.AGENT,
                        onClick = { viewModel.setMode(ConnectionMode.AGENT) },
                        label = { Text("Windows agent") },
                        leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null, Modifier.size(16.dp)) },
                        enabled = !state.pairing && state.result == null,
                    )
                    FilterChip(
                        selected = state.mode == ConnectionMode.BRIDGE,
                        onClick = { viewModel.setMode(ConnectionMode.BRIDGE) },
                        label = { Text("Wake Bridge") },
                        leadingIcon = { Icon(Icons.Rounded.Cloud, null, Modifier.size(16.dp)) },
                        enabled = !state.pairing && state.result == null,
                    )
                }
            }

            item {
                PwCard {
                    Text(
                        if (state.mode == ConnectionMode.AGENT) "Pair this phone with the Windows agent"
                        else "Pair this phone with your Wake Bridge",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (state.mode == ConnectionMode.AGENT) {
                            "On the PC, open the PcWake tray → Pair device. Scan the QR or enter the " +
                                "one-time code and certificate fingerprint."
                        } else {
                            "On the bridge, run the admin CLI to start a 5-minute pairing window " +
                                "(node src/cli.js pair). Scan its QR/payload or enter the code."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            try {
                                scanner.launch(
                                    ScanOptions()
                                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                        .setPrompt("Scan the PC Wake pairing QR")
                                        .setBeepEnabled(false)
                                        .setOrientationLocked(false),
                                )
                            } catch (_: ActivityNotFoundException) {
                                scope.launch { snackbar.showSnackbar("No QR scanner is available.") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.pairing && state.result == null,
                    ) {
                        Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan pairing QR")
                    }
                    Spacer(Modifier.height(10.dp))
                    LabeledField(
                        label = "Or paste the pairing payload",
                        value = payloadInput,
                        onValueChange = { payloadInput = it },
                        placeholder = "{\"v\":1,\"kind\":\"agent\",...}",
                        singleLine = false,
                        enabled = !state.pairing && state.result == null,
                        keyboardType = KeyboardType.Ascii,
                    )
                    TextButton(
                        onClick = { viewModel.applyPayload(payloadInput) },
                        enabled = payloadInput.isNotBlank() && !state.pairing,
                    ) { Text("Load payload") }
                }
            }

            if (state.result == null) {
                item {
                    PwCard {
                        SectionTitle("Manual pairing")
                        Spacer(Modifier.height(10.dp))
                        LabeledField(
                            label = "Host / IP",
                            value = state.host,
                            onValueChange = viewModel::setHost,
                            placeholder = if (state.mode == ConnectionMode.AGENT) "192.168.1.50" else "bridge.example.net",
                            keyboardType = KeyboardType.Uri,
                            enabled = !state.pairing,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LabeledField(
                                label = "HTTPS port",
                                value = state.port,
                                onValueChange = viewModel::setPort,
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier.weight(1f),
                                enabled = !state.pairing,
                            )
                            LabeledField(
                                label = "Pairing code",
                                value = state.code,
                                onValueChange = viewModel::setCode,
                                placeholder = "K7Q2-M4XP",
                                modifier = Modifier.weight(1.3f),
                                enabled = !state.pairing,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        LabeledField(
                            label = "Certificate fingerprint (recommended)",
                            value = state.fingerprint,
                            onValueChange = viewModel::setFingerprint,
                            placeholder = "64 hex characters",
                            helper = "Shown with the QR. Prevents pairing with an impostor server.",
                            keyboardType = KeyboardType.Ascii,
                            enabled = !state.pairing,
                        )
                        Spacer(Modifier.height(12.dp))
                        LabeledField(
                            label = "This phone's name",
                            value = state.deviceName,
                            onValueChange = viewModel::setDeviceName,
                            enabled = !state.pairing,
                        )
                        if (state.fingerprint.isBlank()) {
                            Spacer(Modifier.height(10.dp))
                            InfoBanner(
                                "No fingerprint entered. The app will trust the certificate on first " +
                                    "use (TOFU). Prefer the QR for a verified pin.",
                                warning = true,
                            )
                        }
                        state.error?.let {
                            Spacer(Modifier.height(10.dp))
                            ErrorBanner(it)
                        }
                        Spacer(Modifier.height(14.dp))
                        Button(
                            onClick = { viewModel.pairNow(allowTofu = false) },
                            enabled = !state.pairing,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                        ) {
                            if (state.pairing) {
                                CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.pairing) "Pairing…" else "Pair securely")
                        }
                    }
                }
            }

            state.result?.let { result ->
                item {
                    PairSuccessCard(
                        state = state,
                        viewModel = viewModel,
                        onDone = onBack,
                    )
                }
            }

            item {
                PwCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Shield, null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(8.dp))
                        Text("What pairing does", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The server issues a one-time device token. Every command is HMAC-SHA256 signed " +
                            "with a timestamp and nonce; the server rejects replays and expired requests. " +
                            "The token is stored encrypted by Android Keystore. The certificate pin makes " +
                            "a changed server certificate a hard error.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (state.showTofuWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissTofuWarning,
            icon = { Icon(Icons.Rounded.Shield, null) },
            title = { Text("Trust this certificate?") },
            text = {
                Text(
                    "No fingerprint was provided. The app will connect with trust-on-first-use and " +
                        "store the certificate it sees. On an untrusted network this could pair you " +
                        "with an impostor. For the strongest security, scan the server's QR code.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.pairNow(allowTofu = true) }) {
                    Text("Continue with TOFU")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissTofuWarning) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PairSuccessCard(
    state: PairingUiState,
    viewModel: PairingViewModel,
    onDone: () -> Unit,
) {
    val result = state.result ?: return
    PwCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Paired with ${result.serverName}", style = MaterialTheme.typography.titleLarge)
                Text(
                    ResultKindLabel(result.mode),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        CopyableCode(result.endpoint.removePrefix("https://"))
        Spacer(Modifier.height(8.dp))
        Text(
            "Certificate pin ${Formatting.shortFingerprint(result.fingerprint)}",
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        SectionTitle("Create the PC profile")
        Spacer(Modifier.height(8.dp))

        val candidates = if (result.mode == ConnectionMode.AGENT) {
            result.adapters.mapIndexed { index, adapter ->
                Triple(
                    "${adapter.name}",
                    "${adapter.mac}${if (adapter.ipv4.isNotEmpty()) " · ${adapter.ipv4.first()}" else ""}",
                    index,
                )
            }
        } else {
            result.targets.mapIndexed { index, target ->
                Triple(target.name.ifBlank { target.mac }, target.mac, index)
            }
        }
        if (candidates.isEmpty()) {
            InfoBanner(
                "The server did not report any network adapter or target. The pairing is saved; " +
                    "add a PC manually and enter its MAC.",
            )
        } else {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    candidates.forEach { (name, subtitle, index) ->
                        val selected = state.selectedIndex == index
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (result.mode == ConnectionMode.AGENT) {
                                        viewModel.selectAdapter(index)
                                    } else {
                                        viewModel.selectTarget(index)
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = {
                                if (result.mode == ConnectionMode.AGENT) {
                                    viewModel.selectAdapter(index)
                                } else {
                                    viewModel.selectTarget(index)
                                }
                            })
                            Column {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            LabeledField(
                label = "PC nickname",
                value = state.nickname,
                onValueChange = viewModel::setNickname,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledField(
                    label = "Broadcast",
                    value = state.broadcast,
                    onValueChange = viewModel::setBroadcast,
                    placeholder = "192.168.1.255",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1.6f),
                    helper = "Adjust if not /24",
                )
                LabeledField(
                    label = "UDP port",
                    value = state.udpPort,
                    onValueChange = viewModel::setUdpPort,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier.weight(0.8f),
                )
            }
            state.saveError?.let {
                Spacer(Modifier.height(10.dp))
                ErrorBanner(it)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = viewModel::saveDevice,
                enabled = !state.savingDevice,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.savingDevice) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Save PC & open it")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done — pairing is saved") }
    }
}

private fun ResultKindLabel(mode: ConnectionMode): String =
    if (mode == ConnectionMode.AGENT) "Signed Windows agent" else "Authenticated Wake Bridge"
