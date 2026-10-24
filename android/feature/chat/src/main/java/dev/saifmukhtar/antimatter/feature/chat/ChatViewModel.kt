/*
 * ChatViewModel.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.OutboundMessage
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.data.GzipUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import dev.saifmukhtar.antimatter.core.data.UserPreferencesRepository
import dev.saifmukhtar.antimatter.core.data.AppDatabase
import dev.saifmukhtar.antimatter.core.data.ConversationEntity
import dev.saifmukhtar.antimatter.core.data.toEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import dev.saifmukhtar.antimatter.core.data.AppDao

data class ModelOption(
    val id: String,
    val label: String,
    val subtitle: String
)

val AVAILABLE_MODEL_OPTIONS = listOf(
    ModelOption("default", "Auto (Server Default)", "Uses active session or default server model"),
    ModelOption("gemini-4-argon-high", "Gemini 4 Argon (High)", "Highest reasoning capability"),
    ModelOption("gemini-4-argon-medium", "Gemini 4 Argon (Medium)", "Balanced reasoning & speed"),
    ModelOption("gemini-3.8-flash", "Gemini 3.8 Flash", "Fastest low-latency responses"),
    ModelOption("claude-opus-5-5-high", "Claude Opus 5.5 (High)", "Deep coding & architectural reasoning"),
    ModelOption("claude-sonnet-5-5-high", "Claude Sonnet 5.5 (High)", "Fast coding & verification"),
    ModelOption("gpt-oss-120b", "GPT-OSS 120B", "Open-weights 120B model")
)

data class ChatUiState(
    val connectionState: BridgeWebSocket.ConnectionState = BridgeWebSocket.ConnectionState.DISCONNECTED,
    val isGenerating: Boolean = false,
    val steps: List<TrajectoryStep> = emptyList(),
    val conversationId: String? = "new",
    val expectedStepCount: Int = 0,
    val activeFile: String? = null,
    val activeFileLanguage: String? = null,
    val cloudflareUrl: String? = null,
    val environment: String? = null,
    val currentModel: String = "gemini-4-argon-medium",
    val selectedModel: String = "default",
    val conversationModel: String = "",
    val error: String? = null,
    val history: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary> = emptyList(),
    val filteredHistory: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary>? = null,
    val selectedWorkspaceFilter: String = "ALL",
    val availableWorkspaces: List<String> = emptyList(),
    val showConversationPickerSheet: Boolean = false,
    val showPromptsSheet: Boolean = false,
    val showModelPickerDialog: Boolean = false,
    val pendingOpenSettingsTab: Boolean = false,
    val isInChatSearchActive: Boolean = false,
    val inChatSearchQuery: String = "",
    val inChatMatches: List<Int> = emptyList(),
    val inChatCurrentMatchIdx: Int = 0,
    val focusedOriginalStepIndex: Int? = null,
    val pendingFocusStepIndex: Int? = null,
    val initialScrollIndex: Int = 0,
    val initialScrollOffset: Int = 0,
    val artifacts: List<dev.saifmukhtar.antimatter.core.network.FileNode> = emptyList(),
    val activeArtifactContent: String? = null,
    val activeAgentId: String? = null,
    val availableAgents: List<dev.saifmukhtar.antimatter.core.network.InboundMessage.AgentInfo> = emptyList(),
    val allowedWorkspaces: List<String> = emptyList(),
    val selectedImageUri: Uri? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val webSocket: BridgeWebSocket,
    val userPrefs: UserPreferencesRepository,
    private val appDao: AppDao
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val richSummaryMap = java.util.concurrent.ConcurrentHashMap<String, dev.saifmukhtar.antimatter.core.network.ConversationSummary>()
    private var serverSearchJob: kotlinx.coroutines.Job? = null
    private var pendingFocusIsTranscriptStepIndex: Boolean = false

    private var baseUrl: String = "http://127.0.0.1:3883"
    private var token: String = ""
    private var lastStepIndex: Int = -1
    private var pendingFullRefresh: Boolean = false
    private var pollingJob: kotlinx.coroutines.Job? = null

    private val scrollStateFlow = MutableSharedFlow<Pair<Int, Int>>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    fun configureEndpoint(url: String, authToken: String = "") {
        baseUrl = url.trimEnd('/')
        token = authToken
        webSocket.configureEndpoint(baseUrl, authToken)
        fetchConversations()
    }

    fun fetchConversations() {
        requestHistory()
    }

    fun fetchTranscript(conversationId: String, since: Int = lastStepIndex) {
        if (conversationId.isBlank() || conversationId == "new") return
        webSocket.sendMessage(
            OutboundMessage.SubscribeConversation(
                conversationId = conversationId,
                lastKnownStepCount = _uiState.value.steps.size,
                sinceStepIndex = since,
                agentId = _uiState.value.activeAgentId ?: "antigravity"
            )
        )
    }

    private fun startIncrementalPolling(conversationId: String) {
        pollingJob?.cancel()
        if (conversationId.isBlank() || conversationId == "new") return
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            while (_uiState.value.conversationId == conversationId) {
                val delayMs = if (_uiState.value.isGenerating) 1000L else 2500L
                kotlinx.coroutines.delay(delayMs)
                if (_uiState.value.conversationId == conversationId) {
                    val sinceIdx = if (_uiState.value.isGenerating && lastStepIndex >= 0) {
                        lastStepIndex - 1
                    } else {
                        lastStepIndex
                    }
                    fetchTranscript(conversationId, since = sinceIdx)
                }
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val localAgents = try {
                appDao.getAllAgentsFlow().catch { emit(emptyList()) }.firstOrNull()
            } catch (t: Throwable) {
                android.util.Log.w("ChatViewModel", "getAllAgentsFlow error: ${t.message}")
                null
            }
            val agentInfos = if (localAgents != null && localAgents.isNotEmpty()) {
                localAgents.map { dev.saifmukhtar.antimatter.core.network.InboundMessage.AgentInfo(it.id, it.name, it.status) }
            } else {
                listOf(dev.saifmukhtar.antimatter.core.network.InboundMessage.AgentInfo("antigravity", "Google Antigravity", "online"))
            }
            _uiState.update { state -> 
                state.copy(
                    availableAgents = agentInfos,
                    activeAgentId = state.activeAgentId ?: agentInfos.firstOrNull()?.id ?: "antigravity"
                )
            }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.map { it.activeAgentId }.distinctUntilChanged().flatMapLatest { agentId ->
                if (agentId != null) {
                    appDao.getConversationsForAgentFlow(agentId).catch { e ->
                        android.util.Log.w("ChatViewModel", "getConversationsForAgentFlow error: ${e.message}")
                        emit(emptyList())
                    }
                } else {
                    flowOf(emptyList())
                }
            }.catch { e ->
                android.util.Log.w("ChatViewModel", "conversations flow error: ${e.message}")
                emit(emptyList())
            }.collect { localConversations ->
                try {
                    rebuildHistoryFromMap(localConversations)
                } catch (t: Throwable) {
                    android.util.Log.e("ChatViewModel", "rebuildHistoryFromMap error", t)
                }
            }
        }

        @OptIn(kotlinx.coroutines.FlowPreview::class)
        viewModelScope.launch {
            scrollStateFlow.debounce(500).collect { (index, offset) ->
                _uiState.value.conversationId?.let { cid ->
                    withContext(Dispatchers.IO) {
                        try {
                            appDao.updateScrollState(cid, index, offset)
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "updateScrollState error", e)
                        }
                    }
                }
            }
        }
        // Observe connection state
        viewModelScope.launch {
            var wasConnected = false
            webSocket.connectionState.collect { state ->
                val isConnected = state == BridgeWebSocket.ConnectionState.CONNECTED
                _uiState.update { it.copy(connectionState = state) }
                
                if (!wasConnected && isConnected) {
                    // We just connected or reconnected after connection loss
                    requestHistory()
                    
                    _uiState.value.conversationId?.let { cid ->
                        if (cid != "new") {
                            // Re-subscribe to the active conversation using incremental step index
                            webSocket.sendMessage(OutboundMessage.SubscribeConversation(
                                conversationId = cid,
                                lastKnownStepCount = _uiState.value.steps.size,
                                sinceStepIndex = lastStepIndex,
                                agentId = _uiState.value.activeAgentId ?: "antigravity"
                            ))
                        }
                    }
                }
                wasConnected = isConnected
            }
        }

        // Observe incoming messages
        viewModelScope.launch {
            webSocket.messages.collect { message ->
                handleInboundMessage(message)
            }
        }
    }

    private fun mergeSummaryWithRich(
        incoming: dev.saifmukhtar.antimatter.core.network.ConversationSummary
    ): dev.saifmukhtar.antimatter.core.network.ConversationSummary {
        val prev = richSummaryMap[incoming.id] ?: return incoming
        val bestTitle = when {
            incoming.title.isNotBlank() && incoming.title != "Chat" -> incoming.title
            prev.title.isNotBlank() && prev.title != "Chat" -> prev.title
            else -> incoming.title.ifBlank { prev.title }
        }
        val bestTs = maxOf(prev.timestamp, incoming.timestamp)
        val mergedPrompts = if (incoming.userPrompts.isNotEmpty()) incoming.userPrompts else prev.userPrompts
        return incoming.copy(
            title = bestTitle,
            timestamp = bestTs,
            source = incoming.source.ifBlank { prev.source },
            workspace = incoming.workspace.ifBlank { prev.workspace },
            workspacePath = incoming.workspacePath.ifBlank { prev.workspacePath },
            model = incoming.model.ifBlank { prev.model },
            active = incoming.active || (incoming.stepCount == 0 && prev.active),
            openInTerminal = incoming.openInTerminal || (incoming.stepCount == 0 && prev.openInTerminal),
            terminalPts = incoming.terminalPts.ifBlank { prev.terminalPts },
            stepCount = maxOf(prev.stepCount, incoming.stepCount),
            firstPrompt = incoming.firstPrompt.ifBlank { prev.firstPrompt },
            lastPrompt = incoming.lastPrompt.ifBlank { prev.lastPrompt },
            lastPromptStepIndex = if (incoming.lastPromptStepIndex >= 0) incoming.lastPromptStepIndex else prev.lastPromptStepIndex,
            userPrompts = mergedPrompts,
            matchedPrompt = incoming.matchedPrompt.ifBlank { prev.matchedPrompt },
            matchedStepIndex = if (incoming.matchedStepIndex >= 0) incoming.matchedStepIndex else prev.matchedStepIndex
        )
    }

    private fun sortConversations(
        list: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary>
    ): List<dev.saifmukhtar.antimatter.core.network.ConversationSummary> {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24L * 3600L * 1000L
        return list.sortedWith(
            compareByDescending<dev.saifmukhtar.antimatter.core.network.ConversationSummary> {
                (it.openInTerminal || it.active) && (now - it.timestamp <= sevenDaysMs)
            }.thenByDescending { it.openInTerminal }
                .thenByDescending { it.active }
                .thenByDescending { it.timestamp }
        )
    }

    private fun rebuildHistoryFromMap(localEntities: List<ConversationEntity>? = null) {
        val combined = LinkedHashMap<String, dev.saifmukhtar.antimatter.core.network.ConversationSummary>()
        for ((id, summary) in richSummaryMap) {
            combined[id] = summary
        }
        localEntities?.forEach { entity ->
            val existingRich = combined[entity.id]
            if (existingRich != null) {
                val bestTitle = when {
                    existingRich.title.isNotBlank() && existingRich.title != "Chat" -> existingRich.title
                    entity.title.isNotBlank() && entity.title != "Chat" -> entity.title
                    else -> existingRich.title.ifBlank { entity.title }
                }
                val merged = existingRich.copy(
                    title = bestTitle,
                    timestamp = maxOf(existingRich.timestamp, entity.timestamp),
                    stepCount = maxOf(existingRich.stepCount, entity.stepCount)
                )
                combined[entity.id] = merged
                richSummaryMap[entity.id] = merged
            } else {
                val fromEntity = dev.saifmukhtar.antimatter.core.network.ConversationSummary(
                    id = entity.id,
                    title = entity.title,
                    timestamp = entity.timestamp,
                    stepCount = entity.stepCount
                )
                combined[entity.id] = fromEntity
                richSummaryMap[entity.id] = fromEntity
            }
        }
        val sorted = sortConversations(combined.values.toList())
        val activeCid = _uiState.value.conversationId
        val resolvedConvModel = if (!activeCid.isNullOrBlank() && activeCid != "new") {
            richSummaryMap[activeCid]?.model?.takeIf { it.isNotBlank() } ?: _uiState.value.conversationModel
        } else {
            _uiState.value.conversationModel
        }
        _uiState.update {
            it.copy(
                history = sorted,
                conversationModel = resolvedConvModel
            )
        }
        reapplySearchFilter()
    }

    private fun resolveStepIndexForFocus(
        steps: List<TrajectoryStep>,
        rawTarget: Int?,
        isTranscriptIdx: Boolean
    ): Int? {
        if (rawTarget == null || steps.isEmpty()) return rawTarget
        if (rawTarget == -1) {
            val lastUserIdx = steps.indexOfLast { it.stepCase == StepCase.USER_INPUT }
            return if (lastUserIdx >= 0) lastUserIdx else (steps.size - 1).coerceAtLeast(0)
        }
        if (isTranscriptIdx) {
            val userMatch = steps.indexOfFirst { it.stepCase == StepCase.USER_INPUT && it.stepIndex == rawTarget }
            if (userMatch >= 0) return userMatch
            val anyMatch = steps.indexOfFirst { it.stepIndex == rawTarget }
            if (anyMatch >= 0) return anyMatch
            if (rawTarget in steps.indices) return rawTarget
            val lastUserIdx = steps.indexOfLast { it.stepCase == StepCase.USER_INPUT }
            if (lastUserIdx >= 0) return lastUserIdx
        }
        return rawTarget.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    }

    fun reapplySearchFilter() {
        val state = _uiState.value
        val allHistory = state.history
        val workspaces = allHistory
            .mapNotNull { it.workspace.takeIf { w -> w.isNotBlank() } }
            .distinct()
            .sorted()

        val wsFilter = state.selectedWorkspaceFilter
        val wsFiltered = when (wsFilter) {
            "ALL" -> allHistory
            "OPEN_AGY", "OPEN" -> allHistory.filter { it.openInTerminal || it.active }
            else -> allHistory.filter { it.workspace.equals(wsFilter, ignoreCase = true) }
        }

        val qTrimmed = state.searchQuery.trim()
        if (qTrimmed.isBlank()) {
            val cleaned = wsFiltered.map {
                if (it.matchedPrompt.isNotBlank() || it.matchedStepIndex != -1) {
                    it.copy(matchedPrompt = "", matchedStepIndex = -1)
                } else {
                    it
                }
            }
            _uiState.update {
                it.copy(
                    availableWorkspaces = workspaces,
                    filteredHistory = cleaned,
                    searchResults = if (wsFilter == "ALL") null else cleaned
                )
            }
            return
        }

        val qLower = qTrimmed.lowercase()
        val tokens = qLower.split(Regex("\\s+")).filter { it.isNotBlank() }

        val matchedList = mutableListOf<dev.saifmukhtar.antimatter.core.network.ConversationSummary>()
        for (conv in wsFiltered) {
            val promptsText = conv.userPrompts.joinToString(" ") { it.text }
            val combinedText = buildString {
                append(conv.title).append(' ')
                append(conv.workspace).append(' ')
                append(conv.workspacePath).append(' ')
                append(conv.source).append(' ')
                append(conv.model).append(' ')
                append(conv.id).append(' ')
                append(conv.firstPrompt).append(' ')
                append(conv.lastPrompt).append(' ')
                append(conv.matchedPrompt).append(' ')
                append(promptsText)
            }.lowercase()

            if (tokens.all { token -> combinedText.contains(token) }) {
                val reversedPrompts = conv.userPrompts.asReversed()
                val matchedEntry = reversedPrompts.firstOrNull { it.text.lowercase().contains(qLower) }
                    ?: reversedPrompts.firstOrNull { entry ->
                        val lower = entry.text.lowercase()
                        tokens.all { tok -> lower.contains(tok) }
                    }
                    ?: reversedPrompts.firstOrNull { entry ->
                        val lower = entry.text.lowercase()
                        tokens.any { tok -> lower.contains(tok) }
                    }

                val finalMatchedPrompt: String
                val finalMatchedStepIdx: Int
                when {
                    matchedEntry != null -> {
                        finalMatchedPrompt = matchedEntry.text
                        finalMatchedStepIdx = matchedEntry.stepIndex
                    }
                    conv.matchedPrompt.isNotBlank() && tokens.any { conv.matchedPrompt.lowercase().contains(it) } -> {
                        finalMatchedPrompt = conv.matchedPrompt
                        finalMatchedStepIdx = conv.matchedStepIndex
                    }
                    conv.lastPrompt.isNotBlank() && tokens.any { conv.lastPrompt.lowercase().contains(it) } -> {
                        finalMatchedPrompt = conv.lastPrompt
                        finalMatchedStepIdx = if (conv.lastPromptStepIndex >= 0) conv.lastPromptStepIndex else -1
                    }
                    conv.firstPrompt.isNotBlank() && tokens.any { conv.firstPrompt.lowercase().contains(it) } -> {
                        finalMatchedPrompt = conv.firstPrompt
                        finalMatchedStepIdx = 0
                    }
                    else -> {
                        finalMatchedPrompt = ""
                        finalMatchedStepIdx = -1
                    }
                }

                matchedList.add(
                    conv.copy(
                        matchedPrompt = finalMatchedPrompt,
                        matchedStepIndex = finalMatchedStepIdx
                    )
                )
            }
        }

        _uiState.update {
            it.copy(
                availableWorkspaces = workspaces,
                filteredHistory = matchedList,
                searchResults = matchedList
            )
        }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        reapplySearchFilter()
        serverSearchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length >= 2) {
            serverSearchJob = viewModelScope.launch(Dispatchers.IO) {
                kotlinx.coroutines.delay(250)
                webSocket.searchHttpConversations(trimmed)
            }
        }
    }

    fun setWorkspaceFilter(filter: String) {
        _uiState.update { it.copy(selectedWorkspaceFilter = filter) }
        reapplySearchFilter()
    }

    fun setShowConversationPickerSheet(show: Boolean) {
        _uiState.update { it.copy(showConversationPickerSheet = show) }
        if (show) {
            requestHistory()
        }
    }

    fun setShowPromptsSheet(show: Boolean) {
        _uiState.update { it.copy(showPromptsSheet = show) }
    }

    fun toggleInChatSearch(active: Boolean) {
        if (!active) {
            _uiState.update {
                it.copy(
                    isInChatSearchActive = false,
                    inChatSearchQuery = "",
                    inChatMatches = emptyList(),
                    inChatCurrentMatchIdx = 0
                )
            }
        } else {
            _uiState.update { it.copy(isInChatSearchActive = true) }
        }
    }

    fun updateInChatSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _uiState.update {
                it.copy(
                    inChatSearchQuery = query,
                    inChatMatches = emptyList(),
                    inChatCurrentMatchIdx = 0
                )
            }
            return
        }
        val qLower = trimmed.lowercase()
        val currentSteps = _uiState.value.steps
        val matches = mutableListOf<Int>()
        for (idx in currentSteps.indices) {
            val step = currentSteps[idx]
            val match = step.displayText.lowercase().contains(qLower) ||
                (step.thinkingText?.lowercase()?.contains(qLower) == true) ||
                (step.toolName?.lowercase()?.contains(qLower) == true) ||
                (step.command?.lowercase()?.contains(qLower) == true) ||
                (step.toolSummary?.lowercase()?.contains(qLower) == true) ||
                (step.toolAction?.lowercase()?.contains(qLower) == true)
            if (match) {
                matches.add(idx)
            }
        }
        pendingFocusIsTranscriptStepIndex = false
        val firstMatch = matches.firstOrNull()
        _uiState.update {
            it.copy(
                inChatSearchQuery = query,
                inChatMatches = matches,
                inChatCurrentMatchIdx = 0,
                focusedOriginalStepIndex = firstMatch ?: it.focusedOriginalStepIndex,
                pendingFocusStepIndex = firstMatch
            )
        }
    }

    fun nextInChatMatch() {
        val state = _uiState.value
        if (state.inChatMatches.isEmpty()) return
        val nextIdx = (state.inChatCurrentMatchIdx + 1) % state.inChatMatches.size
        val targetStep = state.inChatMatches[nextIdx]
        pendingFocusIsTranscriptStepIndex = false
        _uiState.update {
            it.copy(
                inChatCurrentMatchIdx = nextIdx,
                focusedOriginalStepIndex = targetStep,
                pendingFocusStepIndex = targetStep
            )
        }
    }

    fun prevInChatMatch() {
        val state = _uiState.value
        if (state.inChatMatches.isEmpty()) return
        val prevIdx = if (state.inChatCurrentMatchIdx - 1 < 0) {
            state.inChatMatches.lastIndex
        } else {
            state.inChatCurrentMatchIdx - 1
        }
        val targetStep = state.inChatMatches[prevIdx]
        pendingFocusIsTranscriptStepIndex = false
        _uiState.update {
            it.copy(
                inChatCurrentMatchIdx = prevIdx,
                focusedOriginalStepIndex = targetStep,
                pendingFocusStepIndex = targetStep
            )
        }
    }

    fun focusStep(originalStepIndex: Int) {
        pendingFocusIsTranscriptStepIndex = false
        _uiState.update {
            it.copy(
                focusedOriginalStepIndex = originalStepIndex,
                pendingFocusStepIndex = originalStepIndex,
                showPromptsSheet = false
            )
        }
    }

    fun clearPendingFocus() {
        _uiState.update { it.copy(pendingFocusStepIndex = null) }
    }

    fun clearFocusHighlight() {
        pendingFocusIsTranscriptStepIndex = false
        _uiState.update {
            it.copy(
                focusedOriginalStepIndex = null,
                pendingFocusStepIndex = null
            )
        }
    }

    fun selectConversation(id: String) {
        subscribeConversation(id, null)
    }

    fun selectConversationAndFocusPrompt(id: String, targetStepIndex: Int? = null) {
        _uiState.update { it.copy(showConversationPickerSheet = false) }
        pendingFocusIsTranscriptStepIndex = true
        if (id == _uiState.value.conversationId && _uiState.value.steps.isNotEmpty() && targetStepIndex != null) {
            val resolved = resolveStepIndexForFocus(_uiState.value.steps, targetStepIndex, true)
            pendingFocusIsTranscriptStepIndex = false
            _uiState.update {
                it.copy(
                    focusedOriginalStepIndex = resolved,
                    pendingFocusStepIndex = resolved
                )
            }
            return
        }
        subscribeConversation(id, targetStepIndex)
    }

    fun refreshHistory() {
        requestHistory()
    }

    fun selectModel(model: String) {
        _uiState.update { it.copy(selectedModel = model) }
    }

    private fun handleInboundMessage(message: InboundMessage) {
        val currentActiveAgent = _uiState.value.activeAgentId ?: "antigravity"
        
        // Guard: Ignore background adapter messages that don't match the active agent
        val msgAgentId = when (message) {
            is InboundMessage.SessionState -> message.agentId
            is InboundMessage.StepBatch -> message.agentId
            is InboundMessage.Generating -> message.agentId
            is InboundMessage.ResponseComplete -> message.agentId
            is InboundMessage.HistoryList -> message.agentId
            is InboundMessage.ArtifactsList -> message.agentId
            is InboundMessage.ArtifactContent -> message.agentId
            is InboundMessage.Step -> message.agentId
            else -> null
        }
        
        if (msgAgentId != null && _uiState.value.activeAgentId != null && msgAgentId != _uiState.value.activeAgentId) {
            return
        }

        when (message) {
            is InboundMessage.SessionState -> {
                val previousConversationId = _uiState.value.conversationId
                if (message.conversationId == "new") {
                    lastStepIndex = -1
                    pendingFullRefresh = false
                    _uiState.update {
                        it.copy(
                            conversationId = "new",
                            steps = emptyList(),
                            expectedStepCount = 0,
                            currentModel = message.model.ifBlank { it.currentModel },
                            selectedModel = "default",
                            conversationModel = "",
                            focusedOriginalStepIndex = null,
                            pendingFocusStepIndex = null
                        )
                    }
                    return
                }

                pendingFullRefresh = true
                val richModel = message.conversationId?.let { richSummaryMap[it]?.model } ?: ""
                val resolvedConvModel = richModel.ifBlank { message.model }
                _uiState.update {
                    it.copy(
                        conversationId = message.conversationId,
                        expectedStepCount = message.stepCount,
                        currentModel = message.model.ifBlank { it.currentModel },
                        conversationModel = resolvedConvModel.ifBlank { it.conversationModel },
                        cloudflareUrl = message.cloudflareUrl,
                        environment = message.environment
                    )
                }
                
                // Truncate local steps if server says there are fewer (due to edit)
                message.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            appDao.deleteStepsFromIndex(cid, message.stepCount)
                            appDao.updateStepCount(cid, message.stepCount)
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "SessionState DB update error", e)
                        }
                    }
                }
                val cid = message.conversationId
                // Only subscribe if the conversation actually changed
                if (cid != null && cid != previousConversationId) {
                    startIncrementalPolling(cid)
                }
            }
            is InboundMessage.Step -> {
                if (message.step.stepIndex > lastStepIndex) {
                    lastStepIndex = message.step.stepIndex
                }
                _uiState.update { state ->
                    val newSteps = state.steps.toMutableList()
                    if (message.index >= 0 && message.index < newSteps.size) {
                        newSteps[message.index] = message.step
                    } else {
                        newSteps.add(message.step)
                    }
                    val targetCid = state.conversationId ?: "default"
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val existing = appDao.getConversation(targetCid)
                            if (existing == null) {
                                appDao.insertConversation(
                                    ConversationEntity(
                                        id = targetCid,
                                        title = "Chat",
                                        timestamp = System.currentTimeMillis(),
                                        agentId = message.agentId ?: state.activeAgentId ?: "antigravity"
                                    )
                                )
                            }
                            appDao.insertSteps(listOf(message.step.toEntity(targetCid, newSteps.size - 1)))
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Safe DB insert error", e)
                        }
                    }
                    state.copy(steps = newSteps, isGenerating = false)
                }
            }
            is InboundMessage.StepBatch -> {
                val targetCid = message.conversationId
                val currentCid = _uiState.value.conversationId

                // Guard: If StepBatch belongs to a different conversation (unless currentCid was "new"), save to DB
                if (targetCid != null && currentCid != null && currentCid != "new" && targetCid != currentCid) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val existing = appDao.getConversation(targetCid)
                            if (existing == null) {
                                appDao.insertConversation(
                                    ConversationEntity(
                                        id = targetCid,
                                        title = "Chat",
                                        timestamp = System.currentTimeMillis(),
                                        agentId = message.agentId ?: "antigravity"
                                    )
                                )
                            }
                            appDao.insertSteps(message.steps.mapIndexed { idx, s -> s.step.toEntity(targetCid, if (s.index >= 0) s.index else idx) })
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Safe DB batch insert error", e)
                        }
                    }
                    return
                }

                val isFullReplace = pendingFullRefresh && (message.steps.isNotEmpty() || !_uiState.value.isGenerating)
                if (message.steps.isNotEmpty() || !_uiState.value.isGenerating) {
                    pendingFullRefresh = false
                }

                _uiState.update { state ->
                    val newSteps = if (isFullReplace) {
                        mutableListOf()
                    } else {
                        state.steps.toMutableList()
                    }
                    val prevMaxStepIdx = lastStepIndex

                    message.steps.forEach { batchStep ->
                        val stepIdx = batchStep.step.stepIndex
                        if (!isFullReplace) {
                            if (batchStep.step.stepCase == StepCase.USER_INPUT) {
                                val optIdx = newSteps.indexOfFirst {
                                    it.stepCase == StepCase.USER_INPUT && it.stepIndex < 0
                                }
                                if (optIdx >= 0) {
                                    newSteps[optIdx] = batchStep.step
                                    if (stepIdx > lastStepIndex) {
                                        lastStepIndex = stepIdx
                                    }
                                    return@forEach
                                }
                            }
                            if (stepIdx >= 0 && stepIdx <= prevMaxStepIdx) {
                                val existingIdx = newSteps.indexOfFirst {
                                    it.stepIndex == stepIdx && it.stepCase == batchStep.step.stepCase
                                }
                                if (existingIdx >= 0) {
                                    newSteps[existingIdx] = batchStep.step
                                    return@forEach
                                }
                            }
                        }
                        if (stepIdx > lastStepIndex) {
                            lastStepIndex = stepIdx
                        }
                        if (batchStep.index >= 0 && batchStep.index < newSteps.size) {
                            newSteps[batchStep.index] = batchStep.step
                        } else {
                            newSteps.add(batchStep.step)
                        }
                    }

                    val stillGenerating = if (state.isGenerating) {
                        val lastUserIdx = newSteps.indexOfLast { it.stepCase == StepCase.USER_INPUT }
                        val candidates = if (lastUserIdx >= 0) {
                            newSteps.subList(lastUserIdx + 1, newSteps.size)
                        } else {
                            newSteps
                        }
                        val hasFinalReply = candidates.any { step ->
                            when (step.stepCase) {
                                StepCase.ERROR_MESSAGE,
                                StepCase.ASK_QUESTION,
                                StepCase.ELICITATION -> true
                                StepCase.TEXT,
                                StepCase.MARKDOWN_CHUNK -> {
                                    candidates.none { other ->
                                        other.stepCase == StepCase.TOOL_CALL &&
                                            other.stepIndex >= 0 &&
                                            other.stepIndex >= step.stepIndex
                                    }
                                }
                                else -> false
                            }
                        }
                        !hasFinalReply
                    } else {
                        false
                    }

                    val cidToSave = targetCid ?: state.conversationId ?: "default"
                    if (cidToSave != "new") {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val existing = appDao.getConversation(cidToSave)
                                val richTitle = richSummaryMap[cidToSave]?.title?.takeIf { it.isNotBlank() && it != "Chat" }
                                if (existing == null) {
                                    appDao.insertConversation(
                                        ConversationEntity(
                                            id = cidToSave,
                                            title = richTitle ?: "Chat",
                                            timestamp = System.currentTimeMillis(),
                                            agentId = message.agentId ?: state.activeAgentId ?: "antigravity",
                                            stepCount = newSteps.size
                                        )
                                    )
                                }
                                appDao.insertSteps(newSteps.mapIndexed { idx, s -> s.toEntity(cidToSave, idx) })
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Safe DB batch insert error", e)
                            }
                        }
                    }

                    var resolvedFocusOrig = state.focusedOriginalStepIndex
                    var resolvedPendingFocus = state.pendingFocusStepIndex
                    if (state.pendingFocusStepIndex != null && newSteps.isNotEmpty()) {
                        val resolved = resolveStepIndexForFocus(
                            newSteps,
                            state.pendingFocusStepIndex,
                            pendingFocusIsTranscriptStepIndex
                        )
                        pendingFocusIsTranscriptStepIndex = false
                        resolvedFocusOrig = resolved
                        resolvedPendingFocus = resolved
                    }

                    state.copy(
                        steps = newSteps,
                        conversationId = targetCid ?: state.conversationId,
                        isGenerating = stillGenerating,
                        focusedOriginalStepIndex = resolvedFocusOrig,
                        pendingFocusStepIndex = resolvedPendingFocus
                    )
                }
            }
            is InboundMessage.Generating -> {
                val prevCid = _uiState.value.conversationId
                val resolvedCid = message.conversationId.takeIf { it.isNotBlank() } ?: prevCid
                if (!resolvedCid.isNullOrBlank() && resolvedCid != "new" && prevCid != null && prevCid != "new" && resolvedCid != prevCid) {
                    lastStepIndex = -1
                    pendingFullRefresh = false
                    _uiState.update { state ->
                        val lastUserStep = state.steps.lastOrNull { it.stepCase == StepCase.USER_INPUT }
                        state.copy(
                            isGenerating = true,
                            conversationId = resolvedCid,
                            steps = if (lastUserStep != null) listOf(lastUserStep.copy(stepIndex = -1)) else emptyList()
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(isGenerating = true, conversationId = resolvedCid)
                    }
                }
                if (!resolvedCid.isNullOrBlank() && resolvedCid != "new") {
                    val sinceIdx = if (lastStepIndex >= 0) lastStepIndex - 1 else -1
                    fetchTranscript(resolvedCid, since = sinceIdx)
                    startIncrementalPolling(resolvedCid)
                }
            }
            is InboundMessage.ResponseComplete -> {
                val resolvedCid = message.conversationId.takeIf { it.isNotBlank() } ?: _uiState.value.conversationId
                _uiState.update {
                    it.copy(isGenerating = false, conversationId = resolvedCid)
                }
                if (!resolvedCid.isNullOrBlank() && resolvedCid != "new") {
                    fetchTranscript(resolvedCid, since = lastStepIndex)
                    startIncrementalPolling(resolvedCid)
                }
            }
            is InboundMessage.ActiveFile -> {
                _uiState.update {
                    it.copy(activeFile = message.path, activeFileLanguage = message.language)
                }
            }
            is InboundMessage.CloudflareUrl -> {
                _uiState.update { it.copy(cloudflareUrl = message.url) }
            }
            is InboundMessage.HistoryList -> {
                android.util.Log.d("ChatViewModel", "Received HISTORY_LIST with ${message.conversations.size} conversations")
                message.conversations.forEach { summary ->
                    richSummaryMap[summary.id] = mergeSummaryWithRich(summary)
                }
                rebuildHistoryFromMap()
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        message.conversations.forEach { summary ->
                            val merged = richSummaryMap[summary.id] ?: summary
                            val existing = appDao.getConversation(summary.id)
                            if (existing == null) {
                                appDao.insertConversation(
                                    ConversationEntity(
                                        id = merged.id,
                                        title = merged.title.ifBlank { "Chat" },
                                        timestamp = merged.timestamp,
                                        agentId = message.agentId ?: "antigravity",
                                        stepCount = merged.stepCount
                                    )
                                )
                            } else if (merged.title.isNotBlank() && merged.title != "Chat") {
                                appDao.insertConversation(
                                    existing.copy(
                                        title = merged.title,
                                        timestamp = maxOf(existing.lastModified, merged.timestamp),
                                        stepCount = maxOf(existing.stepCount, merged.stepCount)
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatViewModel", "HistoryList DB insert error", e)
                    }
                }
                // Auto-select latest conversation if none active yet
                if (_uiState.value.conversationId == null && !_uiState.value.isGenerating && message.conversations.isNotEmpty()) {
                    subscribeConversation(message.conversations.first().id)
                }
            }
            is InboundMessage.ArtifactsList -> {
                _uiState.update { it.copy(artifacts = message.artifacts) }
                _uiState.value.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val entities = message.artifacts.map {
                                val existing = appDao.getArtifact(cid, it.path)
                                dev.saifmukhtar.antimatter.core.data.ArtifactEntity(
                                    conversationId = cid,
                                    path = it.path,
                                    name = it.name,
                                    compressedContent = existing?.compressedContent
                                )
                            }
                            appDao.insertArtifacts(entities)
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "ArtifactsList DB insert error", e)
                        }
                    }
                }
            }
            is InboundMessage.ArtifactContent -> {
                _uiState.update { it.copy(activeArtifactContent = message.content) }
                _uiState.value.conversationId?.let { cid ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val compressed = GzipUtils.compress(message.content)
                            val name = message.path.substringAfterLast("/")
                            appDao.insertArtifacts(listOf(
                                dev.saifmukhtar.antimatter.core.data.ArtifactEntity(
                                    conversationId = cid,
                                    path = message.path,
                                    name = name,
                                    compressedContent = compressed
                                )
                            ))
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "ArtifactContent DB insert error", e)
                        }
                    }
                }
            }
            is InboundMessage.Error -> {
                _uiState.update { state -> 
                    val newSteps = state.steps.toMutableList()
                    if (state.isGenerating && newSteps.isNotEmpty() && newSteps.last().case == "userInput") {
                        newSteps.removeAt(newSteps.size - 1)
                    }
                    state.copy(error = message.message, steps = newSteps, isGenerating = false)
                }
                // Auto-dismiss error after 10 seconds
                viewModelScope.launch {
                    kotlinx.coroutines.delay(10000)
                    _uiState.update { if (it.error == message.message) it.copy(error = null) else it }
                }
            }
            is InboundMessage.AvailableAgents -> {
                val oldActiveAgentId = _uiState.value.activeAgentId
                val newActiveAgentId = if (oldActiveAgentId != null && message.agents.any { it.id == oldActiveAgentId }) {
                    oldActiveAgentId
                } else {
                    message.agents.firstOrNull()?.id ?: "antigravity"
                }

                _uiState.update { it.copy(
                    availableAgents = message.agents, 
                    allowedWorkspaces = message.allowedWorkspaces,
                    activeAgentId = newActiveAgentId
                ) }

                if (newActiveAgentId != oldActiveAgentId) {
                    requestHistory()
                }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        appDao.deleteAllAgents()
                        message.agents.forEach { info ->
                            appDao.insertAgent(dev.saifmukhtar.antimatter.core.data.AgentEntity(id = info.id, name = info.name, status = info.status, lastSeen = System.currentTimeMillis()))
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatViewModel", "AvailableAgents DB insert error", e)
                    }
                }
            }
            else -> {} // Handle FileTree, FileContent etc in a separate ViewModel or UI state
        }
    }

    // --- Actions ---

    fun selectImage(uri: Uri?) {
        _uiState.update { it.copy(selectedImageUri = uri) }
    }

    fun setShowModelPickerDialog(show: Boolean) {
        _uiState.update { it.copy(showModelPickerDialog = show) }
    }

    fun consumeOpenSettingsTab() {
        _uiState.update { it.copy(pendingOpenSettingsTab = false) }
    }

    private fun getDefaultConfiguredModelId(): String {
        val prefs = context.getSharedPreferences("aimon_prefs", Context.MODE_PRIVATE) ?: return "default"
        return prefs.getString("default_model_id", "default")?.takeIf { it.isNotBlank() } ?: "default"
    }

    fun selectModelOption(modelId: String, appendConfirmationStep: Boolean = true) {
        val normalized = modelId.trim().ifBlank { "default" }
        val option = AVAILABLE_MODEL_OPTIONS.find {
            it.id.equals(normalized, ignoreCase = true) ||
                it.label.equals(normalized, ignoreCase = true)
        }
        val finalId = option?.id ?: normalized
        val finalLabel = option?.label ?: normalized
        _uiState.update { state ->
            val updatedSteps = state.steps.toMutableList()
            if (appendConfirmationStep) {
                updatedSteps.add(
                    TrajectoryStep(
                        case = "text",
                        value = "**Active model set to:** `$finalLabel` (`$finalId`)",
                        stepIndex = -1
                    )
                )
            }
            state.copy(
                selectedModel = finalId,
                conversationModel = if (finalId == "default") "" else finalId,
                showModelPickerDialog = false,
                steps = updatedSteps,
                isGenerating = false
            )
        }
    }

    fun sendPrompt(text: String) {
        sendMessage(text)
    }

    fun sendMessage(text: String) {
        val activeAgentId = _uiState.value.activeAgentId 
            ?: _uiState.value.availableAgents.firstOrNull()?.id 
            ?: "antigravity"
            
        if (_uiState.value.activeAgentId == null) {
            _uiState.update { it.copy(activeAgentId = activeAgentId) }
        }
        
        if (text.isBlank() && _uiState.value.selectedImageUri == null) return
        
        val imageUri = _uiState.value.selectedImageUri
        val trimmedInput = text.trim()

        // Intercept client-side slash commands when no image is attached
        if (imageUri == null && trimmedInput.startsWith("/")) {
            val spaceIdx = trimmedInput.indexOf(' ')
            val cmdLower = (if (spaceIdx >= 0) trimmedInput.substring(0, spaceIdx) else trimmedInput).lowercase()
            val cmdArg = if (spaceIdx >= 0) trimmedInput.substring(spaceIdx + 1).trim() else ""

            if (cmdLower == "/clear" || cmdLower == "/new" || cmdLower == "/reset") {
                startNewConversation()
                return
            }
            if (cmdLower == "/settings" || cmdLower == "/config") {
                _uiState.update { it.copy(pendingOpenSettingsTab = true) }
                return
            }
            if (cmdLower == "/model") {
                if (cmdArg.isBlank()) {
                    setShowModelPickerDialog(true)
                } else {
                    _uiState.update { state ->
                        val newSteps = state.steps.toMutableList()
                        newSteps.add(TrajectoryStep(case = "userInput", value = trimmedInput, stepIndex = -1))
                        state.copy(steps = newSteps)
                    }
                    selectModelOption(cmdArg, appendConfirmationStep = true)
                }
                return
            }
        }

        val textToSend = text.ifBlank { "Attached an image." }
        val targetConversationId = _uiState.value.conversationId ?: "new"
        val configuredDefault = getDefaultConfiguredModelId()
        val modelToSend = _uiState.value.selectedModel.takeIf { it.isNotBlank() && it != "default" }
            ?: configuredDefault
        val sourceToSend = richSummaryMap[targetConversationId]?.source ?: ""
        
        // Optimistically add user step
        _uiState.update { state ->
            val newSteps = state.steps.toMutableList()
            newSteps.add(TrajectoryStep(case = "userInput", value = textToSend))
            state.copy(
                steps = newSteps,
                conversationId = targetConversationId,
                isGenerating = true,
                selectedImageUri = null,
                error = null
            )
        }

        if (imageUri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val inputStream = context.contentResolver.openInputStream(imageUri)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream?.close()
                    
                    if (bitmap != null) {
                        // Scale down to max 1024x1024
                        val maxDim = 1024f
                        val scale = minOf(maxDim / bitmap.width, maxDim / bitmap.height)
                        val scaledBitmap = if (scale < 1f) {
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        } else {
                            bitmap
                        }
                        
                        val outputStream = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
                        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                        val dataUri = "data:image/jpeg;base64,$base64"
                        
                        webSocket.sendMessage(OutboundMessage.SendMessage(
                            text = textToSend,
                            images = listOf(dataUri),
                            conversationId = targetConversationId,
                            model = modelToSend,
                            source = sourceToSend,
                            agentId = activeAgentId
                        ))
                        
                        if (scaledBitmap != bitmap) {
                            scaledBitmap.recycle()
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Failed to process image", e)
                    _uiState.update { state -> 
                        val newSteps = state.steps.toMutableList()
                        if (newSteps.isNotEmpty() && newSteps.last().case == "userInput") {
                            newSteps.removeAt(newSteps.size - 1)
                        }
                        state.copy(error = "Failed to process image", steps = newSteps, isGenerating = false)
                    }
                }
            }
        } else {
            webSocket.sendMessage(OutboundMessage.SendMessage(
                text = textToSend,
                conversationId = targetConversationId,
                model = modelToSend,
                source = sourceToSend,
                agentId = activeAgentId
            ))
        }
    }

    fun startNewConversation() {
        pollingJob?.cancel()
        lastStepIndex = -1
        pendingFullRefresh = false
        pendingFocusIsTranscriptStepIndex = false
        val defaultModel = getDefaultConfiguredModelId()
        _uiState.update {
            it.copy(
                steps = emptyList(),
                conversationId = "new",
                isGenerating = false,
                error = null,
                selectedModel = defaultModel,
                conversationModel = if (defaultModel == "default") "" else defaultModel,
                focusedOriginalStepIndex = null,
                pendingFocusStepIndex = null,
                showConversationPickerSheet = false,
                showPromptsSheet = false,
                showModelPickerDialog = false
            )
        }
        val agentId = _uiState.value.activeAgentId ?: "antigravity"
        webSocket.sendMessage(OutboundMessage.NewConversation(agentId = agentId))
        requestHistory() // Refresh history after starting new conversation
    }

    fun subscribeConversation(id: String, targetFocusStepIndex: Int? = null) {
        viewModelScope.launch {
            lastStepIndex = -1
            pendingFullRefresh = true

            // Instantly load cached steps and scroll position from Room to support offline viewing
            val cachedConv = withContext(Dispatchers.IO) {
                try {
                    appDao.getConversation(id)
                } catch (t: Throwable) {
                    android.util.Log.w("ChatViewModel", "getConversation error: ${t.message}")
                    null
                }
            }
            val cachedEntities = withContext(Dispatchers.IO) {
                try {
                    appDao.getStepsForConversation(id)
                } catch (t: Throwable) {
                    android.util.Log.w("ChatViewModel", "getStepsForConversation error: ${t.message}")
                    emptyList()
                }
            }

            val initialSteps = cachedEntities
                .sortedBy { it.stepIndex }
                .mapNotNull { entity ->
                    try {
                        entity.toTrajectoryStep().copy(stepIndex = entity.stepIndex)
                    } catch (_: Throwable) {
                        null
                    }
                }

            val resolvedInitialFocus = if (targetFocusStepIndex != null && initialSteps.isNotEmpty()) {
                val res = resolveStepIndexForFocus(initialSteps, targetFocusStepIndex, pendingFocusIsTranscriptStepIndex)
                pendingFocusIsTranscriptStepIndex = false
                res
            } else {
                targetFocusStepIndex
            }

            val convModel = richSummaryMap[id]?.model ?: ""

            _uiState.update {
                it.copy(
                    steps = initialSteps,
                    conversationId = id,
                    isGenerating = false,
                    error = null,
                    selectedModel = "default",
                    conversationModel = convModel,
                    showConversationPickerSheet = false,
                    showPromptsSheet = false,
                    focusedOriginalStepIndex = resolvedInitialFocus,
                    pendingFocusStepIndex = resolvedInitialFocus,
                    initialScrollIndex = cachedConv?.scrollIndex ?: 0,
                    initialScrollOffset = cachedConv?.scrollOffset ?: 0
                )
            }

            // Fetch full transcript from server and start incremental polling
            webSocket.sendMessage(OutboundMessage.SubscribeConversation(
                conversationId = id,
                lastKnownStepCount = 0,
                sinceStepIndex = -1,
                agentId = _uiState.value.activeAgentId
            ))
            startIncrementalPolling(id)
        }
    }

    fun requestHistory() {
        val agent = _uiState.value.activeAgentId ?: "antigravity"
        webSocket.sendMessage(OutboundMessage.GetHistory(agentId = agent))
    }

    fun requestArtifacts() {
        _uiState.value.conversationId?.let { cid ->
            viewModelScope.launch {
                val cached = withContext(Dispatchers.IO) {
                    try {
                        appDao.getArtifactsForConversation(cid)
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
                if (cached.isNotEmpty()) {
                    val converted = cached.map { 
                        dev.saifmukhtar.antimatter.core.network.FileNode(name = it.name, path = it.path, isDir = false)
                    }
                    _uiState.update { it.copy(artifacts = converted) }
                }
                webSocket.sendMessage(OutboundMessage.GetArtifacts(cid, agentId = _uiState.value.activeAgentId))
            }
        }
    }

    fun requestArtifactContent(path: String) {
        _uiState.value.conversationId?.let { cid ->
            viewModelScope.launch {
                val existing = withContext(Dispatchers.IO) {
                    try {
                        appDao.getArtifact(cid, path)
                    } catch (_: Throwable) {
                        null
                    }
                }
                val compressedContent = existing?.compressedContent
                if (compressedContent != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val decompressed = GzipUtils.decompress(compressedContent)
                            _uiState.update { it.copy(activeArtifactContent = decompressed) }
                        } catch (e: Exception) {
                            android.util.Log.e("ChatViewModel", "Failed to decompress artifact", e)
                            webSocket.sendMessage(OutboundMessage.ReadArtifact(conversationId = cid, path = path, agentId = _uiState.value.activeAgentId))
                        }
                    }
                } else {
                    webSocket.sendMessage(OutboundMessage.ReadArtifact(conversationId = cid, path = path, agentId = _uiState.value.activeAgentId))
                }
            }
        }
    }

    fun clearActiveArtifact() {
        _uiState.update { it.copy(activeArtifactContent = null) }
    }

    fun cancelResponse() {
        webSocket.sendMessage(OutboundMessage.CancelResponse(agentId = _uiState.value.activeAgentId))
        _uiState.update { it.copy(isGenerating = false) }
    }
    
    fun acceptEdits() {
        webSocket.sendMessage(OutboundMessage.AcceptEdits(agentId = _uiState.value.activeAgentId))
    }
    
    fun rejectEdits() {
        webSocket.sendMessage(OutboundMessage.RejectEdits(agentId = _uiState.value.activeAgentId))
    }
    
    fun changeModel() {
        setShowModelPickerDialog(true)
    }
    
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun switchAgent(agentId: String) {
        if (_uiState.value.activeAgentId == agentId) return
        _uiState.update { it.copy(activeAgentId = agentId) }
        startNewConversation()
        viewModelScope.launch(Dispatchers.IO) {
            val localHistory = try {
                appDao.getConversationsForAgentFlow(agentId).catch { emit(emptyList()) }.firstOrNull() ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
            rebuildHistoryFromMap(localHistory)
        }
    }

    private var lastFetchedOffset = -1

    fun onStepVisible(stepIndex: Int) {
        val steps = _uiState.value.steps
        if (stepIndex in steps.indices) {
            val step = steps[stepIndex]
            if (step.case == "unknown" && step.value == "...") {
                val total = _uiState.value.expectedStepCount
                if (total > 0) {
                    // Calculate offset from the end
                    val offset = kotlin.math.max(0, total - stepIndex - 25)
                    if (offset != lastFetchedOffset) {
                        lastFetchedOffset = offset
                        fetchHistoryPage(_uiState.value.conversationId ?: return, offset, 50)
                    }
                }
            }
        }
    }

    fun fetchHistoryPage(conversationId: String, offset: Int, limit: Int) {
        webSocket.sendMessage(OutboundMessage.GetHistoryPage(conversationId, offset, limit, agentId = _uiState.value.activeAgentId))
    }

    fun updateScrollState(index: Int, offset: Int) {
        scrollStateFlow.tryEmit(Pair(index, offset))
    }

    suspend fun getScrollState(id: String): Pair<Int, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val conv = appDao.getConversation(id)
                Pair(conv?.scrollIndex ?: 0, conv?.scrollOffset ?: 0)
            } catch (_: Throwable) {
                Pair(0, 0)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Removed webSocket.disconnect() as it's a singleton (BUG-8)
    }
}
