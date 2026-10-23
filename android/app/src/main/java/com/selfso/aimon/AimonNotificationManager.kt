/*
 * AimonNotificationManager.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

class AimonNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    init {
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

            nm.createNotificationChannel(serviceChannel)
        }
    }

    companion object {
        const val PREFS_NAME = "aimon_prefs"
        const val CHANNEL_SERVICE = "aimon_service"
    }
}
