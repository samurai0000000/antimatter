package dev.saifmukhtar.antimatter.feature.files

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saifmukhtar.antimatter.core.ui.MarkdownText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewScreen(
    uiState: FilesUiState,
    onBack: () -> Unit,
    onSave: (path: String, content: String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var editedContent by remember(uiState.viewedFileContent) { mutableStateOf(uiState.viewedFileContent ?: "") }

    val darkBg = Color(0xFF0D1117)
    val cardBg = Color(0xFF141419)
    val borderColor = Color(0xFF2A2B32)
    val gutterColor = Color(0xFF64748B)
    val codeTextColor = Color(0xFFE2E8F0)
    val cyanAccent = Color(0xFF00A8E8)

    Scaffold(
        containerColor = darkBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.viewedFilePath?.substringAfterLast('/') ?: "File",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1
                        )
                        uiState.viewedFilePath?.let { fullPath ->
                            Text(
                                text = fullPath,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = Color(0xFF94A3B8),
                                maxLines = 1
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    val context = androidx.compose.ui.platform.LocalContext.current
                    if (uiState.viewedFileContent != null) {
                        IconButton(onClick = {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(uiState.viewedFileContent))
                            android.widget.Toast.makeText(context, "Copied file content", android.widget.Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "Copy file",
                                tint = Color(0xFF94A3B8)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F0F14)
                )
            )
        },
        floatingActionButton = {
            if (uiState.viewedFileContent != null && !uiState.isLoadingFile) {
                FloatingActionButton(
                    onClick = {
                        if (isEditing) {
                            showSaveDialog = true
                        } else {
                            isEditing = true
                        }
                    },
                    shape = CircleShape,
                    containerColor = cyanAccent,
                    contentColor = Color.White,
                    elevation = FloatingActionButtonDefaults.elevation(8.dp)
                ) {
                    Icon(
                        imageVector = if (isEditing) Icons.Default.Save else Icons.Default.Edit,
                        contentDescription = if (isEditing) "Save" else "Edit",
                        tint = Color.White
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(darkBg)
        ) {
            if (showSaveDialog) {
                AlertDialog(
                    onDismissRequest = { showSaveDialog = false },
                    title = { Text("Confirm Save", color = Color.White) },
                    text = { Text("Are you sure you want to overwrite this file? This cannot be undone.", color = Color(0xFF94A3B8)) },
                    containerColor = cardBg,
                    confirmButton = {
                        TextButton(onClick = {
                            uiState.viewedFilePath?.let { path ->
                                onSave(path, editedContent)
                            }
                            isEditing = false
                            showSaveDialog = false
                        }) {
                            Text("Overwrite", color = cyanAccent, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSaveDialog = false }) {
                            Text("Cancel", color = Color(0xFF94A3B8))
                        }
                    }
                )
            }
            if (uiState.isLoadingFile) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = cyanAccent
                )
            } else if (uiState.viewedFileContent != null) {
                val isMarkdown = uiState.viewedFileLanguage == "markdown" || uiState.viewedFilePath?.endsWith(".md") == true

                if (isEditing) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = cardBg
                    ) {
                        TextField(
                            value = editedContent,
                            onValueChange = { editedContent = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                color = codeTextColor
                            ),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            )
                        )
                    }
                } else if (isMarkdown) {
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = cardBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            MarkdownText(
                                markdown = uiState.viewedFileContent,
                                textColor = android.graphics.Color.WHITE
                            )
                        }
                    }
                } else {
                    val lines = remember(uiState.viewedFileContent) {
                        uiState.viewedFileContent.lines()
                    }
                    val vScrollState = rememberScrollState()
                    val hScrollState = rememberScrollState()

                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = cardBg
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(vScrollState)
                        ) {
                            // Line numbers gutter
                            Column(
                                modifier = Modifier
                                    .background(Color(0xFF0F0F14))
                                    .padding(horizontal = 10.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.End
                            ) {
                                lines.indices.forEach { idx ->
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 18.sp,
                                            color = gutterColor
                                        )
                                    )
                                }
                            }

                            // Gutter separator line
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .fillMaxHeight()
                                    .background(borderColor)
                            )

                            // Code content
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(hScrollState)
                                    .padding(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Column {
                                    lines.forEach { line ->
                                        Text(
                                            text = if (line.isEmpty()) " " else line,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                                lineHeight = 18.sp,
                                                color = codeTextColor
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "Failed to load file.",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFEF4444)
                )
            }
        }
    }
}
