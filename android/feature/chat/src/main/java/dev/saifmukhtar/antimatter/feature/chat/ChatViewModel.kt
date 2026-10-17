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
    val error: String? = null,
    val history: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<dev.saifmukhtar.antimatter.core.network.ConversationSummary>? = null,
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
            val localAgents = appDao.getAllAgentsFlow().firstOrNull()
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
                    appDao.getConversationsForAgentFlow(agentId)
                } else {
                    flowOf(emptyList())
                }
            }.collect { localConversations ->
                val historyList = localConversations.map { 
                    dev.saifmukhtar.antimatter.core.network.ConversationSummary(id = it.id, title = it.title, timestamp = it.timestamp) 
                }
                _uiState.update { it.copy(history = historyList) }
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

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        if (query.isBlank()) {
            _uiState.update { it.copy(searchResults = null) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val history = _uiState.value.history
            val results = history.filter { it.title.contains(query, ignoreCase = true) }
            _uiState.update { it.copy(searchResults = results) }
        }
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
                            currentModel = message.model ?: it.currentModel
                        )
                    }
                    return
                }

                pendingFullRefresh = true
                _uiState.update {
                    it.copy(
                        conversationId = message.conversationId,
                        expectedStepCount = message.stepCount,
                        currentModel = message.model ?: it.currentModel,
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
                                StepCase.APPROVAL_INTERACTION,
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
                                if (existing == null) {
                                    appDao.insertConversation(
                                        ConversationEntity(
                                            id = cidToSave,
                                            title = "Chat",
                                            timestamp = System.currentTimeMillis(),
                                            agentId = message.agentId ?: state.activeAgentId ?: "antigravity"
                                        )
                                    )
                                }
                                appDao.insertSteps(newSteps.mapIndexed { idx, s -> s.toEntity(cidToSave, idx) })
                            } catch (e: Exception) {
                                android.util.Log.e("ChatViewModel", "Safe DB batch insert error", e)
                            }
                        }
                    }
                    state.copy(
                        steps = newSteps,
                        conversationId = targetCid ?: state.conversationId,
                        isGenerating = stillGenerating
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
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        message.conversations.forEach { summary ->
                            val existing = appDao.getConversation(summary.id)
                            if (existing == null) {
                                appDao.insertConversation(ConversationEntity(id = summary.id, title = summary.title, timestamp = summary.timestamp, agentId = message.agentId ?: "antigravity"))
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("ChatViewModel", "HistoryList DB insert error", e)
                    }
                }
                _uiState.update { it.copy(history = message.conversations) }
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
        val textToSend = text.ifBlank { "Attached an image." }
        val targetConversationId = _uiState.value.conversationId ?: "new"
        
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
                agentId = activeAgentId
            ))
        }
    }

    fun startNewConversation() {
        pollingJob?.cancel()
        lastStepIndex = -1
        pendingFullRefresh = false
        _uiState.update { it.copy(steps = emptyList(), conversationId = "new", isGenerating = false, error = null) }
        val agentId = _uiState.value.activeAgentId ?: "antigravity"
        webSocket.sendMessage(OutboundMessage.NewConversation(agentId = agentId))
        requestHistory() // Refresh history after starting new conversation
    }

    fun subscribeConversation(id: String) {
        viewModelScope.launch {
            lastStepIndex = -1
            pendingFullRefresh = true

            // Instantly load cached steps from Room to support offline viewing
            val cachedSteps = withContext(Dispatchers.IO) {
                appDao.getStepsForConversation(id)
            }

            val initialSteps = mutableListOf<TrajectoryStep>()
            cachedSteps.forEach { entity ->
                while (initialSteps.size < entity.stepIndex) {
                    initialSteps.add(TrajectoryStep(case = "unknown", value = "..."))
                }
                initialSteps.add(entity.toTrajectoryStep())
            }

            _uiState.update { it.copy(steps = initialSteps, conversationId = id, isGenerating = false, error = null) }

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
                    appDao.getArtifactsForConversation(cid)
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
                    appDao.getArtifact(cid, path)
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
        webSocket.sendMessage(OutboundMessage.ChangeModel(agentId = _uiState.value.activeAgentId))
    }
    
    
    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun switchAgent(agentId: String) {
        if (_uiState.value.activeAgentId == agentId) return
        _uiState.update { it.copy(activeAgentId = agentId) }
        startNewConversation()
        viewModelScope.launch(Dispatchers.IO) {
            val localHistory = appDao.getConversationsForAgentFlow(agentId).firstOrNull() ?: emptyList()
            val summaries = localHistory.map { dev.saifmukhtar.antimatter.core.network.ConversationSummary(id = it.id, timestamp = it.timestamp, title = it.title) }
            _uiState.update { it.copy(history = summaries) }
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
            val conv = appDao.getConversation(id)
            Pair(conv?.scrollIndex ?: 0, conv?.scrollOffset ?: 0)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Removed webSocket.disconnect() as it's a singleton (BUG-8)
    }
}
