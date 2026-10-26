/*
 * FilesViewModel.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.network.FileNode
import dev.saifmukhtar.antimatter.core.network.InboundMessage
import dev.saifmukhtar.antimatter.core.network.OutboundMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

data class FilesUiState(
    val fileTree: List<FileNode>? = null,
    val isLoadingTree: Boolean = false,
    val viewedFilePath: String? = null,
    val viewedFileContent: String? = null,
    val viewedFileLanguage: String? = null,
    val isLoadingFile: Boolean = false,
    val allowedWorkspaces: List<String> = emptyList(),
    val currentWorkspace: String? = null,
    val activeConversationId: String? = null,
    val workspaceAvailable: Boolean = false,
    val unavailableReason: String? = "No active session selected. Select a session in Chat to browse its workspace."
)

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val webSocket: BridgeWebSocket
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesUiState())
    val uiState: StateFlow<FilesUiState> = _uiState.asStateFlow()

    private var baseUrl: String = "http://127.0.0.1:3883"
    private var token: String = ""
    private var pendingExpandSubpath: String? = null

    fun configureEndpoint(url: String, authToken: String = "") {
        baseUrl = url.trimEnd('/')
        token = authToken
        webSocket.configureEndpoint(baseUrl, authToken)
        val activeCid = _uiState.value.activeConversationId
        if (!activeCid.isNullOrBlank() && activeCid != "new") {
            loadFileTree(null)
        }
    }

    fun fetchTree(path: String? = null) {
        loadFileTree(path)
    }

    init {
        // Only fetch tree automatically if an active session is already selected
        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.connectionState.collect { state ->
                if (state == BridgeWebSocket.ConnectionState.CONNECTED) {
                    webSocket.sendMessage(OutboundMessage.ListAgents())
                    val activeCid = _uiState.value.activeConversationId
                    if (!activeCid.isNullOrBlank() && activeCid != "new" && _uiState.value.fileTree == null) {
                        loadFileTree(null)
                    }
                }
            }
        }

        viewModelScope.launch(Dispatchers.Main.immediate) {
            webSocket.messages.collect { message ->
                when (message) {
                    is InboundMessage.FileTree -> {
                        android.util.Log.d("FilesViewModel", "Received FileTree with ${message.tree.size} items, workspace=${message.workspace}, available=${message.workspaceAvailable}")
                        val available = message.workspaceAvailable && message.workspace.isNotBlank()
                        if (!available) {
                            pendingExpandSubpath = null
                            _uiState.update {
                                it.copy(
                                    fileTree = emptyList(),
                                    isLoadingTree = false,
                                    currentWorkspace = null,
                                    workspaceAvailable = false,
                                    unavailableReason = message.reason.ifBlank { "Workspace isn't available for the selected session." }
                                )
                            }
                        } else {
                            val subpath = pendingExpandSubpath
                            if (subpath != null && (message.path == subpath || message.path.trim('/') == subpath.trim('/')) && _uiState.value.fileTree != null) {
                                pendingExpandSubpath = null
                                _uiState.update {
                                    it.copy(
                                        fileTree = spliceChildren(it.fileTree ?: emptyList(), subpath, message.tree),
                                        isLoadingTree = false,
                                        workspaceAvailable = true,
                                        unavailableReason = null
                                    )
                                }
                            } else {
                                pendingExpandSubpath = null
                                _uiState.update {
                                    it.copy(
                                        fileTree = message.tree,
                                        isLoadingTree = false,
                                        currentWorkspace = message.workspace,
                                        workspaceAvailable = true,
                                        unavailableReason = null
                                    )
                                }
                            }
                        }
                    }
                    is InboundMessage.FileContent -> {
                        _uiState.update { 
                            it.copy(
                                viewedFileContent = message.content, 
                                viewedFileLanguage = message.language,
                                isLoadingFile = false
                            )
                        }
                    }
                    is InboundMessage.Error -> {
                        pendingExpandSubpath = null
                        _uiState.update { it.copy(isLoadingTree = false, isLoadingFile = false) }
                    }
                    is InboundMessage.AvailableAgents -> {
                        _uiState.update {
                            it.copy(
                                allowedWorkspaces = message.allowedWorkspaces
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    private fun spliceChildren(nodes: List<FileNode>, targetPath: String, children: List<FileNode>): List<FileNode> {
        val normalizedTarget = targetPath.trim('/')
        return nodes.map { node ->
            val nodeChildren = node.children
            if (node.path.trim('/') == normalizedTarget) {
                node.copy(children = children)
            } else if (nodeChildren != null) {
                node.copy(children = spliceChildren(nodeChildren, targetPath, children))
            } else {
                node
            }
        }
    }

    fun syncWithSelectedSession(conversationId: String?, workspacePath: String? = null, forceRefresh: Boolean = false) {
        if (conversationId.isNullOrBlank() || conversationId == "new") {
            pendingExpandSubpath = null
            _uiState.update {
                it.copy(
                    activeConversationId = conversationId,
                    currentWorkspace = null,
                    fileTree = emptyList(),
                    isLoadingTree = false,
                    workspaceAvailable = false,
                    unavailableReason = "No active session selected. Select a session in Chat to browse its workspace."
                )
            }
            return
        }

        if (!forceRefresh &&
            _uiState.value.activeConversationId == conversationId &&
            _uiState.value.fileTree != null &&
            _uiState.value.workspaceAvailable &&
            (workspacePath.isNullOrBlank() || _uiState.value.currentWorkspace == workspacePath)
        ) {
            return
        }

        val resolvedWorkspace = workspacePath?.takeIf { it.isNotBlank() }
        _uiState.update {
            it.copy(
                activeConversationId = conversationId,
                currentWorkspace = resolvedWorkspace,
                isLoadingTree = true,
                unavailableReason = null
            )
        }
        pendingExpandSubpath = null
        webSocket.sendMessage(
            OutboundMessage.GetFiles(
                path = null,
                conversationId = conversationId,
                workspace = resolvedWorkspace
            )
        )
    }

    fun expandDirectory(dirPath: String) {
        pendingExpandSubpath = dirPath
        webSocket.sendMessage(
            OutboundMessage.GetFiles(
                path = dirPath,
                conversationId = _uiState.value.activeConversationId?.takeIf { it != "new" },
                workspace = _uiState.value.currentWorkspace
            )
        )
    }

    fun loadFileTree(path: String? = null) {
        android.util.Log.d("FilesViewModel", "loadFileTree called for path: $path")
        pendingExpandSubpath = null
        _uiState.update { it.copy(isLoadingTree = true) }
        webSocket.sendMessage(
            OutboundMessage.GetFiles(
                path = path,
                conversationId = _uiState.value.activeConversationId?.takeIf { it != "new" },
                workspace = _uiState.value.currentWorkspace
            )
        )
    }

    fun openFile(path: String) {
        _uiState.update { it.copy(viewedFilePath = path, isLoadingFile = true, viewedFileContent = null) }
        webSocket.sendMessage(
            OutboundMessage.ReadFile(
                path = path,
                conversationId = _uiState.value.activeConversationId?.takeIf { it != "new" },
                workspace = _uiState.value.currentWorkspace
            )
        )
    }

    fun closeFile() {
        _uiState.update { it.copy(viewedFilePath = null, viewedFileContent = null, viewedFileLanguage = null) }
    }

    fun writeFile(path: String, content: String) {
        webSocket.sendMessage(
            OutboundMessage.WriteFile(
                path = path,
                content = content,
                conversationId = _uiState.value.activeConversationId?.takeIf { it != "new" },
                workspace = _uiState.value.currentWorkspace
            )
        )
        // Optimistically update viewed content
        _uiState.update { it.copy(viewedFileContent = content) }
    }

    fun changeWorkspace(path: String) {
        webSocket.sendMessage(OutboundMessage.ChangeWorkspace(path))
        _uiState.update { it.copy(currentWorkspace = path, isLoadingTree = true) }
    }
}
