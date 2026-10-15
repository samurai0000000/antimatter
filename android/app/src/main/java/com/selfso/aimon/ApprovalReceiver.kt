/*
 * ApprovalReceiver.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.TimeUnit

class ApprovalReceiver : BroadcastReceiver() {

    private val okHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_APPROVAL_ID)
            ?: intent.getStringExtra(EXTRA_REQUEST_ID)
            ?: intent.getStringExtra("request_id")
            ?: intent.getStringExtra("approval_id")
            ?: return
        val action = intent.action ?: return

        val approved = when (action) {
            ACTION_APPROVE -> true
            ACTION_DENY -> false
            else -> return
        }
        val decision = if (approved) "allow" else "deny"

        try {
            AimonNotificationManager(context).dismissApprovalNotification(requestId)
        } catch (_: Exception) {}

        AimonViewModel.notifyApprovalResolved(requestId)

        val prefs = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            null
        }

        val rawUrl = prefs?.getString("base_url", null)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_BASE_URL)?.takeIf { it.isNotBlank() }
            ?: AimonViewModel.currentBaseUrl
        val baseUrl = if (rawUrl.endsWith("/")) rawUrl.dropLast(1) else rawUrl

        val token = prefs?.getString("mobile_token", null)?.takeIf { it.isNotBlank() }
            ?: prefs?.getString("token", null)?.takeIf { it.isNotBlank() }
            ?: intent.getStringExtra(EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
            ?: AimonViewModel.currentMobileToken

        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                dispatchDecisionHttp(baseUrl, token, requestId, approved, decision)
            } catch (e: Exception) {
                Log.e("ApprovalReceiver", "Failed to dispatch approval decision for $requestId: ${e.message}")
            } finally {
                try {
                    pendingResult?.finish()
                } catch (_: Exception) {}
            }
        }
    }

    internal fun dispatchDecisionHttp(
        baseUrl: String,
        token: String,
        requestId: String,
        approved: Boolean,
        decision: String = if (approved) "allow" else "deny"
    ): Boolean {
        val payload = mapOf(
            "approval_id" to requestId,
            "request_id" to requestId,
            "approved" to approved,
            "decision" to decision
        )
        val jsonBody = gson.toJson(payload)
        val reqBuilder = Request.Builder()
            .url("$baseUrl/api/approvals/decision")
            .header("User-Agent", "aimon-mobile/1.0")
        if (token.isNotBlank()) {
            reqBuilder.header("X-Mobile-Token", token)
            reqBuilder.header("Authorization", "Bearer $token")
        }
        val req = reqBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        okHttpClient.newCall(req).execute().use { res ->
            return res.isSuccessful
        }
    }

    companion object {
        const val PREFS_NAME = "aimon_prefs"
        const val ACTION_APPROVE = "com.selfso.aimon.ACTION_APPROVE"
        const val ACTION_DENY = "com.selfso.aimon.ACTION_DENY"
        const val EXTRA_APPROVAL_ID = "EXTRA_APPROVAL_ID"
        const val EXTRA_REQUEST_ID = "EXTRA_REQUEST_ID"
        const val EXTRA_BASE_URL = "EXTRA_BASE_URL"
        const val EXTRA_TOKEN = "EXTRA_TOKEN"
    }
}
