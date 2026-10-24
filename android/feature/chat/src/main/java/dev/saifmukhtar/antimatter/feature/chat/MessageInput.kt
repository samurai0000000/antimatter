/*
 * MessageInput.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SlashCommandSpec(
    val command: String,
    val description: String,
    val executeImmediatelyOnTap: Boolean
)

val slashCommandCatalog = listOf(
    SlashCommandSpec("/clear", "Clear active chat & start a fresh conversation", executeImmediatelyOnTap = true),
    SlashCommandSpec("/model", "Switch the AI model for this session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/compact", "Compact & summarize conversation history to free context", executeImmediatelyOnTap = true),
    SlashCommandSpec("/help", "Show available slash commands & CLI shortcuts", executeImmediatelyOnTap = true),
    SlashCommandSpec("/usage", "Show Antigravity model quota & reset timers", executeImmediatelyOnTap = true),
    SlashCommandSpec("/credits", "Show Google One AI credit balance", executeImmediatelyOnTap = true),
    SlashCommandSpec("/agents", "List available subagents", executeImmediatelyOnTap = true),
    SlashCommandSpec("/skills", "List installed workspace & built-in skills", executeImmediatelyOnTap = true),
    SlashCommandSpec("/settings", "Open app Settings tab", executeImmediatelyOnTap = true),
    SlashCommandSpec("/plan", "Enter step-by-step planning mode", executeImmediatelyOnTap = false),
    SlashCommandSpec("/grill-me", "Interactive interview to resolve design decisions", executeImmediatelyOnTap = false),
    SlashCommandSpec("/goal", "Thorough autonomous execution until goal is achieved", executeImmediatelyOnTap = false),
    SlashCommandSpec("/boost", "Deep multi-perspective thinking & verification", executeImmediatelyOnTap = false),
    SlashCommandSpec("/teamwork", "Coordinate autonomous subagents in parallel", executeImmediatelyOnTap = false),
    SlashCommandSpec("/learn", "Persist a correction or workflow insight as a skill", executeImmediatelyOnTap = false),
    SlashCommandSpec("/schedule", "Run an instruction on a schedule or timer", executeImmediatelyOnTap = false),
    SlashCommandSpec("/browser", "Launch headless browser automation", executeImmediatelyOnTap = false),
    SlashCommandSpec("/effort", "Set reasoning effort (low, medium, high, max)", executeImmediatelyOnTap = false),
    SlashCommandSpec("/btw", "Ask a quick side question without altering main task", executeImmediatelyOnTap = false)
)

val slashCommands = slashCommandCatalog.map { it.command }

@Composable
fun MessageInput(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    selectedImageUri: Uri? = null,
    onSelectImage: (Uri?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.getOrNull(0)
            if (!spokenText.isNullOrBlank()) {
                text = if (text.isEmpty()) spokenText else "$text $spokenText"
            }
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSelectImage(uri)
        }
    }

    val matchingSlashCommands = remember(text) {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("/") && !trimmed.contains(' ') && !trimmed.contains('\n')) {
            val query = trimmed.lowercase()
            slashCommandCatalog.filter {
                it.command.lowercase().startsWith(query) ||
                    it.description.lowercase().contains(query.removePrefix("/"))
            }
        } else {
            emptyList()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .padding(top = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .imePadding()
        ) {
            // Floating Slash Command Auto-Complete Popup Bar
            if (matchingSlashCommands.isNotEmpty() && !isGenerating) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF141722),
                    border = BorderStroke(1.dp, Color(0xFF2E364F)),
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .padding(bottom = 10.dp)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(
                            items = matchingSlashCommands,
                            key = { it.command }
                        ) { spec ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        if (spec.executeImmediatelyOnTap) {
                                            text = ""
                                            onSend(spec.command)
                                        } else {
                                            text = "${spec.command} "
                                        }
                                    }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (spec.executeImmediatelyOnTap) {
                                        Color(0xFF1E1B4B)
                                    } else {
                                        Color(0xFF083344)
                                    },
                                    border = BorderStroke(
                                        1.dp,
                                        if (spec.executeImmediatelyOnTap) {
                                            Color(0xFF6366F1).copy(alpha = 0.5f)
                                        } else {
                                            Color(0xFF38BDF8).copy(alpha = 0.5f)
                                        }
                                    )
                                ) {
                                    Text(
                                        text = spec.command,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (spec.executeImmediatelyOnTap) {
                                            Color(0xFFA5B4FC)
                                        } else {
                                            Color(0xFF38BDF8)
                                        },
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = spec.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD1D5DB),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = if (spec.executeImmediatelyOnTap) "Run ↵" else "Fill ⇥",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF9CA3AF)
                                )
                            }
                        }
                    }
                }
            }

            if (selectedImageUri != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.9f),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Image attached",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Image attached",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        IconButton(onClick = { onSelectImage(null) }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove image",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = Color(0xFF16161B),
                border = BorderStroke(1.dp, Color(0xFF383842))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF6C5CE7)),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = "Ask Google Antigravity or type /...",
                                    color = Color(0xFF9CA3AF),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            innerTextField()
                        }
                    )

                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = "Attach",
                            tint = Color(0xFFD1D5DB),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    if (isGenerating) {
                        FilledIconButton(
                            onClick = onCancel,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFEF4444),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.Stop,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                if (text.isNotBlank() || selectedImageUri != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSend(text)
                                    text = ""
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFF6C5CE7),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
