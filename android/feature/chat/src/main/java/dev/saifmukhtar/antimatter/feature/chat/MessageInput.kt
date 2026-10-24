/*
 * MessageInput.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

fun mergeDictatedText(prefix: String, utterance: String): String {
    val cleanUtterance = utterance.trim()
    if (cleanUtterance.isEmpty()) return prefix
    if (prefix.isBlank()) return cleanUtterance
    return "${prefix.trimEnd()} $cleanUtterance"
}

class SpeechDictationSession(
    private val onTextUpdated: (String) -> Unit,
    private val onRestartRecognizer: () -> Unit = {}
) {
    var isListening: Boolean = false
        private set
    var userRequestedListening: Boolean = false
        private set
    var committedPrefix: String = ""
        private set
    var livePartial: String = ""
        private set
    var onListeningStateChanged: ((Boolean) -> Unit)? = null

    fun startSession(initialText: String) {
        committedPrefix = initialText
        livePartial = ""
        userRequestedListening = true
        isListening = true
        onListeningStateChanged?.invoke(true)
    }

    fun stopSession() {
        userRequestedListening = false
        isListening = false
        livePartial = ""
        onListeningStateChanged?.invoke(false)
    }

    fun onManualTextChanged(newText: String) {
        committedPrefix = newText
        livePartial = ""
    }

    fun onPartialRecognition(partial: String) {
        if (!isListening) return
        livePartial = partial
        if (partial.isNotBlank()) {
            onTextUpdated(mergeDictatedText(committedPrefix, partial))
        }
    }

    fun onFinalRecognition(finalUtterance: String) {
        if (finalUtterance.isNotBlank()) {
            committedPrefix = mergeDictatedText(committedPrefix, finalUtterance)
            onTextUpdated(committedPrefix)
        }
        livePartial = ""
        if (userRequestedListening) {
            isListening = true
            onRestartRecognizer()
        } else {
            isListening = false
            onListeningStateChanged?.invoke(false)
        }
    }

    fun onRecognitionError(errorCode: Int) {
        if (userRequestedListening && (errorCode == SpeechRecognizer.ERROR_NO_MATCH || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
            isListening = true
            onRestartRecognizer()
        } else {
            userRequestedListening = false
            isListening = false
            livePartial = ""
            onListeningStateChanged?.invoke(false)
        }
    }
}

data class SlashCommandSpec(
    val command: String,
    val description: String,
    val executeImmediatelyOnTap: Boolean,
    val insertText: String = "$command "
)

val slashCommandCatalog = listOf(
    SlashCommandSpec("/clear", "Clear active chat & start a fresh conversation", executeImmediatelyOnTap = true),
    SlashCommandSpec("/model", "Switch the AI model for this session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/provider", "Switch AI provider (Antigravity, Claude Code, Cursor)", executeImmediatelyOnTap = true),
    SlashCommandSpec("/claude", "Switch active provider to Claude Code CLI", executeImmediatelyOnTap = true),
    SlashCommandSpec("/cursor", "Switch active provider to Cursor Agent CLI", executeImmediatelyOnTap = true),
    SlashCommandSpec("/agy", "Switch active provider to Google Antigravity", executeImmediatelyOnTap = true),
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

val claudeSlashCommandCatalog = listOf(
    SlashCommandSpec("/compact", "Compact conversation context in-place via Claude CLI", executeImmediatelyOnTap = true),
    SlashCommandSpec("/clear", "Clear screen and start a fresh Claude Code session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/new", "Start a fresh Claude Code session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/help", "Show available commands and usage guide", executeImmediatelyOnTap = true),
    SlashCommandSpec("/model", "View or switch active AI model", executeImmediatelyOnTap = true),
    SlashCommandSpec("/effort", "Set reasoning effort level (low, medium, high, max)", executeImmediatelyOnTap = false),
    SlashCommandSpec("/plan", "Enter structured planning mode before execution", executeImmediatelyOnTap = false),
    SlashCommandSpec("/doctor", "Diagnose session & gateway connection health", executeImmediatelyOnTap = true),
    SlashCommandSpec("/status", "Show active provider, session & connection status", executeImmediatelyOnTap = true),
    SlashCommandSpec("/provider", "Switch default AI CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/claude", "Switch to Claude Code CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/cursor", "Switch to Cursor Agent CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/agy", "Switch to Google Antigravity CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/settings", "Open connection & notification settings", executeImmediatelyOnTap = true)
)

val cursorSlashCommandCatalog = listOf(
    SlashCommandSpec("/compact", "Summarize & compact session into a fresh Cursor context", executeImmediatelyOnTap = true),
    SlashCommandSpec("/summarize", "Summarize & compact session into a fresh Cursor context", executeImmediatelyOnTap = true),
    SlashCommandSpec("/clear", "Clear screen and start a fresh Cursor Agent session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/new", "Start a fresh Cursor Agent session", executeImmediatelyOnTap = true),
    SlashCommandSpec("/help", "Show available commands and usage guide", executeImmediatelyOnTap = true),
    SlashCommandSpec("/model", "View or switch active AI model", executeImmediatelyOnTap = true),
    SlashCommandSpec("/models", "List available models", executeImmediatelyOnTap = true, insertText = "/model "),
    SlashCommandSpec("/plan", "Switch to plan mode before execution", executeImmediatelyOnTap = false),
    SlashCommandSpec("/ask", "Ask a question in read-only / Q&A mode", executeImmediatelyOnTap = false, insertText = "/ask "),
    SlashCommandSpec("/status", "Show active provider, session & connection status", executeImmediatelyOnTap = true),
    SlashCommandSpec("/provider", "Switch default AI CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/claude", "Switch to Claude Code CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/cursor", "Switch to Cursor Agent CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/agy", "Switch to Google Antigravity CLI provider", executeImmediatelyOnTap = true),
    SlashCommandSpec("/settings", "Open connection & notification settings", executeImmediatelyOnTap = true)
)

val slashCommands = slashCommandCatalog.map { it.command }

fun placeholderForProvider(provider: String): String {
    return when (provider.trim().lowercase()) {
        "claude" -> "Ask Claude Code or type /..."
        "cursor" -> "Ask Cursor Agent or type /..."
        "ide" -> "Ask Antigravity IDE or type /..."
        else -> "Ask Google Antigravity or type /..."
    }
}

fun slashCommandCatalogForProvider(provider: String): List<SlashCommandSpec> {
    return when (provider.trim().lowercase()) {
        "claude" -> claudeSlashCommandCatalog
        "cursor" -> cursorSlashCommandCatalog
        else -> slashCommandCatalog
    }
}

fun computeMaxPromptInputHeightDp(screenHeightDp: Int, imeBottomDp: Int): Int {
    val availableAboveKeyboardDp = (screenHeightDp - imeBottomDp - 80).coerceAtLeast(180)
    return (availableAboveKeyboardDp * 0.40f).toInt().coerceIn(88, 168)
}

@Composable
fun MessageInput(
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    selectedImageUri: Uri? = null,
    onSelectImage: (Uri?) -> Unit = {},
    activeProvider: String = "antigravity",
    modifier: Modifier = Modifier
) {
    var text by remember { mutableStateOf("") }
    var isListening by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp().value.toInt() }
    val maxInputHeightDp = computeMaxPromptInputHeightDp(configuration.screenHeightDp, imeBottomDp).dp
    val providerCatalog = remember(activeProvider) { slashCommandCatalogForProvider(activeProvider) }
    val placeholderText = remember(activeProvider) { placeholderForProvider(activeProvider) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    val speechRecognizer = remember(context) {
        runCatching {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                SpeechRecognizer.createSpeechRecognizer(context)
            } else null
        }.getOrNull()
    }

    DisposableEffect(speechRecognizer) {
        onDispose {
            runCatching { speechRecognizer?.destroy() }
        }
    }

    val recognizerIntent = remember(context) {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
    }

    val listenerHolder = remember { arrayOfNulls<RecognitionListener>(1) }

    val dictationSession = remember {
        SpeechDictationSession(
            onTextUpdated = { updated ->
                text = updated
            },
            onRestartRecognizer = {
                mainHandler.post {
                    if (speechRecognizer != null) {
                        runCatching {
                            listenerHolder[0]?.let { speechRecognizer.setRecognitionListener(it) }
                            speechRecognizer.startListening(recognizerIntent)
                        }
                    }
                }
            }
        ).apply {
            onListeningStateChanged = { listening ->
                isListening = listening
            }
        }
    }

    val recognitionListener = remember(dictationSession) {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                dictationSession.onRecognitionError(error)
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val utterance = matches?.firstOrNull() ?: ""
                dictationSession.onFinalRecognition(utterance)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull() ?: ""
                dictationSession.onPartialRecognition(partial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
    listenerHolder[0] = recognitionListener

    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            dictationSession.startSession(text)
            isListening = true
            runCatching {
                speechRecognizer?.setRecognitionListener(recognitionListener)
                speechRecognizer?.startListening(recognizerIntent)
            }
        }
    }

    val startListening = {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            dictationSession.startSession(text)
            isListening = true
            runCatching {
                speechRecognizer?.setRecognitionListener(recognitionListener)
                speechRecognizer?.startListening(recognizerIntent)
            }
        } else {
            recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    val stopListening = {
        dictationSession.stopSession()
        isListening = false
        runCatching {
            speechRecognizer?.stopListening()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSelectImage(uri)
        }
    }

    val matchingSlashCommands = remember(text, providerCatalog) {
        val trimmed = text.trimStart()
        if (trimmed.startsWith("/") && !trimmed.contains(' ') && !trimmed.contains('\n')) {
            val query = trimmed.lowercase()
            providerCatalog.filter {
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
                                        if (isListening) {
                                            stopListening()
                                        }
                                        if (spec.executeImmediatelyOnTap) {
                                            text = ""
                                            onSend(spec.command)
                                        } else {
                                            text = spec.insertText
                                            dictationSession.onManualTextChanged(spec.insertText)
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
                    .heightIn(min = 52.dp, max = maxInputHeightDp),
                shape = RoundedCornerShape(26.dp),
                color = Color(0xFF16161B),
                border = BorderStroke(
                    1.dp,
                    if (isListening) Color(0xFFEF4444) else Color(0xFF383842)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            dictationSession.onManualTextChanged(it)
                        },
                        maxLines = 6,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF6C5CE7)),
                        decorationBox = { innerTextField ->
                            if (text.isEmpty()) {
                                Text(
                                    text = if (isListening) "Listening... tap mic to stop" else placeholderText,
                                    color = if (isListening) Color(0xFFF87171) else Color(0xFF9CA3AF),
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

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (isListening) {
                                stopListening()
                            } else {
                                startListening()
                            }
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .then(
                                if (isListening) {
                                    Modifier.background(
                                        color = Color(0xFFEF4444).copy(alpha = 0.22f),
                                        shape = CircleShape
                                    )
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = if (isListening) "Stop voice input" else "Start voice input",
                            tint = if (isListening) Color(0xFFEF4444) else Color(0xFFD1D5DB),
                            modifier = Modifier.size(20.dp)
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
                                if (isListening) {
                                    stopListening()
                                }
                                if (text.isNotBlank() || selectedImageUri != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onSend(text)
                                    text = ""
                                    dictationSession.onManualTextChanged("")
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
