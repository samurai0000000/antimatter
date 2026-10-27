/*
 * QuotasScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.ceil
import kotlin.math.max
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

private fun Modifier.drawIdealPaceMarker(idealFraction: Float?): Modifier {
    if (idealFraction == null || idealFraction.isNaN()) return this
    val clamped = idealFraction.coerceIn(0f, 1f)
    return this.drawWithContent {
        drawContent()
        if (size.width > 0f && size.height > 0f) {
            val padPx = 1.dp.toPx()
            val x = (clamped * size.width).coerceIn(padPx, max(padPx, size.width - padPx))
            drawLine(
                color = Color(0xCC000000),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 4.dp.toPx()
            )
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

private val cursorCategoryGradients = listOf(
    listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)),
    listOf(Color(0xFF3B82F6), Color(0xFF06B6D4)),
    listOf(Color(0xFF10B981), Color(0xFF3B82F6)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF6366F1), Color(0xFFA855F7)),
    listOf(Color(0xFF14B8A6), Color(0xFF10B981))
)

@Composable
private fun ProviderCardHeader(
    title: String,
    isOnline: Boolean,
    badgeText: String,
    badgeStyle: String = "cyan"
) {
    val dotColor = if (isOnline) Color(0xFF10B981) else Color(0xFFEF4444)
    val (badgeBg, badgeBorder, badgeFg) = when (badgeStyle) {
        "cyan" -> Triple(Color(0x2606B6D4), Color(0x5906B6D4), Color(0xFF22D3EE))
        "magenta" -> Triple(Color(0x26EC4899), Color(0x59EC4899), Color(0xFFF472B6))
        else -> Triple(Color(0xFF1E2230), Color(0xFF2E3446), Color(0xFF9CA3AF))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
        Box(
            modifier = Modifier
                .background(badgeBg, shape = RoundedCornerShape(999.dp))
                .border(1.dp, badgeBorder, shape = RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = badgeText,
                color = badgeFg,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ClaudeWindowTile(
    label: String,
    costUsd: Double,
    tokens: Long,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF181B24))
            .border(1.dp, Color(0xFF252A37), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = label.uppercase(Locale.US),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = Color(0xFF9CA3AF)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = String.format(Locale.US, "$%.2f", costUsd),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFBBF24)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${formatClaudeTokens(tokens)} tokens",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF)
            )
        }
    }
}

@Composable
private fun CursorCumulativeSpendChart(
    dailySpend: List<DailySpend>,
    modifier: Modifier = Modifier
) {
    if (dailySpend.isEmpty()) return
    val maxCum = max(dailySpend.maxOfOrNull { it.cumulativeUsd } ?: 0.0, 10.0)
    val yMax = max(50.0, ceil(maxCum / 50.0) * 50.0)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Cumulative trend",
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF)
            )
            Text(
                text = String.format(Locale.US, "Scale: $0 – $%.0f", yMax),
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF12141C))
                .border(1.dp, Color(0xFF232734), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val gridColor = Color(0xFF232734)
                listOf(0f, 0.5f, 1f).forEach { frac ->
                    val y = h * frac
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                val n = dailySpend.size
                val points = dailySpend.mapIndexed { idx, pt ->
                    val x = if (n == 1) w / 2f else (idx.toFloat() / (n - 1).toFloat()) * w
                    val y = (h - ((pt.cumulativeUsd / yMax).coerceIn(0.0, 1.0).toFloat() * h))
                    Offset(x, y)
                }

                if (points.isNotEmpty()) {
                    val linePath = Path().apply {
                        moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            lineTo(points[i].x, points[i].y)
                        }
                    }
                    val areaPath = Path().apply {
                        moveTo(points.first().x, h)
                        points.forEach { p -> lineTo(p.x, p.y) }
                        lineTo(points.last().x, h)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(Color(0x663B82F6), Color(0x003B82F6)),
                            startY = 0f,
                            endY = h
                        )
                    )
                    drawPath(
                        path = linePath,
                        color = Color(0xFF3B82F6),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    points.forEach { p ->
                        drawCircle(
                            color = Color(0xFF60A5FA),
                            radius = 3.dp.toPx(),
                            center = p
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = dailySpend.first().dayStr,
                fontSize = 10.sp,
                color = Color(0xFF9CA3AF)
            )
            if (dailySpend.size > 1) {
                Text(
                    text = dailySpend.last().dayStr,
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
    }
}

@Composable
private fun ClaudeDailyCostChart(
    daily: List<ClaudeDailyPoint>,
    modifier: Modifier = Modifier
) {
    val points = daily.takeLast(30)
    if (points.isEmpty()) return
    val maxCostUsd = max(points.maxOfOrNull { it.estCostUsd } ?: 0.0, 0.01)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EST. COST PER DAY (UTC)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
                color = Color(0xFF9CA3AF)
            )
            Text(
                text = String.format(Locale.US, "max $%.2f", maxCostUsd),
                fontSize = 11.sp,
                color = Color(0xFF9CA3AF)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF12141C))
                .border(1.dp, Color(0xFF232734), RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val slot = w / points.size.toFloat()
                val barW = max(2.dp.toPx(), slot * 0.72f)
                points.forEachIndexed { idx, pt ->
                    val frac = (pt.estCostUsd / maxCostUsd).coerceIn(0.0, 1.0).toFloat()
                    val barH = max(2.dp.toPx(), frac * h)
                    val x = idx * slot + (slot - barW) / 2f
                    val y = h - barH
                    drawRoundRect(
                        color = Color(0xFFF59E0B),
                        topLeft = Offset(x, y),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = points.first().dayStr,
                fontSize = 10.sp,
                color = Color(0xFF9CA3AF)
            )
            if (points.size > 1) {
                Text(
                    text = points.last().dayStr,
                    fontSize = 10.sp,
                    color = Color(0xFF9CA3AF)
                )
            }
        }
    }
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
    val nowMs = if (quotaStatus.serverTimestampMs > 0L) {
        quotaStatus.serverTimestampMs
    } else {
        System.currentTimeMillis()
    }

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

        // =====================================================================
        // 1. Google Antigravity Card (matching Web UI #ag-card)
        // =====================================================================
        item {
            val ag = quotaStatus.antigravity
            val isAgOnline = ag != null && ag.isRunning

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
                    ProviderCardHeader(
                        title = "Google Antigravity",
                        isOnline = isAgOnline,
                        badgeText = if (ag != null && ag.isRunning) ag.planTier.ifBlank { "Google AI Ultra" } else "Offline",
                        badgeStyle = if (isAgOnline) "cyan" else "neutral"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (ag == null || !ag.isRunning) {
                        Text(
                            text = ag?.errorMessage?.takeIf { it.isNotBlank() } ?: "Antigravity process not running",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        // Credits & Allowances Strip (2 side-by-side pills matching .credits-strip)
                        val firstCredit = ag.availableCredits.firstOrNull()
                        val availableCreditAmount = firstCredit?.creditAmount ?: 0
                        val minCreditReq = firstCredit?.minimumCreditAmountForUsage ?: 50
                        val promptFlowText = if (
                            ag.availablePromptCredits > 0 ||
                            ag.availableFlowCredits > 0 ||
                            ag.monthlyPromptCredits > 0
                        ) {
                            "${numberFormat.format(ag.availablePromptCredits)} / ${numberFormat.format(ag.availableFlowCredits)}"
                        } else {
                            "--"
                        }
                        val monthlyTierLabel = if (ag.monthlyPromptCredits > 0 || ag.monthlyFlowCredits > 0) {
                            "Mo: ${ag.monthlyPromptCredits / 1000}k / ${ag.monthlyFlowCredits / 1000}k"
                        } else {
                            "Monthly"
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Pill 1: Google One AI Credits
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF181B24))
                                    .border(1.dp, Color(0xFF252A37), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Google One AI Credits",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color(0xFF9CA3AF),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Min $minCreditReq / req",
                                        fontSize = 10.sp,
                                        color = Color(0xFF6B7280)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = numberFormat.format(availableCreditAmount),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF22D3EE)
                                    )
                                }
                            }

                            // Pill 2: Prompt & Flow Balance
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF181B24))
                                    .border(1.dp, Color(0xFF252A37), RoundedCornerShape(12.dp))
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "Prompt & Flow Balance",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF9CA3AF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = monthlyTierLabel,
                                        fontSize = 10.sp,
                                        color = Color(0xFF6B7280)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = promptFlowText,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        // Model Quota Limits (Quota Groups only; no Individual Model Capacities clutter)
                        Text(
                            text = "MODEL QUOTA LIMITS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = Color(0xFF9CA3AF)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (ag.quotaGroups.isNotEmpty()) {
                            ag.quotaGroups.forEach { group ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF181B24))
                                        .border(1.dp, Color(0xFF252A37), RoundedCornerShape(14.dp))
                                        .padding(14.dp)
                                ) {
                                    Column {
                                        Text(
                                            text = group.groupName.ifBlank { "Quota Group" },
                                            style = MaterialTheme.typography.titleSmall,
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
                                        Spacer(modifier = Modifier.height(10.dp))

                                        if (group.buckets.isNotEmpty()) {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(Color(0xFF12141C))
                                                            .border(1.dp, Color(0xFF222634), RoundedCornerShape(10.dp))
                                                            .padding(10.dp)
                                                    ) {
                                                        Column {
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Text(
                                                                    text = bucket.displayName.ifBlank { "Capacity" },
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
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            val bucketIdealFrac = bucket.computeIdealRemainingFraction(nowMs)
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .height(8.dp)
                                                                    .clip(RoundedCornerShape(4.dp))
                                                                    .background(Color(0xFF27272A))
                                                                    .drawIdealPaceMarker(bucketIdealFrac)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .fillMaxHeight()
                                                                        .fillMaxWidth(bFrac)
                                                                        .background(barColor)
                                                                )
                                                            }
                                                            Spacer(modifier = Modifier.height(6.dp))
                                                            Row(
                                                                modifier = Modifier.fillMaxWidth(),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .background(
                                                                            Color(0xFF1E2230),
                                                                            shape = RoundedCornerShape(6.dp)
                                                                        )
                                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                                ) {
                                                                    Text(
                                                                        text = bucket.windowBadge,
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.SemiBold,
                                                                        color = Color(0xFF9CA3AF)
                                                                    )
                                                                }
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
                                                }
                                            }
                                        } else {
                                            val pct = group.remainingPct.coerceIn(0.0, 100.0)
                                            val fraction = (pct / 100.0).toFloat().coerceIn(0f, 1f)
                                            val barColor = when {
                                                pct <= 20.0 -> Color(0xFFEF4444)
                                                pct <= 50.0 -> Color(0xFFF59E0B)
                                                else -> Color(0xFF10B981)
                                            }
                                            val resetSuffix = if (group.resetTime.isNotBlank()) "Resets in ${group.resetTime}" else ""
                                            Column {
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
                                                        text = "${pct.roundToInt()}%",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = barColor
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
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
                                                            .background(barColor)
                                                    )
                                                }
                                                if (resetSuffix.isNotBlank()) {
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = resetSuffix,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF9CA3AF)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "No quota groups reported",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9CA3AF)
                            )
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 2. Cursor Card (matching Web UI .cursor-card)
        // =====================================================================
        item {
            val cur = quotaStatus.cursor
            val isCursorOnline = cur != null && cur.isAuthenticated

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(listOf(Color(0xFFEC4899), Color(0xFF6C5CE7)))
                ),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF12141A))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    ProviderCardHeader(
                        title = "Cursor",
                        isOnline = isCursorOnline,
                        badgeText = if (cur != null && cur.isAuthenticated) cur.planTier.ifBlank { "Pro" } else (cur?.planTier ?: "Unauthenticated"),
                        badgeStyle = if (isCursorOnline) "magenta" else "neutral"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (cur == null || !cur.isAuthenticated) {
                        Text(
                            text = cur?.errorMessage?.takeIf { it.isNotBlank() } ?: "Unauthenticated",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        val used = cur.fastRequestsUsed
                        val limit = cur.fastRequestsLimit
                        val usedFrac = if (limit > 0) (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) else 0f
                        val usedPct = (usedFrac * 100f).roundToInt()
                        val remaining = cur.fastRequestsRemaining

                        // Usage Stat Box: Fast Requests Pool
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF181B24))
                                .border(1.dp, Color(0xFF252A37), RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Fast Requests Pool",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Text(
                                        text = "${numberFormat.format(used)} / ${numberFormat.format(limit)}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                val cursorIdealFrac = cur.computeIdealUsedFraction(nowMs)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF27272A))
                                        .drawIdealPaceMarker(cursorIdealFrac)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(usedFrac)
                                            .background(
                                                Brush.horizontalGradient(
                                                    listOf(Color(0xFFEC4899), Color(0xFF06B6D4))
                                                )
                                            )
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${numberFormat.format(remaining)} fast requests remaining",
                                        fontSize = 12.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Text(
                                        text = "$usedPct% used",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE5E7EB)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Billing Cycle Reset & Days Remaining Details Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF181B24))
                                .border(1.dp, Color(0xFF252A37), RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Billing Cycle Reset",
                                        fontSize = 12.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Text(
                                        text = formatIsoDateShort(cur.cycleResetIso),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Days Remaining",
                                        fontSize = 12.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Text(
                                        text = computeDaysRemaining(cur.cycleResetIso),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Personal Usage / Cumulative Spend Section
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF181B24))
                                .border(1.dp, Color(0xFF252A37), RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "PERSONAL USAGE",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 0.6.sp,
                                            color = Color(0xFF9CA3AF)
                                        )
                                        Text(
                                            text = "Cumulative Spend",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Track your spend against last month.",
                                            fontSize = 11.sp,
                                            color = Color(0xFF9CA3AF)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = String.format(Locale.US, "$%.2f", cur.totalSpendUsd),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color(0xFF38BDF8)
                                        )
                                        if (cur.prevCycleSpendUsd > 0.0) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(
                                                        Color(0xFF1E2230),
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = String.format(
                                                        Locale.US,
                                                        "vs $%.2f last month",
                                                        cur.prevCycleSpendUsd
                                                    ),
                                                    fontSize = 10.sp,
                                                    color = Color(0xFF9CA3AF)
                                                )
                                            }
                                        }
                                    }
                                }

                                if (cur.dailySpend.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    CursorCumulativeSpendChart(dailySpend = cur.dailySpend)
                                }

                                if (cur.spendByCategory.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "SPEND BY MODEL",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.5.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        cur.spendByCategory.forEachIndexed { idx, cat ->
                                            val pct = cat.percentage.coerceIn(0.0, 100.0)
                                            val frac = (pct / 100.0).toFloat().coerceIn(0f, 1f)
                                            val grad = cursorCategoryGradients[idx % cursorCategoryGradients.size]
                                            Column {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = cat.category,
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Medium,
                                                        color = Color(0xFFE5E7EB),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = String.format(
                                                            Locale.US,
                                                            "$%.2f (%.1f%%)",
                                                            cat.spendUsd,
                                                            pct
                                                        ),
                                                        fontSize = 12.sp,
                                                        color = Color(0xFF9CA3AF)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(Color(0xFF27272A))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxHeight()
                                                            .fillMaxWidth(frac)
                                                            .background(Brush.horizontalGradient(grad))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // =====================================================================
        // 3. Claude Code Card (matching Web UI #claude-card)
        // =====================================================================
        item {
            val claude = quotaStatus.claude
            val primaryAccount = claude?.accounts?.firstOrNull()
            val isClaudeOnline = claude != null && claude.enabled && primaryAccount != null && primaryAccount.hasData

            val tierBadgeText = when {
                claude == null || !claude.enabled -> "Disabled"
                primaryAccount == null -> "No account"
                else -> primaryAccount.displayTierName
            }
            val tierBadgeStyle = when (primaryAccount?.planTier?.lowercase()) {
                "enterprise" -> "cyan"
                "personal" -> "magenta"
                else -> "neutral"
            }

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
                    ProviderCardHeader(
                        title = "Claude Code",
                        isOnline = isClaudeOnline,
                        badgeText = tierBadgeText,
                        badgeStyle = if (isClaudeOnline) tierBadgeStyle else "neutral"
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (claude == null || !claude.enabled) {
                        Text(
                            text = "Claude usage collection is disabled.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else if (primaryAccount == null) {
                        Text(
                            text = "No Claude accounts are configured.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else if (!primaryAccount.hasData) {
                        Text(
                            text = "No Claude Code usage found",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9CA3AF)
                        )
                    } else {
                        val a = primaryAccount
                        val windowDays = if (a.windowDays > 0) a.windowDays else 30
                        val hasCycle = a.cycleConfigured || a.spendLimitUsd > 0.0 || (a.cycle != null && a.cycle.estCostUsd > 0.0)

                        // Main Usage Stat Box (Est. cost this cycle vs spend limit)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF181B24))
                                .border(1.dp, Color(0xFF252A37), RoundedCornerShape(12.dp))
                                .padding(14.dp)
                        ) {
                            Column {
                                if (hasCycle) {
                                    val cycleCost = if (a.cycleCostUsd > 0.0) a.cycleCostUsd else a.windowCostUsd
                                    val limit = a.spendLimitUsd
                                    val pctOfLimit = when {
                                        a.estPctOfLimit > 0.0 -> a.estPctOfLimit
                                        limit > 0.0 -> (cycleCost / limit) * 100.0
                                        else -> 0.0
                                    }
                                    val barFrac = (pctOfLimit.coerceIn(0.0, 100.0) / 100.0).toFloat()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${a.valueLabel} this cycle",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF9CA3AF)
                                        )
                                        Text(
                                            text = if (limit > 0.0) {
                                                String.format(Locale.US, "$%.2f / $%.2f", cycleCost, limit)
                                            } else {
                                                String.format(Locale.US, "$%.2f", cycleCost)
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }

                                    if (limit > 0.0) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val claudeIdealFrac = a.computeIdealUsedFraction(nowMs)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFF27272A))
                                                .drawIdealPaceMarker(claudeIdealFrac)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(barFrac)
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(Color(0xFFF59E0B), Color(0xFFEC4899))
                                                        )
                                                    )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Cycle resets ${formatIsoDateShort(a.cycleResetIso)}",
                                            fontSize = 12.sp,
                                            color = Color(0xFF9CA3AF)
                                        )
                                        if (limit > 0.0) {
                                            Text(
                                                text = String.format(Locale.US, "%.1f%% of limit", pctOfLimit),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFFFBBF24)
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${a.valueLabel} ($windowDays d)",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = Color(0xFF9CA3AF)
                                        )
                                        Text(
                                            text = String.format(Locale.US, "$%.2f", a.windowCostUsd),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "${numberFormat.format(a.window?.messages ?: 0L)} messages",
                                        fontSize = 12.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 2x2 Window Tiles Grid: Last 5 h, Last 7 d, Last 30 d, Today (UTC)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ClaudeWindowTile(
                                    label = "Last 5 h",
                                    costUsd = a.cost5h,
                                    tokens = a.last5h?.totalTokens ?: 0L,
                                    modifier = Modifier.weight(1f)
                                )
                                ClaudeWindowTile(
                                    label = "Last 7 d",
                                    costUsd = a.cost7d,
                                    tokens = a.last7d?.totalTokens ?: 0L,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ClaudeWindowTile(
                                    label = "Last $windowDays d",
                                    costUsd = a.windowCostUsd,
                                    tokens = a.window?.totalTokens ?: a.totalTokens,
                                    modifier = Modifier.weight(1f)
                                )
                                ClaudeWindowTile(
                                    label = "Today (UTC)",
                                    costUsd = a.todayCostUsd,
                                    tokens = a.today?.totalTokens ?: 0L,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        // Est. cost by model
                        if (a.models.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "EST. COST BY MODEL",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = Color(0xFF9CA3AF)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val topModels = a.models.take(6)
                            val maxModelCost = max(topModels.maxOfOrNull { it.estCostUsd } ?: 0.0, 0.01)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                topModels.forEach { m ->
                                    val barFrac = (m.estCostUsd / maxModelCost).coerceIn(0.01, 1.0).toFloat()
                                    Column {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = m.model,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0xFFE5E7EB),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = String.format(
                                                    Locale.US,
                                                    "$%.2f · %s",
                                                    m.estCostUsd,
                                                    formatClaudeTokens(m.totalTokens)
                                                ),
                                                fontSize = 12.sp,
                                                color = Color(0xFF9CA3AF)
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(6.dp)
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color(0xFF27272A))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(barFrac)
                                                    .background(
                                                        Brush.horizontalGradient(
                                                            listOf(Color(0xFFF59E0B), Color(0xFFEC4899))
                                                        )
                                                    )
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Est. cost per day (UTC)
                        if (a.daily.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            ClaudeDailyCostChart(daily = a.daily)
                        }

                        // Other accounts
                        if (claude.accounts.size > 1) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "OTHER ACCOUNTS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp,
                                color = Color(0xFF9CA3AF)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            claude.accounts.drop(1).forEach { other ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${other.name} (${other.displayTierName})",
                                        fontSize = 12.sp,
                                        color = Color(0xFFE5E7EB)
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "$%.2f · %s",
                                            other.windowCostUsd,
                                            formatClaudeTokens(other.totalTokens)
                                        ),
                                        fontSize = 12.sp,
                                        color = Color(0xFF9CA3AF)
                                    )
                                }
                            }
                        }

                        // Footnote
                        Spacer(modifier = Modifier.height(12.dp))
                        val pricingEpoch = claude.pricing?.fetchedAtEpoch ?: 0L
                        val priceSuffix = if (pricingEpoch > 0L) {
                            val dateStr = DateTimeFormatter.ISO_LOCAL_DATE
                                .withZone(ZoneOffset.UTC)
                                .format(Instant.ofEpochSecond(pricingEpoch))
                            " Prices as of $dateStr."
                        } else {
                            ""
                        }
                        Text(
                            text = "Estimates from Claude Code transcripts on this host at list prices; they can differ from the billing page.$priceSuffix",
                            fontSize = 11.sp,
                            color = Color(0xFF6B7280)
                        )
                    }
                }
            }
        }
    }
}
