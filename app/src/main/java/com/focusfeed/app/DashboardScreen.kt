package com.focusfeed.app

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.focusfeed.app.FocusAccessibilityService

@Composable
fun DashboardScreen(vm: FocusFeedViewModel) {
    val ctx = LocalContext.current
    val todayBlocks by vm.todayBlocks.collectAsState()
    val minutesSaved by vm.minutesSaved.collectAsState()
    val allowStories by vm.allowStories.collectAsState()
    val strictDmOnly by vm.strictDmOnly.collectAsState()
    val hideExplore by vm.hideExplore.collectAsState()

    var watchdogActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        vm.refreshStats()
        watchdogActive = isAccessibilityEnabled(ctx)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 20.dp)
    ) {
        item {
            Text(
                "Focus Hub",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Your distraction shield is active.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item { Spacer(Modifier.height(4.dp)) }

        // ── Metrics ──────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Blocked Today",
                    value = "$todayBlocks",
                    unit = "reels/shorts"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Focus Saved",
                    value = "$minutesSaved",
                    unit = "minutes"
                )
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

        // ── Native Watchdog ───────────────────────────────────────────
        item {
            Text(
                "Native App Watchdog",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Intercepts Reels & Shorts inside the official Instagram and YouTube apps.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (watchdogActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (watchdogActive) "Active" else "Inactive",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!watchdogActive) {
                            Text(
                                "Tap to enable in Accessibility Settings",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!watchdogActive) {
                        Button(
                            onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        ) { Text("Enable") }
                    } else {
                        Switch(checked = true, onCheckedChange = {
                            ctx.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        })
                    }
                }
            }
        }

        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

        // ── Instagram settings ────────────────────────────────────────
        item {
            Text(
                "Instagram",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        item {
            SettingsRow(
                label = "Allow Stories",
                description = "Show story rings at the top of the feed",
                checked = allowStories,
                onCheckedChange = vm::setAllowStories
            )
        }

        item {
            SettingsRow(
                label = "Hide Explore Tab",
                description = "Block the Explore/Search tab (Reels gateway)",
                checked = hideExplore,
                onCheckedChange = vm::setHideExplore
            )
        }

        item {
            SettingsRow(
                label = "Strict DM-Only Mode",
                description = "Lock the WebView to /direct/ — block all other Instagram sections",
                checked = strictDmOnly,
                onCheckedChange = vm::setStrictDmOnly
            )
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SettingsRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private fun isAccessibilityEnabled(ctx: android.content.Context): Boolean {
    val service = "${ctx.packageName}/${FocusAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        ctx.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(service, ignoreCase = true) }
}
