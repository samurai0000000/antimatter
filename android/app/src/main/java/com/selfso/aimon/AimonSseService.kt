/*
 * AimonSseService.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.TimeUnit

class AimonSseService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null
    @Volatile
    private var isRunning = false
    private var currentBaseUrl: String = ""
    private var currentToken: String = ""

    private val sseHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var notificationManager: AimonNotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = AimonNotificationManager(this)
        promoteToForeground("Initializing Aimon stream...")
    }

    private fun promoteToForeground(statusText: String) {
        try {
            notificationManager.ensureChannels()
            val notification = NotificationCompat.Builder(this, AimonNotificationManager.CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Aimon Companion")
                .setContentText(statusText)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID_SERVICE, notification, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote foreground service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            promoteToForeground("Stopping Aimon stream...")
            isRunning = false
            streamJob?.cancel()
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
            stopSelf()
            return START_NOT_STICKY
        }

        val intentBaseUrl = intent?.getStringExtra(EXTRA_BASE_URL)
        val intentToken = intent?.getStringExtra(EXTRA_TOKEN)
        val prefs = try {
            getSharedPreferences(AimonNotificationManager.PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }

        if (!intentBaseUrl.isNullOrBlank() || !intentToken.isNullOrBlank()) {
            prefs?.edit()?.apply {
                if (!intentBaseUrl.isNullOrBlank()) putString("base_url", intentBaseUrl.trimEnd('/'))
                if (!intentToken.isNullOrBlank()) {
                    putString("mobile_token", intentToken)
                    putString("token", intentToken)
                }
                apply()
            }
        }

        val rawUrl = intentBaseUrl?.takeIf { it.isNotBlank() }
            ?: prefs?.getString("base_url", null)?.takeIf { it.isNotBlank() }
            ?: AimonViewModel.currentBaseUrl
        val baseUrl = rawUrl.trimEnd('/')
        val token = intentToken?.takeIf { it.isNotBlank() }
            ?: prefs?.getString("mobile_token", null)?.takeIf { it.isNotBlank() }
            ?: prefs?.getString("token", null)?.takeIf { it.isNotBlank() }
            ?: AimonViewModel.currentMobileToken

        if (baseUrl.isBlank()) {
            promoteToForeground("Aimon: waiting for server URL...")
            stopSelf()
            return START_NOT_STICKY
        }

        if (baseUrl == currentBaseUrl && token == currentToken && streamJob?.isActive == true) {
            promoteToForeground("Listening for real-time quota and telemetry events")
            return START_STICKY
        }

        currentBaseUrl = baseUrl
        currentToken = token
        isRunning = true
        startSseLoop()
        promoteToForeground("Listening for real-time quota and telemetry events")

        return START_STICKY
    }

    private fun startSseLoop() {
        streamJob?.cancel()
        streamJob = serviceScope.launch {
            while (isActive && isRunning) {
                val prefs = try {
                    getSharedPreferences(AimonNotificationManager.PREFS_NAME, Context.MODE_PRIVATE)
                } catch (_: Exception) {
                    null
                }
                val rawUrl = prefs?.getString("base_url", null)?.takeIf { it.isNotBlank() }
                    ?: AimonViewModel.currentBaseUrl
                val baseUrl = rawUrl.trimEnd('/')
                val token = prefs?.getString("mobile_token", null)?.takeIf { it.isNotBlank() }
                    ?: prefs?.getString("token", null)?.takeIf { it.isNotBlank() }
                    ?: AimonViewModel.currentMobileToken

                if (token.isBlank()) {
                    delay(3000)
                    continue
                }

                val sseUrl = "$baseUrl/sse?profile=mobile"
                val reqBuilder = Request.Builder()
                    .url(sseUrl)
                    .header("Accept", "text/event-stream")
                    .header("User-Agent", "aimon-mobile/1.0")
                    .header("X-Mobile-Token", token)
                    .header("Authorization", "Bearer $token")

                try {
                    sseHttpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val source = response.body?.source()
                            var currentEvent = "message"
                            if (source != null) {
                                while (isActive && isRunning && !source.exhausted()) {
                                    val line = source.readUtf8Line() ?: break
                                    when {
                                        line.startsWith("event:") -> {
                                            currentEvent = line.removePrefix("event:").trim()
                                        }
                                        line.startsWith("data:") -> {
                                            val data = line.removePrefix("data:").trim()
                                            handleSseEvent(currentEvent, data, notificationManager)
                                        }
                                        line.isEmpty() -> {
                                            currentEvent = "message"
                                        }
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "SSE connection returned HTTP ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSE stream disconnected: ${e.message}")
                }

                delay(3000)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        streamJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TAG = "AimonSseService"
        const val ACTION_STOP = "com.selfso.aimon.ACTION_STOP"
        const val NOTIFICATION_ID_SERVICE = 1002
        const val EXTRA_BASE_URL = "EXTRA_BASE_URL"
        const val EXTRA_TOKEN = "EXTRA_TOKEN"

        fun start(context: Context, baseUrl: String? = null, token: String? = null) {
            try {
                val intent = Intent(context, AimonSseService::class.java).apply {
                    if (!baseUrl.isNullOrBlank()) putExtra(EXTRA_BASE_URL, baseUrl)
                    if (!token.isNullOrBlank()) putExtra(EXTRA_TOKEN, token)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start/stop AimonSseService from current lifecycle state: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AimonSseService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot start/stop AimonSseService from current lifecycle state: ${e.message}")
            }
        }

        fun handleSseEvent(
            eventType: String,
            rawData: String,
            @Suppress("UNUSED_PARAMETER") notificationManager: AimonNotificationManager? = null
        ) {
            if (!rawData.startsWith("{")) return
            try {
                val root = JsonParser.parseString(rawData).asJsonObject
                val type = root.get("type")?.takeIf { !it.isJsonNull }?.asString ?: eventType
                when (type) {
                    "quota_update", "telemetry_sample" -> {
                        AimonViewModel.notifyRefreshRequested()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
