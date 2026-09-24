package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.ui.MarkdownText
import dev.saifmukhtar.antimatter.core.ui.shimmerEffect

@Composable
fun ThoughtGroupBubble(
    steps: List<TrajectoryStep>,
    isLatest: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isLatest) 0.6f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    var expanded by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp)
            .alpha(alpha),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.5.dp,
                    color = Color(0xFF6C5CE7),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(
                    color = Color(0xFF141419),
                    shape = RoundedCornerShape(16.dp)
                )
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = !expanded }
                .then(if (isLatest && !expanded) Modifier.shimmerEffect() else Modifier)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLatest && !expanded) "Thinking..." else "Thinking Process (${steps.size} steps)",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowDown,
                    contentDescription = "Expand",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                val fullText = steps.mapNotNull { it.value }.joinToString("\n\n")
                val clipboardManager = LocalClipboardManager.current
                val context = LocalContext.current

                Column(modifier = Modifier.padding(top = 12.dp)) {
                    steps.forEachIndexed { idx, step ->
                        val text = step.value
                        if (!text.isNullOrBlank()) {
                            val displayText = if (text.trim().matches(Regex("^\\d+\\..*", RegexOption.DOT_MATCHES_ALL))) text else "${idx + 1}. $text"
                            MarkdownText(
                                markdown = displayText,
                                textColor = Color(0xFFD1D5DB).toArgb()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(fullText))
                            android.widget.Toast.makeText(context, "Copied thought to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .align(Alignment.End)
                            .size(28.dp)
                            .padding(top = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy thought",
                            tint = Color(0xFF6C5CE7),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
