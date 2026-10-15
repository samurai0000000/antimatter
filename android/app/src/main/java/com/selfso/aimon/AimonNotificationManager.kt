/*
 * AimonNotificationManager.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.saifmukhtar.antimatter.MainActivity

class AimonNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        ensureChannels()
    }

    fun ensureChannels() {
        val nm = notificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val approvalChannel = NotificationChannel(
                CHANNEL_APPROVALS,
                "Action Approvals",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority action confirmation alerts from Antigravity and Cursor IDE"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }

            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent aimon mobile SSE connection"
                setShowBadge(false)
            }

            nm.createNotificationChannel(approvalChannel)
            nm.createNotificationChannel(serviceChannel)
        }
    }

    fun showApprovalNotification(approval: PendingApproval) {
        val nm = notificationManager ?: return
        ensureChannels()

        val approvalId = approval.approvalId.ifEmpty { "default_approval" }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedBaseUrl = prefs.getString("base_url", "http://127.0.0.1:3883") ?: "http://127.0.0.1:3883"
        val storedToken = prefs.getString("mobile_token", "") ?: ""

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
            putExtra(ApprovalReceiver.EXTRA_REQUEST_ID, approvalId)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            approvalId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val approveIntent = Intent(context, ApprovalReceiver::class.java).apply {
            action = ApprovalReceiver.ACTION_APPROVE
            putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
            putExtra(ApprovalReceiver.EXTRA_REQUEST_ID, approvalId)
            putExtra(ApprovalReceiver.EXTRA_BASE_URL, storedBaseUrl)
            putExtra(ApprovalReceiver.EXTRA_TOKEN, storedToken)
        }
        val approvePendingIntent = PendingIntent.getBroadcast(
            context,
            approvalId.hashCode(),
            approveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val denyIntent = Intent(context, ApprovalReceiver::class.java).apply {
            action = ApprovalReceiver.ACTION_DENY
            putExtra(ApprovalReceiver.EXTRA_APPROVAL_ID, approvalId)
            putExtra(ApprovalReceiver.EXTRA_REQUEST_ID, approvalId)
            putExtra(ApprovalReceiver.EXTRA_BASE_URL, storedBaseUrl)
            putExtra(ApprovalReceiver.EXTRA_TOKEN, storedToken)
        }
        val denyPendingIntent = PendingIntent.getBroadcast(
            context,
            approvalId.hashCode() + 1,
            denyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "[${approval.agentType.uppercase()}] Action Approval Required"
        val cmdStr = approval.command
            ?: approval.toolArgs["command"]?.toString()
            ?: approval.toolArgs["CommandLine"]?.toString()
            ?: ""
        val body = buildString {
            append("Tool: ").append(approval.toolName).append("\n")
            if (cmdStr.isNotEmpty()) {
                append("Command: ").append(cmdStr).append("\n")
            }
            if (approval.reason.isNotEmpty()) {
                append("Reason: ").append(approval.reason).append("\n")
            }
            if (approval.workspace.isNotEmpty()) {
                append("Workspace: ").append(approval.workspace).append("\n")
            }
            if (approval.toolArgs.isNotEmpty() && cmdStr.isEmpty()) {
                append("Args: ").append(approval.toolArgs.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
        }.trim()

        val summaryLine = when {
            cmdStr.isNotEmpty() -> "${approval.toolName}: $cmdStr"
            approval.reason.isNotEmpty() -> "${approval.toolName}: ${approval.reason}"
            else -> "${approval.toolName}: Confirm tool execution"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(tapPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.checkbox_on_background, "Approve", approvePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deny", denyPendingIntent)
            .build()

        nm.notify(approvalId.hashCode(), notification)
    }

    fun showApprovalNotification(
        requestId: String,
        agentType: String = "antigravity",
        toolName: String = "run_command",
        command: String? = null,
        workspace: String = "",
        reason: String = "",
        timeoutSeconds: Int = 120
    ) {
        showApprovalNotification(
            PendingApproval(
                approvalId = requestId,
                agentType = agentType,
                toolName = toolName,
                workspace = workspace,
                command = command,
                reason = reason,
                timeoutSeconds = timeoutSeconds,
                requestedAt = System.currentTimeMillis() / 1000
            )
        )
    }

    fun dismissApprovalNotification(approvalId: String) {
        notificationManager?.cancel(approvalId.hashCode())
    }

    companion object {
        const val PREFS_NAME = "aimon_prefs"
        const val CHANNEL_APPROVALS = "aimon_approvals"
        const val CHANNEL_SERVICE = "aimon_service"
    }
}
