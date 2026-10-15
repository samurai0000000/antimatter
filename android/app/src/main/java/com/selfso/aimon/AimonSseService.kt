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
    private var sseJob: Job? = null
    @Volatile
    private var isRunning = false

    private val sseHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private lateinit var notificationManager: AimonNotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = AimonNotificationManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notificationManager.ensureChannels()
        val notification = NotificationCompat.Builder(this, AimonNotificationManager.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Aimon Companion")
            .setContentText("Listening for real-time approval and quota events")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_SERVICE, notification)
            }
        } catch (e: Exception) {
            Log.w("AimonSseService", "Could not startForeground: ${e.message}")
        }

        val intentBaseUrl = intent?.getStringExtra(EXTRA_BASE_URL)
        val intentToken = intent?.getStringExtra(EXTRA_TOKEN)
        if (!intentBaseUrl.isNullOrBlank() || !intentToken.isNullOrBlank()) {
            val prefs = getSharedPreferences(AimonNotificationManager.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (!intentBaseUrl.isNullOrBlank()) {
                editor.putString("base_url", intentBaseUrl.trimEnd('/'))
            }
            if (!intentToken.isNullOrBlank()) {
                editor.putString("mobile_token", intentToken)
                editor.putString("token", intentToken)
            }
            editor.apply()
        }

        if (!isRunning || sseJob?.isActive != true) {
            isRunning = true
            startSseLoop()
        }

        return START_STICKY
    }

    private fun startSseLoop() {
        sseJob?.cancel()
        sseJob = serviceScope.launch {
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
                            Log.w("AimonSseService", "SSE connection returned HTTP ${response.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("AimonSseService", "SSE stream disconnected: ${e.message}")
                }

                delay(3000)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        sseJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
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
                Log.w("AimonSseService", "Failed to start AimonSseService: ${e.message}")
            }
        }

        fun parseSseApprovalEvent(eventType: String, rawData: String): PendingApproval? {
            if (!rawData.startsWith("{")) return null
            return try {
                val root = JsonParser.parseString(rawData).asJsonObject
                val type = root.get("type")?.takeIf { !it.isJsonNull }?.asString ?: eventType
                if (type != "approval_request") return null

                val payloadObj: JsonObject = if (root.has("payload") && root.get("payload").isJsonObject) {
                    root.getAsJsonObject("payload")
                } else {
                    root
                }

                val approvalId = payloadObj.get("approval_id")?.takeIf { !it.isJsonNull }?.asString
                    ?: payloadObj.get("request_id")?.takeIf { !it.isJsonNull }?.asString
                    ?: payloadObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: return null

                val agentType = payloadObj.get("agent_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: payloadObj.get("agent")?.takeIf { !it.isJsonNull }?.asString
                    ?: "antigravity"
                val toolName = payloadObj.get("tool_name")?.takeIf { !it.isJsonNull }?.asString
                    ?: payloadObj.get("action_type")?.takeIf { !it.isJsonNull }?.asString
                    ?: "run_command"
                val workspace = payloadObj.get("workspace")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val reason = payloadObj.get("reason")?.takeIf { !it.isJsonNull }?.asString
                    ?: payloadObj.get("details")?.takeIf { !it.isJsonNull }?.asString
                    ?: ""
                val command = payloadObj.get("command")?.takeIf { !it.isJsonNull }?.asString
                val timeoutSec = payloadObj.get("timeout_seconds")?.takeIf { !it.isJsonNull }?.asInt ?: 120
                val requestedAt = payloadObj.get("requested_at")?.takeIf { !it.isJsonNull }?.asLong
                    ?: (System.currentTimeMillis() / 1000)

                val argsMap = mutableMapOf<String, Any>()
                if (payloadObj.has("tool_args") && payloadObj.get("tool_args").isJsonObject) {
                    val toolArgsObj = payloadObj.getAsJsonObject("tool_args")
                    for ((k, v) in toolArgsObj.entrySet()) {
                        if (v != null && !v.isJsonNull) {
                            argsMap[k] = if (v.isJsonPrimitive) v.asString else v.toString()
                        }
                    }
                }

                PendingApproval(
                    approvalId = approvalId,
                    agentType = agentType,
                    toolName = toolName,
                    workspace = workspace,
                    toolArgs = argsMap,
                    command = command ?: argsMap["CommandLine"]?.toString() ?: argsMap["command"]?.toString(),
                    reason = reason,
                    timeoutSeconds = timeoutSec,
                    requestedAt = requestedAt
                )
            } catch (_: Exception) {
                null
            }
        }

        fun handleSseEvent(
            eventType: String,
            rawData: String,
            notificationManager: AimonNotificationManager? = null
        ) {
            if (!rawData.startsWith("{")) return
            try {
                val root = JsonParser.parseString(rawData).asJsonObject
                val type = root.get("type")?.takeIf { !it.isJsonNull }?.asString ?: eventType
                when (type) {
                    "approval_request" -> {
                        val approval = parseSseApprovalEvent(eventType, rawData) ?: return
                        AimonViewModel.notifyIncomingApproval(approval)
                        notificationManager?.showApprovalNotification(approval)
                    }
                    "approval_resolved" -> {
                        val payloadObj = if (root.has("payload") && root.get("payload").isJsonObject) {
                            root.getAsJsonObject("payload")
                        } else {
                            root
                        }
                        val approvalId = payloadObj.get("approval_id")?.takeIf { !it.isJsonNull }?.asString
                            ?: payloadObj.get("request_id")?.takeIf { !it.isJsonNull }?.asString
                            ?: return
                        AimonViewModel.notifyApprovalResolved(approvalId)
                        notificationManager?.dismissApprovalNotification(approvalId)
                    }
                    "quota_update", "telemetry_sample" -> {
                        AimonViewModel.notifyRefreshRequested()
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
