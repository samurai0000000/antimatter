/*
 * BridgeService.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.core.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.content.pm.ServiceInfo
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BridgeService : Service() {

    @Inject lateinit var webSocket: BridgeWebSocket

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    @Volatile
    private var isForegroundPromoted = false

    override fun onCreate() {
        super.onCreate()
        
        createNotificationChannel()
        createAlertChannel()
        promoteToForeground("Disconnected")

        try {
            // Acquire WakeLocks to prevent CPU and Wi-Fi from sleeping while connected
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Antimatter::BridgeWakeLock")
            wakeLock?.acquire()

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Antimatter::BridgeWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.w("BridgeService", "Failed to acquire wake/wifi locks: ${e.message}")
        }
        
        serviceScope.launch {
            webSocket.messages.collect { message ->
                if (message is InboundMessage.SystemAlert) {
                    showSystemAlert(message.title, message.body)
                }
            }
        }

        serviceScope.launch {
            var sawConnected = false
            webSocket.connectionState.collect { state ->
                if (state == BridgeWebSocket.ConnectionState.CONNECTED) {
                    sawConnected = true
                    promoteToForeground("Connected to Antigravity IDE")
                } else if (state == BridgeWebSocket.ConnectionState.DISCONNECTED && sawConnected) {
                    stopServiceSafely()
                }
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // Ignore release exceptions
        }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = when {
            intent?.action == "ACTION_DISCONNECT" || intent?.action == "ACTION_STOP" -> "Disconnecting..."
            intent == null -> "Reconnecting to Antigravity..."
            else -> "Connected to Antigravity IDE"
        }
        promoteToForeground(statusText)

        if (intent?.action == "ACTION_DISCONNECT" || intent?.action == "ACTION_STOP") {
            webSocket.disconnect()
            stopServiceSafely()
            return START_NOT_STICKY
        }

        if (webSocket.connectionState.value == BridgeWebSocket.ConnectionState.DISCONNECTED) {
            stopServiceSafely()
        }
        
        return START_NOT_STICKY
    }

    private fun promoteToForeground(statusText: String) {
        try {
            val disconnectIntent = PendingIntent.getService(
                this, 0,
                Intent(this, BridgeService::class.java).apply { action = "ACTION_DISCONNECT" },
                PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, "bridge_channel")
                .setContentTitle("Antimatter Bridge")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(1, notification)
            }
            isForegroundPromoted = true
        } catch (e: Exception) {
            Log.w("BridgeService", "Unable to start foreground service: ${e.message}")
        }
    }

    private fun stopServiceSafely() {
        if (!isForegroundPromoted) {
            promoteToForeground("Disconnected")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w("BridgeService", "Unable to stop foreground: ${e.message}")
        }
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.w("BridgeService", "Unable to stopSelf: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bridge_channel",
                "Bridge Connection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the WebSocket connection alive in the background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createAlertChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "alert_channel",
                "System Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority alerts from Antigravity IDE"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun showSystemAlert(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, "alert_channel")
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
