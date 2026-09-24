package dev.saifmukhtar.antimatter.navigation

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.saifmukhtar.antimatter.core.network.BridgeWebSocket
import dev.saifmukhtar.antimatter.feature.chat.ChatScreen
import dev.saifmukhtar.antimatter.feature.chat.ChatViewModel
import dev.saifmukhtar.antimatter.feature.connect.ConnectScreen
import dev.saifmukhtar.antimatter.feature.connect.ConnectionViewModel
import dev.saifmukhtar.antimatter.feature.connect.QRScannerScreen
import dev.saifmukhtar.antimatter.feature.files.FileViewScreen
import dev.saifmukhtar.antimatter.feature.files.FilesScreen
import dev.saifmukhtar.antimatter.feature.files.FilesViewModel
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler
import dev.saifmukhtar.antimatter.MainActivity

import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Sensors
import com.selfso.aimon.AimonViewModel
import com.selfso.aimon.ApprovalsScreen
import com.selfso.aimon.QuotasScreen
import com.selfso.aimon.TelemetryScreen

@Composable
fun AntimatterNavigation(
    intent: Intent?
) {
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
    
    var currentTab by remember { mutableStateOf(2) }
    var isScanningQR by remember { mutableStateOf(false) }
    var pendingDeepLinkUrl by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkToken by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkPubKey by remember { mutableStateOf<String?>(null) }
    var pendingDeepLinkScheme by remember { mutableStateOf<String?>(null) }
    var showCrashDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (LocalCrashHandler.hasUnsentLogs(context)) {
            showCrashDialog = true
        }
    }

    LaunchedEffect(intent) {
        intent?.data?.let { uri ->
            val isLegacy = uri.scheme == "antimatter" && uri.host == "connect"
            val isSecure = uri.scheme == "https" && uri.host == "antimatter.saifmukhtar.dev" && uri.path?.startsWith("/connect") == true
            
            if (isLegacy || isSecure) {
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
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text("Crash Detected") },
            text = { Text("It looks like the app crashed recently. Would you like to share the crash log to help us fix the issue?") },
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
                    LocalCrashHandler.clearLogs(context)
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
                    connectionViewModel.connectManually(pendingDeepLinkUrl!!, null, null, pendingDeepLinkToken, pendingDeepLinkPubKey)
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
                        // We need a new method in ConnectionViewModel to save a NAMED profile
                        // We will add `saveProfileAndConnect` next. For now, use connectManually but it doesn't take a name.
                        // Wait, we need to add `connectNamedProfile` to ConnectionViewModel.
                        connectionViewModel.connectNamedProfile(
                            profileName.ifBlank { "Gateway Connection" },
                            data["url"] ?: "",
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
                aimonViewModel.configureEndpoint(url)
                connectionViewModel.connectNamedProfile(
                    "aimon",
                    url,
                    cfId,
                    cfSecret,
                    token,
                    pubKey
                )
            },
            onNavigateBack = { isScanningQR = false }
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
            LaunchedEffect(savedUrl) {
                if (!savedUrl.isNullOrEmpty()) {
                    aimonViewModel.configureEndpoint(savedUrl)
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
                                onRefresh = { aimonViewModel.fetchQuotas() }
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
                connectionViewModel.connectManually(url, clientId, clientSecret, token)
            },
            onScanQRClick = { isScanningQR = true },
            onProfileSelected = { id -> connectionViewModel.switchProfile(id) },
            onProfileDeleted = { id -> connectionViewModel.deleteProfile(id) }
        )
    }
}
