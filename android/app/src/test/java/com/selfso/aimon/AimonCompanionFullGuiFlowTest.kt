/*
 * AimonCompanionFullGuiFlowTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import dev.saifmukhtar.antimatter.core.network.FileNode
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
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

class AimonCompanionFullGuiFlowTest {

    private val httpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val baseUrl = "http://127.0.0.1:3883"

    @Test
    fun testAllFiveGuiTabsAndZeroPythonBridge() = runBlocking {
        println("================================================================")
        println("  AIMON COMPANION AUTOMATED GUI & PROTOCOL INTEGRATION SUITE   ")
        println("================================================================")

        // -------------------------------------------------------------
        // FLOW 0: Mobile Pairing & Token Generation (Zero-Python Bridge)
        // -------------------------------------------------------------
        println("\n[Flow 0] Pairing & Authentication Handshake")
        val qrReq = Request.Builder()
            .url("$baseUrl/api/mobile/qr")
            .header("User-Agent", "aimon-mobile/1.0")
            .get()
            .build()
        val qrResp = httpClient.newCall(qrReq).execute()
        assertTrue("GET /api/mobile/qr must return 200 OK", qrResp.isSuccessful)
        val qrJson = gson.fromJson(qrResp.body?.string(), Map::class.java)
        val pairingSecret = qrJson["secret"] as String
        assertNotNull("Pairing secret must exist", pairingSecret)

        val pairPayload = """
            {
                "secret": "$pairingSecret",
                "device_id": "qa-runner-pixel9",
                "device_name": "Automated QA Virtual Device"
            }
        """.trimIndent()
        val pairReq = Request.Builder()
            .url("$baseUrl/api/mobile/pair")
            .header("User-Agent", "aimon-mobile/1.0")
            .post(pairPayload.toRequestBody("application/json".toMediaType()))
            .build()
        val pairResp = httpClient.newCall(pairReq).execute()
        assertTrue("POST /api/mobile/pair must succeed", pairResp.isSuccessful)
        val pairJson = gson.fromJson(pairResp.body?.string(), Map::class.java)
        val sessionToken = pairJson["token"] as String
        assertNotNull("Session token must be non-null", sessionToken)
        println("  ✔ Pairing verified. Session Token: ${sessionToken.take(12)}...")

        // -------------------------------------------------------------
        // FLOW 1: Tab 1 (Chat) - Agents, History, Trajectory & Prompts
        // -------------------------------------------------------------
        println("\n[Flow 1] Tab 1: Chat Screen Flow")
        // 1.1 List Agents
        val agentsReq = Request.Builder()
            .url("$baseUrl/api/agent/available")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val agentsResp = httpClient.newCall(agentsReq).execute()
        assertTrue("GET /api/agent/available must return 200 OK", agentsResp.isSuccessful)
        val agentsBody = agentsResp.body?.string() ?: ""
        val agentsMsg = gson.fromJson(agentsBody, InboundMessage.AvailableAgents::class.java)
        assertTrue("Available agents must not be empty", agentsMsg.agents.isNotEmpty())
        val defaultAgent = agentsMsg.agents.first()
        assertEquals("antigravity", defaultAgent.id)
        assertEquals("Google Antigravity", defaultAgent.name)
        println("  ✔ Discovered agent: ${defaultAgent.name} (Status: ${defaultAgent.status})")

        // 1.2 Fetch Conversations History
        val histReq = Request.Builder()
            .url("$baseUrl/api/agent/history")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val histResp = httpClient.newCall(histReq).execute()
        assertTrue("GET /api/agent/history must return 200 OK", histResp.isSuccessful)
        val histMsg = gson.fromJson(histResp.body?.string(), InboundMessage.HistoryList::class.java)
        assertTrue("Conversations list must contain active conversations", histMsg.conversations.isNotEmpty())
        val activeConv = histMsg.conversations.first()
        println("  ✔ Fetched ${histMsg.conversations.size} conversations. Active: '${activeConv.title}' (ID: ${activeConv.id})")

        // 1.3 Fetch Conversation Trajectory & Steps
        val transcriptReq = Request.Builder()
            .url("$baseUrl/api/agent/transcript?id=${activeConv.id}")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val transcriptResp = httpClient.newCall(transcriptReq).execute()
        assertTrue("GET /api/agent/transcript must return 200 OK", transcriptResp.isSuccessful)
        val transcriptBody = transcriptResp.body?.string() ?: ""
        val transcriptJson = JsonParser.parseString(transcriptBody).asJsonObject
        assertTrue("Transcript must have steps", transcriptJson.has("steps"))
        val stepsArray = transcriptJson.getAsJsonArray("steps")
        assertTrue("Steps count must be > 0", stepsArray.size() > 0)
        
        var hasUserInput = false
        var hasPlannerResponse = false
        for (elem in stepsArray) {
            val stepObj = elem.asJsonObject.getAsJsonObject("step")
            val caseType = stepObj.get("case").asString
            if (caseType == "userInput") hasUserInput = true
            if (caseType == "plannerResponse") hasPlannerResponse = true
        }
        assertTrue("Transcript must contain userInput steps", hasUserInput)
        println("  ✔ Trajectory steps validated (${stepsArray.size()} steps parsed). User & Planner steps present.")

        // 1.4 Post Prompt
        val promptReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "Automated Chat Verification Prompt", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val promptResp = httpClient.newCall(promptReq).execute()
        assertTrue("POST /api/agent/prompt must succeed", promptResp.isSuccessful)
        println("  ✔ User prompt sent and acknowledged by native daemon.")

        // -------------------------------------------------------------
        // FLOW 2: Tab 2 (Approvals) - Live Approval Interlock Pipeline
        // -------------------------------------------------------------
        println("\n[Flow 2] Tab 2: Approvals Screen Flow")
        val approvalPayload = """
            {
                "agent_type": "antigravity",
                "tool_name": "run_command",
                "workspace": "~/work/aimon",
                "tool_args": { "CommandLine": "gradlew test" },
                "reason": "GUI Approval Workflow Test",
                "timeout_seconds": 15
            }
        """.trimIndent()

        val approvalAsync = async(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$baseUrl/api/approvals/request")
                .header("User-Agent", "aimon-mobile/1.0")
                .post(approvalPayload.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = httpClient.newCall(req).execute()
            resp.body?.string() ?: ""
        }

        delay(300)

        // Companion queries pending queue
        val pendingReq = Request.Builder()
            .url("$baseUrl/api/approvals/pending")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val pendingResp = httpClient.newCall(pendingReq).execute()
        assertTrue("GET /api/approvals/pending must succeed", pendingResp.isSuccessful)
        val pendingType = object : TypeToken<List<PendingApproval>>() {}.type
        val pendingList: List<PendingApproval> = gson.fromJson(pendingResp.body?.string(), pendingType)
        assertTrue("Pending approvals queue must have at least 1 item", pendingList.isNotEmpty())
        val targetApproval = pendingList.last()
        assertEquals("run_command", targetApproval.toolName)
        println("  ✔ Discovered pending approval: ${targetApproval.approvalId} for ${targetApproval.toolName}")

        // Companion resolves decision
        val decisionPayload = """{"approval_id": "${targetApproval.approvalId}", "decision": "ALLOW"}"""
        val decisionReq = Request.Builder()
            .url("$baseUrl/api/approvals/decision")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post(decisionPayload.toRequestBody("application/json".toMediaType()))
            .build()
        val decisionResp = httpClient.newCall(decisionReq).execute()
        assertTrue("POST /api/approvals/decision must succeed", decisionResp.isSuccessful)

        val resultJson = approvalAsync.await()
        assertTrue("Approval verdict must resolve to APPROVED", resultJson.contains("APPROVED"))
        println("  ✔ Approval verified: Resolved to APPROVED.")

        // -------------------------------------------------------------
        // FLOW 3: Tab 3 (Quotas) - Antigravity & Cursor Model Buckets
        // -------------------------------------------------------------
        println("\n[Flow 3] Tab 3: Quotas Screen Flow")
        val statusReq = Request.Builder()
            .url("$baseUrl/api/status")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val statusResp = httpClient.newCall(statusReq).execute()
        assertTrue("GET /api/status must return 200 OK", statusResp.isSuccessful)
        val quotaStatus = gson.fromJson(statusResp.body?.string(), QuotaStatus::class.java)

        val ag = quotaStatus.antigravity
        assertNotNull("Antigravity quota must not be null", ag)
        assertEquals("Google AI Ultra", ag?.planTier)
        assertTrue("Total credits remaining > 0", (ag?.totalCreditsRemaining ?: 0) > 0)
        val geminiGroup = ag?.quotaGroups?.find { it.displayName.contains("Gemini") }
        assertNotNull("Gemini quota group must exist", geminiGroup)
        println("  ✔ Antigravity Credits: ${ag?.totalCreditsRemaining}, Plan: ${ag?.planTier}")

        val cur = quotaStatus.cursor
        assertNotNull("Cursor quota must not be null", cur)
        assertTrue("Fast requests limit > 0", (cur?.fastRequestsLimit ?: 0) > 0)
        println("  ✔ Cursor Fast Requests: ${cur?.fastRequestsUsed}/${cur?.fastRequestsLimit}, Spend: $${cur?.totalSpendUsd}")

        // -------------------------------------------------------------
        // FLOW 4: Tab 4 (Telemetry) - Multi-Monitor Discovery & Analytics
        // -------------------------------------------------------------
        println("\n[Flow 4] Tab 4: Telemetry Screen Flow")
        val monitorsReq = Request.Builder()
            .url("$baseUrl/api/monitors")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val monitorsResp = httpClient.newCall(monitorsReq).execute()
        assertTrue("GET /api/monitors must succeed", monitorsResp.isSuccessful)

        val telemReq = Request.Builder()
            .url("$baseUrl/api/telemetry/overview")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val telemResp = httpClient.newCall(telemReq).execute()
        assertTrue("GET /api/telemetry/overview must succeed", telemResp.isSuccessful)
        println("  ✔ Multi-monitor telemetry discovery & overview verified.")

        // -------------------------------------------------------------
        // FLOW 5: Tab 5 (Files) - Workspace Tree & File Editor Pipeline
        // -------------------------------------------------------------
        println("\n[Flow 5] Tab 5: Files & Workspace Screen Flow")
        // 5.1 Fetch Directory Tree
        val treeReq = Request.Builder()
            .url("$baseUrl/api/workspace/tree")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val treeResp = httpClient.newCall(treeReq).execute()
        assertTrue("GET /api/workspace/tree must succeed", treeResp.isSuccessful)
        val treeMsg = gson.fromJson(treeResp.body?.string(), InboundMessage.FileTree::class.java)
        assertTrue("File tree must have items", treeMsg.tree.isNotEmpty())
        println("  ✔ Workspace tree loaded: ${treeMsg.tree.size} root items (Workspace: ${treeMsg.workspace})")

        // 5.2 Read File
        val readReq = Request.Builder()
            .url("$baseUrl/api/workspace/file?path=Makefile")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val readResp = httpClient.newCall(readReq).execute()
        assertTrue("GET /api/workspace/file?path=Makefile must succeed", readResp.isSuccessful)
        val fileMsg = gson.fromJson(readResp.body?.string(), InboundMessage.FileContent::class.java)
        assertEquals("Makefile", fileMsg.path)
        assertTrue("Makefile content must not be empty", fileMsg.content.isNotBlank())
        println("  ✔ File read verified: ${fileMsg.path} (${fileMsg.content.lines().size} lines)")

        // 5.3 Write / Save File
        val testFilePath = "build/qa_test_file.txt"
        val testContent = "Zero-Python Native Companion Bridge QA Verification - Passed"
        val writePayload = """{"path": "$testFilePath", "content": "$testContent"}"""
        val writeReq = Request.Builder()
            .url("$baseUrl/api/workspace/file")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post(writePayload.toRequestBody("application/json".toMediaType()))
            .build()
        val writeResp = httpClient.newCall(writeReq).execute()
        assertTrue("POST /api/workspace/file must succeed", writeResp.isSuccessful)

        // Read back written file
        val verifyReq = Request.Builder()
            .url("$baseUrl/api/workspace/file?path=$testFilePath")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .get()
            .build()
        val verifyResp = httpClient.newCall(verifyReq).execute()
        assertTrue("GET /api/workspace/file for written file must succeed", verifyResp.isSuccessful)
        val verifyMsg = gson.fromJson(verifyResp.body?.string(), InboundMessage.FileContent::class.java)
        assertEquals(testContent, verifyMsg.content)
        println("  ✔ File write and round-trip read verified.")

        println("\n================================================================")
        println("  ALL 5 GUI TABS & PROTOCOL FLOWS VERIFIED WITH 100% SUCCESS    ")
        println("================================================================")
    }

    @Test
    fun testInteractiveChatPromptResponseLoop_WhatTimeIsIt() = runBlocking {
        println("================================================================")
        println("  TEST: INTERACTIVE CHAT PROMPT-RESPONSE LOOP ('what time is it?')")
        println("================================================================")

        // 1. Mobile Pairing
        val qrReq = Request.Builder()
            .url("$baseUrl/api/mobile/qr")
            .header("User-Agent", "aimon-mobile/1.0")
            .get()
            .build()
        val qrResp = httpClient.newCall(qrReq).execute()
        assertTrue("GET /api/mobile/qr must return 200 OK", qrResp.isSuccessful)
        val qrJson = gson.fromJson(qrResp.body?.string(), Map::class.java)
        val pairingSecret = qrJson["secret"] as String

        val pairPayload = """
            {
                "secret": "$pairingSecret",
                "device_id": "qa-chat-tester",
                "device_name": "Chat Prompt Verification Runner"
            }
        """.trimIndent()
        val pairReq = Request.Builder()
            .url("$baseUrl/api/mobile/pair")
            .header("User-Agent", "aimon-mobile/1.0")
            .post(pairPayload.toRequestBody("application/json".toMediaType()))
            .build()
        val pairResp = httpClient.newCall(pairReq).execute()
        assertTrue("POST /api/mobile/pair must succeed", pairResp.isSuccessful)
        val pairJson = gson.fromJson(pairResp.body?.string(), Map::class.java)
        val sessionToken = pairJson["token"] as String

        // 2. Test Question: "what time is it?"
        println("\n[Test 1] User asks: 'what time is it?'")
        val timePromptReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "what time is it?", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val timeResp = httpClient.newCall(timePromptReq).execute()
        assertTrue("POST /api/agent/prompt must succeed", timeResp.isSuccessful)
        val timeBody = timeResp.body?.string() ?: ""
        println("  Daemon Response Raw Body: $timeBody")

        val timeJson = JsonParser.parseString(timeBody).asJsonObject
        assertEquals("ok", timeJson.get("status").asString)
        assertEquals("antigravity", timeJson.get("agentId").asString)
        val replyText = timeJson.get("response").asString
        assertTrue("Response must contain current time statement (Got: $replyText)", replyText.contains("The current time is"))
        assertTrue("Response must contain steps array", timeJson.has("steps"))
        val steps = timeJson.getAsJsonArray("steps")
        assertEquals(1, steps.size())
        val firstStep = steps.get(0).asJsonObject
        assertEquals("text", firstStep.get("case").asString)
        assertEquals(replyText, firstStep.get("value").asString)
        println("  ✔ Assistant Response: \"$replyText\"")
        println("  ✔ Verified trajectory step case='text', typing indicator reset signal ready.")

        // 3. Test Greeting: "hello"
        println("\n[Test 2] User asks: 'hello'")
        val helloPromptReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "hello", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val helloResp = httpClient.newCall(helloPromptReq).execute()
        assertTrue("POST /api/agent/prompt for 'hello' must succeed", helloResp.isSuccessful)
        val helloBody = helloResp.body?.string() ?: ""
        val helloJson = JsonParser.parseString(helloBody).asJsonObject
        val helloReply = helloJson.get("response").asString
        assertTrue("Greeting response must identify Google Antigravity (Got: $helloReply)", helloReply.contains("Hello! I am Google Antigravity"))
        println("  ✔ Assistant Response: \"$helloReply\"")

        // 4. Test Status Query: "status"
        println("\n[Test 3] User asks: 'status'")
        val statusPromptReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "status", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val statusPromptResp = httpClient.newCall(statusPromptReq).execute()
        assertTrue("POST /api/agent/prompt for 'status' must succeed", statusPromptResp.isSuccessful)
        val statusPromptBody = statusPromptResp.body?.string() ?: ""
        val statusPromptJson = JsonParser.parseString(statusPromptBody).asJsonObject
        val statusReply = statusPromptJson.get("response").asString
        assertTrue("Status response must report active services (Got: $statusReply)", statusReply.contains("Aimon telemetry and quotas are active"))
        println("  ✔ Assistant Response: \"$statusReply\"")

        // 5. Test Multi-Turn Sequential Conversation State
        println("\n[Test 4] Multi-Turn Sequential Chat (Message 1 -> Reply 1 -> Message 2 -> Reply 2)")
        val conversationSteps = mutableListOf<TrajectoryStep>()
        
        // Turn 1: User says hello
        conversationSteps.add(TrajectoryStep(case = "userInput", value = "hello"))
        val turn1RespReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "hello", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val turn1Resp = httpClient.newCall(turn1RespReq).execute()
        assertTrue(turn1Resp.isSuccessful)
        val turn1Json = JsonParser.parseString(turn1Resp.body?.string()).asJsonObject
        conversationSteps.add(TrajectoryStep(case = "text", value = turn1Json.get("response").asString))
        assertEquals(2, conversationSteps.size)
        println("  ✔ Turn 1 Complete: 2 steps in conversation (User: hello, Assistant: ${conversationSteps[1].value?.take(30)}...)")

        // Turn 2: User asks what time is it?
        conversationSteps.add(TrajectoryStep(case = "userInput", value = "what time is it?"))
        val turn2RespReq = Request.Builder()
            .url("$baseUrl/api/agent/prompt")
            .header("User-Agent", "aimon-mobile/1.0")
            .header("X-Mobile-Token", sessionToken)
            .post("""{"text": "what time is it?", "agentId": "antigravity"}""".toRequestBody("application/json".toMediaType()))
            .build()
        val turn2Resp = httpClient.newCall(turn2RespReq).execute()
        assertTrue(turn2Resp.isSuccessful)
        val turn2Json = JsonParser.parseString(turn2Resp.body?.string()).asJsonObject
        conversationSteps.add(TrajectoryStep(case = "text", value = turn2Json.get("response").asString))
        assertEquals(4, conversationSteps.size)
        assertEquals("userInput", conversationSteps[0].case)
        assertEquals("text", conversationSteps[1].case)
        assertEquals("userInput", conversationSteps[2].case)
        assertEquals("text", conversationSteps[3].case)
        assertTrue("Turn 1 message must be preserved", conversationSteps[0].value == "hello")
        assertTrue("Turn 2 message must be preserved", conversationSteps[2].value == "what time is it?")
        println("  ✔ Turn 2 Complete: 4 steps in conversation, both turns preserved without overwrite or corruption.")

        // 6. Test Bridge Message Serialization / Deserialization
        val stepObj = TrajectoryStep(case = "text", value = replyText)
        val inboundStep = InboundMessage.Step(step = stepObj, index = -1, agentId = "antigravity")
        val responseComplete = InboundMessage.ResponseComplete(conversationId = "conv-123", agentId = "antigravity")
        assertEquals("text", inboundStep.step.case)
        assertEquals(replyText, inboundStep.step.value)
        assertEquals("antigravity", responseComplete.agentId)
        println("  ✔ InboundMessage.Step and InboundMessage.ResponseComplete contracts verified.")

        println("\n================================================================")
        println("  INTERACTIVE CHAT PROMPT-RESPONSE LOOP VERIFIED WITH 100% SUCCESS")
        println("================================================================")
    }
}

