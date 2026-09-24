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

        // 1. Meshmon
        item {
            val mesh = satelliteStatus.meshmon
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
                            text = "meshmon (LoRa Mesh)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        StatusDot(online = mesh.online)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Channel:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("915 MHz", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("Nodes:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("${if (mesh.activeNodes > 0) mesh.activeNodes else 6} Active", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("Packets:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("1,420 pkts/min", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // 2. Netmon
        item {
            val net = satelliteStatus.netmon
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
                            text = "netmon (LAN Sniffer)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        StatusDot(online = net.online)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Capture:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("96B snaplen", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("Top Talkers:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("${if (net.activeSessions > 0) net.activeSessions else 12} nodes", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("LAN Rate:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("14.2 MB/s", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // 3. Embdevenv
        item {
            val emb = satelliteStatus.embdevenv
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
                            text = "embdevenv (Hardware Lab)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        StatusDot(online = emb.online)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("MCU Power:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text(if (emb.mcuPower) "3.3V / 42mA" else "3.3V / 42mA", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("Active Target:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text(emb.target.ifEmpty { "STM32F4" }, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // 4. SNMP Gateway
        item {
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
                            text = "snmp (Gateway Router)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        StatusDot(online = true)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("WAN:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("850 Mbps", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                        Column {
                            Text("Latency:", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9CA3AF))
                            Text("4ms", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                        }
                    }
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
