/*
 * AimonCompanionLiveLifecycleTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.util.concurrent.TimeUnit

class AimonCompanionLiveLifecycleTest {

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val baseUrl = "http://127.0.0.1:3883"

    @Test
    fun testFullLiveLifecycleAgainstDaemon() = runBlocking {
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

        println("=== Stage 4: Live Action Approval Pipeline ===")
        val approvalPayload = """
            {
                "agent_type": "antigravity",
                "tool_name": "run_command",
                "workspace": "~/work/aimon",
                "tool_args": { "CommandLine": "make test" },
                "reason": "Automated Lifecycle Test",
                "timeout_seconds": 15
            }
        """.trimIndent()

        // 1. Launch blocking approval request in background coroutine
        val approvalJob = async(Dispatchers.IO) {
            val reqApproval = Request.Builder()
                .url("$baseUrl/api/approvals/request")
                .header("User-Agent", "aimon-mobile/1.0")
                .post(approvalPayload.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(reqApproval).execute()
            resp.body?.string() ?: ""
        }

        // Wait 300ms for request to register
        delay(300)

        // 2. Fetch pending approvals from companion
        val pendingReq = Request.Builder()
            .url("$baseUrl/api/approvals/pending")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken ?: "")
            .get()
            .build()
        val pendingResp = httpClient.newCall(pendingReq).execute()
        assertTrue("GET /api/approvals/pending must succeed", pendingResp.isSuccessful)
        val pendingBody = pendingResp.body?.string() ?: ""
        val listType = object : com.google.gson.reflect.TypeToken<List<PendingApproval>>() {}.type
        val pendingList: List<PendingApproval> = gson.fromJson(pendingBody, listType) ?: emptyList()
        assertTrue("Pending list should have at least 1 item", pendingList.isNotEmpty())
        val testApproval = pendingList.last()
        println("Pending Approval ID: ${testApproval.approvalId}, Tool: ${testApproval.toolName}")

        // 3. Submit decision from companion
        val decisionPayload = """
            {
                "approval_id": "${testApproval.approvalId}",
                "decision": "ALLOW"
            }
        """.trimIndent()
        val decisionReq = Request.Builder()
            .url("$baseUrl/api/approvals/decision")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken ?: "")
            .post(decisionPayload.toRequestBody("application/json".toMediaType()))
            .build()
        val decisionResp = httpClient.newCall(decisionReq).execute()
        assertTrue("POST /api/approvals/decision must succeed", decisionResp.isSuccessful)
        println("Approval decision ALLOW submitted successfully.")

        // 4. Verify request completes with APPROVED
        val approvalResult = approvalJob.await()
        println("Approval Result: $approvalResult")
        assertTrue("Approval verdict must be APPROVED", approvalResult.contains("APPROVED"))

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

        println("=== ALL 7 LIFECYCLE STAGES PASSED CLEANLY ===")
    }
}

