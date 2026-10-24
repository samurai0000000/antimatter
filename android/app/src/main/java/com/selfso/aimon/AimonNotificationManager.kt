/*
 * AimonNotificationManager.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

class AimonNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
        AimonAppPreferences.init(context)
        ensureChannels()
    }

    fun ensureChannels() {
        val nm = notificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Background Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent aimon mobile SSE connection"
                setShowBadge(false)
            }

            // Silent channel for agent session alerts so Sound On/Off and Vibration On/Off
            // switches in Settings take effect dynamically at runtime without channel immutability.
            val alertsChannel = NotificationChannel(
                CHANNEL_AGENT_ALERTS,
                "Antigravity Session Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Antigravity or agy answers a prompt or asks a question"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(true)
            }

            nm.createNotificationChannel(serviceChannel)
            nm.createNotificationChannel(alertsChannel)
        }
    }

    fun notifyAgentSessionEvent(
        event: String,
        conversationId: String,
        source: String = "cli",
        title: String = "",
        workspace: String = "",
        snippet: String = "",
        stepIndex: Int = -1
    ) {
        AimonAppPreferences.init(context)

        if (conversationId.isNotBlank() && stepIndex >= 0) {
            val dedupKey = "$conversationId:$stepIndex:$event"
            synchronized(notifiedEventsLock) {
                if (notifiedEvents.contains(dedupKey)) {
                    return
                }
                notifiedEvents.add(dedupKey)
                while (notifiedEvents.size > 128) {
                    val first = notifiedEvents.iterator().next()
                    notifiedEvents.remove(first)
                }
            }
        }

        val isQuestion = (event == "agent_question")

        // 1. Play Desktop IDE Chime Sound if enabled
        if (AimonAppPreferences.soundEnabled.value) {
            if (isQuestion) {
                AimonSoundPlayer.playAgentQuestionChime(context)
            } else {
                AimonSoundPlayer.playPromptAnsweredChime(context)
            }
        }

        // 2. Vibrate phone if enabled
        if (AimonAppPreferences.vibrationEnabled.value) {
            triggerVibration(isQuestion)
        }

        // 3. Post Android system notification if enabled and not already viewing this exact session in the foreground
        val isActivelyViewingSession = AimonAppPreferences.isAppInForeground &&
            AimonAppPreferences.currentTab == 0 &&
            conversationId.isNotBlank() &&
            conversationId != "new" &&
            AimonAppPreferences.activeConversationId == conversationId &&
            stepIndex >= 0

        if (!isActivelyViewingSession && AimonAppPreferences.notificationsEnabled.value) {
            postSystemNotification(
                isQuestion = isQuestion,
                conversationId = conversationId,
                source = source,
                title = title,
                workspace = workspace,
                snippet = snippet
            )
        }
    }

    fun triggerVibration(isQuestion: Boolean = false) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return

            val timings = if (isQuestion) {
                longArrayOf(0L, 130L, 70L, 130L, 70L, 220L)
            } else {
                longArrayOf(0L, 160L, 80L, 210L)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun postSystemNotification(
        isQuestion: Boolean,
        conversationId: String,
        source: String,
        title: String,
        workspace: String,
        snippet: String
    ) {
        val nm = notificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted; skipping visual notification")
                return
            }
        }

        ensureChannels()

        val effectiveTitle = title.ifBlank {
            if (conversationId.isNotBlank() && conversationId != "new") {
                "Session ${conversationId.take(8)}"
            } else {
                "Antigravity"
            }
        }
        val headerPrefix = if (isQuestion) "Agent Question" else "Prompt Answered"
        val notifTitle = "$headerPrefix • $effectiveTitle"
        val notifBody = buildString {
            if (workspace.isNotBlank()) {
                append('[').append(workspace).append("] ")
            }
            append(
                snippet.ifBlank {
                    if (isQuestion) "Agent is waiting for your input." else "Response completed."
                }
            )
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId.isNotBlank()) {
                putExtra(EXTRA_OPEN_CONVERSATION_ID, conversationId)
            }
            if (source.isNotBlank()) {
                putExtra(EXTRA_OPEN_CONVERSATION_SOURCE, source)
            }
        }

        val reqCode = if (conversationId.isNotBlank()) conversationId.hashCode() else notificationCounter.incrementAndGet()
        val pendingIntent = PendingIntent.getActivity(
            context,
            reqCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_AGENT_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(notifTitle)
            .setContentText(notifBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notifBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notifId = if (conversationId.isNotBlank() && conversationId != "new") {
            2000 + (conversationId.hashCode() and 0x7FFF)
        } else {
            notificationCounter.incrementAndGet()
        }
        nm.notify(notifId, notification)
    }

    companion object {
        private const val TAG = "AimonNotifManager"
        const val PREFS_NAME = "aimon_prefs"
        const val CHANNEL_SERVICE = "aimon_service"
        const val CHANNEL_AGENT_ALERTS = "aimon_agent_alerts"
        const val EXTRA_OPEN_CONVERSATION_ID = "EXTRA_OPEN_CONVERSATION_ID"
        const val EXTRA_OPEN_CONVERSATION_SOURCE = "EXTRA_OPEN_CONVERSATION_SOURCE"

        private val notificationCounter = AtomicInteger(3000)
        private val notifiedEventsLock = Any()
        private val notifiedEvents = LinkedHashSet<String>()
    }
}
