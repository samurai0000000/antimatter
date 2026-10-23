/*
 * ChatScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.ConversationSummary
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.ui.MarkdownText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

sealed class ChatItem {
    data class SingleStep(val step: TrajectoryStep, val originalIndex: Int) : ChatItem()
    data class ThoughtGroup(
        val steps: List<TrajectoryStep>,
        val originalIndex: Int,
        val isCompleted: Boolean = false
    ) : ChatItem() {
        val firstOriginalIndex: Int get() = originalIndex
        val toolCallCount: Int get() = steps.count { it.stepCase == StepCase.TOOL_CALL }
        val thoughtCount: Int get() = steps.count { it.stepCase == StepCase.PLANNER_RESPONSE }
    }
}

fun cleanThoughtText(text: String?): String? {
    if (text.isNullOrBlank()) return text

    val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

    // 1. Remove the boilerplate paragraphs
    val withoutBoilerplate = paragraphs.filterNot { paragraph ->
        val lower = paragraph.lowercase()
        lower.contains("tool") && (lower.contains("cat") || lower.contains("grep") || lower.contains("sed") || lower.contains("ls"))
    }

    // 2. Remove orphaned headers
    val finalParagraphs = mutableListOf<String>()
    for (i in withoutBoilerplate.indices) {
        val current = withoutBoilerplate[i]
        val isHeader = current.startsWith("**") && current.endsWith("**") && !current.substring(2, current.length - 2).contains("**")

        if (isHeader) {
            val isNextAlsoHeader = if (i < withoutBoilerplate.lastIndex) {
                val next = withoutBoilerplate[i + 1]
                next.startsWith("**") && next.endsWith("**") && !next.substring(2, next.length - 2).contains("**")
            } else true

            if (!isNextAlsoHeader) {
                finalParagraphs.add(current)
            }
        } else {
            finalParagraphs.add(current)
        }
    }

    val result = finalParagraphs.joinToString("\n\n").trim()
    return result.ifEmpty { null }
}

fun groupSteps(steps: List<TrajectoryStep>): List<ChatItem> {
    val visibleEntries = steps.mapIndexedNotNull { origIdx, step ->
        if (step.stepCase in listOf(
                StepCase.USER_INPUT,
                StepCase.TEXT,
                StepCase.MARKDOWN_CHUNK,
                StepCase.TOOL_CALL,
                StepCase.PLANNER_RESPONSE,
                StepCase.ERROR_MESSAGE
            )
        ) {
            origIdx to step
        } else {
            null
        }
    }

    val result = mutableListOf<ChatItem>()
    var currentGroup = mutableListOf<TrajectoryStep>()
    var groupStartIndex = 0

    for ((origIdx, step) in visibleEntries) {
        when (step.stepCase) {
            StepCase.PLANNER_RESPONSE -> {
                val rawThought = step.thinking ?: step.value
                val cleanedText = cleanThoughtText(rawThought)
                if (!cleanedText.isNullOrBlank()) {
                    if (currentGroup.isEmpty()) groupStartIndex = origIdx
                    currentGroup.add(step.copy(value = cleanedText))
                }
            }
            StepCase.TOOL_CALL -> {
                if (currentGroup.isEmpty()) groupStartIndex = origIdx
                currentGroup.add(step)
            }
            else -> {
                if (currentGroup.isNotEmpty()) {
                    result.add(ChatItem.ThoughtGroup(currentGroup, groupStartIndex, isCompleted = true))
                    currentGroup = mutableListOf()
                }
                result.add(ChatItem.SingleStep(step, origIdx))
            }
        }
    }
    if (currentGroup.isNotEmpty()) {
        result.add(ChatItem.ThoughtGroup(currentGroup, groupStartIndex, isCompleted = false))
    }
    return result
}

private fun formatCompactModelLabel(rawModel: String): String {
    val m = rawModel.trim()
    if (m.isBlank() || m.equals("default", ignoreCase = true)) return "Argon"
    val lower = m.lowercase()
    return when {
        lower.contains("argon") && lower.contains("high") -> "Argon High"
        lower.contains("argon") && lower.contains("low") -> "Argon Low"
        lower.contains("argon") -> "Argon Med"
        lower.contains("3.8") && lower.contains("flash") -> "Flash 3.8"
        lower.contains("opus") -> "Opus 5.5"
        lower.contains("sonnet") -> "Sonnet 5.5"
        m.length > 22 -> m.take(20) + "…"
        else -> m
    }
}

private fun formatRelativeTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val normalizedMs = if (timestampMs < 10_000_000_000L) timestampMs * 1000L else timestampMs
    val diffSec = ((System.currentTimeMillis() - normalizedMs) / 1000L).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "just now"
        diffSec < 3600L -> "${diffSec / 60L}m ago"
        diffSec < 86400L -> "${diffSec / 3600L}h ago"
        diffSec < 7L * 86400L -> "${diffSec / 86400L}d ago"
        else -> {
            try {
                SimpleDateFormat("MMM d", Locale.US).format(Date(normalizedMs))
            } catch (_: Exception) {
                ""
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendPrompt: (String) -> Unit,
    onCancel: () -> Unit,
    onNewConversation: () -> Unit,
    onSubscribeConversation: (String) -> Unit,
    onAcceptEdits: () -> Unit,
    onRejectEdits: () -> Unit,
    onChangeModel: () -> Unit,
    onDisconnect: () -> Unit,
    onScrollStateChange: (index: Int, offset: Int) -> Unit,
    onLoadScrollState: suspend (conversationId: String) -> Pair<Int, Int>?,
    onRequestArtifacts: () -> Unit,
    onRequestArtifactContent: (String) -> Unit,
    onClearArtifactContent: () -> Unit,
    onSwitchAgent: (String) -> Unit,
    onSearchHistory: (String) -> Unit,
    onSelectImage: (android.net.Uri?) -> Unit,
    onStepVisible: (Int) -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val listState = rememberLazyListState()
    var isInitialLoad by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showArtifactsSheet by remember { mutableStateOf(false) }
    val artifactsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val promptsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val groupedItems = remember(uiState.steps) { groupSteps(uiState.steps) }
    val reversedItems = remember(groupedItems) { groupedItems.reversed() }
    val totalItemsCount = reversedItems.size + if (uiState.isGenerating) 1 else 0

    val userPromptSteps = remember(uiState.steps) {
        uiState.steps.mapIndexedNotNull { origIdx, step ->
            if (step.stepCase == StepCase.USER_INPUT && step.displayText.isNotBlank()) {
                origIdx to step
            } else {
                null
            }
        }
    }

    val activeConversationSummary = remember(uiState.conversationId, uiState.history) {
        uiState.history.find { it.id == uiState.conversationId }
    }

    // Clamp scroll restoration index and restore only when no pending prompt focus is requested
    LaunchedEffect(uiState.conversationId) {
        val cid = uiState.conversationId
        if (!cid.isNullOrBlank() && cid != "new" && uiState.pendingFocusStepIndex == null) {
            val saved = onLoadScrollState(cid)
            val targetIdx = saved?.first ?: uiState.initialScrollIndex
            val targetOffset = saved?.second ?: uiState.initialScrollOffset
            if (reversedItems.isNotEmpty()) {
                val safeIdx = targetIdx.coerceIn(0, (reversedItems.size - 1).coerceAtLeast(0))
                val safeOffset = targetOffset.coerceAtLeast(0)
                try {
                    listState.scrollToItem(safeIdx, safeOffset)
                } catch (_: Exception) {
                }
            }
        }
        isInitialLoad = true
    }

    // Automatic Scroll-to-Focused-Prompt
    LaunchedEffect(uiState.pendingFocusStepIndex, reversedItems.size) {
        val pendingFocus = uiState.pendingFocusStepIndex
        if (pendingFocus != null && reversedItems.isNotEmpty()) {
            val resolvedRevIdx = if (pendingFocus == -1) {
                reversedItems.indexOfFirst {
                    it is ChatItem.SingleStep && it.step.stepCase == StepCase.USER_INPUT
                }.takeIf { it >= 0 } ?: 0
            } else {
                val exactOrig = reversedItems.indexOfFirst { item ->
                    when (item) {
                        is ChatItem.SingleStep -> item.originalIndex == pendingFocus
                        is ChatItem.ThoughtGroup -> item.originalIndex == pendingFocus
                    }
                }
                val exactTranscript = if (exactOrig >= 0) {
                    exactOrig
                } else {
                    reversedItems.indexOfFirst { item ->
                        when (item) {
                            is ChatItem.SingleStep -> item.step.stepIndex == pendingFocus
                            is ChatItem.ThoughtGroup -> item.steps.any { s -> s.stepIndex == pendingFocus }
                        }
                    }
                }
                if (exactTranscript >= 0) {
                    exactTranscript
                } else {
                    // Nearest item by originalIndex
                    reversedItems.indices.minByOrNull { revIdx ->
                        val itemOrig = when (val item = reversedItems[revIdx]) {
                            is ChatItem.SingleStep -> item.originalIndex
                            is ChatItem.ThoughtGroup -> item.originalIndex
                        }
                        kotlin.math.abs(itemOrig - pendingFocus)
                    } ?: 0
                }
            }

            val matchedItem = reversedItems.getOrNull(resolvedRevIdx)
            if (matchedItem is ChatItem.SingleStep && uiState.focusedOriginalStepIndex == -1) {
                viewModel.focusStep(matchedItem.originalIndex)
            }

            val targetLazyIndex = (resolvedRevIdx + if (uiState.isGenerating) 1 else 0)
                .coerceIn(0, (totalItemsCount - 1).coerceAtLeast(0))
            try {
                listState.animateScrollToItem(targetLazyIndex)
            } catch (_: Exception) {
                try {
                    listState.scrollToItem(targetLazyIndex)
                } catch (_: Exception) {
                }
            }
            viewModel.clearPendingFocus()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                onScrollStateChange(index, offset)
            }
    }

    var unreadCount by remember { mutableStateOf(0) }
    val isScrolledUp by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10
        }
    }

    LaunchedEffect(isScrolledUp) {
        if (!isScrolledUp) {
            unreadCount = 0
        }
    }

    LaunchedEffect(uiState.steps.size) {
        if (totalItemsCount == 0) return@LaunchedEffect
        if (isInitialLoad) {
            if (uiState.steps.size >= uiState.expectedStepCount) {
                isInitialLoad = false
            }
        } else if (uiState.pendingFocusStepIndex == null) {
            if (isScrolledUp) {
                unreadCount++
            } else {
                try {
                    listState.animateScrollToItem(0)
                } catch (_: Exception) {
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(340.dp)) {
                Text(
                    "Antigravity Chats",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                ConversationBrowserPanel(
                    uiState = uiState,
                    onSearchQueryChange = { q ->
                        onSearchHistory(q)
                    },
                    onWorkspaceFilterChange = { ws ->
                        viewModel.setWorkspaceFilter(ws)
                    },
                    onRefresh = {
                        viewModel.refreshHistory()
                    },
                    onSelectConversation = { cid ->
                        onSubscribeConversation(cid)
                        scope.launch { drawerState.close() }
                    },
                    onSelectConversationAndFocusPrompt = { cid, stepIdx ->
                        viewModel.selectConversationAndFocusPrompt(cid, stepIdx)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.weight(1f)
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Disconnect") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onDisconnect()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    colors = NavigationDrawerItemDefaults.colors(
                        unselectedTextColor = MaterialTheme.colorScheme.error,
                        unselectedIconColor = MaterialTheme.colorScheme.error
                    )
                )
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.background
                    )
                )
            ),
            topBar = {
                val isNewChat = uiState.conversationId.isNullOrBlank() || uiState.conversationId == "new"
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Menu",
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Surface(
                            onClick = { viewModel.setShowConversationPickerSheet(true) },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isNewChat) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MiniTagBadge(
                                        text = "AGY",
                                        bgColor = Color(0xFF083344),
                                        textColor = Color(0xFF38BDF8)
                                    )
                                    Text(
                                        text = "Select or Start Chat ▾",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            } else {
                                val srcBadge = activeConversationSummary?.source?.ifBlank { "CLI" } ?: "CLI"
                                val wsName = activeConversationSummary?.workspace ?: ""
                                val openInAgy = activeConversationSummary?.openInTerminal == true
                                val modelLabel = formatCompactModelLabel(
                                    uiState.conversationModel.ifBlank {
                                        activeConversationSummary?.model?.ifBlank { uiState.selectedModel } ?: uiState.selectedModel
                                    }
                                )
                                val convTitle = activeConversationSummary?.title?.takeIf { it.isNotBlank() }
                                    ?: userPromptSteps.firstOrNull()?.second?.displayText?.take(60)
                                    ?: "Conversation ${uiState.conversationId.take(8)}"

                                Column(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        MiniTagBadge(
                                            text = srcBadge,
                                            bgColor = Color(0xFF312E81),
                                            textColor = Color(0xFFC7D2FE)
                                        )
                                        if (wsName.isNotBlank()) {
                                            MiniTagBadge(
                                                text = wsName,
                                                bgColor = Color(0xFF1E293B),
                                                textColor = Color(0xFF93C5FD)
                                            )
                                        }
                                        if (openInAgy) {
                                            MiniTagBadge(
                                                text = "AGY",
                                                bgColor = Color(0xFF083344),
                                                textColor = Color(0xFF22D3EE)
                                            )
                                        }
                                        MiniTagBadge(
                                            text = modelLabel,
                                            bgColor = Color(0xFF1F2937),
                                            textColor = Color(0xFFD1D5DB)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(1.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = convTitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = "▾",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (uiState.steps.isNotEmpty()) {
                            Surface(
                                onClick = { viewModel.setShowPromptsSheet(true) },
                                shape = RoundedCornerShape(10.dp),
                                color = Color(0xFF0C4A6E),
                                border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f))
                            ) {
                                Text(
                                    text = "P(${userPromptSteps.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE0F2FE),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.toggleInChatSearch(!uiState.isInChatSearchActive) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Find in conversation",
                                tint = if (uiState.isInChatSearchActive) Color(0xFF38BDF8) else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                onRequestArtifacts()
                                showArtifactsSheet = true
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Article,
                                contentDescription = "Artifacts",
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onNewConversation,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New Chat",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            bottomBar = {
                MessageInput(
                    isGenerating = uiState.isGenerating,
                    onSend = onSendPrompt,
                    onCancel = onCancel,
                    selectedImageUri = uiState.selectedImageUri,
                    onSelectImage = onSelectImage
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (uiState.connectionState != BridgeWebSocket.ConnectionState.CONNECTED) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Reconnecting...",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                        )
                    }
                }

                if (uiState.error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.error,
                            color = MaterialTheme.colorScheme.onError,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp)
                        )
                    }
                }

                // In-Chat Search & Match Focus Bar
                if (uiState.isInChatSearchActive) {
                    Surface(
                        color = Color(0xFF141824),
                        border = BorderStroke(1.dp, Color(0xFF2A324B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = uiState.inChatSearchQuery,
                                onValueChange = { viewModel.updateInChatSearchQuery(it) },
                                placeholder = { Text("Find in this conversation...", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val matchCountText = if (uiState.inChatMatches.isEmpty()) {
                                "0/0"
                            } else {
                                "${uiState.inChatCurrentMatchIdx + 1}/${uiState.inChatMatches.size}"
                            }
                            Text(
                                text = matchCountText,
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF38BDF8)
                            )
                            IconButton(
                                onClick = { viewModel.prevInChatMatch() },
                                enabled = uiState.inChatMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous Match")
                            }
                            IconButton(
                                onClick = { viewModel.nextInChatMatch() },
                                enabled = uiState.inChatMatches.isNotEmpty(),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match")
                            }
                            IconButton(
                                onClick = { viewModel.toggleInChatSearch(false) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search")
                            }
                        }
                    }
                }

                val isLandingBrowser = (uiState.conversationId == null || uiState.conversationId == "new") &&
                    uiState.steps.isEmpty() && !uiState.isGenerating

                if (isLandingBrowser) {
                    // Embedded Active & Recent Antigravity Conversation Browser on "new" screen
                    ConversationBrowserPanel(
                        uiState = uiState,
                        onSearchQueryChange = { q -> onSearchHistory(q) },
                        onWorkspaceFilterChange = { ws -> viewModel.setWorkspaceFilter(ws) },
                        onRefresh = { viewModel.refreshHistory() },
                        onSelectConversation = { cid -> onSubscribeConversation(cid) },
                        onSelectConversationAndFocusPrompt = { cid, stepIdx ->
                            viewModel.selectConversationAndFocusPrompt(cid, stepIdx)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        LaunchedEffect(listState) {
                            snapshotFlow { listState.firstVisibleItemIndex }
                                .collect { index ->
                                    val itemIdx = if (uiState.isGenerating) index - 1 else index
                                    if (itemIdx in reversedItems.indices) {
                                        when (val item = reversedItems[itemIdx]) {
                                            is ChatItem.SingleStep -> onStepVisible(item.originalIndex)
                                            is ChatItem.ThoughtGroup -> onStepVisible(item.originalIndex)
                                        }
                                    }
                                }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 16.dp),
                            reverseLayout = true
                        ) {
                            if (uiState.isGenerating) {
                                item(key = "typing_indicator") {
                                    TypingIndicator()
                                }
                            }

                            itemsIndexed(
                                items = reversedItems,
                                key = { index, item ->
                                    when (item) {
                                        is ChatItem.SingleStep -> "step_${index}_${item.originalIndex}"
                                        is ChatItem.ThoughtGroup -> "batch_${index}_${item.firstOriginalIndex}"
                                    }
                                }
                            ) { _, item ->
                                when (item) {
                                    is ChatItem.SingleStep -> {
                                        val step = item.step
                                        val isFocused = uiState.focusedOriginalStepIndex != null &&
                                            (uiState.focusedOriginalStepIndex == item.originalIndex ||
                                                (step.stepIndex >= 0 && uiState.focusedOriginalStepIndex == step.stepIndex))

                                        val stepContent: @Composable () -> Unit = {
                                            when (step.stepCase) {
                                                StepCase.USER_INPUT,
                                                StepCase.TEXT,
                                                StepCase.MARKDOWN_CHUNK,
                                                StepCase.ERROR_MESSAGE -> {
                                                    ChatBubble(
                                                        step = step,
                                                        onLinkClicked = { link ->
                                                            if (link.startsWith("file://") && link.endsWith(".md")) {
                                                                val path = link.removePrefix("file://")
                                                                onRequestArtifactContent(path)
                                                            }
                                                        }
                                                    )
                                                }
                                                StepCase.TOOL_CALL -> {
                                                    ToolCallCard(
                                                        step = step,
                                                        onAcceptEdits = onAcceptEdits,
                                                        onRejectEdits = onRejectEdits
                                                    )
                                                }
                                                else -> {}
                                            }
                                        }

                                        if (isFocused) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                                    .border(1.5.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
                                                    .background(Color(0xFF0C4A6E).copy(alpha = 0.20f), RoundedCornerShape(12.dp))
                                                    .padding(vertical = 4.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 12.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "FOCUSED PROMPT · #${item.originalIndex}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF38BDF8)
                                                    )
                                                    Text(
                                                        text = "Clear",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF93C5FD),
                                                        fontWeight = FontWeight.SemiBold,
                                                        modifier = Modifier
                                                            .clickable { viewModel.clearFocusHighlight() }
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                stepContent()
                                            }
                                        } else {
                                            stepContent()
                                        }
                                    }
                                    is ChatItem.ThoughtGroup -> {
                                        val isLatest = uiState.isGenerating && item === reversedItems.first()
                                        ThoughtGroupBubble(
                                            steps = item.steps,
                                            isLatest = isLatest,
                                            isCompleted = item.isCompleted,
                                            onAcceptEdits = onAcceptEdits,
                                            onRejectEdits = onRejectEdits
                                        )
                                    }
                                }
                            }
                        }

                        if (isScrolledUp) {
                            SmallFloatingActionButton(
                                onClick = {
                                    scope.launch {
                                        if (listState.firstVisibleItemIndex > 50) {
                                            listState.scrollToItem(50)
                                        }
                                        listState.animateScrollToItem(0)
                                    }
                                },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp),
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                if (unreadCount > 0) {
                                    BadgedBox(
                                        badge = {
                                            Badge {
                                                Text(unreadCount.toString())
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                                    }
                                } else {
                                    Icon(Icons.Default.ArrowDownward, contentDescription = "Scroll to bottom")
                                }
                            }
                        }
                    }
                }
            }

            // "Prompts (N)" Quick-Jump ModalBottomSheet
            if (uiState.showPromptsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.setShowPromptsSheet(false) },
                    sheetState = promptsSheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    var promptFilter by remember { mutableStateOf("") }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.75f)
                            .padding(bottom = 24.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Prompts in Conversation (${userPromptSteps.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (userPromptSteps.isNotEmpty()) {
                                Surface(
                                    onClick = {
                                        viewModel.focusStep(userPromptSteps.last().first)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0C4A6E),
                                    border = BorderStroke(1.dp, Color(0xFF38BDF8))
                                ) {
                                    Text(
                                        text = "Jump to Latest",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFE0F2FE),
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = promptFilter,
                            onValueChange = { promptFilter = it },
                            placeholder = { Text("Filter prompts in this chat...") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        val indexedPromptsNewestFirst = remember(userPromptSteps, promptFilter) {
                            val numbered = userPromptSteps.mapIndexed { turnIdx, (origIdx, step) ->
                                Triple(turnIdx + 1, origIdx, step)
                            }.reversed()
                            if (promptFilter.isBlank()) {
                                numbered
                            } else {
                                val q = promptFilter.trim().lowercase()
                                numbered.filter { (_, _, step) -> step.displayText.lowercase().contains(q) }
                            }
                        }

                        if (indexedPromptsNewestFirst.isEmpty()) {
                            Text(
                                text = "No matching user prompts found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = indexedPromptsNewestFirst,
                                    key = { idx, (_, origIdx, _) -> "prompt_jump_${idx}_$origIdx" }
                                ) { _, (turnNumber, origIdx, step) ->
                                    Surface(
                                        onClick = { viewModel.focusStep(origIdx) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = Color(0xFF181B26),
                                        border = BorderStroke(
                                            1.dp,
                                            if (uiState.focusedOriginalStepIndex == origIdx) Color(0xFF38BDF8) else Color(0xFF2D3348)
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "Prompt #$turnNumber · Step #$origIdx",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF38BDF8),
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Tap to focus ➔",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = step.displayText,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = Color.White,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Full Conversation Switcher & Prompt Search ModalBottomSheet
            if (uiState.showConversationPickerSheet) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.setShowConversationPickerSheet(false) },
                    sheetState = pickerSheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.88f)
                            .padding(bottom = 16.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Switch Antigravity Conversation",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(onClick = {
                                viewModel.setShowConversationPickerSheet(false)
                                onNewConversation()
                            }) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("New Chat")
                            }
                        }
                        ConversationBrowserPanel(
                            uiState = uiState,
                            onSearchQueryChange = { q -> onSearchHistory(q) },
                            onWorkspaceFilterChange = { ws -> viewModel.setWorkspaceFilter(ws) },
                            onRefresh = { viewModel.refreshHistory() },
                            onSelectConversation = { cid ->
                                viewModel.setShowConversationPickerSheet(false)
                                onSubscribeConversation(cid)
                            },
                            onSelectConversationAndFocusPrompt = { cid, stepIdx ->
                                viewModel.selectConversationAndFocusPrompt(cid, stepIdx)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (showArtifactsSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showArtifactsSheet = false },
                    sheetState = artifactsSheetState,
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 32.dp)
                    ) {
                        Text(
                            text = "Artifacts",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

                        if (uiState.artifacts.isEmpty()) {
                            Text(
                                text = "No artifacts found.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        } else {
                            LazyColumn {
                                items(uiState.artifacts) { artifact ->
                                    ListItem(
                                        headlineContent = { Text(artifact.name) },
                                        leadingContent = {
                                            Icon(
                                                Icons.AutoMirrored.Filled.Article,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onRequestArtifactContent(artifact.path)
                                                scope
                                                    .launch { artifactsSheetState.hide() }
                                                    .invokeOnCompletion {
                                                        if (!artifactsSheetState.isVisible) {
                                                            showArtifactsSheet = false
                                                        }
                                                    }
                                            }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (uiState.activeArtifactContent != null) {
                Dialog(
                    onDismissRequest = onClearArtifactContent,
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { Text("Artifact Viewer") },
                                navigationIcon = {
                                    IconButton(onClick = onClearArtifactContent) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                },
                                actions = {
                                    val clipboardManager = LocalClipboardManager.current
                                    val context = LocalContext.current
                                    IconButton(onClick = {
                                        clipboardManager.setText(AnnotatedString(uiState.activeArtifactContent))
                                        android.widget.Toast.makeText(
                                            context,
                                            "Copied artifact to clipboard",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.ContentCopy,
                                            contentDescription = "Copy artifact content"
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                                )
                            )
                        }
                    ) { innerPadding ->
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp)
                            ) {
                                val scroll = rememberScrollState()
                                MarkdownText(
                                    markdown = uiState.activeArtifactContent,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(scroll)
                                        .padding(vertical = 16.dp),
                                    textColor = MaterialTheme.colorScheme.onSurface.toArgb(),
                                    onLinkClicked = {}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniTagBadge(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
fun ConversationBrowserPanel(
    uiState: ChatUiState,
    onSearchQueryChange: (String) -> Unit,
    onWorkspaceFilterChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onSelectConversationAndFocusPrompt: (String, Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    val displayConversations = remember(
        uiState.searchQuery,
        uiState.selectedWorkspaceFilter,
        uiState.filteredHistory,
        uiState.searchResults,
        uiState.history
    ) {
        if (uiState.searchQuery.isNotBlank() || uiState.selectedWorkspaceFilter != "ALL") {
            uiState.filteredHistory
        } else {
            uiState.filteredHistory.ifEmpty { uiState.searchResults ?: uiState.history }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search chats, workspaces, or prompt text...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh Conversations")
            }
        }

        // Workspace & status filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = uiState.selectedWorkspaceFilter == "ALL",
                onClick = { onWorkspaceFilterChange("ALL") },
                label = { Text("All (${uiState.history.size})") }
            )
            val openCount = uiState.history.count { it.openInTerminal || it.active }
            FilterChip(
                selected = uiState.selectedWorkspaceFilter == "OPEN_AGY",
                onClick = { onWorkspaceFilterChange("OPEN_AGY") },
                label = { Text("Open in agy ($openCount)") }
            )
            uiState.availableWorkspaces.forEach { ws ->
                FilterChip(
                    selected = uiState.selectedWorkspaceFilter.equals(ws, ignoreCase = true),
                    onClick = { onWorkspaceFilterChange(ws) },
                    label = { Text(ws) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        if (displayConversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (uiState.searchQuery.isNotBlank()) {
                        "No conversations or prompts matched \"${uiState.searchQuery}\"."
                    } else {
                        "No conversations found. Tap Refresh or send a message below to start."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(
                    items = displayConversations,
                    key = { idx, conv -> "conv_card_${idx}_${conv.id}" }
                ) { _, conv ->
                    ConversationRichCard(
                        conv = conv,
                        isSelected = conv.id == uiState.conversationId,
                        onOpenConversation = { onSelectConversation(conv.id) },
                        onFocusPrompt = { stepIdx ->
                            onSelectConversationAndFocusPrompt(conv.id, stepIdx)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ConversationRichCard(
    conv: ConversationSummary,
    isSelected: Boolean,
    onOpenConversation: () -> Unit,
    onFocusPrompt: (Int) -> Unit
) {
    val borderColor = when {
        isSelected -> Color(0xFF38BDF8)
        conv.openInTerminal -> Color(0xFF06B6D4).copy(alpha = 0.65f)
        else -> Color(0xFF2A2E3D)
    }
    val containerColor = when {
        isSelected -> Color(0xFF162238)
        conv.openInTerminal -> Color(0xFF111C2D)
        else -> Color(0xFF151722)
    }

    Surface(
        onClick = onOpenConversation,
        shape = RoundedCornerShape(12.dp),
        color = containerColor,
        border = BorderStroke(if (isSelected || conv.openInTerminal) 1.5.dp else 1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                if (conv.openInTerminal) {
                    val ptsSuffix = if (conv.terminalPts.isNotBlank()) " · ${conv.terminalPts}" else ""
                    MiniTagBadge(
                        text = "OPEN IN AGY$ptsSuffix",
                        bgColor = Color(0xFF083344),
                        textColor = Color(0xFF22D3EE)
                    )
                }
                if (conv.active) {
                    MiniTagBadge(
                        text = "RUNNING",
                        bgColor = Color(0xFF064E3B),
                        textColor = Color(0xFF34D399)
                    )
                }
                MiniTagBadge(
                    text = conv.source.ifBlank { "CLI" },
                    bgColor = Color(0xFF312E81),
                    textColor = Color(0xFFC7D2FE)
                )
                if (conv.workspace.isNotBlank()) {
                    MiniTagBadge(
                        text = conv.workspace,
                        bgColor = Color(0xFF1E293B),
                        textColor = Color(0xFF93C5FD)
                    )
                }
                if (conv.model.isNotBlank()) {
                    MiniTagBadge(
                        text = formatCompactModelLabel(conv.model),
                        bgColor = Color(0xFF1F2937),
                        textColor = Color(0xFFD1D5DB)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                val relTime = formatRelativeTimestamp(conv.timestamp)
                if (relTime.isNotBlank()) {
                    Text(
                        text = relTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title (bold, up to 2 lines)
            Text(
                text = conv.title.ifBlank { "Conversation ${conv.id.take(8)}" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Latest Prompt Preview
            if (conv.lastPrompt.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F131D),
                    border = BorderStroke(1.dp, Color(0xFF23293A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Latest prompt: ${conv.lastPrompt}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD1D5DB),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            // Matched Prompt / Focus Action Row
            val hasMatchedPrompt = conv.matchedPrompt.isNotBlank()
            val hasLatestPromptFocus = conv.lastPromptStepIndex >= 0 || conv.lastPrompt.isNotBlank() || conv.stepCount > 0

            if (hasMatchedPrompt || hasLatestPromptFocus) {
                Spacer(modifier = Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasMatchedPrompt) {
                        val stepLabel = if (conv.matchedStepIndex >= 0) "#${conv.matchedStepIndex}" else "Latest"
                        Surface(
                            onClick = {
                                onFocusPrompt(if (conv.matchedStepIndex >= 0) conv.matchedStepIndex else -1)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF083344),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🎯 Matched Prompt ($stepLabel): ${conv.matchedPrompt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE0F2FE),
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (conv.stepCount > 0) {
                            Text(
                                text = "${conv.stepCount} steps · ${conv.userPrompts.size.coerceAtLeast(if (conv.lastPrompt.isNotBlank()) 1 else 0)} prompts",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Surface(
                            onClick = {
                                val targetIdx = if (conv.lastPromptStepIndex >= 0) conv.lastPromptStepIndex else -1
                                onFocusPrompt(targetIdx)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFF1E293B),
                            border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "Focus Latest Prompt ➔",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF7DD3FC),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
