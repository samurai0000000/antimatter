/*
 * AimonCompanionLiveLifecycleTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.Context
import com.google.gson.Gson
import dev.saifmukhtar.antimatter.core.data.AppDao
import dev.saifmukhtar.antimatter.core.data.UserPreferencesRepository
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.feature.chat.ChatItem
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
import dev.saifmukhtar.antimatter.feature.chat.groupSteps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import java.net.Proxy
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class AimonCompanionLiveLifecycleTest {

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val baseUrl = "http://127.0.0.1:3883"

    @Test
    fun testFullLiveLifecycleAgainstDaemon() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        var createdLiveCid: String? = null
        var grantedSessionToken: String? = null
        try {
            println("=== Stage 1: QR Code & Pairing Secret Verification ===")
            val qrReq = Request.Builder()
                .url("$baseUrl/api/mobile/qr")
                .header("User-Agent", "aimon-mobile/1.0")
                .get()
                .build()
            val qrResp = httpClient.newCall(qrReq).execute()
            val qrBody = qrResp.body?.string() ?: ""
            println("QR Response Code: ${qrResp.code}, Body: $qrBody")
            assertTrue("GET /api/mobile/qr must return 200 OK (Got ${qrResp.code}: $qrBody)", qrResp.isSuccessful)
            assertTrue("QR Response must contain secret", qrBody.contains("secret"))
            assertTrue("QR Response must contain lan_ip", qrBody.contains("lan_ip"))

            val qrJson = gson.fromJson(qrBody, Map::class.java)
            val pairingSecret = qrJson["secret"] as String
            assertNotNull("Pairing secret must not be null", pairingSecret)

            println("=== Stage 2: Mobile Pairing Handshake (Zero-Python Bridge) ===")
            val pairPayload = """
                {
                    "secret": "$pairingSecret",
                    "device_id": "test-android-device",
                    "device_name": "Pixel 9 Pro Test"
                }
            """.trimIndent()
            val pairReq = Request.Builder()
                .url("$baseUrl/api/mobile/pair")
                .header("User-Agent", "aimon-mobile/1.0")
                .post(pairPayload.toRequestBody("application/json".toMediaType()))
                .build()
            val pairResp = httpClient.newCall(pairReq).execute()
            assertTrue("POST /api/mobile/pair must return 200 OK", pairResp.isSuccessful)
            val pairBody = pairResp.body?.string() ?: ""
            val pairData = gson.fromJson(pairBody, Map::class.java)
            val sessionToken = pairData["token"] as? String
            grantedSessionToken = sessionToken
            assertNotNull("Session token must be granted", sessionToken)
            println("Pairing successful! Granted Mobile Session Token: ${sessionToken?.take(12)}...")

            println("=== Stage 3: Quota Status Parsing & Deserialization ===")
            val statusReq = Request.Builder()
                .url("$baseUrl/api/status")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val statusResp = httpClient.newCall(statusReq).execute()
            assertTrue("GET /api/status must return 200 OK", statusResp.isSuccessful)
            val statusBody = statusResp.body?.string() ?: ""
            val quotaStatus = gson.fromJson(statusBody, QuotaStatus::class.java)
            assertNotNull("QuotaStatus must not be null", quotaStatus)

            // Antigravity verification
            val ag = quotaStatus.antigravity
            assertNotNull("Antigravity quota must exist", ag)
            assertEquals("Google AI Ultra", ag?.planTier)
            assertTrue("Total credits remaining must be > 0", (ag?.totalCreditsRemaining ?: 0) > 0)
            assertTrue("Quota groups must exist", ag?.quotaGroups?.isNotEmpty() == true)
            val geminiGroup = ag?.quotaGroups?.find { it.displayName.contains("Gemini") }
            assertNotNull("Gemini quota group must exist", geminiGroup)
            assertTrue("Gemini group buckets must not be empty", geminiGroup?.buckets?.isNotEmpty() == true)

            // Cursor verification
            val cur = quotaStatus.cursor
            assertNotNull("Cursor quota must exist", cur)
            assertTrue("Fast requests limit must be > 0", (cur?.fastRequestsLimit ?: 0) > 0)
            assertTrue("Total spend must be >= 0", (cur?.totalSpendUsd ?: -1.0) >= 0.0)
            println("Antigravity Credits: ${ag?.totalCreditsRemaining}, Cursor Fast Requests: ${cur?.fastRequestsUsed}/${cur?.fastRequestsLimit}, Spend: $${cur?.totalSpendUsd}")

            println("=== Stage 4: Verify Removed Action Approvals Endpoints Return 404 ===")
            val removedApprovalsReq = Request.Builder()
                .url("$baseUrl/api/approvals/pending")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val removedApprovalsResp = httpClient.newCall(removedApprovalsReq).execute()
            assertEquals("GET /api/approvals/pending must return 404 Not Found", 404, removedApprovalsResp.code)
            removedApprovalsResp.close()

            println("=== Stage 5: Telemetry Overview & Timeseries ===")
            val telemetryReq = Request.Builder()
                .url("$baseUrl/api/telemetry/overview")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val telemetryResp = httpClient.newCall(telemetryReq).execute()
            assertTrue("GET /api/telemetry/overview must return 200 OK", telemetryResp.isSuccessful)

            val timeseriesReq = Request.Builder()
                .url("$baseUrl/api/telemetry/timeseries")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val timeseriesResp = httpClient.newCall(timeseriesReq).execute()
            assertTrue("GET /api/telemetry/timeseries must return 200 OK", timeseriesResp.isSuccessful)

            println("=== Stage 6: Discovered Network Monitors ===")
            val monitorsReq = Request.Builder()
                .url("$baseUrl/api/monitors")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val monitorsResp = httpClient.newCall(monitorsReq).execute()
            assertTrue("GET /api/monitors must return 200 OK", monitorsResp.isSuccessful)

            println("=== Stage 7: Android APK Download Endpoint ===")
            val apkReq = Request.Builder()
                .url("$baseUrl/download/aimon-companion.apk")
                .head()
                .build()
            val apkResp = httpClient.newCall(apkReq).execute()
            assertTrue("HEAD /download/aimon-companion.apk must return 200 OK", apkResp.isSuccessful)
            val contentLength = apkResp.header("Content-Length")?.toLongOrNull() ?: 0L
            assertTrue("APK size must be > 50 MB", contentLength > 50_000_000L)
            println("APK Download validated: $contentLength bytes")

            println("=== Stage 7b: Live Slash Commands (/help) & Session Notifications (/api/mobile/notifications) ===")
            val slashHelpReq = Request.Builder()
                .url("$baseUrl/api/agent/prompt")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .post("""{"prompt":"/help","conversationId":"new"}""".toRequestBody("application/json".toMediaType()))
                .build()
            val slashHelpResp = httpClient.newCall(slashHelpReq).execute()
            assertTrue("POST /api/agent/prompt with /help must return 200 OK", slashHelpResp.isSuccessful)
            val slashHelpBody = slashHelpResp.body?.string() ?: ""
            val slashHelpJson = org.json.JSONObject(slashHelpBody)
            assertEquals("Slash /help response must return status=command_response", "command_response", slashHelpJson.optString("status"))
            assertTrue("Slash /help response must list /model", slashHelpBody.contains("/model"))

            val testNotifReq = Request.Builder()
                .url("$baseUrl/api/mobile/test-notification")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .post("""{"event":"prompt_answered","conversationId":"live-notif-test","title":"Live Test","snippet":"Completed"}""".toRequestBody("application/json".toMediaType()))
                .build()
            val testNotifResp = httpClient.newCall(testNotifReq).execute()
            assertTrue("POST /api/mobile/test-notification must return 200 OK", testNotifResp.isSuccessful)

            val listNotifReq = Request.Builder()
                .url("$baseUrl/api/mobile/notifications?since=0")
                .header("User-Agent", "aimon-mobile/1.0")
                .header("X-Mobile-Token", sessionToken ?: "")
                .get()
                .build()
            val listNotifResp = httpClient.newCall(listNotifReq).execute()
            assertTrue("GET /api/mobile/notifications must return 200 OK", listNotifResp.isSuccessful)
            val listNotifBody = listNotifResp.body?.string() ?: ""
            assertTrue("Notifications feed must include live-notif-test", listNotifBody.contains("live-notif-test"))

            println("=== Stage 8: Live Antigravity Chat via Android BridgeWebSocket + ChatViewModel + groupSteps ===")
            val mockContext = mock(Context::class.java)
            val mockUserPrefs = mock(UserPreferencesRepository::class.java)
            val mockDao = mock(AppDao::class.java)
            `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
            `when`(mockDao.getAllConversationsFlow()).thenReturn(flowOf(emptyList()))
            `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(emptyList()))
            `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())
            `when`(mockDao.getAllConversationsSnapshot()).thenReturn(emptyList())

            val bridgeWebSocket = BridgeWebSocket(mockContext)
            val chatVm = ChatViewModel(mockContext, bridgeWebSocket, mockUserPrefs, mockDao)
            chatVm.configureEndpoint(baseUrl, sessionToken ?: "")

            // Wait for history to load and verify internal SYSTEM_MESSAGE subagent trajectories are filtered out
            var waitedMs = 0L
            while (chatVm.uiState.value.history.isEmpty() && waitedMs < 20000L) {
                delay(100)
                waitedMs += 100
            }
            assertTrue("Conversation history must not be empty", chatVm.uiState.value.history.isNotEmpty())
            assertEquals("Initial ChatUiState conversationId must default to 'new'", "new", chatVm.uiState.value.conversationId)

            // 8a. Subscribe to a real conversation containing tool calls with JSON object arguments
            val existingWithTools = chatVm.uiState.value.history.firstOrNull {
                it.stepCount > 5 && !it.title.contains("Identify yourself", ignoreCase = true)
            }?.id ?: chatVm.uiState.value.history.first().id
            chatVm.subscribeConversation(existingWithTools)
            waitedMs = 0L
            while (chatVm.uiState.value.steps.isEmpty() && waitedMs < 20000L) {
                delay(100)
                waitedMs += 100
            }
            assertNull("Subscribing to real conversation with tool calls must not produce an error", chatVm.uiState.value.error)
            assertTrue("Real conversation steps must load into ChatViewModel", chatVm.uiState.value.steps.isNotEmpty())
            println("Loaded ${chatVm.uiState.value.steps.size} steps from existing conversation $existingWithTools without error")

            // 8b. Start a new conversation and send "Identify yourself and tell me the time"
            chatVm.startNewConversation()
            val promptText = "Identify yourself and tell me the time"
            println("Sending live prompt through ChatViewModel: \"$promptText\"")
            chatVm.sendMessage(promptText)
            assertTrue("ChatViewModel must enter isGenerating=true immediately after sendMessage", chatVm.uiState.value.isGenerating)

            waitedMs = 0L
            while (waitedMs < 90000L) {
                val st = chatVm.uiState.value
                if (!st.conversationId.isNullOrBlank() && st.conversationId != "new") {
                    createdLiveCid = st.conversationId
                }
                val hasAgentReply = st.steps.any {
                    it.stepCase == StepCase.TEXT && !it.value.isNullOrBlank()
                }
                if (!st.isGenerating && hasAgentReply) {
                    break
                }
                if (st.error != null && !st.isGenerating) {
                    break
                }
                delay(250)
                waitedMs += 250
            }

            val finalState = chatVm.uiState.value
            println("Live Chat Final State after ${waitedMs}ms: conversationId=${finalState.conversationId}, isGenerating=${finalState.isGenerating}, error=${finalState.error}, steps=${finalState.steps.size}")
            assertNull("Live chat must not fail with an error (got: ${finalState.error})", finalState.error)
            assertNotNull("Live chat must resolve a real conversationId", finalState.conversationId)
            assertNotEquals("Live chat conversationId must not remain 'new'", "new", finalState.conversationId)
            assertFalse("Live chat isGenerating must transition to false when reply arrives", finalState.isGenerating)

            val grouped = groupSteps(finalState.steps)
            val userBubbles = grouped.filterIsInstance<ChatItem.SingleStep>()
                .map { it.step }
                .filter { it.stepCase == StepCase.USER_INPUT }
            val textBubbles = grouped.filterIsInstance<ChatItem.SingleStep>()
                .map { it.step }
                .filter { it.stepCase == StepCase.TEXT }

            assertTrue("ChatScreen groupSteps must include the user's prompt bubble", userBubbles.any { it.value?.contains("Identify yourself") == true })
            assertTrue("ChatScreen groupSteps must include the AI agent's text response bubble", textBubbles.isNotEmpty())

            val replyText = textBubbles.joinToString("\n") { it.value ?: "" }
            println("Live Antigravity Agent Response:\n$replyText")
            assertTrue(
                "AI response must identify itself (got: $replyText)",
                replyText.contains("Antigravity", ignoreCase = true) ||
                    replyText.contains("assistant", ignoreCase = true) ||
                    replyText.contains("Gemini", ignoreCase = true)
            )
            assertTrue(
                "AI response must include the time (got: $replyText)",
                replyText.contains(":") ||
                    replyText.contains("AM", ignoreCase = true) ||
                    replyText.contains("PM", ignoreCase = true) ||
                    replyText.contains("UTC", ignoreCase = true) ||
                    replyText.contains("2026")
            )

            println("=== ALL 8 LIFECYCLE STAGES (INCLUDING LIVE ANTIGRAVITY CHAT) PASSED CLEANLY ===")
        } finally {
            if (!grantedSessionToken.isNullOrBlank()) {
                try {
                    val revokeReq = Request.Builder()
                        .url("$baseUrl/api/mobile/revoke")
                        .header("X-Mobile-Token", grantedSessionToken!!)
                        .post("""{"device_id":"test-android-device"}""".toRequestBody("application/json".toMediaType()))
                        .build()
                    httpClient.newCall(revokeReq).execute().close()
                } catch (_: Exception) {}
            }
            if (!createdLiveCid.isNullOrBlank() && createdLiveCid != "new") {
                val home = System.getProperty("user.home") ?: "/home/samurai"
                File("$home/.gemini/antigravity-ide/brain/$createdLiveCid").deleteRecursively()
                File("$home/.gemini/antigravity-cli/brain/$createdLiveCid").deleteRecursively()
                File("$home/.gemini/antigravity-cli/conversations/$createdLiveCid.db").delete()
                File("$home/.gemini/antigravity-cli/annotations/$createdLiveCid.pbtxt").delete()
            }
            Dispatchers.resetMain()
        }
    }
}

