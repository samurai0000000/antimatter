/*
 * TelemetryScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TelemetryScreen(
    satelliteStatus: SatelliteStatus?,
    onRefresh: () -> Unit
) {
    if (satelliteStatus == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Discovering satellite telemetry nodes...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Satellite Infrastructure",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color(0xFF0284C7), shape = RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 0. Agent Telemetry Overview (/api/telemetry/overview)
        item {
            val overview = satelliteStatus.overview
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141419))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Agent Telemetry Overview",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        StatusDot(online = overview.totalSessions > 0 || overview.totalEvents > 0)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Sessions:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("${overview.totalSessions} (${overview.activeSessions} active)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }
                        Column {
                            Text("Tool Calls:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("${overview.totalToolCalls}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }
                        Column {
                            Text("Events:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("${overview.totalEvents}", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        }
                    }
                    if (overview.platforms.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val platformsSummary = overview.platforms.joinToString(" • ")
                        Text(
                            text = "Platforms: $platformsSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF9CA3AF)
                        )
                    }
                }
            }
        }

        // 1. Meshmon
        item {
            val mesh = satelliteStatus.meshmon
            MonitorNodeCard(
                title = "meshmon (LoRa Mesh)",
                online = mesh.online,
                endpoint = mesh.endpoint,
                lastSeenEpoch = mesh.lastSeenEpoch,
                extraLabel = "Active Nodes:",
                extraValue = if (mesh.online) "${mesh.activeNodes}" else "Offline"
            )
        }

        // 2. Netmon
        item {
            val net = satelliteStatus.netmon
            MonitorNodeCard(
                title = "netmon (LAN Monitor)",
                online = net.online,
                endpoint = net.endpoint,
                lastSeenEpoch = net.lastSeenEpoch,
                extraLabel = "Sessions:",
                extraValue = if (net.online) "${net.activeSessions}" else "Offline"
            )
        }

        // 3. Embdevenv
        item {
            val emb = satelliteStatus.embdevenv
            MonitorNodeCard(
                title = "embdevenv (Hardware Lab)",
                online = emb.online,
                endpoint = emb.endpoint,
                lastSeenEpoch = emb.lastSeenEpoch,
                extraLabel = "Target:",
                extraValue = if (emb.online) emb.target.ifEmpty { "Online" } else "Offline"
            )
        }

        // 4. ABC-80
        item {
            val abc = satelliteStatus.abc80
            MonitorNodeCard(
                title = "abc80 (Microcomputer)",
                online = abc.online,
                endpoint = abc.endpoint,
                lastSeenEpoch = abc.lastSeenEpoch,
                extraLabel = "Status:",
                extraValue = if (abc.online) abc.statusText.ifEmpty { "Online" } else "Offline"
            )
        }
    }
}

@Composable
private fun MonitorNodeCard(
    title: String,
    online: Boolean,
    endpoint: String,
    lastSeenEpoch: Long,
    extraLabel: String,
    extraValue: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF27272A)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141419))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                StatusDot(online = online)
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Endpoint:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                    Text(
                        text = if (online) endpoint.ifBlank { "Online" } else "Offline",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Column {
                    Text("Last Seen:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                    Text(
                        text = if (online && lastSeenEpoch > 0L) "${lastSeenEpoch}s" else "N/A",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
                Column {
                    Text(extraLabel, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                    Text(
                        text = extraValue,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun StatusDot(online: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (online) Color(0xFF10B981) else Color(0xFFEF4444))
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (online) "Online" else "Offline",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (online) Color(0xFF10B981) else Color(0xFFEF4444)
        )
    }
}
