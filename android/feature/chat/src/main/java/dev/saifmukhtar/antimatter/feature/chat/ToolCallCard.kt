package dev.saifmukhtar.antimatter.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.remember
import dev.saifmukhtar.antimatter.core.network.TrajectoryStep
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import dev.saifmukhtar.antimatter.core.ui.MarkdownText

@Composable
fun ToolCallCard(
    step: TrajectoryStep,
    onAcceptEdits: () -> Unit = {},
    onRejectEdits: () -> Unit = {}
) {
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = Color(0xFF2A2B32),
                    shape = RoundedCornerShape(14.dp)
                )
                .background(
                    color = Color(0xFF16161B),
                    shape = RoundedCornerShape(14.dp)
                )
                .clip(RoundedCornerShape(14.dp))
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Using tool: ${step.tool ?: "run_command"}",
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = androidx.compose.ui.graphics.Color(0xFF0F9D58),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "✔ Success",
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }

                val cmd = step.command ?: step.value
                if (!cmd.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (cmd.startsWith("CommandLine:")) cmd else "CommandLine: $cmd",
                        color = androidx.compose.ui.graphics.Color(0xFFD1D5DB),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                
                if (step.tool == "replace_file_content" || step.tool == "multi_replace_file_content") {
                    Spacer(modifier = Modifier.height(8.dp))
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
