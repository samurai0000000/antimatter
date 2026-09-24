package dev.saifmukhtar.antimatter.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.saifmukhtar.antimatter.core.network.FileNode
import dev.saifmukhtar.antimatter.feature.files.FilesUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    uiState: FilesUiState,
    onRefresh: () -> Unit,
    onOpenFile: (String) -> Unit,
    onChangeWorkspace: (String) -> Unit
) {
    var expandedFolders by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        if (uiState.fileTree == null && !uiState.isLoadingTree) {
            onRefresh()
        }
    }

    Scaffold(
        topBar = {
            var workspaceDropdownExpanded by remember { mutableStateOf(false) }

            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Workspace", fontWeight = FontWeight.Bold, color = Color.White)

                        Spacer(modifier = Modifier.weight(1f))

                        Box {
                            Surface(
                                onClick = {
                                    if (uiState.allowedWorkspaces.size > 1) {
                                        workspaceDropdownExpanded = true
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = Color(0xFF1E3A40),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = Color(0xFF00CEC9),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = uiState.currentWorkspace
                                            ?.substringAfterLast("/")
                                            ?.ifBlank { uiState.currentWorkspace }
                                            ?: "aimon",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        maxLines = 1
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ArrowDropDown,
                                        contentDescription = "Switch workspace",
                                        tint = Color(0xFF00CEC9),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = workspaceDropdownExpanded,
                                onDismissRequest = { workspaceDropdownExpanded = false }
                            ) {
                                uiState.allowedWorkspaces.forEach { ws ->
                                    val isActive = ws == uiState.currentWorkspace
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isActive) Icons.Default.FolderOpen else Icons.Default.Folder,
                                                    contentDescription = null,
                                                    tint = if (isActive) Color(0xFF00CEC9) else Color.White,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Text(
                                                    text = ws.substringAfterLast("/").ifBlank { ws },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isActive) Color(0xFF00CEC9) else Color.White
                                                )
                                            }
                                        },
                                        onClick = {
                                            workspaceDropdownExpanded = false
                                            onChangeWorkspace(ws)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onRefresh,
                containerColor = Color(0xFF1E3A40),
                contentColor = Color.White,
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(24.dp))
            }
        },
        containerColor = Color(0xFF0F1015)
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoadingTree) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (uiState.fileTree.isNullOrEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color(0xFF6B7280)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No files found",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF9CA3AF)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 16.dp, end = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(uiState.fileTree) { node ->
                        FileTreeNode(
                            node = node,
                            depth = 0,
                            expandedFolders = expandedFolders,
                            onToggleFolder = { path ->
                                expandedFolders = if (expandedFolders.contains(path)) {
                                    expandedFolders - path
                                } else {
                                    expandedFolders + path
                                }
                            },
                            onOpenFile = onOpenFile
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileTreeNode(
    node: FileNode,
    depth: Int,
    expandedFolders: Set<String>,
    onToggleFolder: (String) -> Unit,
    onOpenFile: (String) -> Unit
) {
    val isExpanded = expandedFolders.contains(node.path)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (node.isDir && isExpanded) Color(0xFF1E2630) else Color.Transparent
                )
                .clickable {
                    if (node.isDir) {
                        onToggleFolder(node.path)
                    } else {
                        onOpenFile(node.path)
                    }
                }
                .padding(vertical = 10.dp, horizontal = 12.dp)
                .padding(start = (depth * 20).dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (node.isDir) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = if (isExpanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(20.dp)
                )
            } else {
                val (icon, tint) = getIconForNode(node)
                Spacer(modifier = Modifier.width(26.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (node.isDir) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White
            )
        }

        val children = node.children
        if (node.isDir && isExpanded && children != null) {
            children.forEach { child ->
                FileTreeNode(
                    node = child,
                    depth = depth + 1,
                    expandedFolders = expandedFolders,
                    onToggleFolder = onToggleFolder,
                    onOpenFile = onOpenFile
                )
            }
        }
    }
}

@Composable
private fun getIconForNode(node: FileNode): Pair<ImageVector, Color> {
    if (node.isDir) {
        return Pair(Icons.Default.Folder, Color(0xFF38BDF8))
    }
    
    val nameLower = node.name.lowercase()
    val ext = node.name.substringAfterLast('.', "").lowercase()
    return when {
        ext in listOf("cxx", "cpp", "c", "hxx", "hpp", "h") -> Pair(Icons.Default.Code, Color(0xFF38BDF8))
        nameLower == "makefile" -> Pair(Icons.Default.Build, Color(0xFF94A3B8))
        nameLower.startsWith("cmakelists") -> Pair(Icons.Default.Settings, Color(0xFF94A3B8))
        ext in listOf("md", "txt") -> Pair(Icons.AutoMirrored.Filled.Article, Color(0xFF94A3B8))
        ext in listOf("kt", "java") -> Pair(Icons.Default.Code, Color(0xFFA855F7))
        ext in listOf("json", "xml", "yaml", "yml") -> Pair(Icons.Default.DataObject, Color(0xFFF59E0B))
        else -> Pair(Icons.Default.Description, Color(0xFF94A3B8))
    }
}
