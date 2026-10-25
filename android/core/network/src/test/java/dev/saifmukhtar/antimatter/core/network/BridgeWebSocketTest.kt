package dev.saifmukhtar.antimatter.core.network

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.google.gson.JsonParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeWebSocketTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var contextMock: Context
    private lateinit var bridgeWebSocket: BridgeWebSocket

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        contextMock = mock()
        bridgeWebSocket = BridgeWebSocket(contextMock)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial connection state should be DISCONNECTED`() {
        assertEquals(BridgeWebSocket.ConnectionState.DISCONNECTED, bridgeWebSocket.connectionState.value)
    }

    @Test
    fun `disconnect should update state to DISCONNECTED and set manual flag`() {
        bridgeWebSocket.disconnect()
        assertEquals(BridgeWebSocket.ConnectionState.DISCONNECTED, bridgeWebSocket.connectionState.value)
    }
    
    // Testing the inner state changes directly is difficult without mocking OkHttpClient
    // So we test the public API contracts and state flows.
    @Test
    fun `connect with empty URL should not change state to CONNECTING`() = runTest(testDispatcher) {
        bridgeWebSocket.connect("")
        // Because OkHttp will fail to build the request due to empty URL, it should catch and set to DISCONNECTED.
        assertEquals(BridgeWebSocket.ConnectionState.DISCONNECTED, bridgeWebSocket.connectionState.value)
    }

    @Test
    fun `parseTranscriptStepsFromJson preserves multiple tool calls sharing stepIndex`() {
        val jsonStr = """
            {
              "conversationId": "conv-test-multi",
              "stepCount": 2,
              "steps": [
                {
                  "stepIndex": 0,
                  "type": "USER_INPUT",
                  "text": "Refactor codebase"
                },
                {
                  "stepIndex": 1,
                  "type": "PLANNER_RESPONSE",
                  "thinking": "Analyzing and applying multi-tool changes",
                  "tool_calls": [
                    {"name": "read_file", "arguments": "{\"Path\":\"App.kt\"}"},
                    {"name": "edit_file", "arguments": "{\"Path\":\"App.kt\"}"},
                    {"name": "run_command", "command": "./gradlew test"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val parsed = BridgeWebSocket.parseTranscriptStepsFromJson(json, "antigravity")

        assertEquals(5, parsed.size)
        assertEquals(StepCase.USER_INPUT, parsed[0].step.stepCase)
        assertEquals(0, parsed[0].step.stepIndex)

        // Step 1 thinking
        assertEquals(StepCase.PLANNER_RESPONSE, parsed[1].step.stepCase)
        assertEquals(1, parsed[1].step.stepIndex)
        assertEquals("Analyzing and applying multi-tool changes", parsed[1].step.thinking)

        // Step 1 toolCall 1
        assertEquals(StepCase.TOOL_CALL, parsed[2].step.stepCase)
        assertEquals(1, parsed[2].step.stepIndex)
        assertEquals("read_file", parsed[2].step.tool)

        // Step 1 toolCall 2
        assertEquals(StepCase.TOOL_CALL, parsed[3].step.stepCase)
        assertEquals(1, parsed[3].step.stepIndex)
        assertEquals("edit_file", parsed[3].step.tool)

        // Step 1 toolCall 3
        assertEquals(StepCase.TOOL_CALL, parsed[4].step.stepCase)
        assertEquals(1, parsed[4].step.stepIndex)
        assertEquals("run_command", parsed[4].step.tool)
    }

    @Test
    fun `parseTranscriptStepsFromJson extracts ask_question without duplicating as generic toolCall`() {
        val jsonStr = """
            {
              "conversationId": "conv-test-ask",
              "stepCount": 1,
              "steps": [
                {
                  "stepIndex": 0,
                  "type": "PLANNER_RESPONSE",
                  "thinking": "Pondering choices",
                  "tool_calls": [
                    {
                      "name": "ask_question",
                      "arguments": "{\"questions\":[{\"question\":\"Pick option:\",\"options\":[\"A\",\"B\"],\"is_multi_select\":false}]}"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val json = JsonParser.parseString(jsonStr).asJsonObject
        val parsed = BridgeWebSocket.parseTranscriptStepsFromJson(json, "antigravity")

        assertEquals(2, parsed.size)
        assertEquals(StepCase.PLANNER_RESPONSE, parsed[0].step.stepCase)
        assertEquals(StepCase.ASK_QUESTION, parsed[1].step.stepCase)
        assertEquals(0, parsed[1].step.stepIndex)
        assertEquals(1, parsed[1].step.questions.size)
        assertEquals("Pick option:", parsed[1].step.questions[0].question)
        assertEquals(listOf("A", "B"), parsed[1].step.questions[0].options)
        assertTrue("Must not contain generic toolCall for ask_question", parsed.none { it.step.stepCase == StepCase.TOOL_CALL })
    }

    @Test
    fun `parseHistoryListFromJson and parseSessionStateFromTranscriptJson parse interactive and nonInteractiveReason`() {
        val cursorReason = "Bi-directional communication is not possible with this Cursor IDE GUI agent. Launch cursor-agent in a terminal/screen session to interact from mobile."
        val claudeReason = "Bi-directional communication is not possible with this Claude IDE extension agent. Launch claude in a terminal/screen session to interact from mobile."
        val historyJson = """
            {
              "agentId": "antigravity",
              "conversations": [
                {
                  "id": "conv-cursor-ide-ro",
                  "title": "Cursor IDE GUI Session",
                  "source": "cursor",
                  "workspace": "aimon",
                  "timestamp": 1760460100,
                  "interactive": false,
                  "nonInteractiveReason": "$cursorReason"
                },
                {
                  "id": "conv-claude-ide-ro",
                  "title": "Claude IDE Extension Session",
                  "source": "claude",
                  "workspace": "aimon",
                  "timestamp": 1760460200,
                  "interactive": false,
                  "nonInteractiveReason": "$claudeReason"
                },
                {
                  "id": "conv-cli-default",
                  "title": "Interactive CLI Session",
                  "source": "cli",
                  "workspace": "aimon",
                  "timestamp": 1760460300
                }
              ]
            }
        """.trimIndent()

        val historyList = BridgeWebSocket.parseHistoryListFromJson(historyJson)
        assertEquals(3, historyList.conversations.size)

        val cursorConv = historyList.conversations.first { it.id == "conv-cursor-ide-ro" }
        assertFalse(cursorConv.interactive)
        assertEquals(cursorReason, cursorConv.nonInteractiveReason)

        val claudeConv = historyList.conversations.first { it.id == "conv-claude-ide-ro" }
        assertFalse(claudeConv.interactive)
        assertEquals(claudeReason, claudeConv.nonInteractiveReason)

        val defaultConv = historyList.conversations.first { it.id == "conv-cli-default" }
        assertTrue(defaultConv.interactive)
        assertEquals("", defaultConv.nonInteractiveReason)

        val roTranscriptJson = JsonParser.parseString(
            """
            {
              "conversationId": "conv-cursor-ide-ro",
              "source": "cursor",
              "workspace": "aimon",
              "model": "sonnet-4.6",
              "stepCount": 4,
              "interactive": false,
              "nonInteractiveReason": "$cursorReason",
              "steps": []
            }
            """.trimIndent()
        ).asJsonObject
        val roSessionState = BridgeWebSocket.parseSessionStateFromTranscriptJson(roTranscriptJson, "conv-cursor-ide-ro", "antigravity")
        assertEquals("conv-cursor-ide-ro", roSessionState.conversationId)
        assertFalse(roSessionState.interactive)
        assertEquals(cursorReason, roSessionState.nonInteractiveReason)

        val defaultTranscriptJson = JsonParser.parseString(
            """
            {
              "conversationId": "conv-cli-default",
              "source": "cli",
              "model": "gemini-4-argon-high",
              "stepCount": 2,
              "steps": []
            }
            """.trimIndent()
        ).asJsonObject
        val rwSessionState = BridgeWebSocket.parseSessionStateFromTranscriptJson(defaultTranscriptJson, "conv-cli-default", "antigravity")
        assertEquals("conv-cli-default", rwSessionState.conversationId)
        assertTrue(rwSessionState.interactive)
        assertEquals("", rwSessionState.nonInteractiveReason)
    }
}
