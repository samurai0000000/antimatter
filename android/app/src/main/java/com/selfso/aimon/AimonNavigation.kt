/*
 * AimonNavigation.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.selfso.selfupdate.SelfUpdateController
import com.selfso.selfupdate.UpdateState
import com.selfso.selfupdate.ui.SelfUpdateDialog
import com.selfso.selfupdate.ui.rememberSelfUpdateController
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler
import dev.saifmukhtar.antimatter.feature.chat.ChatScreen
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
import dev.saifmukhtar.antimatter.feature.connect.ConnectScreen
import dev.saifmukhtar.antimatter.feature.connect.ConnectionViewModel
import dev.saifmukhtar.antimatter.feature.connect.QRScannerScreen
import dev.saifmukhtar.antimatter.feature.files.FileViewScreen
import dev.saifmukhtar.antimatter.feature.files.FilesScreen
import dev.saifmukhtar.antimatter.feature.files.FilesViewModel
import kotlinx.coroutines.delay

@Composable
fun AimonNavigation(
    intent: Intent?
) {
    val updateController: SelfUpdateController = rememberSelfUpdateController(checkOnLaunch = true)
    val updateState by updateController.state.collectAsState()
    val dialogRequested by updateController.dialogVisibleFlow().collectAsState()

    LaunchedEffect(updateController) {
        while (true) {
            delay(6L * 60L * 60L * 1000L) // 6 hours
            val current = updateController.state.value
            if (current is UpdateState.Idle ||
                current is UpdateState.UpToDate ||
                current is UpdateState.Error
            ) {
                updateController.checkForUpdates()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, updateController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (updateController.state.value is UpdateState.PermissionRequired) {
                    updateController.proceedWithInstallAfterPermission()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val chatViewModel: ChatViewModel = hiltViewModel()
    val filesViewModel: FilesViewModel = hiltViewModel()
    val connectionViewModel: ConnectionViewModel = hiltViewModel()
    val aimonViewModel: AimonViewModel = hiltViewModel()

    val chatUiState by chatViewModel.uiState.collectAsState()
    val filesUiState by filesViewModel.uiState.collectAsState()
    val connectionState by connectionViewModel.connectionState.collectAsState()
    val savedCredentials by connectionViewModel.savedCredentialsFlow.collectAsState()
    val savedUrl = savedCredentials.url

    val quotaStatus by aimonViewModel.quotaStatus.collectAsState()
    val pendingApprovals by aimonViewModel.pendingApprovals.collectAsState()
    val satelliteStatus by aimonViewModel.satelliteStatus.collectAsState()
    val mobileToken by aimonViewModel.mobileToken.collectAsState()

    var currentTab by remember { mutableStateOf(2) }
    var isScanningQR by remember { mutableStateOf(false) }
    var pendingDeepLinkUrl by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkToken by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkPubKey by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkScheme by remember { mutableStateOf<String?>(null) }
    var showCrashDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current

    val isQuotasTabVisible = !isScanningQR &&
        !savedUrl.isNullOrEmpty() &&
        filesUiState.viewedFilePath == null &&
        currentTab == 2

    LaunchedEffect(updateState) {
        if (updateState is UpdateState.Downloading) {
            updateController.hideDialog()
        }
    }

    if (dialogRequested || !isQuotasTabVisible) {
        SelfUpdateDialog(
            controller = updateController,
            onDismiss = {
                updateController.hideDialog()
                updateController.dismiss()
            }
        )
    }

    LaunchedEffect(Unit) {
        aimonViewModel.initPreferences(context)
        if (LocalCrashHandler.hasUnsentLogs(context)) {
            showCrashDialog = true
        }
    }

    LaunchedEffect(intent) {
        intent?.data?.let { uri ->
            val isAimonPair = (uri.scheme == "aimon" && uri.host == "pair") ||
                              (uri.scheme == "aimon" && uri.path == "/pair")
            val isLegacy = uri.scheme == "antimatter" && uri.host == "connect"
            val isSecure = uri.scheme == "https" && uri.host == "antimatter.saifmukhtar.dev" && uri.path?.startsWith("/connect") == true

            if (isAimonPair) {
                val host = uri.getQueryParameter("host") ?: "127.0.0.1"
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 3883
                val secret = uri.getQueryParameter("secret") ?: ""
                val url = "http://$host:$port"
                aimonViewModel.pairWithServer(host, port, secret) { success, grantedToken, errMsg ->
                    if (success && !grantedToken.isNullOrBlank()) {
                        aimonViewModel.configureEndpoint(url, grantedToken)
                        chatViewModel.configureEndpoint(url, grantedToken)
                        filesViewModel.configureEndpoint(url, grantedToken)
                        connectionViewModel.connectNamedProfile("aimon", url, null, null, grantedToken, null)
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            errMsg ?: "Pairing failed",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } else if (isLegacy || isSecure) {
                val url = uri.getQueryParameter("url")
                val token = uri.getQueryParameter("token")
                val pubKey = uri.getQueryParameter("pubkey")
                if (url != null && token != null) {
                    pendingDeepLinkUrl = url
                    pendingDeepLinkToken = token
                    pendingDeepLinkPubKey = pubKey
                    pendingDeepLinkScheme = uri.scheme
                }
            }
        }
    }

    if (showCrashDialog) {
        val crashSummary = remember { LocalCrashHandler.getLatestCrashSummary(context) }
        AlertDialog(
            onDismissRequest = {
                LocalCrashHandler.clearCrashLogs(context)
                showCrashDialog = false
            },
            title = { Text("Crash Detected") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("It looks like the app crashed recently. Would you like to share the crash log to help us fix the issue?")
                    if (crashSummary.isNotBlank()) {
                        Surface(
                            color = Color(0xFF18181B),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = crashSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = Color(0xFFE4E4E7),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    LocalCrashHandler.shareLatestCrashLog(context)
                    showCrashDialog = false
                }) {
                    Text("Share Log")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    LocalCrashHandler.clearCrashLogs(context)
                    showCrashDialog = false
                }) {
                    Text("Dismiss")
                }
            }
        )
    }

    if (pendingDeepLinkUrl != null) {
        AlertDialog(
            onDismissRequest = { pendingDeepLinkUrl = null },
            title = { Text("Incoming Connection") },
            text = {
                Column {
                    Text("Connect to workspace at $pendingDeepLinkUrl?")
                    if (pendingDeepLinkScheme == "antimatter") {
                        Text(
                            text = "\nWARNING: You are using the legacy insecure connection method. Custom URI schemes are vulnerable to intent hijacking. Please use the secure HTTPS QR code.",
                            color = Color.Red,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetUrl = pendingDeepLinkUrl!!
                    val targetToken = pendingDeepLinkToken ?: ""
                    aimonViewModel.configureEndpoint(targetUrl, targetToken)
                    chatViewModel.configureEndpoint(targetUrl, targetToken)
                    filesViewModel.configureEndpoint(targetUrl, targetToken)
                    connectionViewModel.connectManually(targetUrl, null, null, pendingDeepLinkToken, pendingDeepLinkPubKey)
                    pendingDeepLinkUrl = null
                    pendingDeepLinkToken = null
                    pendingDeepLinkPubKey = null
                }) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDeepLinkUrl = null
                    pendingDeepLinkToken = null
                    pendingDeepLinkPubKey = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    var scannedProfileData by remember { mutableStateOf<Map<String, String?>?>(null) }

    if (scannedProfileData != null) {
        var profileName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { scannedProfileData = null },
            title = { Text("Name this Connection") },
            text = {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    label = { Text("Profile Name (e.g., Work PC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val data = scannedProfileData!!
                        val url = data["url"] ?: ""
                        val token = data["token"] ?: ""
                        aimonViewModel.configureEndpoint(url, token)
                        chatViewModel.configureEndpoint(url, token)
                        filesViewModel.configureEndpoint(url, token)
                        connectionViewModel.connectNamedProfile(
                            profileName.ifBlank { "Gateway Connection" },
                            url,
                            data["cfId"],
                            data["cfSecret"],
                            data["token"],
                            data["pubKey"]
                        )
                        scannedProfileData = null
                    }
                ) {
                    Text("Save & Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { scannedProfileData = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (isScanningQR) {
        BackHandler {
            isScanningQR = false
        }
        QRScannerScreen(
            onQRScanned = { url, token, cfId, cfSecret, pubKey ->
                isScanningQR = false
                currentTab = 2
                aimonViewModel.configureEndpoint(url, token)
                chatViewModel.configureEndpoint(url, token)
                filesViewModel.configureEndpoint(url, token)
                connectionViewModel.connectNamedProfile(
                    "aimon",
                    url,
                    cfId,
                    cfSecret,
                    token,
                    pubKey
                )
            },
            onNavigateBack = { isScanningQR = false },
            onPairAimon = { host, port, secret, onResult ->
                aimonViewModel.pairWithServer(host, port, secret, onResult = onResult)
            }
        )
    } else if (!savedUrl.isNullOrEmpty()) {
        if (filesUiState.viewedFilePath != null) {
            BackHandler {
                filesViewModel.closeFile()
            }
            FileViewScreen(
                uiState = filesUiState,
                onBack = { filesViewModel.closeFile() },
                onSave = { path, content -> filesViewModel.writeFile(path, content) }
            )
        } else {
            LaunchedEffect(savedUrl, mobileToken, savedCredentials.token) {
                if (!savedUrl.isNullOrEmpty()) {
                    val effectiveToken = mobileToken.ifBlank { savedCredentials.token ?: "" }
                    aimonViewModel.configureEndpoint(savedUrl, effectiveToken)
                    chatViewModel.configureEndpoint(savedUrl, effectiveToken)
                    filesViewModel.configureEndpoint(savedUrl, effectiveToken)
                }
            }
            BackHandler(enabled = currentTab != 2) {
                currentTab = 2
            }
            Scaffold(
                containerColor = Color(0xFF0D0D12),
                bottomBar = {
                    NavigationBar(
                        modifier = Modifier
                            .height(64.dp)
                            .border(
                                width = 1.dp,
                                color = Color(0xFF1E1E26)
                            ),
                        containerColor = Color(0xFF0F0F14),
                        tonalElevation = 8.dp
                    ) {
                        val unselectedColor = Color(0xFF71717A)
                        val indicatorColor = Color(0xFF1C1C24)

                        NavigationBarItem(
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat") },
                            label = { Text("Chat") },
                            selected = currentTab == 0,
                            onClick = { currentTab = 0 },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF818CF8),
                                selectedTextColor = Color(0xFF818CF8),
                                unselectedIconColor = unselectedColor,
                                unselectedTextColor = unselectedColor,
                                indicatorColor = indicatorColor
                            )
                        )
                        NavigationBarItem(
                            icon = {
                                if (pendingApprovals.isNotEmpty()) {
                                    BadgedBox(
                                        badge = {
                                            Badge(
                                                containerColor = Color(0xFFEF4444),
                                                contentColor = Color.White
                                            ) {
                                                Text("${pendingApprovals.size}")
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Approvals")
                                    }
                                } else {
                                    Icon(Icons.Default.CheckCircle, contentDescription = "Approvals")
                                }
                            },
                            label = { Text("Approvals") },
                            selected = currentTab == 1,
                            onClick = { currentTab = 1 },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFF59E0B),
                                selectedTextColor = Color(0xFFF59E0B),
                                unselectedIconColor = unselectedColor,
                                unselectedTextColor = unselectedColor,
                                indicatorColor = indicatorColor
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Analytics, contentDescription = "Quotas") },
                            label = { Text("Quotas") },
                            selected = currentTab == 2,
                            onClick = { currentTab = 2 },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF2DD4BF),
                                selectedTextColor = Color(0xFF2DD4BF),
                                unselectedIconColor = unselectedColor,
                                unselectedTextColor = unselectedColor,
                                indicatorColor = indicatorColor
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Sensors, contentDescription = "Telemetry") },
                            label = { Text("Telemetry") },
                            selected = currentTab == 3,
                            onClick = { currentTab = 3 },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF38BDF8),
                                selectedTextColor = Color(0xFF38BDF8),
                                unselectedIconColor = unselectedColor,
                                unselectedTextColor = unselectedColor,
                                indicatorColor = indicatorColor
                            )
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Folder, contentDescription = "Workspace") },
                            label = { Text("Files") },
                            selected = currentTab == 4,
                            onClick = { currentTab = 4 },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF06B6D4),
                                selectedTextColor = Color(0xFF06B6D4),
                                unselectedIconColor = unselectedColor,
                                unselectedTextColor = unselectedColor,
                                indicatorColor = indicatorColor
                            )
                        )
                    }
                }
            ) { paddingValues ->
                Box(modifier = Modifier
                    .padding(bottom = paddingValues.calculateBottomPadding())
                    .consumeWindowInsets(PaddingValues(bottom = paddingValues.calculateBottomPadding()))
                ) {
                    when (currentTab) {
                        0 -> {
                            ChatScreen(
                                uiState = chatUiState,
                                onSendPrompt = { chatViewModel.sendMessage(it) },
                                onCancel = { chatViewModel.cancelResponse() },
                                onNewConversation = { chatViewModel.startNewConversation() },
                                onSubscribeConversation = { id -> chatViewModel.subscribeConversation(id) },
                                onAcceptEdits = { chatViewModel.acceptEdits() },
                                onRejectEdits = { chatViewModel.rejectEdits() },
                                onChangeModel = { chatViewModel.changeModel() },
                                onDisconnect = { connectionViewModel.disconnectManually() },
                                onScrollStateChange = { index, offset -> chatViewModel.updateScrollState(index, offset) },
                                onLoadScrollState = { id -> chatViewModel.getScrollState(id) },
                                onRequestArtifacts = { chatViewModel.requestArtifacts() },
                                onRequestArtifactContent = { path -> chatViewModel.requestArtifactContent(path) },
                                onClearArtifactContent = { chatViewModel.clearActiveArtifact() },
                                onSwitchAgent = { id -> chatViewModel.switchAgent(id) },
                                onSearchHistory = { query -> chatViewModel.updateSearchQuery(query) },
                                onSelectImage = { uri -> chatViewModel.selectImage(uri) },
                                onStepVisible = { index -> chatViewModel.onStepVisible(index) }
                            )
                        }
                        1 -> {
                            ApprovalsScreen(
                                pendingApprovals = pendingApprovals,
                                onDecision = { id, verdict -> aimonViewModel.submitDecision(id, verdict) },
                                onRefresh = { aimonViewModel.fetchApprovals() }
                            )
                        }
                        2 -> {
                            QuotasScreen(
                                quotaStatus = quotaStatus,
                                onRefresh = { aimonViewModel.fetchQuotas() },
                                updateController = updateController
                            )
                        }
                        3 -> {
                            TelemetryScreen(
                                satelliteStatus = satelliteStatus,
                                onRefresh = { aimonViewModel.fetchSatelliteTelemetry() }
                            )
                        }
                        4 -> {
                            FilesScreen(
                                uiState = filesUiState,
                                onRefresh = { filesViewModel.loadFileTree() },
                                onOpenFile = { path -> filesViewModel.openFile(path) },
                                onChangeWorkspace = { path ->
                                    (context as? MainActivity)?.showBiometricPrompt { success ->
                                        if (success) {
                                            filesViewModel.changeWorkspace(path)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    } else {
        val profiles by connectionViewModel.profilesFlow.collectAsState()
        ConnectScreen(
            connectionState = connectionState,
            savedUrl = savedUrl,
            savedClientId = savedCredentials.clientId,
            savedClientSecret = savedCredentials.clientSecret,
            profiles = profiles,
            onConnectClick = { url, clientId, clientSecret, token ->
                if (!token.isNullOrBlank() && token.length <= 16) {
                    val parsedUri = try { android.net.Uri.parse(url) } catch (_: Exception) { null }
                    val host = parsedUri?.host ?: "127.0.0.1"
                    val port = if (parsedUri != null && parsedUri.port > 0) parsedUri.port else 3883
                    aimonViewModel.pairWithServer(host, port, token) { success, grantedToken, errMsg ->
                        if (success && !grantedToken.isNullOrBlank()) {
                            aimonViewModel.configureEndpoint(url, grantedToken)
                            chatViewModel.configureEndpoint(url, grantedToken)
                            filesViewModel.configureEndpoint(url, grantedToken)
                            connectionViewModel.connectManually(url, clientId, clientSecret, grantedToken)
                        } else {
                            android.widget.Toast.makeText(
                                context,
                                errMsg ?: "Pairing failed",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    val effectiveToken = token ?: mobileToken
                    aimonViewModel.configureEndpoint(url, effectiveToken)
                    chatViewModel.configureEndpoint(url, effectiveToken)
                    filesViewModel.configureEndpoint(url, effectiveToken)
                    connectionViewModel.connectManually(url, clientId, clientSecret, effectiveToken.ifBlank { null })
                }
            },
            onScanQRClick = { isScanningQR = true },
            onProfileSelected = { id -> connectionViewModel.switchProfile(id) },
            onProfileDeleted = { id -> connectionViewModel.deleteProfile(id) },
            onCheckForUpdates = {
                if (updateState is UpdateState.Available ||
                    updateState is UpdateState.Downloading ||
                    updateState is UpdateState.PermissionRequired ||
                    updateState is UpdateState.ReadyToInstall
                ) {
                    updateController.showDialog()
                } else {
                    updateController.checkForUpdates()
                }
            },
            updateStatusText = if (updateState is UpdateState.Idle) null else formatSelfUpdateStatusText(updateState)
        )
    }
}
