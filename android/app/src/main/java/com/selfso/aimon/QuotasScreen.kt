/*
 * QuotasScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.selfso.selfupdate.SelfUpdateController
import com.selfso.selfupdate.UpdateState
import com.selfso.selfupdate.ui.SelfUpdateBanner
import dev.saifmukhtar.antimatter.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.NumberFormat
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

private val dialogVisibilityRegistry = Collections.synchronizedMap(
    WeakHashMap<SelfUpdateController, MutableStateFlow<Boolean>>()
)

fun SelfUpdateController.dialogVisibleFlow(): StateFlow<Boolean> {
    synchronized(dialogVisibilityRegistry) {
        return dialogVisibilityRegistry.getOrPut(this) { MutableStateFlow(false) }.asStateFlow()
    }
}

fun SelfUpdateController.showDialog() {
    synchronized(dialogVisibilityRegistry) {
        dialogVisibilityRegistry.getOrPut(this) { MutableStateFlow(false) }.value = true
    }
}

fun SelfUpdateController.hideDialog() {
    synchronized(dialogVisibilityRegistry) {
        dialogVisibilityRegistry.getOrPut(this) { MutableStateFlow(false) }.value = false
    }
}

fun formatSelfUpdateStatusText(state: UpdateState): String = when (state) {
    is UpdateState.Idle -> "Automatic update checks active (every 6h)"
    is UpdateState.Checking -> "Checking selfso.com for updates..."
    is UpdateState.UpToDate -> "App is up to date"
    is UpdateState.Available -> "Update v${state.manifest.versionName} is available"
    is UpdateState.Downloading -> {
        val progressPercent = ((state.progressFraction ?: 0f) * 100f).roundToInt()
        "Downloading update ($progressPercent%)..."
    }
    is UpdateState.PermissionRequired -> "Permission required to install APK"
    is UpdateState.ReadyToInstall, is UpdateState.Installing -> "Ready to install update"
    is UpdateState.Error -> "Update check failed: ${state.message}"
}

fun computeAntigravitySweepAngle(ag: AntigravityQuota?): Float {
    if (ag == null || !ag.isRunning) return 0f
    val avgRemainingPct = when {
        ag.quotaGroups.isNotEmpty() -> ag.quotaGroups.map { it.remainingPct }.average()
        ag.models.isNotEmpty() -> ag.models.map { it.remainingPct }.average()
        else -> 0.0
    }
    return (avgRemainingPct.coerceIn(0.0, 100.0).toFloat() / 100f) * 360f
}

@Composable
fun QuotasScreen(
    quotaStatus: QuotaStatus?,
    onRefresh: () -> Unit,
    updateController: SelfUpdateController? = null
) {
    val updateState = updateController?.state?.collectAsState()?.value ?: UpdateState.Idle

    if (quotaStatus == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (updateController != null) {
                    SelfUpdateBanner(controller = updateController)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Loading quota balances from daemon...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val numberFormat = NumberFormat.getNumberInstance(Locale.US)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Unified AI Quota Balances",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "Live telemetry from aimon daemon",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9CA3AF)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (updateController != null) {
                        val isUpdateActionable = updateState is UpdateState.Available ||
                            updateState is UpdateState.PermissionRequired ||
                            updateState is UpdateState.ReadyToInstall
                        IconButton(
                            onClick = {
                                if (isUpdateActionable) {
                                    updateController.showDialog()
                                } else {
                                    updateController.checkForUpdates()
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    if (isUpdateActionable) Color(0xFF10B981) else Color(0xFF1E293B),
                                    shape = RoundedCornerShape(12.dp)
                                )
                        ) {
                            Icon(
                                imageVector = Icons.Default.SystemUpdateAlt,
                                contentDescription = if (isUpdateActionable) "Install Update" else "Check for Updates",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFF0E7490), shape = RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        if (updateController != null) {
            item {
                SelfUpdateBanner(controller = updateController)
            }
        }

        // 1. Google Antigravity Section
        item {
            val ag = quotaStatus.antigravity
            val primaryRemainingPct = ag?.averageRemainingPct ?: 0.0
            val sweepAngle = (primaryRemainingPct.coerceIn(0.0, 100.0).toFloat() / 100f) * 360f

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF00CEC9), Color(0xFF6C5CE7)))
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
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = Color(0xFF00CEC9),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Google Antigravity",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (!ag?.userName.isNullOrBlank()) {
                                    Text(
                                        text = ag?.userName ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF9CA3AF)
                                    )
                                }
                            }
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2C324B), shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (ag != null && ag.isRunning) ag.planTier.ifBlank { "Online" } else "Offline",
                                color = Color(0xFFE0E7FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    if (ag == null || !ag.isRunning) {
                        Text(
                            text = ag?.errorMessage?.takeIf { it.isNotBlank() } ?: "Offline / Not Configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        // Large Balance + Dynamic Radial Arc Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val formattedCredits = numberFormat.format(ag.totalCreditsRemaining)
                                val minCreditReq = ag.availableCredits.firstOrNull()?.minimumCreditAmountForUsage ?: 0
                                val creditLabel = if (ag.availableCredits.isNotEmpty()) {
                                    if (minCreditReq > 0) "Google One AI Credits (Min $minCreditReq/req)" else "Google One AI Credits"
                                } else {
                                    "Total Credits Remaining"
                                }
                                Text(
                                    text = formattedCredits,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00CEC9)
                                )
                                Text(
                                    text = creditLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF9CA3AF)
                                )
                                if (ag.promptCreditsLimit > 0 || ag.flowCreditsLimit > 0 || ag.availablePromptCredits > 0 || ag.availableFlowCredits > 0) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val promptSummary = if (ag.availablePromptCredits > 0) {
                                        "${numberFormat.format(ag.availablePromptCredits)} avail"
                                    } else {
                                        "${numberFormat.format(ag.promptCreditsUsed)} / ${numberFormat.format(ag.promptCreditsLimit)}"
                                    }
                                    val flowSummary = if (ag.availableFlowCredits > 0) {
                                        "${numberFormat.format(ag.availableFlowCredits)} avail"
                                    } else {
                                        "${numberFormat.format(ag.flowCreditsUsed)} / ${numberFormat.format(ag.flowCreditsLimit)}"
                                    }
                                    val monthlySuffix = if (ag.monthlyPromptCredits > 0 || ag.monthlyFlowCredits > 0) {
                                        " (Mo: ${ag.monthlyPromptCredits / 1000}k/${ag.monthlyFlowCredits / 1000}k)"
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = "Prompt: $promptSummary · Flow: $flowSummary$monthlySuffix",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF9CA3AF)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier.size(96.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                    val strokeWidth = 10.dp.toPx()
                                    drawArc(
                                        color = Color(0xFF27272A),
                                        startAngle = -90f,
                                        sweepAngle = 360f,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                    drawArc(
                                        brush = Brush.sweepGradient(
                                            listOf(Color(0xFF00CEC9), Color(0xFF6C5CE7))
                                        ),
                                        startAngle = -90f,
                                        sweepAngle = sweepAngle,
                                        useCenter = false,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                                Text(
                                    text = "${primaryRemainingPct.coerceIn(0.0, 100.0).roundToInt()}%",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        if (ag.quotaGroups.isNotEmpty()) {
                            Text(
                                text = "Quota Groups",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ag.quotaGroups.forEach { group ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF1A1D26))
                                        .padding(12.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = group.groupName.ifBlank { "Quota Group" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        if (group.description.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = group.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF9CA3AF)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (group.buckets.isNotEmpty()) {
                                            group.buckets.forEach { bucket ->
                                                val bPct = bucket.remainingPct.coerceIn(0.0, 100.0)
                                                val bFrac = (bPct / 100.0).toFloat().coerceIn(0f, 1f)
                                                val barColor = when {
                                                    bPct <= 20.0 -> Color(0xFFEF4444)
                                                    bPct <= 50.0 -> Color(0xFFF59E0B)
                                                    else -> Color(0xFF10B981)
                                                }
                                                val resetLabel = when {
                                                    bucket.resetTimeDisplay.isNotBlank() -> "Resets in ${bucket.resetTimeDisplay}"
                                                    bPct >= 99.9 -> "Active"
                                                    else -> ""
                                                }
                                                Column(modifier = Modifier.padding(vertical = 5.dp)) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = bucket.displayName.ifBlank { bucket.windowBadge },
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color(0xFFE5E7EB)
                                                        )
                                                        Text(
                                                            text = "${bPct.roundToInt()}%",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = barColor
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(8.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color(0xFF27272A))
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxHeight()
                                                                .fillMaxWidth(bFrac)
                                                                .background(barColor)
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(3.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = bucket.windowBadge,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFF9CA3AF)
                                                        )
                                                        if (resetLabel.isNotBlank()) {
                                                            Text(
                                                                text = resetLabel,
                                                                fontSize = 11.sp,
                                                                color = Color(0xFF9CA3AF)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            val pct = group.remainingPct.coerceIn(0.0, 100.0)
                                            val fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f)
                                            val resetSuffix = if (group.resetTime.isNotBlank()) " · Resets: ${group.resetTime}" else ""
                                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "Remaining",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFFE5E7EB)
                                                    )
                                                    Text(
                                                        text = "${pct.roundToInt()}% remaining$resetSuffix",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFF9CA3AF)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(8.dp)
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(Color(0xFF27272A))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(fraction)
                                                            .background(
                                                                Brush.horizontalGradient(
                                                                    listOf(Color(0xFF00CEC9), Color(0xFF6C5CE7))
                                                                )
                                                            )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (ag.models.isNotEmpty()) {
                            if (ag.quotaGroups.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            Text(
                                text = "Individual Model Capacities (${ag.models.size})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            ag.models.forEach { model ->
                                val pct = model.remainingPct.coerceIn(0.0, 100.0)
                                val fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f)
                                val resetSuffix = if (model.resetTime.isNotBlank()) " · Resets: ${model.resetTime}" else ""
                                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = model.name.ifBlank { "Model" },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFFE5E7EB)
                                        )
                                        Text(
                                            text = "${pct.roundToInt()}% remaining$resetSuffix",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF9CA3AF)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFF27272A))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(fraction)
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(Color(0xFF00CEC9), Color(0xFF6C5CE7))
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        if (ag.quotaGroups.isEmpty() && ag.models.isEmpty()) {
                            Text(
                                text = "No active model quota data",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }
            }
        }

        // 2. Cursor IDE Section
        item {
            val cur = quotaStatus.cursor
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFF00CEC9), Color(0xFF6C5CE7)))
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
                                    .background(Color(0xFF5B21B6), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Cursor AI Spend",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2C324B), shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (cur != null && cur.isAuthenticated) cur.planName.ifBlank { "Active" } else "Offline",
                                color = Color(0xFFE0E7FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (cur == null || !cur.isAuthenticated) {
                        Text(
                            text = cur?.errorMessage?.takeIf { it.isNotBlank() } ?: "Offline / Not Configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        Text(
                            text = "Fast Requests: ${cur.individualUsed} / ${cur.individualLimit}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        val reqFraction = if (cur.individualLimit > 0) {
                            (cur.individualUsed.toFloat() / cur.individualLimit.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF27272A))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(reqFraction)
                                    .background(Color(0xFF3B82F6))
                            )
                        }

                        if (cur.billingCycleEnd.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Billing Cycle Ends: ${cur.billingCycleEnd}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }

                        if (cur.onDemandUsedCents > 0 || cur.onDemandLimitCents > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "On-Demand: $%.2f / $%.2f",
                                    cur.onDemandUsedCents / 100.0,
                                    cur.onDemandLimitCents / 100.0
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Total Spend:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            val spendUsd = cur.totalSpendCents / 100.0
                            Text(
                                text = String.format(Locale.US, "$%.2f USD", spendUsd),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981)
                            )
                        }
                    }
                }
            }
        }

        // 3. Claude Code Section
        item {
            val claude = quotaStatus.claude
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFEC4899)))
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
                                    .background(Color(0xFFB45309), shape = CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Claude Code",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        val statusBadgeText = when {
                            claude == null || !claude.enabled -> "Offline"
                            claude.accounts.isNotEmpty() -> claude.accounts.first().planTier.ifBlank { "Active" }
                            else -> "Active"
                        }
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF2C324B), shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = statusBadgeText,
                                color = Color(0xFFE0E7FF),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val isClaudeConfigured = claude != null && claude.enabled &&
                        (claude.accounts.isNotEmpty() || claude.fiveHourUtilization > 0.0 || claude.sevenDayUtilization > 0.0 || claude.totalTokens > 0L)

                    if (!isClaudeConfigured || claude == null) {
                        Text(
                            text = "Offline / Not Configured",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        val fiveHrPct = claude.fiveHourUtilization.coerceIn(0.0, 100.0)
                        val sevenDayPct = claude.sevenDayUtilization.coerceIn(0.0, 100.0)

                        // 5-Hour Utilization Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "5-Hour Window Utilization",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE5E7EB)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f%%", fiveHrPct),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF59E0B)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF27272A))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((fiveHrPct / 100.0).toFloat().coerceIn(0f, 1f))
                                    .background(Color(0xFFF59E0B))
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 7-Day Utilization Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "7-Day Window Utilization",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE5E7EB)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1f%%", sevenDayPct),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFEC4899)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color(0xFF27272A))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((sevenDayPct / 100.0).toFloat().coerceIn(0f, 1f))
                                    .background(Color(0xFFEC4899))
                            )
                        }

                        if (claude.extraUsageLimit > 0.0 || claude.extraUsageUsed > 0.0) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Extra Usage: $%.2f / $%.2f",
                                    claude.extraUsageUsed,
                                    claude.extraUsageLimit
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }

                        if (claude.accounts.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Accounts",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            claude.accounts.forEach { acc ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = acc.name.ifBlank { "Claude Account" },
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        if (acc.planTier.isNotBlank()) {
                                            Text(
                                                text = acc.planTier,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF9CA3AF)
                                            )
                                        }
                                    }
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "5h: $%.2f (%.0f%%) · 7d: $%.2f (%.0f%%)",
                                            acc.cost5h,
                                            acc.fiveHourUtilization,
                                            acc.cost7d,
                                            acc.sevenDayUtilization
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFFD1D5DB)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. App Version & OTA Update Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
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
    }
}


