package com.pcwake.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private data class OnboardPage(
    val icon: ImageVector,
    val title: String,
    val body: String,
)

private val pages = listOf(
    OnboardPage(
        icon = Icons.Rounded.PowerSettingsNew,
        title = "Wake your PC from anywhere",
        body = "Send a real Wake-on-LAN magic packet over your home LAN, or through an always-on " +
            "Wake Bridge when you are on mobile data.",
    ),
    OnboardPage(
        icon = Icons.Rounded.Bolt,
        title = "Shut down, restart, sleep",
        body = "A signed Windows agent executes power commands. The app only reports success when " +
            "the agent accepts them, and shows honest online health instead of guessing.",
    ),
    OnboardPage(
        icon = Icons.Rounded.Shield,
        title = "Pinned, paired, private",
        body = "One-time pairing codes, HMAC-signed commands, replay protection and certificate " +
            "pinning. Tokens are encrypted with Android Keystore. No cloud subscription.",
    ),
)

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme

    Scaffold(containerColor = colors.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(360.dp)
                    .padding(top = 8.dp)
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(colors.primary.copy(alpha = 0.20f), Color.Transparent),
                        ),
                    ),
            )
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(12.dp))
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
                            modifier = Modifier.size(21.dp),
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "PC Wake",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text("Skip") }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) { pageIndex ->
                    val page = pages[pageIndex]
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier
                                .size(128.dp)
                                .clip(RoundedCornerShape(40.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            colors.primary.copy(alpha = 0.22f),
                                            colors.secondary.copy(alpha = 0.14f),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                page.icon,
                                null,
                                tint = colors.primary,
                                modifier = Modifier.size(58.dp),
                            )
                        }
                        Spacer(Modifier.height(34.dp))
                        Text(
                            page.title,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            page.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 22.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    pages.indices.forEach { index ->
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .size(if (pagerState.currentPage == index) 22.dp else 8.dp, 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pagerState.currentPage == index) colors.primary
                                    else colors.outlineVariant,
                                ),
                        )
                    }
                }

                Button(
                    onClick = {
                        if (pagerState.currentPage < pages.lastIndex) {
                            scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                        } else {
                            onDone()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        if (pagerState.currentPage == pages.lastIndex) "Get started" else "Continue",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "No accounts. No ads. No subscription.",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
