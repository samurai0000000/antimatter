/*
 * ToolCallCard.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep

@Composable
fun ToolCallCard(
    step: TrajectoryStep,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    onAcceptEdits: () -> Unit = {},
    onRejectEdits: () -> Unit = {},
    compactMargin: Boolean = false
) {
    var internalExpanded by remember(step) { mutableStateOf(expanded) }
    val isExpanded = if (onToggleExpand != null) expanded else internalExpanded
    val toggle = {
        if (onToggleExpand != null) {
            onToggleExpand()
        } else {
            internalExpanded = !internalExpanded
        }
    }

    val parsedArgs = remember(step.value) {
        val stepValue = step.value
        if (!stepValue.isNullOrBlank()) {
            try {
                val jsonObj = org.json.JSONObject(stepValue)
                jsonObj.toString(2)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    val toolBadgeText = step.tool?.takeIf { it.isNotBlank() } ?: "run_command"
    val primarySummary = step.toolSummary?.takeIf { it.isNotBlank() }
        ?: step.toolAction?.takeIf { it.isNotBlank() }
        ?: step.command?.takeIf { it.isNotBlank() }
        ?: "Using tool: $toolBadgeText"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                vertical = if (compactMargin) 2.dp else 4.dp,
                horizontal = if (compactMargin) 0.dp else 12.dp
            ),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = if (isExpanded) Color(0xFF0F9D58).copy(alpha = 0.55f) else Color(0xFF2A2B32),
                    shape = RoundedCornerShape(10.dp)
                )
                .background(
                    color = Color(0xFF16161B),
                    shape = RoundedCornerShape(10.dp)
                )
                .clip(RoundedCornerShape(10.dp))
                .clickable { toggle() }
                .padding(
                    vertical = if (isExpanded) 10.dp else 6.dp,
                    horizontal = 10.dp
                )
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = Color(0xFF0F9D58),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "✔ $toolBadgeText",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    Text(
                        text = primarySummary,
                        color = Color(0xFFE5E7EB),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        maxLines = if (isExpanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (isExpanded) "Collapse tool details" else "Expand tool details",
                        tint = Color(0xFF9CA3AF),
                        modifier = Modifier.size(18.dp)
                    )
                }

                AnimatedVisibility(visible = isExpanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        if (!step.toolAction.isNullOrBlank() && step.toolAction != primarySummary) {
                            Text(
                                text = step.toolAction!!,
                                color = Color(0xFF9CA3AF),
                                style = MaterialTheme.typography.labelSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        val cmd = step.command ?: parsedArgs ?: step.value
                        if (!cmd.isNullOrBlank()) {
                            Text(
                                text = if (step.tool == "run_command" && !cmd.startsWith("CommandLine:")) "CommandLine: $cmd" else cmd,
                                color = Color(0xFFD1D5DB),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        if (step.tool == "replace_file_content" || step.tool == "multi_replace_file_content") {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                TextButton(
                                    onClick = onAcceptEdits,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Accept Edits")
                                }
                                TextButton(
                                    onClick = onRejectEdits,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reject Edits")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
