package com.pcwake.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.pcwake.app.core.CommandRecord
import com.pcwake.app.core.CommandResult
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.DeviceStatus
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.Formatting
import com.pcwake.app.core.PowerAction
import com.pcwake.app.ui.viewmodel.AgentCheckUi
import com.pcwake.app.ui.viewmodel.UiEvent
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    )
}

@Composable
fun PwCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(18.dp),
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    showBorder: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val border = if (showBorder) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    } else {
        null
    }
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = containerColor),
            border = border,
        ) {
            Column(Modifier.padding(padding), content = content)
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun statusColor(status: DeviceStatus): Color = when (status) {
    DeviceStatus.ONLINE -> MaterialTheme.colorScheme.secondary
    DeviceStatus.OFFLINE -> MaterialTheme.colorScheme.error
    DeviceStatus.UNKNOWN -> MaterialTheme.colorScheme.tertiary
    DeviceStatus.WAKE_ONLY -> MaterialTheme.colorScheme.primary
    DeviceStatus.CHECKING -> MaterialTheme.colorScheme.outline
}

@Composable
fun statusLabel(status: DeviceStatus): String = when (status) {
    DeviceStatus.ONLINE -> "Online"
    DeviceStatus.OFFLINE -> "Offline"
    DeviceStatus.UNKNOWN -> "Unknown"
    DeviceStatus.WAKE_ONLY -> "Wake-only"
    DeviceStatus.CHECKING -> "Checking"
}

@Composable
fun StatusPill(status: DeviceStatus, modifier: Modifier = Modifier) {
    val color = statusColor(status)
    val animated by animateColorAsState(color, label = "statusColor")
    val pulse = if (status == DeviceStatus.ONLINE) {
        val transition = rememberInfiniteTransition(label = "onlinePulse")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
            label = "pulse",
        )
    } else {
        null
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = animated.copy(alpha = 0.13f),
        border = BorderStroke(1.dp, animated.copy(alpha = 0.35f)),
    ) {
        Row(
            Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .alpha(pulse?.value ?: 1f)
                    .clip(CircleShape)
                    .background(animated),
            )
            Text(
                statusLabel(status),
                style = MaterialTheme.typography.labelMedium,
                color = animated,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun ModeChip(mode: ConnectionMode, modifier: Modifier = Modifier) {
    val icon: ImageVector = when (mode) {
        ConnectionMode.AGENT -> Icons.Rounded.DesktopWindows
        ConnectionMode.BRIDGE -> Icons.Rounded.Cloud
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun PowerCircleButton(
    onClick: () -> Unit,
    loading: Boolean = false,
    enabled: Boolean = true,
    label: String = "Power on",
    size: androidx.compose.ui.unit.Dp = 104.dp,
) {
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        Brush.linearGradient(
                            listOf(Color(0xFF5E9BFF), Color(0xFF35D0A0)),
                        )
                    } else {
                        Brush.linearGradient(
                            listOf(colors.surfaceVariant, colors.surfaceVariant),
                        )
                    },
                )
                .clickable(enabled = enabled && !loading, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(size / 2.8f),
                )
            } else {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = label,
                    tint = if (enabled) Color(0xFF07121F) else colors.outline,
                    modifier = Modifier.size(size / 2.5f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) colors.onSurfaceVariant else colors.outline,
        )
    }
}

@Composable
fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = if (destructive) colors.errorContainer.copy(alpha = 0.55f) else colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (!enabled) colors.outline
                else if (destructive) colors.error else colors.onSurface,
                modifier = Modifier.size(22.dp),
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (!enabled) colors.outline else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceWithConnections,
    check: AgentCheckUi,
    onClick: () -> Unit,
    onWake: () -> Unit,
    waking: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.DesktopWindows, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        device.device.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            device.device.macAddress,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant,
                        )
                        if (device.agentEndpoint != null) ModeChip(ConnectionMode.AGENT)
                        if (device.bridgeEndpoint != null) ModeChip(ConnectionMode.BRIDGE)
                    }
                }
                StatusPill(check.status)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                check.detail ?: "No status yet",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Seen ${Formatting.relativeTime(device.device.lastSeenAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                    Text(
                        "Last wake sent ${Formatting.relativeTime(device.device.lastWakeAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                FilledTonalButton(
                    onClick = onWake,
                    enabled = !waking,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    if (waking) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Bolt, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Wake")
                    }
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClick) {
                    Icon(Icons.Rounded.ChevronRight, "Open details", tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    PwCard(containerColor = colors.surfaceContainer) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = colors.primary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(
                Icons.Rounded.ErrorOutline,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(19.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
fun InfoBanner(message: String, modifier: Modifier = Modifier, warning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (warning) colors.tertiaryContainer.copy(alpha = 0.65f) else colors.primary.copy(alpha = 0.08f),
    ) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(
                if (warning) Icons.Rounded.WarningAmber else Icons.Rounded.Info,
                null,
                tint = if (warning) colors.onTertiaryContainer else colors.primary,
                modifier = Modifier.size(19.dp),
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (warning) colors.onTertiaryContainer else colors.onSurface,
            )
        }
    }
}

@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    helper: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = if (error != null || helper != null) {
                { Text(error ?: helper.orEmpty()) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            singleLine = singleLine,
            enabled = enabled,
            leadingIcon = leadingIcon?.let { icon ->
                { Icon(icon, null, Modifier.size(19.dp)) }
            },
            shape = MaterialTheme.shapes.medium,
        )
    }
}

@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            color = valueColor,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

@Composable
fun ConfirmPowerDialog(
    action: PowerAction,
    deviceName: String,
    graceSeconds: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val description = when (action) {
        PowerAction.SHUTDOWN ->
            "All programs will close and unsaved work may be lost. Windows will display its own " +
                "warning and allow a $graceSeconds-second grace period."
        PowerAction.RESTART ->
            "The PC will restart after a $graceSeconds-second grace period. Unsaved work may be lost."
        PowerAction.SLEEP ->
            "The PC will sleep in $graceSeconds seconds. It remains powered for Wake-on-LAN."
        PowerAction.CANCEL -> return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.WarningAmber, null) },
        title = { Text("${action.label} $deviceName?") },
        text = { Text(description, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (action == PowerAction.SHUTDOWN) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
            ) { Text("Yes, ${action.label.lowercase()}") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun HistoryRow(record: CommandRecord, showDevice: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val icon = when (record.type) {
        com.pcwake.app.core.HistoryType.LOCAL_WAKE,
        com.pcwake.app.core.HistoryType.BRIDGE_WAKE -> Icons.Rounded.Bolt
        com.pcwake.app.core.HistoryType.SHUTDOWN -> Icons.Rounded.PowerSettingsNew
        com.pcwake.app.core.HistoryType.RESTART -> Icons.Rounded.RestartAlt
        com.pcwake.app.core.HistoryType.SLEEP -> Icons.Rounded.Schedule
        com.pcwake.app.core.HistoryType.CANCEL -> Icons.Rounded.WarningAmber
        com.pcwake.app.core.HistoryType.PAIR -> Icons.Rounded.Shield
        com.pcwake.app.core.HistoryType.TEST -> Icons.Rounded.Lan
    }
    val resultColor = when (record.result) {
        CommandResult.SENT, CommandResult.ACCEPTED -> colors.secondary
        CommandResult.INFO -> colors.primary
        CommandResult.FAILED -> colors.error
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(record.type.label, style = MaterialTheme.typography.titleMedium)
                if (showDevice) {
                    Text(
                        " · ${record.deviceName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    record.result.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = resultColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                Formatting.date(record.timestamp),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
            (record.errorMessage ?: record.detail)?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (record.errorMessage != null) colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun EventSnackbar(events: SharedFlow<UiEvent>, host: SnackbarHostState) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { event ->
                host.showSnackbar(event.message)
            }
        }
    }
}

@Composable
fun CopyableCode(value: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
            null,
            tint = if (checked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp),
        )
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
