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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create wakeLock: ${e.message}")
        }

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Antimatter::BridgeWifiLock")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create wifiLock: ${e.message}")
        }

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes fallback */)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakeLock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifiLock: ${e.message}")
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
        releaseLocks()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val statusText = when {
            intent?.action == ACTION_DISCONNECT || intent?.action == ACTION_STOP -> "Disconnecting..."
            intent == null -> "Reconnecting to Antigravity..."
            else -> "Connected to Antigravity IDE"
        }
        promoteToForeground(statusText)

        if (intent?.action == ACTION_DISCONNECT || intent?.action == ACTION_STOP) {
            webSocket.disconnect()
            stopServiceSafely()
            return START_NOT_STICKY
        }

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L /* 10 minutes fallback */)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakeLock: ${e.message}")
        }

        try {
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wifiLock: ${e.message}")
        }

        if (webSocket.connectionState.value == BridgeWebSocket.ConnectionState.DISCONNECTED) {
            stopServiceSafely()
        }
        
        return START_NOT_STICKY
    }

    private fun buildNotification(text: String): Notification {
        val disconnectIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BridgeService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, "bridge_channel")
            .setContentTitle("Antimatter Bridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Disconnect", disconnectIntent)
            .setOngoing(true)
            .build()
    }

    private fun promoteToForeground(statusText: String) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(statusText), type)
            isForegroundPromoted = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote foreground service", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wakeLock: ${e.message}")
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wifiLock: ${e.message}")
        }
    }

    private fun stopServiceSafely() {
        if (!isForegroundPromoted) {
            promoteToForeground("Disconnected")
        }
        releaseLocks()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop foreground: ${e.message}")
        }
        try {
            stopSelf()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stopSelf: ${e.message}")
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

    companion object {
        private const val TAG = "BridgeService"
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"

        fun start(context: Context) {
            try {
                val intent = Intent(context, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start BridgeService from current lifecycle state: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, BridgeService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot stop BridgeService from current lifecycle state: ${e.message}")
            }
        }
    }
}
