/*
 * AimonModels.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import com.google.gson.annotations.SerializedName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

fun formatDurationSeconds(seconds: Long, fallback: String = ""): String {
    if (seconds <= 0L) return fallback
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    return when {
        hours >= 24L -> "${hours / 24L}d ${hours % 24L}h"
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m"
        else -> "${seconds}s"
    }
}

fun formatClaudeTokens(tokens: Long): String {
    val v = maxOf(0L, tokens)
    return when {
        v < 1_000L -> v.toString()
        v < 1_000_000L -> String.format(Locale.US, "%.1fK", v / 1e3)
        v < 1_000_000_000L -> String.format(Locale.US, "%.2fM", v / 1e6)
        else -> String.format(Locale.US, "%.2fB", v / 1e9)
    }
}

fun formatIsoDateShort(iso: String): String {
    val trimmed = iso.trim()
    if (trimmed.isEmpty()) return "--"
    val tIdx = trimmed.indexOf('T')
    return if (tIdx > 0) trimmed.substring(0, tIdx) else trimmed
}

fun computeDaysRemaining(iso: String, nowEpochMs: Long = System.currentTimeMillis()): String {
    val trimmed = iso.trim()
    if (trimmed.isEmpty()) return "--"
    return try {
        val targetMs = if (trimmed.contains('T')) {
            Instant.parse(trimmed).toEpochMilli()
        } else {
            LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val diffDays = ceil((targetMs - nowEpochMs).toDouble() / (1000.0 * 60.0 * 60.0 * 24.0)).toInt()
        if (diffDays > 0) "$diffDays days left" else "Reset pending"
    } catch (_: Exception) {
        "--"
    }
}

data class QuotaStatus(
    @SerializedName(value = "antigravity", alternate = ["ag"])
    val antigravity: AntigravityQuota? = null,
    @SerializedName("cursor")
    val cursor: CursorUsage? = null,
    @SerializedName("claude")
    val claude: ClaudeUsage? = null,
    @SerializedName(value = "last_updated_epoch", alternate = ["lastUpdatedEpoch"])
    val lastUpdatedEpoch: Long = 0L,
    @SerializedName(value = "server_timestamp_ms", alternate = ["serverTimestampMs"])
    val serverTimestampMs: Long = 0L
) {
    val ag: AntigravityQuota?
        get() = antigravity
}

data class UserCreditItem(
    @SerializedName(value = "credit_type", alternate = ["creditType"])
    val creditType: String = "",
    @SerializedName(value = "credit_amount", alternate = ["creditAmount"])
    val creditAmount: Int = 0,
    @SerializedName(value = "minimum_credit_amount_for_usage", alternate = ["minimumCreditAmountForUsage"])
    val minimumCreditAmountForUsage: Int = 0
)

data class AntigravityQuota(
    @SerializedName(value = "is_running", alternate = ["isRunning"])
    val isRunning: Boolean = true,
    @SerializedName(value = "plan_tier", alternate = ["tierName", "tier_name", "planTier"])
    val planTier: String = "Unknown",
    @SerializedName(value = "available_prompt_credits", alternate = ["availablePromptCredits"])
    val availablePromptCredits: Int = 0,
    @SerializedName(value = "available_flow_credits", alternate = ["availableFlowCredits"])
    val availableFlowCredits: Int = 0,
    @SerializedName(value = "monthly_flow_credits", alternate = ["monthlyFlowCredits", "flowCreditsLimit", "flow_credits_limit"])
    val monthlyFlowCredits: Int = 0,
    @SerializedName(value = "monthly_prompt_credits", alternate = ["monthlyPromptCredits", "promptCreditsLimit", "prompt_credits_limit"])
    val monthlyPromptCredits: Int = 0,
    @SerializedName(value = "promptCreditsUsed", alternate = ["prompt_credits_used"])
    val promptCreditsUsed: Int = 0,
    @SerializedName(value = "flowCreditsUsed", alternate = ["flow_credits_used"])
    val flowCreditsUsed: Int = 0,
    @SerializedName(value = "user_email", alternate = ["userName", "user_name", "userEmail"])
    val userEmail: String = "",
    @SerializedName(value = "available_credits", alternate = ["availableCredits"])
    val availableCredits: List<UserCreditItem> = emptyList(),
    @SerializedName(value = "quota_groups", alternate = ["quotaGroups"])
    val quotaGroups: List<QuotaGroup> = emptyList(),
    @SerializedName("models")
    val models: List<ModelCapacity> = emptyList(),
    @SerializedName(value = "error_message", alternate = ["errorMessage"])
    val errorMessage: String = ""
) {
    val tierName: String
        get() = planTier

    val userName: String
        get() = userEmail

    val promptCreditsLimit: Int
        get() = monthlyPromptCredits

    val flowCreditsLimit: Int
        get() = monthlyFlowCredits

    val totalCreditsRemaining: Int
        get() = when {
            availableCredits.isNotEmpty() -> availableCredits.sumOf { it.creditAmount }
            availablePromptCredits > 0 || availableFlowCredits > 0 -> availablePromptCredits + availableFlowCredits
            monthlyPromptCredits > 0 || monthlyFlowCredits > 0 ->
                maxOf(0, monthlyPromptCredits - promptCreditsUsed) + maxOf(0, monthlyFlowCredits - flowCreditsUsed)
            else -> 0
        }

    val averageRemainingPct: Double
        get() = when {
            !isRunning -> 0.0
            quotaGroups.isNotEmpty() -> quotaGroups.first().remainingPct
            models.isNotEmpty() -> models.minOf { it.remainingPct }
            else -> 0.0
        }
}

data class QuotaGroup(
    @SerializedName(value = "display_name", alternate = ["groupName", "group_name", "name"])
    val displayName: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName("buckets")
    val buckets: List<QuotaBucket> = emptyList(),
    @SerializedName(value = "remainingPct", alternate = ["remaining_pct"])
    val rawRemainingPct: Double? = null,
    @SerializedName(value = "remaining_fraction", alternate = ["remainingFraction"])
    val rawRemainingFraction: Double? = null,
    @SerializedName(value = "resetTime", alternate = ["reset_time", "reset_time_iso", "resetTimeIso"])
    val rawResetTime: String? = null
) {
    val groupName: String
        get() = displayName

    val remainingPct: Double
        get() = when {
            rawRemainingPct != null -> rawRemainingPct
            rawRemainingFraction != null -> rawRemainingFraction * 100.0
            buckets.isNotEmpty() -> buckets.minOf { it.remainingPct }
            else -> 0.0
        }

    val resetTime: String
        get() = when {
            !rawResetTime.isNullOrBlank() -> rawResetTime
            buckets.isNotEmpty() -> (buckets.minByOrNull { it.remainingPct } ?: buckets.first()).resetTimeDisplay
            else -> ""
        }
}

data class QuotaBucket(
    @SerializedName(value = "bucket_id", alternate = ["bucketId"])
    val bucketId: String = "",
    @SerializedName(value = "display_name", alternate = ["displayName", "name"])
    val displayName: String = "",
    @SerializedName("description")
    val description: String = "",
    @SerializedName(value = "remaining_fraction", alternate = ["remainingFraction"])
    val remainingFraction: Double = 0.0,
    @SerializedName(value = "remainingPct", alternate = ["remaining_pct"])
    val rawRemainingPct: Double? = null,
    @SerializedName(value = "reset_time_iso", alternate = ["resetTimeIso", "resetTime", "reset_time"])
    val resetTimeIso: String = "",
    @SerializedName(value = "reset_time_remaining_seconds", alternate = ["resetTimeRemainingSeconds"])
    val resetTimeRemainingSeconds: Long = 0L,
    @SerializedName("window")
    val window: String = "weekly"
) {
    val remainingPct: Double
        get() = rawRemainingPct ?: (remainingFraction * 100.0)

    val percentRemaining: Int
        get() = remainingPct.roundToInt()

    val windowBadge: String
        get() = when (window.lowercase()) {
            "weekly" -> "Weekly"
            "5h", "five_hour" -> "5-Hour"
            else -> displayName.ifBlank { "Limit" }
        }

    val resetTimeDisplay: String
        get() = formatDurationSeconds(resetTimeRemainingSeconds, resetTimeIso)
}

data class ModelCapacity(
    @SerializedName(value = "model_id", alternate = ["modelId", "id"])
    val modelId: String = "",
    @SerializedName(value = "model_name", alternate = ["modelName", "name"])
    val modelName: String = "",
    @SerializedName(value = "remaining_fraction", alternate = ["remainingFraction"])
    val remainingFraction: Double = 0.0,
    @SerializedName(value = "remainingPct", alternate = ["remaining_pct"])
    val rawRemainingPct: Double? = null,
    @SerializedName(value = "reset_time_iso", alternate = ["resetTimeIso", "resetTime", "reset_time"])
    val resetTimeIso: String = "",
    @SerializedName(value = "reset_time_remaining_seconds", alternate = ["resetTimeRemainingSeconds"])
    val resetTimeRemainingSeconds: Long = 0L
) {
    val name: String
        get() = modelName.ifBlank { modelId }

    val remainingPct: Double
        get() = rawRemainingPct ?: (remainingFraction * 100.0)

    val percentRemaining: Int
        get() = remainingPct.roundToInt()

    val resetTime: String
        get() = formatDurationSeconds(resetTimeRemainingSeconds, resetTimeIso)
}

data class CursorUsage(
    @SerializedName(value = "is_authenticated", alternate = ["isAuthenticated"])
    val isAuthenticated: Boolean = true,
    @SerializedName(value = "plan_tier", alternate = ["planName", "plan_name", "planTier"])
    val planTier: String = "Unknown",
    @SerializedName(value = "fast_requests_used", alternate = ["individualUsed", "individual_used", "fastRequestsUsed"])
    val fastRequestsUsed: Int = 0,
    @SerializedName(value = "fast_requests_limit", alternate = ["individualLimit", "individual_limit", "fastRequestsLimit"])
    val fastRequestsLimit: Int = 0,
    @SerializedName(value = "total_spend_usd", alternate = ["totalSpendUsd"])
    val rawTotalSpendUsd: Double? = null,
    @SerializedName(value = "totalSpendCents", alternate = ["total_spend_cents"])
    val rawTotalSpendCents: Int? = null,
    @SerializedName(value = "prev_cycle_spend_usd", alternate = ["prevCycleSpendUsd"])
    val prevCycleSpendUsd: Double = 0.0,
    @SerializedName(value = "cycle_reset_iso", alternate = ["billingCycleEnd", "billing_cycle_end", "cycleResetIso"])
    val cycleResetIso: String = "",
    @SerializedName(value = "on_demand_spend", alternate = ["onDemandSpend"])
    val onDemandSpendUsd: Double = 0.0,
    @SerializedName(value = "onDemandUsedCents", alternate = ["on_demand_used_cents"])
    val onDemandUsedCents: Int = 0,
    @SerializedName(value = "onDemandLimitCents", alternate = ["on_demand_limit_cents"])
    val onDemandLimitCents: Int = 0,
    @SerializedName(value = "daily_spend", alternate = ["dailySpend"])
    val dailySpend: List<DailySpend> = emptyList(),
    @SerializedName(value = "spend_by_category", alternate = ["spendByCategory"])
    val spendByCategory: List<CategorySpend> = emptyList(),
    @SerializedName(value = "error_message", alternate = ["errorMessage"])
    val errorMessage: String = ""
) {
    val individualUsed: Int
        get() = fastRequestsUsed

    val individualLimit: Int
        get() = fastRequestsLimit

    val planName: String
        get() = planTier

    val billingCycleEnd: String
        get() = cycleResetIso

    val totalSpendCents: Int
        get() = rawTotalSpendCents ?: ((rawTotalSpendUsd ?: 0.0) * 100.0).roundToInt()

    val totalSpendUsd: Double
        get() = rawTotalSpendUsd ?: ((rawTotalSpendCents ?: 0) / 100.0)

    val fastRequestsRemaining: Int
        get() = maxOf(0, fastRequestsLimit - fastRequestsUsed)
}

data class DailySpend(
    @SerializedName(value = "day_str", alternate = ["dayStr"])
    val dayStr: String = "",
    @SerializedName(value = "day_ms", alternate = ["dayMs"])
    val dayMs: Long = 0L,
    @SerializedName(value = "spend_usd", alternate = ["spendUsd"])
    val spendUsd: Double = 0.0,
    @SerializedName(value = "cumulative_usd", alternate = ["cumulativeUsd"])
    val cumulativeUsd: Double = 0.0
)

data class CategorySpend(
    @SerializedName("category")
    val category: String = "",
    @SerializedName(value = "spend_usd", alternate = ["spendUsd"])
    val spendUsd: Double = 0.0,
    @SerializedName("percentage")
    val percentage: Double = 0.0
)

data class ClaudeWindowTotals(
    @SerializedName("label")
    val label: String = "",
    @SerializedName("messages")
    val messages: Long = 0L,
    @SerializedName(value = "total_tokens", alternate = ["totalTokens"])
    val totalTokens: Long = 0L,
    @SerializedName(value = "est_cost_nano", alternate = ["estCostNano"])
    val estCostNano: Long = 0L,
    @SerializedName(value = "est_cost_usd", alternate = ["estCostUsd", "costUsd"])
    val rawEstCostUsd: Double? = null
) {
    val estCostUsd: Double
        get() = rawEstCostUsd ?: (estCostNano / 1e9)
}

data class ClaudeModelUsage(
    @SerializedName("model")
    val model: String = "",
    @SerializedName("messages")
    val messages: Long = 0L,
    @SerializedName(value = "input_tokens", alternate = ["inputTokens"])
    val inputTokens: Long = 0L,
    @SerializedName(value = "output_tokens", alternate = ["outputTokens"])
    val outputTokens: Long = 0L,
    @SerializedName(value = "cache_read_tokens", alternate = ["cacheReadTokens"])
    val cacheReadTokens: Long = 0L,
    @SerializedName(value = "cache_write_5m_tokens", alternate = ["cacheWrite5mTokens"])
    val cacheWrite5mTokens: Long = 0L,
    @SerializedName(value = "cache_write_1h_tokens", alternate = ["cacheWrite1hTokens"])
    val cacheWrite1hTokens: Long = 0L,
    @SerializedName(value = "est_cost_nano", alternate = ["estCostNano"])
    val estCostNano: Long = 0L,
    @SerializedName(value = "est_cost_usd", alternate = ["estCostUsd"])
    val rawEstCostUsd: Double? = null
) {
    val estCostUsd: Double
        get() = rawEstCostUsd ?: (estCostNano / 1e9)

    val totalTokens: Long
        get() = inputTokens + outputTokens + cacheReadTokens + cacheWrite5mTokens + cacheWrite1hTokens
}

data class ClaudeDailyPoint(
    @SerializedName(value = "day_str", alternate = ["dayStr"])
    val dayStr: String = "",
    @SerializedName(value = "day_ms", alternate = ["dayMs"])
    val dayMs: Long = 0L,
    @SerializedName("messages")
    val messages: Long = 0L,
    @SerializedName("tokens")
    val tokens: Long = 0L,
    @SerializedName(value = "est_cost_nano", alternate = ["estCostNano"])
    val estCostNano: Long = 0L,
    @SerializedName(value = "est_cost_usd", alternate = ["estCostUsd"])
    val rawEstCostUsd: Double? = null
) {
    val estCostUsd: Double
        get() = rawEstCostUsd ?: (estCostNano / 1e9)
}

data class ClaudePricingStatus(
    @SerializedName(value = "source_url", alternate = ["sourceUrl"])
    val sourceUrl: String = "",
    @SerializedName(value = "fetched_at_epoch", alternate = ["fetchedAtEpoch"])
    val fetchedAtEpoch: Long = 0L,
    @SerializedName("stale")
    val stale: Boolean = false,
    @SerializedName(value = "models_loaded", alternate = ["modelsLoaded"])
    val modelsLoaded: Int = 0,
    @SerializedName(value = "quarantined_count", alternate = ["quarantinedCount"])
    val quarantinedCount: Int = 0,
    @SerializedName("error")
    val error: String = ""
)

data class ClaudeAccount(
    @SerializedName(value = "name", alternate = ["email"])
    val name: String = "",
    @SerializedName(value = "tier", alternate = ["planTier", "plan_tier"])
    val planTier: String = "",
    @SerializedName(value = "raw_subscription_type", alternate = ["rawSubscriptionType"])
    val rawSubscriptionType: String = "",
    @SerializedName(value = "raw_rate_limit_tier", alternate = ["rawRateLimitTier"])
    val rawRateLimitTier: String = "",
    @SerializedName(value = "has_data", alternate = ["hasData"])
    val hasData: Boolean = true,
    @SerializedName(value = "error_message", alternate = ["errorMessage"])
    val errorMessage: String = "",
    @SerializedName("warning")
    val warning: String = "",
    @SerializedName(value = "window_days", alternate = ["windowDays"])
    val windowDays: Int = 30,
    @SerializedName(value = "fiveHourUtilization", alternate = ["five_hour_utilization"])
    val rawFiveHourUtilization: Double? = null,
    @SerializedName(value = "sevenDayUtilization", alternate = ["seven_day_utilization"])
    val rawSevenDayUtilization: Double? = null,
    @SerializedName(value = "cost5h", alternate = ["cost_5h"])
    val rawCost5h: Double? = null,
    @SerializedName(value = "cost7d", alternate = ["cost_7d"])
    val rawCost7d: Double? = null,
    @SerializedName(value = "last_5h", alternate = ["last5h"])
    val last5h: ClaudeWindowTotals? = null,
    @SerializedName(value = "last_7d", alternate = ["last7d"])
    val last7d: ClaudeWindowTotals? = null,
    @SerializedName("window")
    val window: ClaudeWindowTotals? = null,
    @SerializedName("today")
    val today: ClaudeWindowTotals? = null,
    @SerializedName(value = "cycle_configured", alternate = ["cycleConfigured"])
    val cycleConfigured: Boolean = false,
    @SerializedName("cycle")
    val cycle: ClaudeWindowTotals? = null,
    @SerializedName(value = "spend_limit_usd", alternate = ["spendLimitUsd"])
    val spendLimitUsd: Double = 0.0,
    @SerializedName(value = "est_pct_of_limit", alternate = ["estPctOfLimit"])
    val estPctOfLimit: Double = 0.0,
    @SerializedName(value = "cycle_reset_iso", alternate = ["cycleResetIso"])
    val cycleResetIso: String = "",
    @SerializedName("models")
    val models: List<ClaudeModelUsage> = emptyList(),
    @SerializedName("daily")
    val daily: List<ClaudeDailyPoint> = emptyList(),
    @SerializedName(value = "unpriced_models", alternate = ["unpricedModels"])
    val unpricedModels: List<String> = emptyList()
) {
    val email: String
        get() = name

    val displayTierName: String
        get() = when (planTier.lowercase()) {
            "enterprise" -> "Enterprise"
            "personal" -> "Personal"
            "", "unknown" -> if (rawSubscriptionType.isNotBlank()) rawSubscriptionType else "Unknown tier"
            else -> planTier
        }

    val valueLabel: String
        get() = when (planTier.lowercase()) {
            "enterprise" -> "Est. cost"
            "personal" -> "Est. API-equivalent value"
            else -> "Est. value"
        }

    val cost5h: Double
        get() = rawCost5h ?: last5h?.estCostUsd ?: 0.0

    val cost7d: Double
        get() = rawCost7d ?: last7d?.estCostUsd ?: 0.0

    val cycleCostUsd: Double
        get() = cycle?.estCostUsd ?: 0.0

    val windowCostUsd: Double
        get() = window?.estCostUsd ?: cycle?.estCostUsd ?: cost7d

    val todayCostUsd: Double
        get() = today?.estCostUsd ?: 0.0

    val totalTokens: Long
        get() = window?.totalTokens ?: last7d?.totalTokens ?: last5h?.totalTokens ?: 0L

    val fiveHourUtilization: Double
        get() = when {
            rawFiveHourUtilization != null -> rawFiveHourUtilization
            spendLimitUsd > 0.0 && cost5h > 0.0 -> ((cost5h / spendLimitUsd) * 100.0).coerceIn(0.0, 100.0)
            else -> 0.0
        }

    val sevenDayUtilization: Double
        get() = when {
            rawSevenDayUtilization != null -> rawSevenDayUtilization
            spendLimitUsd > 0.0 && cost7d > 0.0 -> ((cost7d / spendLimitUsd) * 100.0).coerceIn(0.0, 100.0)
            estPctOfLimit > 0.0 -> estPctOfLimit.coerceIn(0.0, 100.0)
            else -> 0.0
        }
}

data class ClaudeUsage(
    @SerializedName("enabled")
    val enabled: Boolean = true,
    @SerializedName("pricing")
    val pricing: ClaudePricingStatus? = null,
    @SerializedName(value = "fiveHourUtilization", alternate = ["five_hour_utilization"])
    val rawFiveHourUtilization: Double? = null,
    @SerializedName(value = "sevenDayUtilization", alternate = ["seven_day_utilization"])
    val rawSevenDayUtilization: Double? = null,
    @SerializedName(value = "extraUsageUsed", alternate = ["extra_usage_used"])
    val extraUsageUsed: Double = 0.0,
    @SerializedName(value = "extraUsageLimit", alternate = ["extra_usage_limit"])
    val extraUsageLimit: Double = 0.0,
    @SerializedName(value = "estimatedCostUsd", alternate = ["estimated_cost_usd", "est_cost_usd"])
    val rawEstimatedCostUsd: Double? = null,
    @SerializedName(value = "totalTokens", alternate = ["total_tokens"])
    val rawTotalTokens: Long? = null,
    @SerializedName("accounts")
    val accounts: List<ClaudeAccount> = emptyList()
) {
    val fiveHourUtilization: Double
        get() = rawFiveHourUtilization ?: accounts.firstOrNull()?.fiveHourUtilization ?: 0.0

    val sevenDayUtilization: Double
        get() = rawSevenDayUtilization ?: accounts.firstOrNull()?.sevenDayUtilization ?: 0.0

    val estimatedCostUsd: Double
        get() = rawEstimatedCostUsd ?: accounts.sumOf { it.windowCostUsd }

    val totalTokens: Long
        get() = rawTotalTokens ?: accounts.sumOf { it.totalTokens }
}

data class PendingApproval(
    @SerializedName(value = "approval_id", alternate = ["id", "request_id", "approvalId"])
    val approvalId: String = "",
    @SerializedName(value = "agent_type", alternate = ["agent", "agentType"])
    val agentType: String = "antigravity",
    @SerializedName(value = "tool_name", alternate = ["action_type", "toolName"])
    val toolName: String = "",
    @SerializedName("workspace")
    val workspace: String = "",
    @SerializedName(value = "tool_args", alternate = ["details", "toolArgs"])
    val toolArgs: Map<String, Any> = emptyMap(),
    @SerializedName("command")
    val command: String? = null,
    @SerializedName("reason")
    val reason: String = "",
    @SerializedName(value = "timeout_seconds", alternate = ["timeoutSeconds"])
    val timeoutSeconds: Int = 120,
    @SerializedName(value = "remaining_seconds", alternate = ["remainingSeconds"])
    val remainingSeconds: Int = -1,
    @SerializedName(value = "requested_at", alternate = ["requestedAt"])
    val requestedAt: Long = System.currentTimeMillis() / 1000L
) {
    fun computeSecondsLeft(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        if (remainingSeconds >= 0) {
            return remainingSeconds.toLong()
        }
        val reqEpochSec = if (requestedAt > 100_000_000_000L) {
            requestedAt / 1000L
        } else {
            requestedAt
        }
        val elapsed = maxOf(0L, nowEpochSeconds - reqEpochSec)
        return maxOf(0L, timeoutSeconds.toLong() - elapsed)
    }
}

data class DiscoveredMonitorItem(
    @SerializedName("id")
    val id: String = "",
    @SerializedName("name")
    val name: String = "",
    @SerializedName(value = "short_name", alternate = ["shortName"])
    val shortName: String = "",
    @SerializedName(value = "subsystem", alternate = ["type"])
    val subsystem: String = "",
    @SerializedName("host")
    val host: String = "",
    @SerializedName("port")
    val port: Int = 0,
    @SerializedName("path")
    val path: String = "/",
    @SerializedName("connected")
    val connected: Boolean = false,
    @SerializedName("reachable")
    val reachable: Boolean = false,
    @SerializedName("online")
    val rawOnline: Boolean? = null,
    @SerializedName(value = "is_self", alternate = ["isSelf"])
    val isSelf: Boolean = false,
    @SerializedName(value = "last_seen_epoch", alternate = ["lastSeen", "lastSeenEpoch"])
    val lastSeenEpoch: Long = 0L,
    @SerializedName("endpoint")
    val rawEndpoint: String? = null
) {
    val online: Boolean
        get() = rawOnline ?: (connected || reachable)

    val endpoint: String
        get() = when {
            !rawEndpoint.isNullOrBlank() -> rawEndpoint
            host.isNotBlank() && port > 0 -> "$host:$port"
            host.isNotBlank() -> host
            else -> "N/A"
        }
}

data class AgentTelemetryOverview(
    @SerializedName(value = "active_sessions", alternate = ["activeSessions"])
    val activeSessions: Int = 0,
    @SerializedName(value = "total_sessions", alternate = ["totalSessions"])
    val totalSessions: Int = 0,
    @SerializedName(value = "total_events", alternate = ["totalEvents"])
    val rawTotalEvents: Int? = null,
    @SerializedName(value = "total_turns", alternate = ["totalTurns"])
    val totalTurns: Int = 0,
    @SerializedName(value = "total_tool_calls", alternate = ["totalToolCalls"])
    val totalToolCalls: Int = 0,
    @SerializedName(value = "total_errors", alternate = ["totalErrors"])
    val totalErrors: Int = 0,
    @SerializedName(value = "error_rate_pct", alternate = ["errorRatePct"])
    val errorRatePct: Double = 0.0,
    @SerializedName(value = "avg_turn_latency_ms", alternate = ["avgTurnLatencyMs"])
    val avgTurnLatencyMs: Double = 0.0,
    @SerializedName(value = "db_size_bytes", alternate = ["dbSizeBytes"])
    val dbSizeBytes: Long = 0L,
    @SerializedName(value = "platforms", alternate = ["active_platforms", "activePlatforms"])
    val platforms: List<String> = emptyList()
) {
    val totalEvents: Int
        get() = rawTotalEvents ?: (totalTurns + totalToolCalls)
}

data class SatelliteStatus(
    @SerializedName("meshmon")
    val meshmon: MeshmonStatus = MeshmonStatus(),
    @SerializedName("netmon")
    val netmon: NetmonStatus = NetmonStatus(),
    @SerializedName("embdevenv")
    val embdevenv: EmbdevenvStatus = EmbdevenvStatus(),
    @SerializedName("abc80")
    val abc80: Abc80Status = Abc80Status(),
    @SerializedName("monitors")
    val monitors: List<DiscoveredMonitorItem> = emptyList(),
    @SerializedName("overview")
    val overview: AgentTelemetryOverview = AgentTelemetryOverview()
)

data class MeshmonStatus(
    @SerializedName("online")
    val online: Boolean = false,
    @SerializedName("endpoint")
    val endpoint: String = "N/A",
    @SerializedName(value = "last_seen_epoch", alternate = ["lastSeen", "lastSeenEpoch"])
    val lastSeenEpoch: Long = 0L,
    @SerializedName(value = "active_nodes", alternate = ["activeNodes"])
    val activeNodes: Int = 0,
    @SerializedName(value = "total_nodes", alternate = ["totalNodes"])
    val totalNodes: Int = 0,
    @SerializedName(value = "battery_avg", alternate = ["batteryAvg"])
    val batteryAvg: Int = 0,
    @SerializedName(value = "channel_util", alternate = ["channelUtil"])
    val channelUtil: Double = 0.0,
    @SerializedName("channel")
    val channel: String = "",
    @SerializedName(value = "packet_rate", alternate = ["packetRate"])
    val packetRate: String = ""
)

data class NetmonStatus(
    @SerializedName("online")
    val online: Boolean = false,
    @SerializedName("endpoint")
    val endpoint: String = "N/A",
    @SerializedName(value = "last_seen_epoch", alternate = ["lastSeen", "lastSeenEpoch"])
    val lastSeenEpoch: Long = 0L,
    @SerializedName(value = "wan_down_mbps", alternate = ["wanDownMbps"])
    val wanDownMbps: Double = 0.0,
    @SerializedName(value = "wan_up_mbps", alternate = ["wanUpMbps"])
    val wanUpMbps: Double = 0.0,
    @SerializedName(value = "active_sessions", alternate = ["activeSessions"])
    val activeSessions: Int = 0,
    @SerializedName(value = "blocked_ips", alternate = ["blockedIps"])
    val blockedIps: Int = 0,
    @SerializedName(value = "lan_rate", alternate = ["lanRate"])
    val lanRate: String = ""
)

data class EmbdevenvStatus(
    @SerializedName("online")
    val online: Boolean = false,
    @SerializedName("endpoint")
    val endpoint: String = "N/A",
    @SerializedName(value = "last_seen_epoch", alternate = ["lastSeen", "lastSeenEpoch"])
    val lastSeenEpoch: Long = 0L,
    @SerializedName("target")
    val target: String = "",
    @SerializedName(value = "mcu_power", alternate = ["mcuPower"])
    val mcuPower: Boolean = false,
    @SerializedName(value = "voltage_mv", alternate = ["voltageMv"])
    val voltageMv: Int = 0,
    @SerializedName(value = "current_ma", alternate = ["currentMa"])
    val currentMa: Int = 0
)

data class Abc80Status(
    @SerializedName("online")
    val online: Boolean = false,
    @SerializedName("endpoint")
    val endpoint: String = "N/A",
    @SerializedName(value = "last_seen_epoch", alternate = ["lastSeen", "lastSeenEpoch"])
    val lastSeenEpoch: Long = 0L,
    @SerializedName(value = "status", alternate = ["display", "statusText"])
    val statusText: String = ""
)


