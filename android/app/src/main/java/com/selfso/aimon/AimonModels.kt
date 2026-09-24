/*
 * AimonModels.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import com.google.gson.annotations.SerializedName

data class QuotaStatus(
    @SerializedName("antigravity") val antigravity: AntigravityQuota? = null,
    @SerializedName("cursor") val cursor: CursorUsage? = null,
    @SerializedName("last_updated_epoch") val lastUpdatedEpoch: Long = 0L,
    @SerializedName("server_timestamp_ms") val serverTimestampMs: Long = System.currentTimeMillis()
)

data class AntigravityQuota(
    @SerializedName("plan_tier") val planTier: String = "Google AI Ultra",
    @SerializedName("monthly_flow_credits") val monthlyFlowCredits: Int = 150000,
    @SerializedName("monthly_prompt_credits") val monthlyPromptCredits: Int = 50000,
    @SerializedName("user_email") val userEmail: String = "",
    @SerializedName("quota_groups") val quotaGroups: List<QuotaGroup> = emptyList(),
    @SerializedName("models") val models: List<ModelCapacity> = emptyList()
) {
    val totalCreditsRemaining: Int
        get() = monthlyFlowCredits + monthlyPromptCredits
}

data class QuotaGroup(
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("buckets") val buckets: List<QuotaBucket> = emptyList()
)

data class QuotaBucket(
    @SerializedName("bucket_id") val bucketId: String = "",
    @SerializedName("display_name") val displayName: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("remaining_fraction") val remainingFraction: Double = 1.0,
    @SerializedName("reset_time_iso") val resetTimeIso: String = "",
    @SerializedName("reset_time_remaining_seconds") val resetTimeRemainingSeconds: Long = 0L,
    @SerializedName("window") val window: String = "weekly"
) {
    val percentRemaining: Int
        get() = (remainingFraction * 100).toInt()
}

data class ModelCapacity(
    @SerializedName("model_id") val modelId: String = "",
    @SerializedName("model_name") val modelName: String = "",
    @SerializedName("remaining_fraction") val remainingFraction: Double = 1.0,
    @SerializedName("reset_time_iso") val resetTimeIso: String = "",
    @SerializedName("reset_time_remaining_seconds") val resetTimeRemainingSeconds: Long = 0L
) {
    val percentRemaining: Int
        get() = (remainingFraction * 100).toInt()
}

data class CursorUsage(
    @SerializedName("plan_tier") val planTier: String = "Enterprise",
    @SerializedName("fast_requests_used") val fastRequestsUsed: Int = 0,
    @SerializedName("fast_requests_limit") val fastRequestsLimit: Int = 120000,
    @SerializedName("total_spend_usd") val totalSpendUsd: Double = 0.0,
    @SerializedName("prev_cycle_spend_usd") val prevCycleSpendUsd: Double = 0.0,
    @SerializedName("cycle_reset_iso") val cycleResetIso: String = "",
    @SerializedName("daily_spend") val dailySpend: List<DailySpend> = emptyList(),
    @SerializedName("spend_by_category") val spendByCategory: List<CategorySpend> = emptyList()
) {
    val fastRequestsRemaining: Int
        get() = maxOf(0, fastRequestsLimit - fastRequestsUsed)
}

data class DailySpend(
    @SerializedName("day_str") val dayStr: String = "",
    @SerializedName("day_ms") val dayMs: Long = 0L,
    @SerializedName("spend_usd") val spendUsd: Double = 0.0,
    @SerializedName("cumulative_usd") val cumulativeUsd: Double = 0.0
)

data class CategorySpend(
    @SerializedName("category") val category: String = "",
    @SerializedName("spend_usd") val spendUsd: Double = 0.0,
    @SerializedName("percentage") val percentage: Double = 0.0
)

data class PendingApproval(
    @SerializedName("approval_id") val approvalId: String = "",
    @SerializedName("agent_type") val agentType: String = "antigravity",
    @SerializedName("tool_name") val toolName: String = "",
    @SerializedName("workspace") val workspace: String = "",
    @SerializedName("tool_args") val toolArgs: Map<String, Any> = emptyMap(),
    @SerializedName("command") val command: String? = null,
    @SerializedName("reason") val reason: String = "",
    @SerializedName("timeout_seconds") val timeoutSeconds: Int = 120,
    @SerializedName("requested_at") val requestedAt: Long = System.currentTimeMillis()
)

data class SatelliteStatus(
    @SerializedName("meshmon") val meshmon: MeshmonStatus = MeshmonStatus(),
    @SerializedName("netmon") val netmon: NetmonStatus = NetmonStatus(),
    @SerializedName("embdevenv") val embdevenv: EmbdevenvStatus = EmbdevenvStatus()
)

data class MeshmonStatus(
    @SerializedName("online") val online: Boolean = false,
    @SerializedName("active_nodes") val activeNodes: Int = 0,
    @SerializedName("total_nodes") val totalNodes: Int = 0,
    @SerializedName("battery_avg") val batteryAvg: Int = 0,
    @SerializedName("channel_util") val channelUtil: Double = 0.0
)

data class NetmonStatus(
    @SerializedName("online") val online: Boolean = false,
    @SerializedName("wan_down_mbps") val wanDownMbps: Double = 0.0,
    @SerializedName("wan_up_mbps") val wanUpMbps: Double = 0.0,
    @SerializedName("active_sessions") val activeSessions: Int = 0,
    @SerializedName("blocked_ips") val blockedIps: Int = 0
)

data class EmbdevenvStatus(
    @SerializedName("online") val online: Boolean = false,
    @SerializedName("target") val target: String = "n1-655-pro",
    @SerializedName("mcu_power") val mcuPower: Boolean = false,
    @SerializedName("voltage_mv") val voltageMv: Int = 0,
    @SerializedName("current_ma") val currentMa: Int = 0
)

