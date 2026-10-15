/*
 * AimonCompanionFullGuiFlowTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonParser
import dev.saifmukhtar.antimatter.core.data.AppDao
import dev.saifmukhtar.antimatter.core.data.UserPreferencesRepository
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
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

        registerRoute("/api/approvals/pending", requireToken = expectedToken) {
            val reqAt = nowEpochSeconds - 12L
            """
            [
              {
                "id": "req-epoch-test-01",
                "agent": "antigravity",
                "action_type": "run_command",
                "workspace": "~/work/aimon",
                "command": "make test",
                "details": {},
                "reason": "Run unit test suite",
                "requested_at": $reqAt,
                "timeout_seconds": 60,
                "remaining_seconds": 48
              }
            ]
            """.trimIndent()
        }

        registerRoute("/api/approvals/decision", requireToken = expectedToken) {
            """{"status":"resolved"}"""
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
                recordedRequests.any { it.path == "/api/approvals/pending" && it.mobileToken == "tok-live-verified-001" } &&
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
    fun testApprovalsEpochSecondsCountdownAndDecisionResolution() = runBlocking {
        val vm = AimonViewModel()
        vm.configureEndpoint(serverBaseUrl, "tok-live-verified-001")
        vm.fetchApprovals()

        waitUntil { vm.pendingApprovals.value.isNotEmpty() }
        val item = vm.pendingApprovals.value.first()
        assertEquals("req-epoch-test-01", item.approvalId)
        assertEquals("run_command", item.toolName)
        assertEquals("make test", item.command)

        // Verify epoch-second countdown calculation (must not be 0 from ms-minus-seconds bug)
        val secondsFromRemaining = item.computeSecondsLeft(nowEpochSeconds)
        assertEquals(48L, secondsFromRemaining)

        val itemWithoutServerRemaining = item.copy(remainingSeconds = -1)
        val computedFromEpochSec = itemWithoutServerRemaining.computeSecondsLeft(nowEpochSeconds)
        assertEquals(48L, computedFromEpochSec)

        // Resolve approval decision
        val ok = vm.submitDecisionSuspend(item.approvalId, "allow")
        assertTrue("submitDecisionSuspend must succeed", ok)
        waitUntil { recordedRequests.any { it.path == "/api/approvals/decision" } }

        val decReq = recordedRequests.first { it.path == "/api/approvals/decision" }
        assertEquals("tok-live-verified-001", decReq.mobileToken)
        assertTrue(decReq.body.contains("\"approval_id\":\"req-epoch-test-01\""))
        assertTrue("Resolved approval must be removed from UI state", vm.pendingApprovals.value.isEmpty())
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

        waitUntil { chatVm.uiState.value.steps.size >= 4 }
        val steps = chatVm.uiState.value.steps

        // Step 0: userInput
        assertEquals("userInput", steps[0].case)
        assertEquals("Run the Android companion tests", steps[0].value)

        // Step 1a: plannerResponse (thinking)
        assertEquals("plannerResponse", steps[1].case)
        assertEquals("Checking test runner configuration", steps[1].thinking)

        // Step 1b: toolCall
        assertEquals("toolCall", steps[2].case)
        assertEquals("run_command", steps[2].tool)
        assertEquals("./gradlew testDebugUnitTest", steps[2].command)

        // Step 1c: text
        assertEquals("text", steps[3].case)
        assertEquals("Executing Gradle test task now.", steps[3].value)

        // Incremental polling with since=1
        chatVm.fetchTranscript("conv-antigravity-001", since = 1)
        waitUntil { chatVm.uiState.value.steps.size >= 6 }

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
}

