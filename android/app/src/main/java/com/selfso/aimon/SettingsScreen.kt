/*
 * SettingsScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfso.selfupdate.SelfUpdateController
import com.selfso.selfupdate.UpdateState
import dev.saifmukhtar.antimatter.BuildConfig
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler
import dev.saifmukhtar.antimatter.feature.chat.AVAILABLE_MODEL_OPTIONS
import dev.saifmukhtar.antimatter.feature.chat.CLAUDE_MODEL_OPTIONS
import dev.saifmukhtar.antimatter.feature.chat.CURSOR_MODEL_OPTIONS

@Composable
fun SettingsScreen(
    savedUrl: String?,
    activeConversationId: String?,
    updateController: SelfUpdateController?,
    onScanQRClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDefaultModelChanged: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val notifManager = remember(context) { AimonNotificationManager(context) }
    LaunchedEffect(Unit) {
        AimonAppPreferences.init(context)
    }

    val notificationsEnabled by AimonAppPreferences.notificationsEnabled.collectAsState()
    val soundEnabled by AimonAppPreferences.soundEnabled.collectAsState()
    val vibrationEnabled by AimonAppPreferences.vibrationEnabled.collectAsState()
    val defaultModelId by AimonAppPreferences.defaultModelId.collectAsState()
    val biometricLockEnabled by AimonAppPreferences.biometricLockEnabled.collectAsState()
    val antigravitySessionsEnabled by AimonAppPreferences.antigravitySessionsEnabled.collectAsState()
    val claudeSessionsEnabled by AimonAppPreferences.claudeSessionsEnabled.collectAsState()
    val cursorSessionsEnabled by AimonAppPreferences.cursorSessionsEnabled.collectAsState()
    val antigravityNotificationsEnabled by AimonAppPreferences.antigravityNotificationsEnabled.collectAsState()
    val claudeNotificationsEnabled by AimonAppPreferences.claudeNotificationsEnabled.collectAsState()
    val cursorNotificationsEnabled by AimonAppPreferences.cursorNotificationsEnabled.collectAsState()
    val autoApproveMobileTools by AimonAppPreferences.autoApproveMobileTools.collectAsState()
    val sessionCutoffHours by AimonAppPreferences.sessionCutoffHours.collectAsState()
    val defaultProvider by AimonAppPreferences.defaultProvider.collectAsState()
    val defaultClaudeModelId by AimonAppPreferences.defaultClaudeModelId.collectAsState()
    val defaultCursorModelId by AimonAppPreferences.defaultCursorModelId.collectAsState()
    val updateState = updateController?.state?.collectAsState()?.value ?: UpdateState.Idle
    var hasCrashLogs by remember { mutableStateOf(LocalCrashHandler.hasUnsentLogs(context)) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Session alerts, desktop IDE chime, vibration, OTA updates & connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF)
                )
            }
        }

        // =====================================================================
        // 1. Notifications, Desktop IDE Sound & Vibration Card
        // =====================================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFF38BDF8)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF312E81), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color(0xFFA5B4FC),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Antigravity & agy Session Alerts",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Alerts when a prompt finishes or an agent asks a question",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    SettingsToggleRow(
                        icon = Icons.Default.NotificationsActive,
                        title = "Notifications On/Off",
                        subtitle = "Post system notifications that deep-link to the active session",
                        checked = notificationsEnabled,
                        onCheckedChange = { AimonAppPreferences.setNotificationsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.VolumeUp,
                        title = "Sound On/Off",
                        subtitle = "Play the Antigravity Desktop IDE chime when a prompt completes or needs input",
                        checked = soundEnabled,
                        onCheckedChange = {
                            AimonAppPreferences.setSoundEnabled(context, it)
                            if (it) {
                                AimonSoundPlayer.playPromptAnsweredChime(context)
                            }
                        }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.Vibration,
                        title = "Vibration On/Off",
                        subtitle = "Vibrate phone on prompt answers and agent questions",
                        checked = vibrationEnabled,
                        onCheckedChange = {
                            AimonAppPreferences.setVibrationEnabled(context, it)
                            if (it) {
                                notifManager.triggerVibration(isQuestion = false)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val targetCid = activeConversationId?.takeIf { it.isNotBlank() && it != "new" } ?: "new"
                                notifManager.notifyAgentSessionEvent(
                                    event = "prompt_answered",
                                    conversationId = targetCid,
                                    source = "cli",
                                    title = "Prompt Answered (Test)",
                                    workspace = "aimon",
                                    snippet = "Tap this notification to jump directly to the session in Chat.",
                                    stepIndex = -1
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Answer Alert", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val targetCid = activeConversationId?.takeIf { it.isNotBlank() && it != "new" } ?: "new"
                                notifManager.notifyAgentSessionEvent(
                                    event = "agent_question",
                                    conversationId = targetCid,
                                    source = "ide",
                                    title = "Agent Question (Test)",
                                    workspace = "aimon",
                                    snippet = "Agent is waiting for your decision. Tap to open session.",
                                    stepIndex = -1
                                )
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Test Question Alert", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { AimonSoundPlayer.playPromptAnsweredChime(context, "cli") },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("AGY Chime", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { AimonSoundPlayer.playPromptAnsweredChime(context, "claude") },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFDBA74)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Claude Chime", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { AimonSoundPlayer.playPromptAnsweredChime(context, "cursor") },
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFD8B4FE)),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cursor Chime", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 1B. AI Session Providers (Antigravity, Claude Code & Cursor) & Cut-Off
        // =====================================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFFF59E0B), Color(0xFFEC4899)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF78350F), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = Color(0xFFFDE68A),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "AI Session Providers & Activity Cut-Off",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Antigravity, Claude Code & Cursor discovery, cut-off window & tool approval",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Recent Session Activity Cut-Off Slider (default 24h, range 1..168h)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Session Cut-Off Window",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        val cutoffLabel = if (sessionCutoffHours % 24 == 0) {
                            "${sessionCutoffHours}h (${sessionCutoffHours / 24}d)"
                        } else {
                            "${sessionCutoffHours}h"
                        }
                        Text(
                            text = cutoffLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF38BDF8)
                        )
                    }
                    Text(
                        text = "Sessions older than ${sessionCutoffHours}h are hidden from the main list unless expanded",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9CA3AF)
                    )
                    Slider(
                        value = sessionCutoffHours.toFloat(),
                        onValueChange = { AimonAppPreferences.setSessionCutoffHours(context, it.toInt()) },
                        valueRange = 1f..168f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF38BDF8),
                            activeTrackColor = Color(0xFF38BDF8),
                            inactiveTrackColor = Color(0xFF1F2937)
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(6, 12, 24, 48, 72, 168).forEach { hrs ->
                            val selected = sessionCutoffHours == hrs
                            FilterChip(
                                selected = selected,
                                onClick = { AimonAppPreferences.setSessionCutoffHours(context, hrs) },
                                label = {
                                    Text(
                                        text = if (hrs == 168) "7d" else "${hrs}h",
                                        fontSize = 11.sp
                                    )
                                }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.Memory,
                        title = "Antigravity Sessions (IDE & agy)",
                        subtitle = "Show Antigravity IDE and agy CLI sessions in the Chat browser",
                        checked = antigravitySessionsEnabled,
                        onCheckedChange = { AimonAppPreferences.setAntigravitySessionsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.Memory,
                        title = "Claude Code Sessions (claude)",
                        subtitle = "Discover & resume Claude Code CLI sessions from ~/.claude/projects",
                        checked = claudeSessionsEnabled,
                        onCheckedChange = { AimonAppPreferences.setClaudeSessionsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.Memory,
                        title = "Cursor Agent Sessions (cursor-agent)",
                        subtitle = "Discover & resume Cursor Agent CLI sessions from ~/.cursor/projects",
                        checked = cursorSessionsEnabled,
                        onCheckedChange = { AimonAppPreferences.setCursorSessionsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.NotificationsActive,
                        title = "Antigravity Session Alerts",
                        subtitle = "Notify when Antigravity IDE or agy CLI finishes or asks a question",
                        checked = antigravityNotificationsEnabled,
                        onCheckedChange = { AimonAppPreferences.setAntigravityNotificationsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.NotificationsActive,
                        title = "Claude Code Session Alerts",
                        subtitle = "Notify with warm D-major9 bell chime for Claude Code sessions",
                        checked = claudeNotificationsEnabled,
                        onCheckedChange = { AimonAppPreferences.setClaudeNotificationsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.NotificationsActive,
                        title = "Cursor Agent Session Alerts",
                        subtitle = "Notify with crisp E-minor7 synth chime for Cursor Agent sessions",
                        checked = cursorNotificationsEnabled,
                        onCheckedChange = { AimonAppPreferences.setCursorNotificationsEnabled(context, it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = Color(0xFF1F2430)
                    )

                    SettingsToggleRow(
                        icon = Icons.Default.Fingerprint,
                        title = "Auto-Approve Mobile Tool Execution",
                        subtitle = "Pass --dangerously-skip-permissions (claude) and -f (cursor-agent) so mobile prompts never hang on local TTY approval",
                        checked = autoApproveMobileTools,
                        onCheckedChange = { AimonAppPreferences.setAutoApproveMobileTools(context, it) }
                    )
                }
            }
        }

        // =====================================================================
        // 2. App Version & OTA Update Card (Relocated from Quotas)
        // =====================================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF00CEC9), Color(0xFF3B82F6)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(Color(0xFF0E7490), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SystemUpdateAlt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "App Version & OTA Update",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val statusText = formatSelfUpdateStatusText(updateState)
                    val statusColor = when (updateState) {
                        is UpdateState.Available,
                        is UpdateState.ReadyToInstall -> Color(0xFF10B981)
                        is UpdateState.Error,
                        is UpdateState.PermissionRequired -> Color(0xFFF59E0B)
                        else -> Color(0xFFD1D5DB)
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor
                    )

                    if (updateController != null) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isBusy = updateState is UpdateState.Checking ||
                                updateState is UpdateState.Downloading
                            OutlinedButton(
                                onClick = { updateController.checkForUpdates() },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFF38BDF8)
                                )
                            ) {
                                Text("Check for Updates")
                            }

                            val isInstallActionable = updateState is UpdateState.Available ||
                                updateState is UpdateState.PermissionRequired ||
                                updateState is UpdateState.ReadyToInstall
                            if (isInstallActionable) {
                                Button(
                                    onClick = { updateController.showDialog() },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF10B981),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text("Install Update")
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 3. Default AI Model Card
        // =====================================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF4C1D95), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = Color(0xFFDDD6FE),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Default Provider & AI Models",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = "Used when starting new conversations (or switch anytime with /provider and /model)",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Default New-Chat Provider",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "antigravity" to "Antigravity",
                            "claude" to "Claude Code",
                            "cursor" to "Cursor"
                        ).forEach { (provKey, provTitle) ->
                            FilterChip(
                                selected = defaultProvider.equals(provKey, ignoreCase = true),
                                onClick = { AimonAppPreferences.setDefaultProvider(context, provKey) },
                                label = { Text(provTitle, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Antigravity Default Model",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFA5B4FC),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AVAILABLE_MODEL_OPTIONS.forEach { option ->
                            val isSelected = defaultModelId.equals(option.id, ignoreCase = true)
                            Surface(
                                onClick = {
                                    AimonAppPreferences.setDefaultModelId(context, option.id)
                                    onDefaultModelChanged(option.id)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Color(0xFF1E1B4B) else Color(0xFF171A24),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF8B5CF6) else Color(0xFF262B3D)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = {
                                            AimonAppPreferences.setDefaultModelId(context, option.id)
                                            onDefaultModelChanged(option.id)
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = option.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF9CA3AF)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Claude Code Default Model",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFFDBA74),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CLAUDE_MODEL_OPTIONS.forEach { option ->
                            FilterChip(
                                selected = defaultClaudeModelId.equals(option.id, ignoreCase = true),
                                onClick = { AimonAppPreferences.setDefaultClaudeModelId(context, option.id) },
                                label = { Text(option.label.removePrefix("Claude "), fontSize = 11.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Cursor Agent Default Model",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFFD8B4FE),
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CURSOR_MODEL_OPTIONS.take(3).forEach { option ->
                            FilterChip(
                                selected = defaultCursorModelId.equals(option.id, ignoreCase = true),
                                onClick = { AimonAppPreferences.setDefaultCursorModelId(context, option.id) },
                                label = { Text(option.id, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 4. Server Connection, Biometric Lock & Diagnostics Card
        // =====================================================================
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF10B981), Color(0xFF06B6D4)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0xFF064E3B), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Dns,
                                contentDescription = null,
                                tint = Color(0xFF6EE7B7),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Gateway Connection & Security",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = savedUrl ?: "Not connected",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF34D399)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    SettingsToggleRow(
                        icon = Icons.Default.Fingerprint,
                        title = "Biometric App Lock On/Off",
                        subtitle = "Require fingerprint / biometric unlock when opening the app",
                        checked = biometricLockEnabled,
                        onCheckedChange = { AimonAppPreferences.setBiometricLockEnabled(context, it) }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onScanQRClick,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR / Pair")
                        }

                        OutlinedButton(
                            onClick = onDisconnectClick,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Disconnect")
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 14.dp),
                        color = Color(0xFF1F2430)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = if (hasCrashLogs) Color(0xFFF59E0B) else Color(0xFF9CA3AF),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (hasCrashLogs) "Unsent crash log available" else "No recent crash logs",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasCrashLogs) Color(0xFFFDE68A) else Color(0xFF9CA3AF)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    if (LocalCrashHandler.hasUnsentLogs(context)) {
                                        LocalCrashHandler.shareLatestCrashLog(context)
                                    } else {
                                        Toast.makeText(context, "No crash logs recorded", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            ) {
                                Text("Share Log", fontSize = 12.sp)
                            }
                            if (hasCrashLogs) {
                                TextButton(
                                    onClick = {
                                        LocalCrashHandler.clearCrashLogs(context)
                                        hasCrashLogs = false
                                        Toast.makeText(context, "Crash logs cleared", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Text("Clear", fontSize = 12.sp, color = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) Color(0xFF38BDF8) else Color(0xFF6B7280),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF38BDF8),
                uncheckedThumbColor = Color(0xFF9CA3AF),
                uncheckedTrackColor = Color(0xFF1F2937)
            )
        )
    }
}
