package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.ui.MarkdownText

@Composable
fun ChatBubble(
    step: TrajectoryStep,
    onLinkClicked: ((String) -> Unit)? = null
) {
    val isUser = step.stepCase == StepCase.USER_INPUT
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    
    var isVisible by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isVisible = true
    }
    
    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = tween(durationMillis = 300)
        ) + fadeIn(animationSpec = tween(durationMillis = 300))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 12.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            var showCopyButton by remember { mutableStateOf(false) }
            val clipboardManager = LocalClipboardManager.current
            val context = LocalContext.current

            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(end = if (isUser) 0.dp else 32.dp, start = if (isUser) 32.dp else 0.dp)
                    .background(
                        color = if (isUser) Color(0xFF38438C) else Color(0xFF18181F),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showCopyButton = !showCopyButton }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Column {
                    if (isUser) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = step.displayText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        // For AI responses, render Markdown
                        MarkdownText(
                            markdown = step.displayText,
                            textColor = textColor.toArgb(),
                            onLinkClicked = onLinkClicked
                        )
                    }
                    
                    AnimatedVisibility(
                        visible = showCopyButton,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(step.displayText))
                                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                showCopyButton = false
                            },
                            modifier = Modifier
                                .size(32.dp)
                                .padding(top = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy message",
                                tint = textColor.copy(alpha = 0.6f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
