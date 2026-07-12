package com.pocketclaw.app.ui.settings

import android.os.Environment
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketclaw.app.data.Preferences
import com.pocketclaw.app.data.ScheduledTask
import com.pocketclaw.app.ui.theme.*
import com.pocketclaw.claw.security.AuditLog
import com.pocketclaw.app.service.ScreenControlService
import com.llmhub.llmhub.data.LLMModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun AppearanceSection(
    themeMode: String,
    onThemeChange: (String) -> Unit,
    colors: AppColors,
) {
    SettingsSection("Appearance", colors) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Palette, null, tint = CrabOrange, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text("Theme", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((mode, label) in listOf("dark" to "Dark", "light" to "Light", "system" to "Auto")) {
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { onThemeChange(mode) },
                    label = { Text(label) },
                    leadingIcon = {
                        if (themeMode == mode) Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CrabOrangeDark,
                        selectedLabelColor = DarkTextPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
fun VoiceSection(
    sttEnabled: Boolean,
    ttsEnabled: Boolean,
    onToggleStt: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    colors: AppColors,
) {
    SettingsSection("Voice", colors) {
        SettingsToggle(
            icon = Icons.Default.Mic, title = "Speech-to-Text",
            subtitle = "Android Speech (on-device)", checked = sttEnabled,
            onCheckedChange = onToggleStt, colors = colors,
        )
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        SettingsToggle(
            icon = Icons.AutoMirrored.Filled.VolumeUp, title = "Text-to-Speech",
            subtitle = "Kokoro / System TTS", checked = ttsEnabled,
            onCheckedChange = onToggleTts, colors = colors,
        )
    }
}

@Composable
fun PermissionsSection(
    onOpenNotificationSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    colors: AppColors,
) {
    SettingsSection("Permissions", colors) {
        val accessibilityOn = ScreenControlService.isEnabled
        SettingsItem(
            icon = Icons.Default.Accessibility, title = "Screen Control",
            subtitle = if (accessibilityOn) "Enabled" else "Tap to enable in system settings",
            onClick = onOpenAccessibilitySettings, colors = colors,
        )
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        val storageGranted = Environment.isExternalStorageManager()
        SettingsItem(
            icon = Icons.Default.Folder, title = "File Access",
            subtitle = if (storageGranted) "Full access granted" else "Tap to grant storage access",
            onClick = onRequestStoragePermission, colors = colors,
        )
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        SettingsItem(
            icon = Icons.Default.Notifications, title = "Notification Access",
            subtitle = "Manage which apps to monitor",
            onClick = onOpenNotificationSettings, colors = colors,
        )
    }
}

@Composable
fun RemindersSection(
    scheduledTasks: List<ScheduledTask>,
    onDeleteTask: (ScheduledTask) -> Unit,
    onToggleTask: (ScheduledTask) -> Unit,
    onCreateTask: (String, Int, Int, Boolean) -> Unit,
    showCreateTaskDialog: Boolean,
    onShowCreateTaskDialog: () -> Unit,
    colors: AppColors,
) {
    SettingsSection("Reminders (${scheduledTasks.size})", colors) {
        if (scheduledTasks.isEmpty()) {
            Text(
                "No reminders. Ask PocketClaw or create one below.",
                color = colors.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            for (task in scheduledTasks.take(5)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d:%02d".format(task.hour, task.minute),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (task.enabled) CrabOrange else colors.textMuted,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(task.name, modifier = Modifier.weight(1f), color = colors.textPrimary, style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = task.enabled, onCheckedChange = { onToggleTask(task) },
                        colors = SwitchDefaults.colors(checkedThumbColor = CrabOrange, checkedTrackColor = CrabOrangeDark),
                        modifier = Modifier.height(24.dp),
                    )
                    IconButton(onClick = { onDeleteTask(task) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, tint = colors.textMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        HorizontalDivider(color = colors.surface, thickness = 1.dp)
        Surface(onClick = onShowCreateTaskDialog, color = colors.card) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Add, null, tint = CrabOrange, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Create Reminder", color = CrabOrange, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun MessagingSection(
    onShowDialog: () -> Unit,
    colors: AppColors,
) {
    SettingsSection("Messaging", colors) {
        SettingsItem(
            icon = Icons.Default.Send, title = "Bot Tokens",
            subtitle = "Configure Telegram / Discord / Feishu / Slack",
            onClick = onShowDialog, colors = colors,
        )
    }
}

@Composable
fun SecuritySection(
    auditEntries: List<AuditLog.Entry>,
    showAuditLog: Boolean,
    onToggleAuditLog: () -> Unit,
    colors: AppColors,
) {
    SettingsSection("Security", colors) {
        SettingsItem(
            icon = Icons.Default.Security, title = "Audit Log",
            subtitle = "${auditEntries.size} recent tool executions",
            onClick = onToggleAuditLog, colors = colors,
        )
        if (showAuditLog && auditEntries.isNotEmpty()) {
            HorizontalDivider(color = colors.surface, thickness = 1.dp)
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            for (entry in auditEntries.take(15)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        fmt.format(Date(entry.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textMuted,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${if (entry.success) "✓" else "✗"} ${entry.toolId}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.success) AccentGreen else AccentRed,
                        )
                        if (entry.args.isNotBlank()) {
                            Text(
                                entry.args.take(60),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AboutSection(colors: AppColors) {
    SettingsSection("About", colors) {
        SettingsItem(
            icon = Icons.Default.Info, title = "PocketClaw",
            subtitle = "v0.4.0 — Your pocket butler, no server needed",
            onClick = {}, colors = colors,
        )
    }
}
