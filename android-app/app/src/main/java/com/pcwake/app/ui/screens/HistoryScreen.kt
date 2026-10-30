package com.pcwake.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pcwake.app.AppContainer
import com.pcwake.app.core.Formatting
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.ConfirmDialog
import com.pcwake.app.ui.components.EmptyStateCard
import com.pcwake.app.ui.components.EventSnackbar
import com.pcwake.app.ui.components.HistoryRow
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.viewmodel.HistoryViewModel
import com.pcwake.app.ui.viewmodel.appViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(container: AppContainer) {
    val viewModel = appViewModel { HistoryViewModel(container) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    EventSnackbar(viewModel.events, snackbar)
    var menuOpen by remember { mutableStateOf(false) }
    var clearConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) viewModel.exportCsv(uri) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AppTopBar(
                title = "Command history",
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Rounded.MoreVert, "History options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Export CSV") },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                            onClick = {
                                menuOpen = false
                                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                                exportLauncher.launch("pcwake-history-$stamp.csv")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear history") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteSweep, null) },
                            onClick = { menuOpen = false; clearConfirm = true },
                        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.filterDeviceId == null,
                        onClick = { viewModel.setFilter(null) },
                        label = { Text("All") },
                    )
                    state.devices.forEach { device ->
                        FilterChip(
                            selected = state.filterDeviceId == device.device.id,
                            onClick = { viewModel.setFilter(device.device.id) },
                            label = { Text(device.device.name) },
                        )
                    }
                }
            }

            if (state.records.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Rounded.History,
                        title = "No commands yet",
                        message = "Wake packets, shutdowns, restarts, sleep commands and diagnostics " +
                            "will be recorded here with their honest result.",
                    )
                }
            } else {
                val groups = state.records.groupBy {
                    Formatting.date(it.timestamp, "EEEE, d MMMM yyyy")
                }
                groups.forEach { (day, records) ->
                    item(key = "day-$day") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                day,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${records.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "day-card-$day") {
                        PwCard(padding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                            records.forEachIndexed { index, record ->
                                HistoryRow(
                                    record,
                                    showDevice = state.filterDeviceId == null,
                                )
                                if (index < records.size - 1) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "History is local to this phone. Export creates a CSV without any tokens or " +
                        "certificate pins.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (clearConfirm) {
        ConfirmDialog(
            title = "Clear all history?",
            message = "This removes every recorded command on this phone. It cannot be undone.",
            confirmLabel = "Clear",
            destructive = true,
            onConfirm = { clearConfirm = false; viewModel.clearHistory() },
            onDismiss = { clearConfirm = false },
        )
    }
}
