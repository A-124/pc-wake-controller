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
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pcwake.app.BuildConfig
import com.pcwake.app.ui.components.AppTopBar
import com.pcwake.app.ui.components.InfoBanner
import com.pcwake.app.ui.components.PwCard
import com.pcwake.app.ui.components.SectionTitle

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = "About", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 10.dp,
                bottom = padding.calculateBottomPadding() + 30.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(78.dp)
                            .clip(RoundedCornerShape(25.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF5E9BFF), Color(0xFF35D0A0)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PowerSettingsNew,
                            null,
                            tint = Color(0xFF07121F),
                            modifier = Modifier.size(42.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("PC Wake", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Local and remote Windows PC power control",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            item {
                PwCard {
                    SectionTitle("What it does")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Sends real Wake-on-LAN magic packets on your LAN, or through an " +
                            "authenticated Wake Bridge from mobile data. With the Windows agent it can " +
                            "shut down, restart and sleep a PC, and report honest agent-backed health. " +
                            "Everything is local or self-hosted; there is no mandatory cloud.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                PwCard {
                    SectionTitle("Security model")
                    Spacer(Modifier.height(6.dp))
                    Bullet("One-time pairing codes; tokens issued once and stored in Android Keystore.")
                    Bullet("Every authenticated request is HMAC-SHA256 signed over method, path, timestamp, nonce and body hash.")
                    Bullet("±60 s timestamp window, 180 s replay cache, per-device rate limits.")
                    Bullet("Exact SHA-256 certificate pinning learned from the pairing QR; a changed certificate is a hard failure.")
                    Bullet("Only fixed power actions reach the agent. No shell, no arbitrary commands, no UAC bypass.")
                }
            }

            item {
                PwCard {
                    SectionTitle("Honest limitations")
                    Spacer(Modifier.height(6.dp))
                    Bullet("Wake from full shutdown depends on the motherboard, BIOS, NIC and standby power. Test Sleep first; not all boards wake from S5.")
                    Bullet("A sent magic packet is never treated as a successful boot. Online requires an authenticated agent reply.")
                    Bullet("Remote wake needs an always-on Wake Bridge on your network and a private network or HTTPS certificate.")
                    Bullet("PCs on Wi-Fi or with USB Ethernet adapters often cannot Wake-on-LAN. Use wired Ethernet.")
                    Bullet("This app cannot confirm PC health through ICMP alone; unreachable is shown as Unknown.")
                }
            }

            item {
                InfoBanner(
                    "PC Wake stores no personal data and has no analytics. Command history and paired " +
                        "tokens stay on this phone until you clear or revoke them.",
                )
            }

            item {
                PwCard {
                    SectionTitle("Open source")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Built with Kotlin, Jetpack Compose, Material 3, Room, DataStore, OkHttp, " +
                            "kotlinx.serialization and ZXing. The Windows agent is .NET 8; the Wake " +
                            "Bridge is zero-dependency Node.js. Project license: MIT.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Libraries retain their respective licenses (Apache-2.0, MIT, BSD). " +
                            "See the repository LICENSE and component READMEs.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Text(
                    "Made for people who want remote power control without subscriptions.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = MaterialTheme.colorScheme.primary)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
