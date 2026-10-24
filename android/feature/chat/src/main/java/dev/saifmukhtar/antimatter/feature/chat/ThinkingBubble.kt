/*
 * ThinkingBubble.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saifmukhtar.antimatter.core.network.StepCase
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import dev.saifmukhtar.antimatter.core.ui.MarkdownText
import dev.saifmukhtar.antimatter.core.ui.shimmerEffect

@Composable
fun ThoughtGroupBubble(
    steps: List<TrajectoryStep>,
    isLatest: Boolean,
    isCompleted: Boolean = false,
    onAcceptEdits: () -> Unit = {},
    onRejectEdits: () -> Unit = {}
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = if (isLatest) 0.7f else 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // While streaming before real chat output arrives (isCompleted == false), keep the group open
    // so the latest green tool box is visible; once real chat output arrives (isCompleted == true),
    // automatically collapse the entire group into a single summary bar.
    var groupExpanded by remember(isCompleted) { mutableStateOf(!isCompleted) }
    var showAllSteps by remember(steps.size, isCompleted) { mutableStateOf(false) }

    // Track per-step manual expand/collapse overrides. Reset when steps.size or isCompleted changes
    // so a newly arriving green tool call automatically collapses the previous one.
    val manualStepExpanded = remember(steps.size, isCompleted) { mutableStateMapOf<Int, Boolean>() }
    val latestToolIndex = remember(steps) {
        steps.indexOfLast { it.stepCase == StepCase.TOOL_CALL }
    }

    val toolCount = remember(steps) { steps.count { it.stepCase == StepCase.TOOL_CALL } }
    val thoughtCount = remember(steps) { steps.count { it.stepCase == StepCase.PLANNER_RESPONSE } }

    val summaryTitle = remember(toolCount, thoughtCount, isLatest, groupExpanded) {
        when {
            toolCount > 0 && thoughtCount > 0 -> {
                val tLabel = if (toolCount == 1) "1 command" else "$toolCount commands"
                val pLabel = if (thoughtCount == 1) "1 thought" else "$thoughtCount thoughts"
                if (isLatest && !groupExpanded) "Executing ($tLabel · $pLabel)..." else "Executed $tLabel · $pLabel"
            }
            toolCount > 0 -> {
                val tLabel = if (toolCount == 1) "1 command" else "$toolCount commands"
                if (isLatest && !groupExpanded) "Executing ($tLabel)..." else "Executed $tLabel"
            }
            else -> {
                val pLabel = if (thoughtCount == 1) "1 thought" else "$thoughtCount thoughts"
                if (isLatest && !groupExpanded) "Thinking ($pLabel)..." else "Thinking ($pLabel)"
            }
        }
    }

    val borderColor = if (toolCount > 0) Color(0xFF0F9D58).copy(alpha = 0.55f) else Color(0xFF6C5CE7).copy(alpha = 0.7f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 12.dp)
            .alpha(alpha),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(12.dp)
                )
                .background(
                    color = Color(0xFF141419),
                    shape = RoundedCornerShape(12.dp)
                )
                .clip(RoundedCornerShape(12.dp))
                .then(if (isLatest && !groupExpanded) Modifier.shimmerEffect() else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { groupExpanded = !groupExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = if (toolCount > 0) Color(0xFF0F9D58).copy(alpha = 0.2f) else Color(0xFF6C5CE7).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (toolCount > 0) "⚡ EXEC" else "💭 THINK",
                        color = if (toolCount > 0) Color(0xFF34D399) else Color(0xFFA78BFA),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = summaryTitle,
                    color = Color(0xFFE5E7EB),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = if (groupExpanded) "Hide" else "Show",
                    color = Color(0xFF9CA3AF),
                    style = MaterialTheme.typography.labelSmall
                )

                Icon(
                    imageVector = if (groupExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (groupExpanded) "Collapse group" else "Expand group",
                    tint = Color(0xFF9CA3AF),
                    modifier = Modifier.size(18.dp)
                )
            }

            AnimatedVisibility(visible = groupExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val maxInitialVisibleSteps = 6
                    val tailVisibleSteps = 4
                    if (steps.size > maxInitialVisibleSteps && !showAllSteps) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1F29))
                                .clickable { showAllSteps = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "… ${steps.size - tailVisibleSteps} earlier steps hidden — tap to show all",
                                color = Color(0xFF9CA3AF),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }

                    val startIndex = if (steps.size > maxInitialVisibleSteps && !showAllSteps) {
                        steps.size - tailVisibleSteps
                    } else {
                        0
                    }

                    for (idx in startIndex until steps.size) {
                        val step = steps[idx]
                        val defaultExpanded = !isCompleted && (step.stepCase == StepCase.TOOL_CALL && idx == latestToolIndex)
                        val isStepExpanded = manualStepExpanded[idx] ?: defaultExpanded

                        when (step.stepCase) {
                            StepCase.TOOL_CALL -> {
                                ToolCallCard(
                                    step = step,
                                    expanded = isStepExpanded,
                                    onToggleExpand = {
                                        manualStepExpanded[idx] = !isStepExpanded
                                    },
                                    onAcceptEdits = onAcceptEdits,
                                    onRejectEdits = onRejectEdits,
                                    compactMargin = true
                                )
                            }
                            StepCase.PLANNER_RESPONSE -> {
                                ThoughtStepPill(
                                    step = step,
                                    expanded = isStepExpanded,
                                    onToggleExpand = {
                                        manualStepExpanded[idx] = !isStepExpanded
                                    }
                                )
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThoughtStepPill(
    step: TrajectoryStep,
    expanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val rawText = (step.value ?: step.thinking ?: "").trim()
    if (rawText.isEmpty()) return

    val oneLinePreview = remember(rawText) {
        rawText.lineSequence()
            .map { it.trim().removePrefix("**").removeSuffix("**").trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: "Thinking..."
    }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (expanded) Color(0xFF6C5CE7).copy(alpha = 0.6f) else Color(0xFF2A2B36),
                shape = RoundedCornerShape(10.dp)
            )
            .background(
                color = Color(0xFF181820),
                shape = RoundedCornerShape(10.dp)
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggleExpand() }
            .padding(horizontal = 10.dp, vertical = if (expanded) 8.dp else 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        color = Color(0xFF6C5CE7).copy(alpha = 0.25f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "💭 thought",
                    color = Color(0xFFC4B5FD),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = oneLinePreview,
                color = Color(0xFFD1D5DB),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse thought" else "Expand thought",
                tint = Color(0xFF9CA3AF),
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                MarkdownText(
                    markdown = rawText,
                    textColor = Color(0xFFD1D5DB).toArgb()
                )
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(rawText))
                        android.widget.Toast.makeText(context, "Copied thought to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .size(26.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy thought",
                        tint = Color(0xFF6C5CE7),
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}
