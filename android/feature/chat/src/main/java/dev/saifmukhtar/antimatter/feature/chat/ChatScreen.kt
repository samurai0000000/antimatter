package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.feature.chat.ChatUiState
import dev.saifmukhtar.antimatter.core.ui.MarkdownText

sealed class ChatItem {
    data class SingleStep(val step: TrajectoryStep, val originalIndex: Int) : ChatItem()
    data class ThoughtGroup(val steps: List<TrajectoryStep>, val originalIndex: Int) : ChatItem()
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
            // A header is orphaned if it's the last item, or if the NEXT item is also a header
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
    val visibleSteps = steps.filter {
        it.stepCase in listOf(
            StepCase.USER_INPUT,
            StepCase.TEXT,
            StepCase.MARKDOWN_CHUNK,
            StepCase.TOOL_CALL,
            StepCase.PLANNER_RESPONSE
        )
    }

    val result = mutableListOf<ChatItem>()
    var currentGroup = mutableListOf<TrajectoryStep>()
    var groupStartIndex = 0

    for (i in visibleSteps.indices) {
        val step = visibleSteps[i]
        if (step.stepCase == StepCase.PLANNER_RESPONSE) {
            if (currentGroup.isEmpty()) groupStartIndex = i
            val cleanedText = cleanThoughtText(step.value)
            if (!cleanedText.isNullOrBlank()) {
                currentGroup.add(step.copy(value = cleanedText))
            }
        } else {
            if (currentGroup.isNotEmpty()) {
                result.add(ChatItem.ThoughtGroup(currentGroup, groupStartIndex))
                currentGroup = mutableListOf<TrajectoryStep>()
            }
            result.add(ChatItem.SingleStep(step, i))
        }
    }
    if (currentGroup.isNotEmpty()) {
        result.add(ChatItem.ThoughtGroup(currentGroup, groupStartIndex))
    }
    return result
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
    onStepVisible: (Int) -> Unit
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var isInitialLoad by remember { mutableStateOf(true) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showArtifactsSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    // Reset initial load flag and load scroll state when conversation changes
    LaunchedEffect(uiState.conversationId) {
        uiState.conversationId?.let { cid ->
            onLoadScrollState(cid)?.let { (index, offset) ->
                listState.scrollToItem(index, offset)
            }
        }
        isInitialLoad = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .collect { (index, offset) ->
                onScrollStateChange(index, offset)
            }
    }

    val groupedItems = remember(uiState.steps) { groupSteps(uiState.steps) }
    val totalItemsCount = groupedItems.size + if (uiState.isGenerating) 1 else 0

    var unreadCount by remember { mutableStateOf(0) }
    val isScrolledUp by remember { 
        derivedStateOf { 
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10 
        } 
    }

    // Reset unread count when user scrolls to bottom
    LaunchedEffect(isScrolledUp) {
        if (!isScrolledUp) {
            unreadCount = 0
        }
    }

    // Auto-scroll logic or unread badge increment
    LaunchedEffect(uiState.steps.size) {
        if (totalItemsCount == 0) return@LaunchedEffect
        if (isInitialLoad) {
            // We wait until the initial load is complete before enabling the auto-scroll badge logic.
            // We removed the forced listState.scrollToItem(0) here because it was overwriting the saved scroll state!
            if (uiState.steps.size >= uiState.expectedStepCount) {
                isInitialLoad = false
            }
        } else {
            if (isScrolledUp) {
                // If user scrolled up and a new step arrived, increment badge
                unreadCount++
            } else {
                // If already at bottom, just keep it there (reverseLayout does this automatically,
                // but just in case, we animate to 0)
                listState.animateScrollToItem(0)
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Text(
                    "Chat History",
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchHistory,
                    placeholder = { Text("Search offline history...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    val displayList = uiState.searchResults ?: uiState.history
                    items(displayList) { conversation ->
                        NavigationDrawerItem(
                            label = { Text(conversation.title, maxLines = 1) },
                            selected = conversation.id == uiState.conversationId,
                            onClick = {
                                onSubscribeConversation(conversation.id)
                                scope.launch { drawerState.close() }
                                onSearchHistory("") // Clear search
                            },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        )
                    }
                }
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
                var expanded by remember { mutableStateOf(false) }
                TopAppBar(
                    title = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val currentAgent = uiState.availableAgents.find { it.id == uiState.activeAgentId }
                            Surface(
                                onClick = { expanded = true },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Agent selector",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = currentAgent?.name ?: "Google Antigravity",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(
                                                    color = androidx.compose.ui.graphics.Color(0xFF10B981),
                                                    shape = androidx.compose.foundation.shape.CircleShape
                                                )
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.ArrowDownward,
                                            contentDescription = "Switch Agent",
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                uiState.availableAgents.forEach { agent ->
                                    DropdownMenuItem(
                                        text = { 
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(
                                                            color = if (agent.status == "online") androidx.compose.ui.graphics.Color.Green else androidx.compose.ui.graphics.Color.Red,
                                                            shape = androidx.compose.foundation.shape.CircleShape
                                                        )
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(agent.name)
                                            }
                                        },
                                        onClick = {
                                            onSwitchAgent(agent.id)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            onRequestArtifacts()
                            showArtifactsSheet = true
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "Artifacts")
                        }
                        IconButton(onClick = onNewConversation) {
                            Icon(Icons.Default.Add, contentDescription = "New Chat")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                    )
                )
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
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                LaunchedEffect(listState) {
                    snapshotFlow { listState.firstVisibleItemIndex }
                        .collect { index ->
                            // Calculate step index from reversed grouped list index
                            val reversedItems = groupedItems.reversed()
                            if (index in reversedItems.indices) {
                                val item = reversedItems[index]
                                if (item is ChatItem.SingleStep) {
                                    onStepVisible(uiState.steps.indexOf(item.step))
                                } else if (item is ChatItem.ThoughtGroup) {
                                    item.steps.firstOrNull()?.let {
                                        onStepVisible(uiState.steps.indexOf(it))
                                    }
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
                    val reversedItems = groupedItems.reversed()
                    itemsIndexed(
                        items = reversedItems,
                        key = { index, item ->
                            when (item) {
                                is ChatItem.SingleStep -> "step_${item.originalIndex}_${index}_${item.step.displayText.hashCode()}"
                                is ChatItem.ThoughtGroup -> "thought_group_${item.originalIndex}_${index}"
                            }
                        }
                    ) { _, item ->
                        when (item) {
                            is ChatItem.SingleStep -> {
                                val step = item.step
                                when (step.stepCase) {
                                    StepCase.USER_INPUT, StepCase.TEXT, StepCase.MARKDOWN_CHUNK -> {
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
                            is ChatItem.ThoughtGroup -> {
                                val isLatest = uiState.isGenerating && item === reversedItems.first()
                                ThoughtGroupBubble(steps = item.steps, isLatest = isLatest)
                            }
                        }
                    }
                    
                    if (uiState.isGenerating) {
                        item {
                            TypingIndicator()
                        }
                    }
                } // End of LazyColumn
                
                // FAB for scroll to bottom
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
            } // End of Box
        } // End of Column
        
        if (showArtifactsSheet) {
            ModalBottomSheet(
                onDismissRequest = { showArtifactsSheet = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surface,
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
                                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                                if (!sheetState.isVisible) {
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
                                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                                val context = androidx.compose.ui.platform.LocalContext.current
                                IconButton(onClick = {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(uiState.activeArtifactContent))
                                    android.widget.Toast.makeText(context, "Copied artifact to clipboard", android.widget.Toast.LENGTH_SHORT).show()
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
                        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                            val scroll = rememberScrollState()
                            MarkdownText(
                                markdown = uiState.activeArtifactContent,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scroll)
                                    .padding(vertical = 16.dp),
                                textColor = MaterialTheme.colorScheme.onSurface.toArgb(),
                                onLinkClicked = { link ->
                                    // Handle internal artifact links if needed
                                }
                            )
                        }
                    }
                }
            }
        }
        
    } // End of Scaffold
} // End of ChatScreen
}
