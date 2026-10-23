/*
 * ConnectScreen.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.saifmukhtar.antimatter.core.data.GatewayProfile
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.core.ui.PulseAnimation

@Composable
fun ConnectRoute(
    connectionState: BridgeWebSocket.ConnectionState,
    savedUrl: String? = null,
    savedClientId: String? = null,
    savedClientSecret: String? = null,
    profiles: List<GatewayProfile> = emptyList(),
    onConnectClick: (url: String, clientId: String?, clientSecret: String?, token: String?) -> Unit,
    onScanQRClick: () -> Unit,
    onProfileSelected: (String) -> Unit,
    onProfileDeleted: (String) -> Unit,
    onCheckForUpdates: (() -> Unit)? = null,
    updateStatusText: String? = null
) {
    ConnectScreen(
        connectionState = connectionState,
        savedUrl = savedUrl,
        savedClientId = savedClientId,
        savedClientSecret = savedClientSecret,
        profiles = profiles,
        onConnectClick = onConnectClick,
        onScanQRClick = onScanQRClick,
        onProfileSelected = onProfileSelected,
        onProfileDeleted = onProfileDeleted,
        onCheckForUpdates = onCheckForUpdates,
        updateStatusText = updateStatusText
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
    connectionState: BridgeWebSocket.ConnectionState,
    savedUrl: String? = null,
    savedClientId: String? = null,
    savedClientSecret: String? = null,
    profiles: List<GatewayProfile> = emptyList(),
    onConnectClick: (url: String, clientId: String?, clientSecret: String?, token: String?) -> Unit,
    onScanQRClick: () -> Unit,
    onProfileSelected: (String) -> Unit,
    onProfileDeleted: (String) -> Unit,
    onCheckForUpdates: (() -> Unit)? = null,
    updateStatusText: String? = null
) {
    var ipAddress by remember { mutableStateOf(savedUrl?.ifBlank { "http://127.0.0.1:3883" } ?: "http://127.0.0.1:3883") }
    var pairingToken by remember { mutableStateOf("") }
    var clientId by remember { mutableStateOf(savedClientId ?: "") }
    var clientSecret by remember { mutableStateOf(savedClientSecret ?: "") }

    val darkBg = Color(0xFF0D0D12)
    val cardBg = Color(0xFF16161B)
    val borderColor = Color(0xFF2A2B32)
    val purpleAccent = Color(0xFF6C5CE7)
    val tealAccent = Color(0xFF00CEC9)
    val greenAccent = Color(0xFF10B981)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Hero Section: Dual Glowing Rings
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(130.dp)
            ) {
                // Outer ring
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.sweepGradient(
                                listOf(purpleAccent.copy(alpha = 0.25f), tealAccent.copy(alpha = 0.25f), purpleAccent.copy(alpha = 0.25f))
                            )
                        )
                        .border(1.5.dp, purpleAccent.copy(alpha = 0.5f), CircleShape)
                )
                // Inner ring
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF14141E))
                        .border(1.5.dp, tealAccent.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (connectionState == BridgeWebSocket.ConnectionState.CONNECTING) {
                        PulseAnimation(
                            modifier = Modifier.size(72.dp),
                            color = purpleAccent.copy(alpha = 0.4f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = "Connecting",
                                modifier = Modifier.size(40.dp),
                                tint = tealAccent
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = "Gateway",
                            modifier = Modifier.size(44.dp),
                            tint = Color.White
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Aimon Companion",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                ),
                color = Color.White
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = when (connectionState) {
                    BridgeWebSocket.ConnectionState.DISCONNECTED -> "Zero-Python Native Gateway & Agent Pairing"
                    BridgeWebSocket.ConnectionState.CONNECTING -> "Connecting to native C++ daemon..."
                    BridgeWebSocket.ConnectionState.CONNECTED -> "Connected to native C++ daemon."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Unified Connection Card
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, borderColor, RoundedCornerShape(20.dp)),
                shape = RoundedCornerShape(20.dp),
                color = cardBg
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Connection Settings",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("Gateway Host / URL", color = Color(0xFF94A3B8)) },
                        placeholder = { Text("http://127.0.0.1:3883", color = Color(0xFF64748B)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = purpleAccent,
                            unfocusedBorderColor = borderColor,
                            focusedContainerColor = Color(0xFF0F0F14),
                            unfocusedContainerColor = Color(0xFF0F0F14)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = pairingToken,
                        onValueChange = { pairingToken = it },
                        label = { Text("Pairing Token (Optional)", color = Color(0xFF94A3B8)) },
                        placeholder = { Text("Paste token or leave blank", color = Color(0xFF64748B)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = purpleAccent,
                            unfocusedBorderColor = borderColor,
                            focusedContainerColor = Color(0xFF0F0F14),
                            unfocusedContainerColor = Color(0xFF0F0F14)
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // QR Scan Button
                    OutlinedButton(
                        onClick = onScanQRClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, greenAccent),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = greenAccent.copy(alpha = 0.1f),
                            contentColor = greenAccent
                        )
                    ) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = "Scan QR",
                            modifier = Modifier.size(22.dp),
                            tint = greenAccent
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            "Scan Pairing QR Code",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = greenAccent
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Connect Button with Gradient
                    Button(
                        onClick = {
                            var url = ipAddress.trim()
                            if (!url.startsWith("http://") && !url.startsWith("https://") &&
                                !url.startsWith("ws://") && !url.startsWith("wss://")) {
                                url = "http://$url"
                            }
                            val token = pairingToken.trim().ifBlank { null }
                            onConnectClick(url, clientId.trim().ifBlank { null }, clientSecret.trim().ifBlank { null }, token)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .background(
                                Brush.horizontalGradient(listOf(purpleAccent, tealAccent)),
                                RoundedCornerShape(14.dp)
                            ),
                        enabled = connectionState != BridgeWebSocket.ConnectionState.CONNECTING,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        )
                    ) {
                        Text(
                            if (connectionState == BridgeWebSocket.ConnectionState.CONNECTING) "Connecting..." else "Connect",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            if (profiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    "Saved Gateways",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = purpleAccent,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(12.dp))

                profiles.forEach { profile ->
                    Surface(
                        onClick = { onProfileSelected(profile.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .border(1.dp, borderColor, RoundedCornerShape(14.dp)),
                        shape = RoundedCornerShape(14.dp),
                        color = cardBg
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(purpleAccent.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Computer,
                                    contentDescription = null,
                                    tint = purpleAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    profile.credentials.url ?: "Unknown",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onProfileDeleted(profile.id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete Profile",
                                    tint = Color(0xFFEF4444)
                                )
                            }
                        }
                    }
                }
            }

            if (onCheckForUpdates != null) {
                Spacer(modifier = Modifier.height(24.dp))
                if (!updateStatusText.isNullOrBlank()) {
                    Text(
                        text = updateStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = tealAccent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                OutlinedButton(
                    onClick = onCheckForUpdates,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = cardBg,
                        contentColor = Color(0xFFE2E8F0)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdateAlt,
                        contentDescription = "Check for App Updates",
                        modifier = Modifier.size(18.dp),
                        tint = tealAccent
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = updateStatusText?.takeIf { it.isNotBlank() } ?: "Check for App Updates",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(36.dp))
        }
    }
}
