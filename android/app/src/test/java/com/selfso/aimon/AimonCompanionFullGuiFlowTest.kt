/*
 * AimonCompanionFullGuiFlowTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.selfso.selfupdate.ApkDownloader
import com.selfso.selfupdate.SelfUpdateChecker
import com.selfso.selfupdate.SelfUpdateController
import com.selfso.selfupdate.UpdateManifest
import com.selfso.selfupdate.UpdateState
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import dev.saifmukhtar.antimatter.core.data.AppDao
import dev.saifmukhtar.antimatter.core.data.UserPreferencesRepository
import dev.saifmukhtar.antimatter.core.data.toEntity
import dev.saifmukhtar.antimatter.core.network.AskQuestionPrompt
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.QuestionResponsePayload
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.feature.chat.ChatItem
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
import dev.saifmukhtar.antimatter.feature.chat.SpeechDictationSession
import dev.saifmukhtar.antimatter.feature.chat.computeMaxPromptInputHeightDp
import dev.saifmukhtar.antimatter.feature.chat.groupSteps
import dev.saifmukhtar.antimatter.feature.chat.mergeDictatedText
import dev.saifmukhtar.antimatter.feature.chat.placeholderForProvider
import dev.saifmukhtar.antimatter.feature.chat.slashCommandCatalog
import dev.saifmukhtar.antimatter.feature.chat.slashCommandCatalogForProvider
import dev.saifmukhtar.antimatter.feature.files.FilesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class AimonCompanionFullGuiFlowTest {

    data class RecordedRequest(
        val method: String,
        val path: String,
        val query: String?,
        val mobileToken: String?,
        val authHeader: String?,
        val body: String
    )

    private data class RouteHandler(
        val requireToken: String?,
        val statusCode: Int,
        val responseBody: (RecordedRequest) -> String
    )

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverExecutor: ExecutorService
    private val serverRunning = AtomicBoolean(false)
    private val routes = ConcurrentHashMap<String, RouteHandler>()
    private var serverPort: Int = 0
    private lateinit var serverBaseUrl: String
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val prefsBacking = mutableMapOf<String, String?>()

    private lateinit var mockApplication: Application
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor

    private var nowEpochSeconds: Long = 1760460000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        recordedRequests.clear()
        prefsBacking.clear()
        routes.clear()
        nowEpochSeconds = System.currentTimeMillis() / 1000L

        mockApplication = mock(Application::class.java)
        mockPrefs = mock(SharedPreferences::class.java)
        mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(mockApplication.applicationContext).thenReturn(mockApplication)
        `when`(mockApplication.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val v = inv.getArgument<String?>(1)
            prefsBacking[k] = v
            mockEditor
        }
        `when`(mockPrefs.getString(anyString(), org.mockito.ArgumentMatchers.nullable(String::class.java))).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val def = inv.getArgument<String?>(1)
            prefsBacking[k] ?: def
        }
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val v = inv.getArgument<Boolean>(1)
            prefsBacking[k] = v.toString()
            mockEditor
        }
        `when`(mockPrefs.getBoolean(anyString(), anyBoolean())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val def = inv.getArgument<Boolean>(1)
            prefsBacking[k]?.toBooleanStrictOrNull() ?: def
        }
        `when`(mockEditor.putInt(anyString(), anyInt())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val v = inv.getArgument<Int>(1)
            prefsBacking[k] = v.toString()
            mockEditor
        }
        `when`(mockPrefs.getInt(anyString(), anyInt())).thenAnswer { inv ->
            val k = inv.getArgument<String>(0)
            val def = inv.getArgument<Int>(1)
            prefsBacking[k]?.toIntOrNull() ?: def
        }

        setupServerRoutes()
        serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverPort = serverSocket.localPort
        serverBaseUrl = "http://127.0.0.1:$serverPort"
        serverRunning.set(true)
        serverExecutor = Executors.newCachedThreadPool()
        serverExecutor.submit {
            while (serverRunning.get() && !serverSocket.isClosed) {
                try {
                    val client = serverSocket.accept()
                    serverExecutor.submit { handleClientSocket(client) }
                } catch (_: Exception) {
                    if (!serverRunning.get()) break
                }
            }
        }
    }

    @After
    fun tearDown() {
        serverRunning.set(false)
        try {
            serverSocket.close()
        } catch (_: Exception) {
        }
        serverExecutor.shutdownNow()
        Dispatchers.resetMain()
    }

    private fun registerRoute(
        path: String,
        requireToken: String? = null,
        statusCode: Int = 200,
        responseBody: (RecordedRequest) -> String
    ) {
        routes[path] = RouteHandler(requireToken, statusCode, responseBody)
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (sb.isEmpty()) null else sb.toString()
            }
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.setLength(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun handleClientSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 3000
            val input = BufferedInputStream(s.getInputStream())
            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val rawUri = parts[1]
            val parsedUri = URI.create(rawUri)
            val path = parsedUri.path ?: "/"
            val query = parsedUri.rawQuery

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val bodyBytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val r = input.read(bodyBytes, offset, contentLength - offset)
                if (r <= 0) break
                offset += r
            }
            val bodyStr = String(bodyBytes, 0, offset, Charsets.UTF_8)

            val tokenHeader = headers["x-mobile-token"]
            val authHeader = headers["authorization"]
            val rec = RecordedRequest(
                method = method,
                path = path,
                query = query,
                mobileToken = tokenHeader,
                authHeader = authHeader,
                body = bodyStr
            )
            recordedRequests.add(rec)

            val handler = routes[path]
            val out = s.getOutputStream()
            if (handler == null) {
                val notFound = """{"error":"Not Found"}""".toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\nContent-Length: ${notFound.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(notFound)
                out.flush()
                return
            }

            val reqTok = handler.requireToken
            if (reqTok != null && tokenHeader != reqTok && authHeader != "Bearer $reqTok") {
                val unauthorized = """{"error":"Unauthorized: missing or invalid X-Mobile-Token"}""".toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: ${unauthorized.size}\r\nConnection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.UTF_8))
                out.write(unauthorized)
                out.flush()
                return
            }

            val payload = handler.responseBody(rec).toByteArray(Charsets.UTF_8)
            val statusText = if (handler.statusCode == 200) "OK" else "Error"
            val header = "HTTP/1.1 ${handler.statusCode} $statusText\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
            out.write(header.toByteArray(Charsets.UTF_8))
            out.write(payload)
            out.flush()
        }
    }

    private fun setupServerRoutes() {
        val expectedToken = "tok-live-verified-001"

        registerRoute("/api/mobile/pair") { rec ->
            val json = JsonParser.parseString(rec.body).asJsonObject
            val secret = json.get("secret")?.asString ?: ""
            if (secret == "sec-987654") {
                """{"status":"paired","token":"$expectedToken","device_id":"dev-pixel9-qa"}"""
            } else {
                """{"error":"Invalid pairing secret"}"""
            }
        }

        registerRoute("/api/status", requireToken = expectedToken) {
                """
                {
                  "ag": {
                    "plan_tier": "Pro",
                    "total_credits_remaining": 150000,
                    "models": [
                      {"label": "Gemini 2.5 Pro", "model_id": "gemini-2.5-pro", "remaining_pct": 74.5, "is_exhausted": false, "reset_time": "2h 15m"},
                      {"label": "Claude 3.7 Sonnet", "model_id": "claude-3-7-sonnet", "remaining_pct": 62.0, "is_exhausted": false, "reset_time": "4h 40m"}
                    ],
                    "quota_groups": [
                      {
                        "group_name": "Gemini",
                        "remaining_pct": 74.5,
                        "is_exhausted": false,
                        "reset_time": "2h 15m",
                        "models": ["gemini-2.5-pro"]
                      },
                      {
                        "group_name": "Claude",
                        "remaining_pct": 62.0,
                        "is_exhausted": false,
                        "reset_time": "4h 40m",
                        "models": ["claude-3-7-sonnet"]
                      }
                    ]
                  },
                  "cursor": {
                    "account_email": "samurai@selfso.com",
                    "plan_name": "Business",
                    "individual_used": 1420,
                    "individual_limit": 5000,
                    "total_spend_cents": 3850,
                    "billing_cycle_end": "2026-11-01"
                  },
                  "claude": {
                    "fiveHourUtilization": 41.2,
                    "sevenDayUtilization": 68.9,
                    "extraUsageUsed": 12.5,
                    "extraUsageLimit": 100.0,
                    "accounts": [
                      {
                        "email": "samurai@selfso.com",
                        "plan_tier": "Max",
                        "five_hour_utilization": 41.2,
                        "seven_day_utilization": 68.9,
                        "spend_limit_usd": 100.0
                      }
                    ]
                  }
                }
                """.trimIndent()
        }

        registerRoute("/api/monitors", requireToken = expectedToken) {
            """
            {
              "monitors": [
                {
                  "id": "meshmon-node1",
                  "name": "meshmon",
                  "subsystem": "meshmon",
                  "host": "192.168.1.50",
                  "port": 8081,
                  "online": true,
                  "last_seen_epoch": 1760460000
                },
                {
                  "id": "netmon-node2",
                  "name": "netmon",
                  "subsystem": "netmon",
                  "host": "192.168.1.10",
                  "port": 8082,
                  "online": true,
                  "last_seen_epoch": 1760460000
                }
              ],
              "meshmon": {
                "online": true,
                "endpoint": "192.168.1.50:8081",
                "active_nodes": 9
              },
              "netmon": {
                "online": true,
                "endpoint": "192.168.1.10:8082",
                "active_sessions": 31
              }
            }
            """.trimIndent()
        }

        registerRoute("/api/telemetry/overview", requireToken = expectedToken) {
            """
            {
              "totalSessions": 48,
              "totalEvents": 1290,
              "totalToolCalls": 615,
              "activeSessions": 3,
              "errorRatePct": 1.4,
              "platforms": [
                "antigravity",
                "cursor"
              ]
            }
            """.trimIndent()
        }

        registerRoute("/api/agent/available", requireToken = expectedToken) {
            """
            {
              "type": "AVAILABLE_AGENTS",
              "agents": [
                {"id": "antigravity", "name": "Google Antigravity", "status": "online"}
              ],
              "allowedWorkspaces": ["~/work/aimon"],
              "current_workspace": "~/work/aimon"
            }
            """.trimIndent()
        }

        registerRoute("/api/agent/conversations", requireToken = expectedToken) {
            """
            {
              "type": "HISTORY_LIST",
              "conversations": [
                {"id": "conv-antigravity-001", "title": "Repair Antimatter Companion", "timestamp": 1760460000}
              ],
              "agentId": "antigravity"
            }
            """.trimIndent()
        }

        registerRoute("/api/agent/transcript", requireToken = expectedToken) { rec ->
            val query = rec.query ?: ""
            if (query.contains("since=1")) {
                """
                {
                  "conversationId": "conv-antigravity-001",
                  "model": "gemini-2.5-pro",
                  "stepCount": 3,
                  "steps": [
                    {
                      "stepIndex": 2,
                      "type": "PLANNER_RESPONSE",
                      "thinking": "Verifying incremental step 2",
                      "text": "All tests passed.",
                      "tool_calls": []
                    }
                  ]
                }
                """.trimIndent()
            } else {
                """
                {
                  "conversationId": "conv-antigravity-001",
                  "model": "gemini-2.5-pro",
                  "stepCount": 2,
                  "steps": [
                    {
                      "stepIndex": 0,
                      "type": "USER_INPUT",
                      "text": "Run the Android companion tests"
                    },
                    {
                      "stepIndex": 1,
                      "type": "PLANNER_RESPONSE",
                      "thinking": "Checking test runner configuration",
                      "text": "Executing Gradle test task now.",
                      "tool_calls": [
                        {
                          "name": "run_command",
                          "toolAction": "Running Gradle unit tests",
                          "toolSummary": "Gradle test execution",
                          "command": "./gradlew testDebugUnitTest",
                          "arguments": {"CommandLine": "./gradlew testDebugUnitTest"}
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent()
            }
        }

        registerRoute("/api/workspace/tree", requireToken = expectedToken) { rec ->
            val q = rec.query ?: ""
            if (q.contains("path=src%2Fnested")) {
                """
                {
                  "type": "FILE_TREE",
                  "workspace": "~/work/aimon",
                  "tree": [
                    {"name": "DeepModule.cxx", "path": "src/nested/DeepModule.cxx", "type": "file"}
                  ]
                }
                """.trimIndent()
            } else {
                """
                {
                  "type": "FILE_TREE",
                  "workspace": "~/work/aimon",
                  "tree": [
                    {
                      "name": "src",
                      "path": "src",
                      "type": "directory",
                      "children": [
                        {"name": "WebServer.cxx", "path": "src/WebServer.cxx", "type": "file"},
                        {"name": "MobileGateway.cxx", "path": "src/MobileGateway.cxx", "type": "file"}
                      ]
                    },
                    {"name": "Makefile", "path": "Makefile", "type": "file"}
                  ]
                }
                """.trimIndent()
            }
        }

        registerRoute("/api/workspace/file", requireToken = expectedToken) { rec ->
            if (rec.method == "POST") {
                """{"status":"ok"}"""
            } else {
                """
                {
                  "type": "FILE_CONTENT",
                  "path": "Makefile",
                  "content": "all:\n\tcmake --build build\n",
                  "language": "makefile"
                }
                """.trimIndent()
            }
        }

        registerRoute("/api/agent/prompt", requireToken = expectedToken) { rec ->
            val json = JsonParser.parseString(rec.body).asJsonObject
            val prompt = json.get("prompt")?.asString ?: ""
            if (prompt.trim() == "/help") {
                """
                {
                  "status": "command_response",
                  "conversationId": "conv-antigravity-001",
                  "command": "/help",
                  "response": "### Available Slash Commands\n- `/clear` — Clear chat\n- `/model` — Switch model\n- `/compact` — Compact context"
                }
                """.trimIndent()
            } else {
                """
                {
                  "status": "completed",
                  "conversationId": "conv-antigravity-001",
                  "response": "Processed prompt: $prompt"
                }
                """.trimIndent()
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 3000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        assertTrue("Condition timed out after ${timeoutMs}ms", condition())
    }

    @Test
    fun testPairingHandshakeAndTokenHeaderInjection() = runBlocking {
        val vm = AimonViewModel()
        vm.initPreferences(mockApplication)
        val latch = CountDownLatch(1)
        var pairSuccess = false
        var pairToken: String? = null

        vm.pairWithServer(
            host = "127.0.0.1",
            port = serverPort,
            secret = "sec-987654",
            deviceName = "Pixel 9 QA"
        ) { ok, tok, _ ->
            pairSuccess = ok
            pairToken = tok
            latch.countDown()
        }

        assertTrue("pairWithServer callback must complete", latch.await(3, TimeUnit.SECONDS))
        assertTrue("pairWithServer must succeed", pairSuccess)
        assertEquals("tok-live-verified-001", pairToken)
        assertEquals("tok-live-verified-001", vm.mobileToken.value)
        assertEquals("tok-live-verified-001", prefsBacking["mobile_token"])
        assertEquals("dev-pixel9-qa", prefsBacking["device_id"])

        val pairReq = recordedRequests.find { it.path == "/api/mobile/pair" }
        assertNotNull("POST /api/mobile/pair must have been sent", pairReq)
        assertEquals("POST", pairReq?.method)
        assertTrue(pairReq!!.body.contains("sec-987654"))

        // Wait for initial fetches triggered by pairWithServer to complete with X-Mobile-Token
        waitUntil {
            recordedRequests.any { it.path == "/api/status" && it.mobileToken == "tok-live-verified-001" } &&
                recordedRequests.any { it.path == "/api/monitors" && it.mobileToken == "tok-live-verified-001" }
        }

        val statusReq = recordedRequests.first { it.path == "/api/status" && it.mobileToken == "tok-live-verified-001" }
        assertEquals("tok-live-verified-001", statusReq.mobileToken)
        assertEquals("Bearer tok-live-verified-001", statusReq.authHeader)
    }

    @Test
    fun testQuotaStatusParsingAntigravityCursorAndClaudeCode() = runBlocking {
        val vm = AimonViewModel()
        vm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")
        vm.fetchQuotas()

        waitUntil { vm.quotaStatus.value != null }
        val status = vm.quotaStatus.value!!

        // 1. Antigravity dynamic models and quotaGroups (zero hardcoded 88%/95%/230f)
        val ag = status.antigravity
        assertNotNull("Antigravity quota must be parsed", ag)
        assertEquals("Pro", ag!!.planTier)
        assertEquals(2, ag.models.size)
        assertEquals("gemini-2.5-pro", ag.models[0].modelId)
        assertEquals(74.5, ag.models[0].remainingPct, 0.01)
        assertEquals("2h 15m", ag.models[0].resetTime)
        assertEquals(2, ag.quotaGroups.size)
        assertEquals("Gemini", ag.quotaGroups[0].displayName)
        assertEquals(74.5, ag.quotaGroups[0].remainingPct, 0.01)

        // Verify multi-bucket QuotaGroup uses binding minimum bucket (not average of Weekly + 5-Hour)
        val multiBucketGroup = QuotaGroup(
            displayName = "Gemini Models",
            description = "Models within this group: Gemini Flash, Gemini Pro",
            buckets = listOf(
                QuotaBucket(
                    bucketId = "gemini-weekly",
                    displayName = "Weekly Limit Remaining",
                    remainingFraction = 0.201,
                    resetTimeRemainingSeconds = 86400L,
                    window = "weekly"
                ),
                QuotaBucket(
                    bucketId = "gemini-5h",
                    displayName = "Five Hour Limit Remaining",
                    remainingFraction = 0.950,
                    resetTimeRemainingSeconds = 5400L,
                    window = "5h"
                )
            )
        )
        assertEquals(20.1, multiBucketGroup.remainingPct, 0.01)
        assertEquals("1d 0h", multiBucketGroup.resetTime)
        assertEquals("Weekly", multiBucketGroup.buckets[0].windowBadge)
        assertEquals("5-Hour", multiBucketGroup.buckets[1].windowBadge)

        // 2. Cursor dynamic spend and individual usage
        val cur = status.cursor
        assertNotNull("Cursor usage must be parsed", cur)
        assertEquals("Business", cur!!.planName)
        assertEquals(1420, cur.individualUsed)
        assertEquals(5000, cur.individualLimit)
        assertEquals(3850, cur.totalSpendCents)
        assertEquals(38.50, cur.totalSpendUsd, 0.01)
        assertEquals("2026-11-01", cur.billingCycleEnd)

        // 3. Claude Code 5h/7d utilization and accounts
        val claude = status.claude
        assertNotNull("Claude usage must be parsed", claude)
        assertEquals(41.2, claude!!.fiveHourUtilization, 0.01)
        assertEquals(68.9, claude.sevenDayUtilization, 0.01)
        assertEquals(12.5, claude.extraUsageUsed, 0.01)
        assertEquals(100.0, claude.extraUsageLimit, 0.01)
        assertEquals(1, claude.accounts.size)
        val acct = claude.accounts[0]
        assertEquals("samurai@selfso.com", acct.email)
        assertEquals("Max", acct.planTier)
        assertEquals(41.2, acct.fiveHourUtilization, 0.01)
        assertEquals(68.9, acct.sevenDayUtilization, 0.01)

        // Verify Web UI-aligned ClaudeAccount cycle, window tiles, and model breakdown helpers
        val enterpriseAcct = ClaudeAccount(
            name = "default",
            planTier = "enterprise",
            hasData = true,
            windowDays = 30,
            last5h = ClaudeWindowTotals(label = "5h", totalTokens = 0L, rawEstCostUsd = 0.0),
            last7d = ClaudeWindowTotals(label = "7d", totalTokens = 1_110_000_000L, rawEstCostUsd = 335.81),
            window = ClaudeWindowTotals(label = "30d", messages = 12293L, totalTokens = 4_320_000_000L, rawEstCostUsd = 901.55),
            today = ClaudeWindowTotals(label = "today", totalTokens = 45_000_000L, rawEstCostUsd = 14.20),
            cycleConfigured = true,
            cycle = ClaudeWindowTotals(label = "cycle", totalTokens = 3_950_000_000L, rawEstCostUsd = 827.33),
            spendLimitUsd = 1000.0,
            estPctOfLimit = 82.7,
            cycleResetIso = "2026-11-01T00:00:00Z",
            models = listOf(
                ClaudeModelUsage(
                    model = "claude-sonnet-5-5",
                    messages = 7603L,
                    inputTokens = 900_000_000L,
                    outputTokens = 2_000_000_000L,
                    rawEstCostUsd = 483.11
                )
            )
        )
        assertEquals("Enterprise", enterpriseAcct.displayTierName)
        assertEquals("Est. cost", enterpriseAcct.valueLabel)
        assertEquals(827.33, enterpriseAcct.cycleCostUsd, 0.01)
        assertEquals(14.20, enterpriseAcct.todayCostUsd, 0.01)
        assertEquals("2026-11-01", formatIsoDateShort(enterpriseAcct.cycleResetIso))
        assertEquals("1.11B", formatClaudeTokens(enterpriseAcct.last7d!!.totalTokens))
        assertEquals("4.32B", formatClaudeTokens(enterpriseAcct.window!!.totalTokens))
        assertEquals(2_900_000_000L, enterpriseAcct.models[0].totalTokens)
    }

    @Test
    fun testSatelliteTelemetryMonitorsAndAgentOverviewParsing() = runBlocking {
        val vm = AimonViewModel()
        vm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")
        vm.fetchSatelliteTelemetry()

        waitUntil { vm.satelliteStatus.value?.meshmon?.online == true }
        val sat = vm.satelliteStatus.value!!

        // Online monitors from /api/monitors
        assertTrue("meshmon should be online", sat.meshmon.online)
        assertEquals("192.168.1.50:8081", sat.meshmon.endpoint)
        assertEquals(9, sat.meshmon.activeNodes)

        assertTrue("netmon should be online", sat.netmon.online)
        assertEquals("192.168.1.10:8082", sat.netmon.endpoint)
        assertEquals(31, sat.netmon.activeSessions)

        // Offline monitors must report offline with zero fake strings ("STM32F4", "3.3V / 42mA", etc.)
        assertFalse("embdevenv is not in /api/monitors and must be offline", sat.embdevenv.online)
        assertEquals("", sat.embdevenv.target)
        assertFalse("abc80 is not in /api/monitors and must be offline", sat.abc80.online)

        // Agent telemetry overview from /api/telemetry/overview
        val overview = sat.overview
        assertEquals(48, overview.totalSessions)
        assertEquals(1290, overview.totalEvents)
        assertEquals(615, overview.totalToolCalls)
        assertEquals(3, overview.activeSessions)
        assertEquals(1.4, overview.errorRatePct, 0.01)
        assertTrue(overview.platforms.contains("antigravity"))
        assertTrue(overview.platforms.contains("cursor"))
    }

    @Test
    fun testGroupStepsStreamingVsCompletedToolAndThoughtGrouping() {
        val promptStep = TrajectoryStep(case = "userInput", value = "Build and test the project", stepIndex = 0)
        val thought1 = TrajectoryStep(case = "plannerResponse", thinking = "Inspecting build configuration", stepIndex = 1)
        val tool1 = TrajectoryStep(
            case = "toolCall",
            tool = "run_command",
            toolSummary = "Compile binary",
            command = "make -j8",
            stepIndex = 2
        )
        val thought2 = TrajectoryStep(case = "plannerResponse", thinking = "Running test suite now", stepIndex = 3)
        val tool2 = TrajectoryStep(
            case = "toolCall",
            tool = "run_command",
            toolSummary = "Run unit tests",
            command = "make test",
            stepIndex = 4
        )

        // 1. While streaming before real chat output arrives: ThoughtGroup has isCompleted == false
        val streamingGrouped = groupSteps(listOf(promptStep, thought1, tool1, thought2, tool2))
        assertEquals(2, streamingGrouped.size)
        assertTrue(streamingGrouped[0] is ChatItem.SingleStep)
        assertTrue(streamingGrouped[1] is ChatItem.ThoughtGroup)
        val streamingGroup = streamingGrouped[1] as ChatItem.ThoughtGroup
        assertFalse("Trailing group without chat output must have isCompleted=false", streamingGroup.isCompleted)
        assertEquals(4, streamingGroup.steps.size)
        assertEquals(2, streamingGroup.toolCallCount)
        assertEquals(2, streamingGroup.thoughtCount)
        assertEquals(3, streamingGroup.steps.indexOfLast { it.stepCase == StepCase.TOOL_CALL })

        // 2. Once real chat output (TEXT / MARKDOWN_CHUNK) arrives: ThoughtGroup has isCompleted == true
        val finalReplyStep = TrajectoryStep(case = "text", value = "Build and tests passed cleanly!", stepIndex = 5)
        val completedGrouped = groupSteps(listOf(promptStep, thought1, tool1, thought2, tool2, finalReplyStep))
        assertEquals(3, completedGrouped.size)
        assertTrue(completedGrouped[0] is ChatItem.SingleStep)
        assertEquals(StepCase.USER_INPUT, (completedGrouped[0] as ChatItem.SingleStep).step.stepCase)
        assertTrue(completedGrouped[1] is ChatItem.ThoughtGroup)
        val completedGroup = completedGrouped[1] as ChatItem.ThoughtGroup
        assertTrue("Group followed by real chat output must have isCompleted=true", completedGroup.isCompleted)
        assertEquals(2, completedGroup.toolCallCount)
        assertEquals(2, completedGroup.thoughtCount)
        assertTrue(completedGrouped[2] is ChatItem.SingleStep)
        assertEquals(StepCase.TEXT, (completedGrouped[2] as ChatItem.SingleStep).step.stepCase)

        // 3. Transitional TEXT accompanying a tool call breaks and completes the preceding group
        val midTextStep = TrajectoryStep(case = "text", value = "Compilation succeeded, running tests...", stepIndex = 3)
        val splitGrouped = groupSteps(listOf(promptStep, thought1, tool1, midTextStep, thought2, tool2, finalReplyStep))
        assertEquals(5, splitGrouped.size)
        assertTrue((splitGrouped[1] as ChatItem.ThoughtGroup).isCompleted)
        assertEquals(StepCase.TEXT, (splitGrouped[2] as ChatItem.SingleStep).step.stepCase)
        assertTrue((splitGrouped[3] as ChatItem.ThoughtGroup).isCompleted)
        assertEquals(StepCase.TEXT, (splitGrouped[4] as ChatItem.SingleStep).step.stepCase)
    }

    @Test
    fun testChatViewModelTranscriptParsingAndIncrementalPolling() = runBlocking {
        val mockContext = mock(Context::class.java)
        val mockUserPrefs = mock(UserPreferencesRepository::class.java)
        val mockDao = mock(AppDao::class.java)
        `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())

        val bridgeWebSocket = BridgeWebSocket(mockContext)
        val chatVm = ChatViewModel(mockContext, bridgeWebSocket, mockUserPrefs, mockDao)

        chatVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")
        chatVm.fetchTranscript("conv-antigravity-001", since = -1)

        waitUntil { chatVm.uiState.value.steps.size >= 3 }
        val steps = chatVm.uiState.value.steps

        // Step 0: userInput
        assertEquals("userInput", steps[0].case)
        assertEquals("Run the Android companion tests", steps[0].value)

        // Step 1a: plannerResponse (combined thinking + transitional thought)
        assertEquals("plannerResponse", steps[1].case)
        assertTrue(steps[1].thinking?.contains("Checking test runner configuration") == true)
        assertTrue(steps[1].thinking?.contains("Executing Gradle test task now.") == true)

        // Step 1b: toolCall
        assertEquals("toolCall", steps[2].case)
        assertEquals("run_command", steps[2].tool)
        assertEquals("./gradlew testDebugUnitTest", steps[2].command)

        // Incremental polling with since=1
        chatVm.fetchTranscript("conv-antigravity-001", since = 1)
        waitUntil { chatVm.uiState.value.steps.size >= 5 }

        val updatedSteps = chatVm.uiState.value.steps
        assertTrue("Incremental poll must append new plannerResponse", updatedSteps.any { it.thinking == "Verifying incremental step 2" })
        assertTrue("Incremental poll must append new text", updatedSteps.any { it.value == "All tests passed." })

        val transcriptReqs = recordedRequests.filter { it.path == "/api/agent/transcript" }
        assertTrue(transcriptReqs.all { it.mobileToken == "tok-live-verified-001" })
        assertTrue(transcriptReqs.any { it.query?.contains("since=1") == true })
    }

    @Test
    fun testFilesViewModelRecursiveTreeAndFileReadWrite() = runBlocking {
        val mockContext = mock(Context::class.java)
        val bridgeWebSocket = BridgeWebSocket(mockContext)
        val filesVm = FilesViewModel(bridgeWebSocket)

        filesVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")

        waitUntil { filesVm.uiState.value.fileTree != null }
        val rootTree = filesVm.uiState.value.fileTree!!
        assertEquals(2, rootTree.size)
        val srcNode = rootTree.first { it.name == "src" }
        assertEquals(2, srcNode.children?.size)

        // Recursive subdirectory fetch with ?path=src/nested
        filesVm.loadFileTree("src/nested")
        waitUntil {
            filesVm.uiState.value.fileTree?.any { it.name == "DeepModule.cxx" } == true
        }

        // Open file
        filesVm.openFile("Makefile")
        waitUntil { filesVm.uiState.value.viewedFileContent != null }
        assertTrue(filesVm.uiState.value.viewedFileContent!!.contains("cmake --build build"))

        // Write file
        filesVm.writeFile("Makefile", "all:\n\tmake test\n")
        waitUntil {
            recordedRequests.any { it.path == "/api/workspace/file" && it.method == "POST" }
        }
        val postReq = recordedRequests.first { it.path == "/api/workspace/file" && it.method == "POST" }
        assertEquals("tok-live-verified-001", postReq.mobileToken)
        assertTrue(postReq.body.contains("make test"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun createMockPackageContext(
        pkgName: String,
        installedVersionCode: Int,
        installedVersionName: String
    ): Context {
        val ctx = mock(Context::class.java)
        val pm = mock(PackageManager::class.java)
        val pkgInfo = PackageInfo().apply {
            packageName = pkgName
            @Suppress("DEPRECATION")
            versionCode = installedVersionCode
            versionName = installedVersionName
        }
        `when`(ctx.packageName).thenReturn(pkgName)
        `when`(ctx.packageManager).thenReturn(pm)
        @Suppress("DEPRECATION")
        `when`(pm.getPackageInfo(pkgName, 0)).thenReturn(pkgInfo)
        return ctx
    }

    @Test
    fun testSelfUpdateManifestAndCheckerLoopbackUpToDateAndAvailable() = runBlocking {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        var servedVersionCode = 7L
        var servedVersionName = "2.0.5"
        val dummySha = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"

        httpServer.createContext("/software/android/manifest.json", HttpHandler { exchange: HttpExchange ->
            val body = """
                {
                  "schemaVersion": 1,
                  "baseUrl": "http://127.0.0.1:$port/software/android/",
                  "updatedAt": "2026-10-22T12:00:00Z",
                  "apps": {
                    "com.selfso.aimon": {
                      "slug": "aimon",
                      "title": "AIMon Companion",
                      "applicationId": "com.selfso.aimon",
                      "apkFilename": "aimon-companion.apk",
                      "versionCode": $servedVersionCode,
                      "versionName": "$servedVersionName",
                      "minSdk": 33,
                      "targetSdk": 35,
                      "apkUrl": "aimon/aimon-companion.apk",
                      "pageUrl": "http://127.0.0.1:$port/software/android/aimon/",
                      "sha256": "$dummySha",
                      "sizeBytes": 16384,
                      "publishedAt": "2026-10-22T12:00:00Z",
                      "releaseNotes": "OTA self-update verification build."
                    }
                  }
                }
            """.trimIndent().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        })
        httpServer.start()

        try {
            val manifestEndpoint = "http://127.0.0.1:$port/software/android/manifest.json"

            // 1. Verify both release and .debug package IDs resolve the aimon catalog entry and see UpToDate when versionCode <= 7
            for (pkgId in listOf("com.selfso.aimon", "com.selfso.aimon.debug")) {
                val ctx = createMockPackageContext(pkgId, installedVersionCode = 7, installedVersionName = "2.0.5")
                val checker = SelfUpdateChecker(ctx, manifestUrl = manifestEndpoint)
                val parsedManifest = checker.fetchManifest(pkgId)
                assertEquals("com.selfso.aimon", parsedManifest.applicationId)
                assertEquals("aimon", parsedManifest.slug)
                assertEquals(7L, parsedManifest.versionCode)
                assertEquals("2.0.5", parsedManifest.versionName)
                assertEquals("http://127.0.0.1:$port/software/android/aimon/aimon-companion.apk", parsedManifest.apkUrl)

                val stateEqual = checker.checkForUpdate()
                assertTrue("Expected UpToDate when versionCode == 7 for $pkgId", stateEqual is UpdateState.UpToDate)
                assertEquals("App is up to date", formatSelfUpdateStatusText(stateEqual))
            }

            // Also verify versionCode < 7 (e.g. remote = 6) reports UpToDate
            servedVersionCode = 6L
            servedVersionName = "2.0.4"
            val ctxOlder = createMockPackageContext("com.selfso.aimon.debug", installedVersionCode = 7, installedVersionName = "2.0.5")
            val stateOlder = SelfUpdateChecker(ctxOlder, manifestUrl = manifestEndpoint).checkForUpdate()
            assertTrue("Expected UpToDate when remote versionCode < 7", stateOlder is UpdateState.UpToDate)

            // 2. Verify Available when remote versionCode > 7 (versionCode = 8, versionName = "2.0.6")
            servedVersionCode = 8L
            servedVersionName = "2.0.6"
            for (pkgId in listOf("com.selfso.aimon", "com.selfso.aimon.debug")) {
                val ctx = createMockPackageContext(pkgId, installedVersionCode = 7, installedVersionName = "2.0.5")
                val checker = SelfUpdateChecker(ctx, manifestUrl = manifestEndpoint)
                val stateAvail = checker.checkForUpdate()
                assertTrue("Expected Available when remote versionCode == 8 for $pkgId", stateAvail is UpdateState.Available)
                val avail = stateAvail as UpdateState.Available
                assertEquals(8L, avail.manifest.versionCode)
                assertEquals("2.0.6", avail.manifest.versionName)
                assertEquals(7L, avail.installedVersionCode)
                assertEquals("2.0.5", avail.installedVersionName)
                assertEquals("Update v2.0.6 is available", formatSelfUpdateStatusText(avail))
            }
        } finally {
            httpServer.stop(0)
        }
    }

    @Test
    fun testSelfUpdateApkDownloaderStreamingSha256AndMismatchRejection() = runBlocking {
        val tempCacheDir = Files.createTempDirectory("aimon_selfupdate_apk_test").toFile()
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = httpServer.address.port
        val apkPayload = ByteArray(48 * 1024) { ((it * 31 + 7) and 0xFF).toByte() }
        val validSha256 = sha256Hex(apkPayload)

        httpServer.createContext("/software/android/aimon/aimon-companion.apk", HttpHandler { exchange: HttpExchange ->
            exchange.responseHeaders.set("Content-Type", "application/vnd.android.package-archive")
            exchange.sendResponseHeaders(200, apkPayload.size.toLong())
            exchange.responseBody.use { it.write(apkPayload) }
        })
        httpServer.start()

        try {
            val apkUrl = "http://127.0.0.1:$port/software/android/aimon/aimon-companion.apk"
            val downloader = ApkDownloader(cacheRootDir = tempCacheDir)

            // 1. Valid streaming download and SHA-256 verification
            val validManifest = UpdateManifest(
                applicationId = "com.selfso.aimon",
                versionCode = 8L,
                versionName = "2.0.6",
                apkUrl = apkUrl,
                sha256 = validSha256,
                sizeBytes = apkPayload.size.toLong(),
                slug = "aimon",
                title = "AIMon Companion"
            )
            val progressSteps = mutableListOf<Pair<Long, Long>>()
            val stagedApk = downloader.downloadAndVerify(validManifest) { bytesRead, totalBytes ->
                progressSteps.add(bytesRead to totalBytes)
            }
            assertTrue("Staged APK must exist", stagedApk.exists())
            assertEquals("com.selfso.aimon-8.apk", stagedApk.name)
            assertEquals(apkPayload.size.toLong(), stagedApk.length())
            assertEquals(validSha256, sha256Hex(stagedApk.readBytes()))
            assertTrue("Progress callbacks must be emitted", progressSteps.isNotEmpty())
            assertEquals(apkPayload.size.toLong(), progressSteps.last().first)

            // Verify status formatting for Downloading, PermissionRequired, ReadyToInstall, and Error
            val downloadingHalf = UpdateState.Downloading(validManifest, apkPayload.size.toLong() / 2L, apkPayload.size.toLong())
            assertEquals("Downloading update (50%)...", formatSelfUpdateStatusText(downloadingHalf))
            assertEquals("Permission required to install APK", formatSelfUpdateStatusText(UpdateState.PermissionRequired(validManifest, stagedApk)))
            assertEquals("Ready to install update", formatSelfUpdateStatusText(UpdateState.ReadyToInstall(validManifest, stagedApk)))
            assertEquals("Update check failed: timeout", formatSelfUpdateStatusText(UpdateState.Error("timeout")))

            // 2. SHA-256 mismatch rejection and cleanup
            val tamperedManifest = validManifest.copy(
                versionCode = 9L,
                versionName = "2.0.7",
                sha256 = "0000000000000000000000000000000000000000000000000000000000000000"
            )
            try {
                downloader.downloadAndVerify(tamperedManifest)
                fail("Expected SecurityException on SHA-256 mismatch")
            } catch (e: SecurityException) {
                assertTrue(e.message?.contains("SHA-256 mismatch") == true)
            }
            assertFalse(
                "Corrupted target APK must not exist",
                File(tempCacheDir, "com.selfso.aimon-9.apk").exists()
            )
            assertFalse(
                "Temporary .part file must be deleted on SHA-256 mismatch",
                File(tempCacheDir, "com.selfso.aimon-9.apk.part").exists()
            )
        } finally {
            httpServer.stop(0)
            tempCacheDir.deleteRecursively()
        }
    }

    @Test
    fun testSlashCommandsModelPickerAndSettingsPreferences() = runBlocking {
        // 1. Verify slash command auto-complete catalog & tap behavior
        val cmds = slashCommandCatalog.associateBy { it.command }
        assertTrue(cmds.containsKey("/clear"))
        assertTrue(cmds.containsKey("/model"))
        assertTrue(cmds.containsKey("/compact"))
        assertTrue(cmds.containsKey("/help"))
        assertTrue(cmds.containsKey("/usage"))
        assertTrue(cmds.containsKey("/credits"))
        assertTrue(cmds.containsKey("/agents"))
        assertTrue(cmds.containsKey("/skills"))
        assertTrue(cmds.containsKey("/settings"))
        assertTrue(cmds.containsKey("/plan"))
        assertTrue(cmds.containsKey("/grill-me"))
        assertTrue(cmds.containsKey("/goal"))
        assertTrue(cmds["/clear"]!!.executeImmediatelyOnTap)
        assertTrue(cmds["/model"]!!.executeImmediatelyOnTap)
        assertTrue(cmds["/compact"]!!.executeImmediatelyOnTap)
        assertFalse(cmds["/plan"]!!.executeImmediatelyOnTap)
        assertFalse(cmds["/grill-me"]!!.executeImmediatelyOnTap)

        // 2. Verify Settings preferences switches (Notifications, Sound, Vibration, Default Model, Biometric Lock)
        AimonAppPreferences.init(mockApplication)
        AimonAppPreferences.setNotificationsEnabled(mockApplication, false)
        assertFalse(AimonAppPreferences.notificationsEnabled.value)
        AimonAppPreferences.setNotificationsEnabled(mockApplication, true)
        assertTrue(AimonAppPreferences.notificationsEnabled.value)

        AimonAppPreferences.setSoundEnabled(mockApplication, false)
        assertFalse(AimonAppPreferences.soundEnabled.value)
        AimonAppPreferences.setSoundEnabled(mockApplication, true)
        assertTrue(AimonAppPreferences.soundEnabled.value)

        AimonAppPreferences.setVibrationEnabled(mockApplication, false)
        assertFalse(AimonAppPreferences.vibrationEnabled.value)
        AimonAppPreferences.setVibrationEnabled(mockApplication, true)
        assertTrue(AimonAppPreferences.vibrationEnabled.value)

        AimonAppPreferences.setDefaultModelId(mockApplication, "gemini-4-argon-high")
        assertEquals("gemini-4-argon-high", AimonAppPreferences.defaultModelId.value)

        // 3. Verify ChatViewModel client-side & server-side slash commands
        val mockUserPrefs = mock(UserPreferencesRepository::class.java)
        val mockDao = mock(AppDao::class.java)
        `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())

        val bridgeWebSocket = BridgeWebSocket(mockApplication)
        val chatVm = ChatViewModel(mockApplication, bridgeWebSocket, mockUserPrefs, mockDao)
        chatVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")

        // /model (bare) opens model picker dialog without sending network prompt
        chatVm.sendMessage("/model")
        assertTrue(chatVm.uiState.value.showModelPickerDialog)
        chatVm.setShowModelPickerDialog(false)
        assertFalse(chatVm.uiState.value.showModelPickerDialog)

        // /model <arg> switches model and appends confirmation step
        chatVm.sendMessage("/model claude-opus-5-5-high")
        assertEquals("claude-opus-5-5-high", chatVm.uiState.value.selectedModel)
        assertTrue(chatVm.uiState.value.steps.any { it.value?.contains("claude-opus-5-5-high") == true })

        // /settings sets pendingOpenSettingsTab = true
        chatVm.sendMessage("/settings")
        assertTrue(chatVm.uiState.value.pendingOpenSettingsTab)
        chatVm.consumeOpenSettingsTab()
        assertFalse(chatVm.uiState.value.pendingOpenSettingsTab)

        // /help executes against server and appends command_response markdown step
        chatVm.sendMessage("/help")
        waitUntil {
            chatVm.uiState.value.steps.any { it.value?.contains("Available Slash Commands") == true }
        }
        assertFalse(chatVm.uiState.value.isGenerating)

        // /clear resets conversation to "new" and clears steps
        chatVm.sendMessage("/clear")
        assertEquals("new", chatVm.uiState.value.conversationId)
        assertTrue(chatVm.uiState.value.steps.isEmpty())
    }

    @Test
    fun testMultiProviderSettingsDescendingActivitySortAndCutoff() = runBlocking {
        // 1. Verify slash command catalog includes /provider, /claude, /cursor, /agy
        val cmds = slashCommandCatalog.associateBy { it.command }
        assertTrue(cmds.containsKey("/provider"))
        assertTrue(cmds.containsKey("/claude"))
        assertTrue(cmds.containsKey("/cursor"))
        assertTrue(cmds.containsKey("/agy"))

        // 2. Verify AimonAppPreferences multi-provider settings & 24h cut-off slider clamping
        AimonAppPreferences.init(mockApplication)
        assertEquals(24, AimonAppPreferences.sessionCutoffHours.value)
        AimonAppPreferences.setSessionCutoffHours(mockApplication, 48)
        assertEquals(48, AimonAppPreferences.sessionCutoffHours.value)
        AimonAppPreferences.setSessionCutoffHours(mockApplication, 0)
        assertEquals(1, AimonAppPreferences.sessionCutoffHours.value)
        AimonAppPreferences.setSessionCutoffHours(mockApplication, 500)
        assertEquals(168, AimonAppPreferences.sessionCutoffHours.value)
        AimonAppPreferences.setSessionCutoffHours(mockApplication, 24)

        AimonAppPreferences.setAutoApproveMobileTools(mockApplication, false)
        assertFalse(AimonAppPreferences.autoApproveMobileTools.value)
        AimonAppPreferences.setAutoApproveMobileTools(mockApplication, true)
        assertTrue(AimonAppPreferences.autoApproveMobileTools.value)

        AimonAppPreferences.setDefaultProvider(mockApplication, "claude")
        assertEquals("claude", AimonAppPreferences.defaultProvider.value)
        AimonAppPreferences.setDefaultClaudeModelId(mockApplication, "claude-opus-4-7")
        assertEquals("claude-opus-4-7", AimonAppPreferences.defaultClaudeModelId.value)
        AimonAppPreferences.setDefaultCursorModelId(mockApplication, "sonnet-4.6")
        assertEquals("sonnet-4.6", AimonAppPreferences.defaultCursorModelId.value)
        AimonAppPreferences.setDefaultProvider(mockApplication, "antigravity")

        // 3. Configure /api/agent/conversations with mixed providers and timestamps
        val nowSec = System.currentTimeMillis() / 1000L
        registerRoute("/api/agent/conversations", requireToken = "tok-live-verified-001") {
            """
            {
              "conversations": [
                {"id": "conv-old-agy", "title": "Old AGY Session", "source": "cli", "workspace": "aimon", "timestamp": ${nowSec - 72 * 3600}},
                {"id": "conv-mid-claude", "title": "Claude Refactor", "source": "claude", "workspace": "aimon", "timestamp": ${nowSec - 2 * 3600}},
                {"id": "conv-newest-cursor", "title": "Cursor Latest Fix", "source": "cursor", "workspace": "netmon", "timestamp": ${nowSec - 300}}
              ]
            }
            """.trimIndent()
        }

        val mockUserPrefs = mock(UserPreferencesRepository::class.java)
        val mockDao = mock(AppDao::class.java)
        `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())

        val bridgeWebSocket = BridgeWebSocket(mockApplication)
        val chatVm = ChatViewModel(mockApplication, bridgeWebSocket, mockUserPrefs, mockDao)
        chatVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")

        waitUntil { chatVm.uiState.value.history.size == 3 }

        // Strictly sorted in descending order of last activity
        val sortedHistory = chatVm.uiState.value.history
        assertEquals("conv-newest-cursor", sortedHistory[0].id)
        assertEquals("conv-mid-claude", sortedHistory[1].id)
        assertEquals("conv-old-agy", sortedHistory[2].id)

        // Filter by provider chip: CLAUDE
        chatVm.setWorkspaceFilter("CLAUDE")
        assertEquals(1, chatVm.uiState.value.filteredHistory.size)
        assertEquals("conv-mid-claude", chatVm.uiState.value.filteredHistory[0].id)

        // Filter by provider chip: CURSOR
        chatVm.setWorkspaceFilter("CURSOR")
        assertEquals(1, chatVm.uiState.value.filteredHistory.size)
        assertEquals("conv-newest-cursor", chatVm.uiState.value.filteredHistory[0].id)

        // Disabling Claude sessions in Settings hides Claude from ALL filter
        chatVm.setWorkspaceFilter("ALL")
        AimonAppPreferences.setClaudeSessionsEnabled(mockApplication, false)
        chatVm.reapplySearchFilter()
        assertEquals(2, chatVm.uiState.value.filteredHistory.size)
        assertTrue(chatVm.uiState.value.filteredHistory.none { it.source == "claude" })
        AimonAppPreferences.setClaudeSessionsEnabled(mockApplication, true)
        chatVm.reapplySearchFilter()
        assertEquals(3, chatVm.uiState.value.filteredHistory.size)

        // Expand/collapse older sessions toggle
        assertFalse(chatVm.uiState.value.showOlderSessions)
        chatVm.setShowOlderSessions(true)
        assertTrue(chatVm.uiState.value.showOlderSessions)
        chatVm.setShowOlderSessions(false)
        assertFalse(chatVm.uiState.value.showOlderSessions)

        // Slash commands /claude, /cursor, /agy, /provider
        chatVm.sendMessage("/claude")
        assertEquals("claude", chatVm.uiState.value.activeProvider)
        chatVm.sendMessage("/cursor")
        assertEquals("cursor", chatVm.uiState.value.activeProvider)
        chatVm.sendMessage("/agy")
        assertEquals("antigravity", chatVm.uiState.value.activeProvider)
        chatVm.sendMessage("/provider")
        assertTrue(chatVm.uiState.value.showProviderPickerDialog)
        chatVm.setShowProviderPickerDialog(false)
    }

    @Test
    fun testDynamicPromptInputHeightPlaceholderAndProviderSlashCatalog() {
        // 1. Dynamic prompt input box max height calculation
        // Normal portrait screen (800dp) with keyboard closed (0dp): caps at 168dp (~6 lines)
        assertEquals(168, computeMaxPromptInputHeightDp(screenHeightDp = 800, imeBottomDp = 0))
        // Normal portrait screen (800dp) with soft keyboard open (340dp): (800 - 340 - 80) * 0.4 = 152dp
        assertEquals(152, computeMaxPromptInputHeightDp(screenHeightDp = 800, imeBottomDp = 340))
        // Compact screen or landscape (640dp) with tall keyboard (400dp): clamped to 88dp minimum cap
        assertEquals(88, computeMaxPromptInputHeightDp(screenHeightDp = 640, imeBottomDp = 400))

        // 2. Provider-specific placeholder text
        assertEquals("Ask Google Antigravity or type /...", placeholderForProvider("antigravity"))
        assertEquals("Ask Google Antigravity or type /...", placeholderForProvider("cli"))
        assertEquals("Ask Antigravity IDE or type /...", placeholderForProvider("ide"))
        assertEquals("Ask Claude Code or type /...", placeholderForProvider("claude"))
        assertEquals("Ask Cursor Agent or type /...", placeholderForProvider("cursor"))

        // 3. Provider-specific slash command catalogs
        val agyCatalog = slashCommandCatalogForProvider("antigravity").associateBy { it.command }
        assertTrue(agyCatalog.containsKey("/grill-me"))
        assertTrue(agyCatalog.containsKey("/goal"))
        assertTrue(agyCatalog.containsKey("/usage"))

        val ideCatalog = slashCommandCatalogForProvider("ide").associateBy { it.command }
        assertEquals(agyCatalog.keys, ideCatalog.keys)

        val claudeCatalog = slashCommandCatalogForProvider("claude").associateBy { it.command }
        assertTrue(claudeCatalog.containsKey("/compact"))
        assertTrue(claudeCatalog.containsKey("/clear"))
        assertTrue(claudeCatalog.containsKey("/new"))
        assertTrue(claudeCatalog.containsKey("/effort"))
        assertTrue(claudeCatalog.containsKey("/doctor"))
        assertTrue(claudeCatalog.containsKey("/status"))
        assertTrue(claudeCatalog.containsKey("/plan"))
        assertFalse("Claude catalog should not include Antigravity-only /grill-me", claudeCatalog.containsKey("/grill-me"))

        val cursorCatalog = slashCommandCatalogForProvider("cursor").associateBy { it.command }
        assertTrue(cursorCatalog.containsKey("/compact"))
        assertTrue(cursorCatalog.containsKey("/summarize"))
        assertTrue(cursorCatalog.containsKey("/clear"))
        assertTrue(cursorCatalog.containsKey("/new"))
        assertTrue(cursorCatalog.containsKey("/models"))
        assertEquals("/model ", cursorCatalog["/models"]!!.insertText)
        assertTrue(cursorCatalog.containsKey("/ask"))
        assertEquals("/ask ", cursorCatalog["/ask"]!!.insertText)
        assertTrue(cursorCatalog.containsKey("/plan"))
        assertTrue(cursorCatalog.containsKey("/status"))
        assertFalse("Cursor catalog should not include Antigravity-only /grill-me", cursorCatalog.containsKey("/grill-me"))
    }

    @Test
    fun testProviderClearPreservationAndCompactionInPlaceAndRollover() = runBlocking {
        val mockUserPrefs = mock(UserPreferencesRepository::class.java)
        val mockDao = mock(AppDao::class.java)
        `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())

        var compactMode = "in_place"
        var claudeCompactedInPlace = false
        val initNowSec = System.currentTimeMillis() / 1000L
        registerRoute("/api/agent/conversations", requireToken = "tok-live-verified-001") {
            """
            {
              "conversations": [
                {"id": "conv-claude-01", "title": "Claude Main Session", "source": "claude", "workspace": "aimon", "timestamp": $initNowSec},
                {"id": "conv-cursor-02", "title": "Cursor Feature Session", "source": "cursor", "workspace": "aimon", "timestamp": ${initNowSec - 100}, "model": ""},
                {"id": "conv-subagent-orphan", "title": "Subagent Session", "source": "cursor", "workspace": "aimon", "timestamp": ${initNowSec - 200}}
              ]
            }
            """.trimIndent()
        }
        registerRoute("/api/agent/transcript", requireToken = "tok-live-verified-001") { rec ->
            val q = rec.query ?: ""
            when {
                q.contains("id=conv-claude-01") && claudeCompactedInPlace -> """
                    {
                      "conversationId": "conv-claude-01",
                      "source": "claude",
                      "model": "claude-sonnet-4-6",
                      "stepCount": 2,
                      "steps": [
                        {"stepIndex": 0, "type": "USER_INPUT", "text": "/compact"},
                        {"stepIndex": 1, "type": "PLANNER_RESPONSE", "text": "📦 **Context Compacted**\n\nIn-place Claude summary."}
                      ]
                    }
                """.trimIndent()
                q.contains("id=conv-claude-01") -> """
                    {
                      "conversationId": "conv-claude-01",
                      "source": "claude",
                      "model": "claude-sonnet-4-6",
                      "stepCount": 4,
                      "steps": [
                        {"stepIndex": 0, "type": "USER_INPUT", "text": "Turn 1 user"},
                        {"stepIndex": 1, "type": "PLANNER_RESPONSE", "text": "Turn 1 reply"},
                        {"stepIndex": 2, "type": "USER_INPUT", "text": "Turn 2 user"},
                        {"stepIndex": 3, "type": "PLANNER_RESPONSE", "text": "Turn 2 reply"}
                      ]
                    }
                """.trimIndent()
                q.contains("id=conv-subagent-orphan") -> """
                    {
                      "conversationId": "conv-subagent-orphan",
                      "source": "cursor",
                      "model": "sonnet-4.6",
                      "stepCount": 1,
                      "steps": [
                        {"stepIndex": 0, "type": "USER_INPUT", "text": "Subagent step"}
                      ]
                    }
                """.trimIndent()
                else -> """
                    {
                      "conversationId": "conv-cursor-02",
                      "source": "cursor",
                      "model": "sonnet-4.6",
                      "stepCount": 2,
                      "steps": [
                        {"stepIndex": 0, "type": "USER_INPUT", "text": "Initial Cursor prompt"},
                        {"stepIndex": 1, "type": "PLANNER_RESPONSE", "text": "Initial Cursor reply"}
                      ]
                    }
                """.trimIndent()
            }
        }

        registerRoute("/api/agent/prompt", requireToken = "tok-live-verified-001") { rec ->
            val json = JsonParser.parseString(rec.body).asJsonObject
            val prompt = json.get("prompt")?.asString ?: ""
            val cid = json.get("conversationId")?.asString ?: ""
            if (prompt.startsWith("/compact")) {
                if (compactMode == "in_place") {
                    claudeCompactedInPlace = true
                    """
                    {
                      "status": "compacted",
                      "compactedFrom": "$cid",
                      "agentId": "claude",
                      "source": "claude",
                      "conversationId": "$cid",
                      "command": "/compact",
                      "steps": [
                        {"stepIndex": 0, "type": "USER_INPUT", "text": "/compact"},
                        {"stepIndex": 1, "type": "PLANNER_RESPONSE", "text": "📦 **Context Compacted**\n\nIn-place Claude summary."}
                      ],
                      "response": "In-place Claude summary."
                    }
                    """.trimIndent()
                } else {
                    """
                    {
                      "status": "command_response",
                      "agentId": "cursor",
                      "source": "cursor",
                      "conversationId": "$cid",
                      "command": "/compact",
                      "response": "Cursor manages context compaction automatically in the IDE and CLI. Full conversation history for this session is preserved."
                    }
                    """.trimIndent()
                }
            } else {
                """{"status":"completed","conversationId":"$cid","response":"ok"}"""
            }
        }

        val bridgeWebSocket = BridgeWebSocket(mockApplication)
        val chatVm = ChatViewModel(mockApplication, bridgeWebSocket, mockUserPrefs, mockDao)
        chatVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")

        // 1. Subscribe to Claude session (source = "claude" from /api/agent/transcript)
        chatVm.selectConversation("conv-claude-01")
        waitUntil {
            chatVm.uiState.value.conversationId == "conv-claude-01" &&
                chatVm.uiState.value.steps.size == 4 &&
                chatVm.uiState.value.activeSessionSource == "claude"
        }
        assertEquals("claude", chatVm.resolveEffectiveProvider())
        assertEquals("claude", chatVm.uiState.value.activeProvider)

        // 2. In-place /compact on Claude session replaces 4 steps with 2 compacted steps on same conversationId
        compactMode = "in_place"
        chatVm.sendMessage("/compact")
        waitUntil {
            !chatVm.uiState.value.isGenerating &&
                chatVm.uiState.value.steps.any { it.value?.contains("In-place Claude summary") == true }
        }
        assertEquals("conv-claude-01", chatVm.uiState.value.conversationId)
        assertEquals(2, chatVm.uiState.value.steps.size)
        assertEquals("claude", chatVm.resolveEffectiveProvider())

        // 3. /clear on Claude session preserves "claude" provider instead of resetting to default "antigravity"
        chatVm.sendMessage("/clear")
        assertEquals("new", chatVm.uiState.value.conversationId)
        assertTrue(chatVm.uiState.value.steps.isEmpty())
        assertEquals("claude", chatVm.uiState.value.activeProvider)
        assertEquals("claude", chatVm.resolveEffectiveProvider())

        // 4. Subscribe to Cursor session and run Cursor /compact -> command_response preserving session
        chatVm.selectConversation("conv-cursor-02")
        waitUntil {
            chatVm.uiState.value.conversationId == "conv-cursor-02" &&
                chatVm.uiState.value.steps.size == 2 &&
                chatVm.uiState.value.activeSessionSource == "cursor"
        }
        assertEquals("cursor", chatVm.resolveEffectiveProvider())
        // Verify SessionState upserted richSummaryMap and uiState.history has conv-cursor-02 with source cursor and real model sonnet-4.6 even when /api/agent/conversations had model: ""
        assertEquals(
            "cursor",
            chatVm.uiState.value.history.firstOrNull { it.id == "conv-cursor-02" }?.source
        )
        assertEquals(
            "sonnet-4.6",
            chatVm.uiState.value.history.firstOrNull { it.id == "conv-cursor-02" }?.model
        )

        compactMode = "command_response"
        chatVm.sendMessage("/compact focus on auth")
        waitUntil {
            !chatVm.uiState.value.isGenerating &&
                chatVm.uiState.value.steps.any { it.value?.contains("Cursor manages context compaction automatically") == true }
        }
        assertEquals("conv-cursor-02", chatVm.uiState.value.conversationId)
        assertTrue(chatVm.uiState.value.steps.any { it.value?.contains("Initial Cursor prompt") == true })
        assertTrue(chatVm.uiState.value.steps.any { it.value?.contains("Initial Cursor reply") == true })
        assertEquals("cursor", chatVm.resolveEffectiveProvider())

        // 5. Select a stale subagent session that gets omitted from HISTORY_LIST -> ChatViewModel auto-switches to first valid main session
        chatVm.selectConversation("conv-subagent-orphan")
        waitUntil { chatVm.uiState.value.conversationId == "conv-subagent-orphan" }

        val pruneNowSec = System.currentTimeMillis() / 1000L
        registerRoute("/api/agent/conversations", requireToken = "tok-live-verified-001") {
            """
            {
              "conversations": [
                {"id": "conv-claude-01", "title": "Claude Main Session", "source": "claude", "workspace": "aimon", "timestamp": $pruneNowSec},
                {"id": "conv-cursor-02", "title": "Cursor Feature Session", "source": "cursor", "workspace": "aimon", "timestamp": ${pruneNowSec - 100}},
                {"id": "conv-subagent-orphan", "title": "", "source": "cursor", "workspace": "aimon", "timestamp": ${pruneNowSec - 200}}
              ]
            }
            """.trimIndent()
        }
        chatVm.refreshHistory()
        waitUntil {
            chatVm.uiState.value.conversationId == "conv-claude-01" &&
                chatVm.uiState.value.history.none { it.id == "conv-subagent-orphan" }
        }
        assertEquals("conv-claude-01", chatVm.uiState.value.conversationId)

        // /new on active session preserves active provider
        chatVm.sendMessage("/new")
        assertEquals("new", chatVm.uiState.value.conversationId)
        assertEquals("claude", chatVm.uiState.value.activeProvider)
    }

    @Test
    fun testAskQuestionStepGroupingTranscriptParsingAndAnswerSubmission() = runBlocking {
        // 1. Verify groupSteps promotes StepCase.ASK_QUESTION to a top-level SingleStep and closes preceding ThoughtGroup
        val userStep = TrajectoryStep(case = "userInput", value = "/grill-me on database choice", stepIndex = 0)
        val thinkingStep = TrajectoryStep(case = "plannerResponse", thinking = "Formulating design questions", stepIndex = 1)
        val askStep = TrajectoryStep(
            case = "askQuestion",
            value = "Which database engine should we use?\n1. (Recommended) SQLite\n2. PostgreSQL",
            stepIndex = 1,
            questions = listOf(
                AskQuestionPrompt(
                    question = "Which database engine should we use?",
                    options = listOf("(Recommended) SQLite", "PostgreSQL"),
                    isMultiSelect = false
                )
            ),
            isQuestionAnswered = false
        )
        val grouped = groupSteps(listOf(userStep, thinkingStep, askStep))
        assertEquals(3, grouped.size)
        assertTrue(grouped[0] is ChatItem.SingleStep)
        assertTrue("Thinking step must be in ThoughtGroup", grouped[1] is ChatItem.ThoughtGroup)
        assertTrue("ThoughtGroup before ASK_QUESTION must be marked completed", (grouped[1] as ChatItem.ThoughtGroup).isCompleted)
        assertTrue("ASK_QUESTION must be promoted to top-level SingleStep", grouped[2] is ChatItem.SingleStep)
        assertEquals(StepCase.ASK_QUESTION, (grouped[2] as ChatItem.SingleStep).step.stepCase)

        // 2. Verify StepEntity persistence preserves questions and isQuestionAnswered without schema changes
        val entity = askStep.toEntity("conv-grill-01", 2)
        val roundTrip = entity.toTrajectoryStep()
        assertEquals(StepCase.ASK_QUESTION, roundTrip.stepCase)
        assertEquals(1, roundTrip.questions.size)
        assertEquals("Which database engine should we use?", roundTrip.questions[0].question)
        assertEquals(listOf("(Recommended) SQLite", "PostgreSQL"), roundTrip.questions[0].options)
        assertFalse(roundTrip.isQuestionAnswered)

        // 3. Verify BridgeWebSocket + ChatViewModel parse stringified args.questions from transcript and submit questionResponses
        registerRoute("/api/agent/transcript", requireToken = "tok-live-verified-001") {
            """
            {
              "conversationId": "conv-grill-01",
              "source": "cli",
              "model": "gemini-4-argon-high",
              "stepCount": 2,
              "steps": [
                {
                  "stepIndex": 0,
                  "type": "USER_INPUT",
                  "text": "/grill-me on storage"
                },
                {
                  "stepIndex": 1,
                  "type": "PLANNER_RESPONSE",
                  "thinking": "Preparing architecture question",
                  "text": "",
                  "tool_calls": [
                    {
                      "name": "ask_question",
                      "arguments": {
                        "questions": "[{\"question\":\"Which storage backend?\",\"options\":[\"(Recommended) SQLite\",\"DuckDB\"],\"is_multi_select\":false}]"
                      }
                    }
                  ]
                }
              ]
            }
            """.trimIndent()
        }

        val grillNowSec = System.currentTimeMillis() / 1000L
        registerRoute("/api/agent/conversations", requireToken = "tok-live-verified-001") {
            """
            {
              "conversations": [
                {"id": "conv-grill-01", "title": "Grill Session", "source": "cli", "workspace": "aimon", "timestamp": $grillNowSec}
              ]
            }
            """.trimIndent()
        }

        registerRoute("/api/agent/prompt", requireToken = "tok-live-verified-001") {
            """
            {
              "status": "dispatched",
              "interactionHandled": true,
              "agentId": "antigravity",
              "conversationId": "conv-grill-01"
            }
            """.trimIndent()
        }

        val staleSubagentEntity = dev.saifmukhtar.antimatter.core.data.ConversationEntity(
            id = "674e381e-26de-40eb-8eb3-84db42e61d34",
            title = "Conversation 674e381e",
            timestamp = 1760450000000L,
            agentId = "antigravity",
            stepCount = 4
        )
        val emptyTitleEntity = dev.saifmukhtar.antimatter.core.data.ConversationEntity(
            id = "0c21caee-0000-0000-0000-000000000000",
            title = "",
            timestamp = 1760450001000L,
            agentId = "antigravity",
            stepCount = 0
        )
        val deletedIds = CopyOnWriteArrayList<String>()
        val mockUserPrefs = mock(UserPreferencesRepository::class.java)
        val mockDao = mock(AppDao::class.java)
        `when`(mockDao.getAllAgentsFlow()).thenReturn(flowOf(emptyList()))
        `when`(mockDao.getConversationsForAgentFlow(anyString())).thenReturn(flowOf(listOf(staleSubagentEntity, emptyTitleEntity)))
        `when`(mockDao.getAllConversationsSnapshot()).thenReturn(listOf(staleSubagentEntity, emptyTitleEntity))
        `when`(mockDao.deleteConversationById(anyString())).thenAnswer { inv ->
            deletedIds.add(inv.getArgument(0))
            Unit
        }
        `when`(mockDao.getStepsForConversation(anyString())).thenReturn(emptyList())

        val bridgeWebSocket = BridgeWebSocket(mockApplication)
        val chatVm = ChatViewModel(mockApplication, bridgeWebSocket, mockUserPrefs, mockDao)
        chatVm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")
        chatVm.selectConversation("conv-grill-01")

        waitUntil {
            chatVm.uiState.value.steps.any { it.stepCase == StepCase.ASK_QUESTION } &&
                chatVm.uiState.value.history.isNotEmpty()
        }
        assertFalse(
            "Stale Room subagent rows must be purged from history",
            chatVm.uiState.value.history.any { it.id == staleSubagentEntity.id || it.id == emptyTitleEntity.id }
        )
        assertTrue(
            "Stale subagent rows must be deleted from Room DB",
            deletedIds.contains(staleSubagentEntity.id) && deletedIds.contains(emptyTitleEntity.id)
        )

        val steps = chatVm.uiState.value.steps
        assertFalse("ask_question must not be duplicated as a generic toolCall step", steps.any { it.stepCase == StepCase.TOOL_CALL && it.tool == "ask_question" })
        val parsedAsk = steps.first { it.stepCase == StepCase.ASK_QUESTION }
        assertEquals(1, parsedAsk.questions.size)
        assertEquals("Which storage backend?", parsedAsk.questions[0].question)
        assertEquals(listOf("(Recommended) SQLite", "DuckDB"), parsedAsk.questions[0].options)
        assertFalse(parsedAsk.isQuestionAnswered)

        // Submit interactive answer from AskQuestionCard
        recordedRequests.removeIf { it.path == "/api/agent/prompt" }
        chatVm.submitQuestionAnswers(
            step = parsedAsk,
            responses = listOf(
                QuestionResponsePayload(
                    question = "Which storage backend?",
                    selectedOptions = listOf("(Recommended) SQLite"),
                    writeInResponse = ""
                )
            )
        )

        waitUntil {
            recordedRequests.any { it.path == "/api/agent/prompt" }
        }

        val updatedAsk = chatVm.uiState.value.steps.first { it.stepCase == StepCase.ASK_QUESTION }
        assertTrue("Submitted question step must be marked answered", updatedAsk.isQuestionAnswered)
        assertEquals(listOf("(Recommended) SQLite"), updatedAsk.questions[0].selectedAnswers)

        val promptReq = recordedRequests.last { it.path == "/api/agent/prompt" }
        val sentJson = JsonParser.parseString(promptReq.body).asJsonObject
        assertEquals("conv-grill-01", sentJson.get("conversationId").asString)
        assertEquals("(Recommended) SQLite", sentJson.get("text").asString)
        assertTrue("questionResponses array must be sent in POST /api/agent/prompt", sentJson.has("questionResponses"))
        val qRespArr = sentJson.getAsJsonArray("questionResponses")
        assertEquals(1, qRespArr.size())
        val firstResp = qRespArr[0].asJsonObject
        assertEquals("Which storage backend?", firstResp.get("question").asString)
        assertEquals("(Recommended) SQLite", firstResp.getAsJsonArray("selectedOptions")[0].asString)
    }

    @Test
    fun testSpeechToTextDictationAndCursorToolTurnGrouping() {
        // 1. Verify mergeDictatedText pure helper
        assertEquals("Check the cursor", mergeDictatedText("Check", "the cursor"))
        assertEquals("the cursor", mergeDictatedText("", "the cursor"))
        assertEquals("Check", mergeDictatedText("Check", ""))
        assertEquals("Check the cursor", mergeDictatedText("Check ", " the cursor "))

        // Verify SpeechDictationSession state machine
        var currentText = "Check"
        var restartCount = 0
        val session = SpeechDictationSession(
            onTextUpdated = { newText -> currentText = newText },
            onRestartRecognizer = { restartCount++ }
        )

        // Starting dictation with existing text "Check"
        session.startSession(currentText)
        assertTrue("Session must be listening after startSession", session.isListening)
        assertTrue("userRequestedListening must be true", session.userRequestedListening)
        assertEquals("Check", session.committedPrefix)

        // Streaming partial results
        session.onPartialRecognition("the cursor")
        assertEquals("Check the cursor", currentText)
        session.onPartialRecognition("the cursor session")
        assertEquals("Check the cursor session", currentText)

        // onFinalRecognition commits utterance, keeps isListening true, triggers onRestartRecognizer
        session.onFinalRecognition("the cursor session")
        assertEquals("Check the cursor session", session.committedPrefix)
        assertEquals("Check the cursor session", currentText)
        assertTrue("Session must remain listening for continuous dictation across pauses", session.isListening)
        assertEquals("onRestartRecognizer must be triggered on final utterance", 1, restartCount)

        // Streaming next sentence
        session.onPartialRecognition("and fix the freeze")
        assertEquals("Check the cursor session and fix the freeze", currentText)
        session.onFinalRecognition("and fix the freeze")
        assertEquals("Check the cursor session and fix the freeze", session.committedPrefix)
        assertEquals("Check the cursor session and fix the freeze", currentText)
        assertTrue(session.isListening)
        assertEquals(2, restartCount)

        // Second tap: stopSession
        session.stopSession()
        assertFalse("Session must stop listening after stopSession", session.isListening)
        assertFalse("userRequestedListening must be false", session.userRequestedListening)
        assertEquals("Check the cursor session and fix the freeze", currentText)
        assertEquals("Check the cursor session and fix the freeze", session.committedPrefix)

        // 2. Verify BridgeWebSocket.parseTranscriptStepsFromJson and groupSteps on 20 tool-calling turns + final text turn
        val transcriptJson = JsonObject()
        val stepsArray = JsonArray()

        for (i in 1..20) {
            val turn = JsonObject()
            turn.addProperty("stepIndex", i)
            turn.addProperty("type", "PLANNER_RESPONSE")
            turn.addProperty("text", "Intermediate thought $i")
            val tcArr = JsonArray()
            val tc = JsonObject()
            tc.addProperty("name", "Shell")
            tc.addProperty("command", "git status $i")
            tcArr.add(tc)
            turn.add("tool_calls", tcArr)
            stepsArray.add(turn)
        }

        val finalTurn = JsonObject()
        finalTurn.addProperty("stepIndex", 21)
        finalTurn.addProperty("type", "PLANNER_RESPONSE")
        finalTurn.addProperty("text", "Done!")
        stepsArray.add(finalTurn)

        transcriptJson.add("steps", stepsArray)

        val inboundSteps = BridgeWebSocket.parseTranscriptStepsFromJson(transcriptJson, "cursor")
        assertEquals(41, inboundSteps.size) // 20 thoughts + 20 tool calls + 1 final text

        // Verify every intermediate text accompanying tool_calls is emitted as PLANNER_RESPONSE before its TOOL_CALL
        for (i in 0 until 20) {
            val thoughtStep = inboundSteps[i * 2].step
            val toolStep = inboundSteps[i * 2 + 1].step
            assertEquals(StepCase.PLANNER_RESPONSE, thoughtStep.stepCase)
            assertEquals("Intermediate thought ${i + 1}", thoughtStep.value)
            assertEquals(StepCase.TOOL_CALL, toolStep.stepCase)
            assertEquals("Shell", toolStep.tool)
            assertEquals("git status ${i + 1}", toolStep.command)
        }

        // Verify final turn is emitted as TEXT
        val finalStep = inboundSteps[40].step
        assertEquals(StepCase.TEXT, finalStep.stepCase)
        assertEquals("Done!", finalStep.value)

        // Verify groupSteps collapses all 20 intermediate thoughts + 20 tool calls into a SINGLE ThoughtGroup
        val trajectorySteps = inboundSteps.map { it.step }
        val chatItems = groupSteps(trajectorySteps)

        assertEquals("Must produce exactly 1 ThoughtGroup followed by 1 SingleStep", 2, chatItems.size)
        assertTrue("First item must be ThoughtGroup", chatItems[0] is ChatItem.ThoughtGroup)
        val thoughtGroup = chatItems[0] as ChatItem.ThoughtGroup
        assertTrue("ThoughtGroup must be completed", thoughtGroup.isCompleted)
        assertEquals(40, thoughtGroup.steps.size)
        assertEquals(20, thoughtGroup.thoughtCount)
        assertEquals(20, thoughtGroup.toolCallCount)

        assertTrue("Second item must be SingleStep", chatItems[1] is ChatItem.SingleStep)
        val singleStep = chatItems[1] as ChatItem.SingleStep
        assertEquals(StepCase.TEXT, singleStep.step.stepCase)
        assertEquals("Done!", singleStep.step.displayText)
    }
}

