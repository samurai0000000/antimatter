/*
 * BridgeWebSocket.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.core.network

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.pow

class BridgeWebSocket(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Disable timeout for websocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var currentUrl: String? = null
    private var token: String? = null
    private var clientId: String? = null
    private var clientSecret: ByteArray? = null
    private var pubKey: String? = null
    private var reconnectAttempt = 0
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private var manuallyDisconnected = false

    private var pendingChallenge: ByteArray? = null
    private var authTimeoutJob: Job? = null
    private var e2eeSession: E2EESession? = null

    private data class PendingMessage(
        val id: String,
        val message: OutboundMessage,
        val sentAt: Long,
        val retryCount: AtomicInteger = AtomicInteger(0),
        val timeoutJob: AtomicReference<Job?> = AtomicReference(null)
    )
    private val pendingAcks = ConcurrentHashMap<String, PendingMessage>()
    private val notificationIdCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private fun updateConnectionState(newState: ConnectionState) {
        val prevState = _connectionState.value
        _connectionState.update { newState }
        if (prevState == newState) return

        // In HTTP mode, AimonSseService is already the dedicated foreground service.
        if (isHttpMode()) return

        if (newState == ConnectionState.CONNECTED) {
            try {
                val intent = Intent(context, BridgeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("BridgeWebSocket", "Unable to start BridgeService: ${e.message}")
            }
        } else if (newState == ConnectionState.DISCONNECTED && prevState == ConnectionState.CONNECTED) {
            try {
                context.stopService(Intent(context, BridgeService::class.java))
            } catch (e: Exception) {
                Log.w("BridgeWebSocket", "Unable to stop BridgeService: ${e.message}")
            }
        }
    }
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<InboundMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<InboundMessage> = _messages

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    init {
        // No init needed
    }

    fun connect(url: String, clientId: String? = null, clientSecret: String? = null, token: String? = null, pubKey: String? = null) {
        manuallyDisconnected = false
        reconnectAttempt = 0
        currentUrl = if (url.endsWith("/")) url.dropLast(1) else url
        this.token = token?.takeIf { it.isNotBlank() }
        this.clientId = clientId?.takeIf { it.isNotBlank() }
        this.clientSecret = clientSecret?.takeIf { it.isNotBlank() }?.toByteArray()
        this.pubKey = pubKey?.takeIf { it.isNotBlank() }
        connectInternal()
    }

    fun configureEndpoint(url: String, authToken: String = "") {
        currentUrl = if (url.endsWith("/")) url.dropLast(1) else url
        if (authToken.isNotBlank()) {
            this.token = authToken
            if (_connectionState.value == ConnectionState.DISCONNECTED && !isConnecting.get()) {
                manuallyDisconnected = false
                connectInternal()
            }
        }
    }

    fun getCurrentUrl(): String? = currentUrl

    fun getAuthToken(): String? = token

    fun disconnect() {
        manuallyDisconnected = true
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        updateConnectionState(ConnectionState.DISCONNECTED)
    }

    private fun isHttpMode(): Boolean {
        val url = currentUrl ?: return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun connectInternal() {
        if (isConnecting.get() || currentUrl == null || manuallyDisconnected) return
        isConnecting.set(true)
        updateConnectionState(ConnectionState.CONNECTING)

        val targetUrl = currentUrl!!

        if (isHttpMode()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val requestBuilder = Request.Builder()
                        .url("$targetUrl/api/agent/available")
                        .header("User-Agent", "aimon-mobile/1.0")
                        .get()

                    if (!token.isNullOrBlank()) {
                        requestBuilder.header("X-Mobile-Token", token!!)
                        requestBuilder.header("Authorization", "Bearer $token")
                    }

                    val resp = client.newCall(requestBuilder.build()).execute()
                    val body = resp.body?.string() ?: ""

                    if (resp.isSuccessful) {
                        Log.d("BridgeWebSocket", "HTTP Bridge connected to $targetUrl")
                        isConnecting.set(false)
                        reconnectAttempt = 0
                        updateConnectionState(ConnectionState.CONNECTED)

                        val agentsMsg = gson.fromJson(body, InboundMessage.AvailableAgents::class.java)
                        _messages.emit(agentsMsg)

                        // Also fetch conversations / history
                        fetchHttpConversations("")
                    } else {
                        Log.e("BridgeWebSocket", "HTTP Bridge connection failed with code: ${resp.code}")
                        isConnecting.set(false)
                        updateConnectionState(ConnectionState.DISCONNECTED)
                        scheduleReconnect()
                    }
                } catch (e: Exception) {
                    Log.e("BridgeWebSocket", "HTTP Bridge connection exception", e)
                    isConnecting.set(false)
                    updateConnectionState(ConnectionState.DISCONNECTED)
                    scheduleReconnect()
                }
            }
            return
        }

        try {
            val requestBuilder = Request.Builder()
                .url(currentUrl!!)
                .header("Bypass-Tunnel-Reminder", "true")
                
            if (!token.isNullOrBlank()) {
                requestBuilder.header("X-Mobile-Token", token!!)
                requestBuilder.header("Authorization", "Bearer $token")
            }
                
            if (clientId != null && clientSecret != null) {
                requestBuilder.header("CF-Access-Client-Id", clientId!!)
                requestBuilder.header("CF-Access-Client-Secret", String(clientSecret!!))
                // Note: Not clearing the secret to allow reconnects
            }
                
            val request = requestBuilder.build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d("BridgeWebSocket", "Connected to $currentUrl")
                    isConnecting.set(false)
                    
                    if (pubKey != null) {
                        val random = java.security.SecureRandom()
                        val nonce = ByteArray(32)
                        random.nextBytes(nonce)
                        pendingChallenge = nonce
                        
                        val challengeBase64 = android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP)
                        val authMsg = OutboundMessage.AuthChallenge(challengeBase64)
                        webSocket.send(gson.toJson(authMsg))
                        Log.d("BridgeWebSocket", "Sent AUTH_CHALLENGE, waiting for signature...")
                        
                        authTimeoutJob = scope.launch {
                            delay(5000)
                            Log.e("BridgeWebSocket", "AUTH_RESPONSE timeout. Terminating connection.")
                            webSocket.close(4001, "Timeout waiting for AUTH_RESPONSE")
                            updateConnectionState(ConnectionState.DISCONNECTED)
                        }
                    } else {
                        // Fallback/Legacy
                        updateConnectionState(ConnectionState.CONNECTED)
                    }
                }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch {
                    try {
                        val jsonObject = JsonParser.parseString(text).asJsonObject
                        
                        val payloadText = if (jsonObject.has("iv") && jsonObject.has("ct") && jsonObject.has("aad")) {
                            e2eeSession?.decrypt(
                                jsonObject.get("iv").asString,
                                jsonObject.get("ct").asString,
                                jsonObject.get("aad").asString,
                                "output"
                            ) ?: throw java.lang.IllegalStateException("Received encrypted packet but E2EE session is not initialized")
                        } else {
                            text
                        }

                        val payloadObj = JsonParser.parseString(payloadText).asJsonObject
                        val type = payloadObj.get("type").asString
                        Log.d("BridgeWebSocket", "Decrypted and parsed message type: $type")
                        
                        val message = when (type) {
                            "PONG" -> InboundMessage.Pong()
                            "SESSION_STATE" -> gson.fromJson(payloadText, InboundMessage.SessionState::class.java)
                            "STEP" -> gson.fromJson(payloadText, InboundMessage.Step::class.java)
                            "STEP_BATCH" -> gson.fromJson(payloadText, InboundMessage.StepBatch::class.java)
                            "GENERATING" -> gson.fromJson(payloadText, InboundMessage.Generating::class.java)
                            "RESPONSE_COMPLETE" -> gson.fromJson(payloadText, InboundMessage.ResponseComplete::class.java)
                            "ACTIVE_FILE" -> gson.fromJson(payloadText, InboundMessage.ActiveFile::class.java)
                            "ARTIFACT_CONTENT" -> gson.fromJson(payloadText, InboundMessage.ArtifactContent::class.java)
                            "FILE_CONTENT" -> gson.fromJson(payloadText, InboundMessage.FileContent::class.java)
                            "FILE_TREE" -> gson.fromJson(payloadText, InboundMessage.FileTree::class.java)
                            "CLOUDFLARE_URL" -> gson.fromJson(payloadText, InboundMessage.CloudflareUrl::class.java)
                            "HISTORY_LIST" -> gson.fromJson(payloadText, InboundMessage.HistoryList::class.java)
                            "AVAILABLE_AGENTS" -> gson.fromJson(payloadText, InboundMessage.AvailableAgents::class.java)
                            "ERROR" -> gson.fromJson(payloadText, InboundMessage.Error::class.java)
                            "SYSTEM_ALERT" -> gson.fromJson(payloadText, InboundMessage.SystemAlert::class.java)
                            "SYSTEM_NOTIFICATION" -> gson.fromJson(payloadText, InboundMessage.SystemNotification::class.java)
                            "AUTH_RESPONSE" -> gson.fromJson(payloadText, InboundMessage.AuthResponse::class.java)
                            "ARTIFACTS_LIST" -> gson.fromJson(payloadText, InboundMessage.ArtifactsList::class.java)
                            "PTY_OUTPUT" -> gson.fromJson(payloadText, InboundMessage.PtyOutput::class.java)
                            "ACK" -> gson.fromJson(payloadText, InboundMessage.Ack::class.java)
                            else -> InboundMessage.Unknown
                        }
                        
                        Log.d("BridgeWebSocket", "Mapped to message class: ${message.javaClass.simpleName}")
                        
                        if (message is InboundMessage.AuthResponse) {
                            authTimeoutJob?.cancel()
                            authTimeoutJob = null
                            
                            val pubKeyStr = pubKey
                            val challenge = pendingChallenge
                            pendingChallenge = null
                            
                            if (pubKeyStr != null && challenge != null) {
                                try {
                                    val signatureBytes = android.util.Base64.decode(message.signature, android.util.Base64.DEFAULT)
                                    val pubKeyRawBytes = android.util.Base64.decode(pubKeyStr, android.util.Base64.DEFAULT)
                                    
                                    // RFC 8410 SPKI prefix for Ed25519 (OID 1.3.101.112)
                                    val prefix = byteArrayOf(
                                        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
                                    )
                                    val reconstructedSpki = prefix + pubKeyRawBytes
                                    
                                    val kf = java.security.KeyFactory.getInstance("Ed25519")
                                    val publicKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(reconstructedSpki))
                                    
                                    val verifier = java.security.Signature.getInstance("Ed25519")
                                    verifier.initVerify(publicKey)
                                    verifier.update(challenge)
                                    val isValid = verifier.verify(signatureBytes)
                                    
                                    if (isValid) {
                                        Log.d("BridgeWebSocket", "Ed25519 Handshake Verified. Initializing E2EE.")
                                        try {
                                            val e2ee = E2EESession()
                                            e2ee.deriveSessionKeys(message.pubkey)
                                            e2eeSession = e2ee
                                            
                                             val helloMsg = OutboundMessage.Hello(e2ee.publicKeyBase64)
                                            webSocket.send(gson.toJson(helloMsg))
                                            Log.d("BridgeWebSocket", "Sent HELLO X25519 payload. E2EE Established.")
                                            
                                            updateConnectionState(ConnectionState.CONNECTED)
                                            reconnectAttempt = 0
                                        } catch (e: Exception) {
                                            Log.e("BridgeWebSocket", "E2EE Handshake failed", e)
                                            webSocket.close(4003, "Crypto Error")
                                            updateConnectionState(ConnectionState.DISCONNECTED)
                                        }
                                    } else {
                                        Log.e("BridgeWebSocket", "Ed25519 Signature INVALID. Aborting connection.")
                                        webSocket.close(4003, "Invalid Signature")
                                        updateConnectionState(ConnectionState.DISCONNECTED)
                                    }
                                } catch (e: Exception) {
                                    Log.e("BridgeWebSocket", "Error verifying Ed25519 signature", e)
                                    webSocket.close(4003, "Crypto Error")
                                    updateConnectionState(ConnectionState.DISCONNECTED)
                                }
                            }
                            return@launch
                        } else if (message is InboundMessage.Ack) {
                            val pending = pendingAcks.remove(message.id)
                            pending?.timeoutJob?.get()?.cancel()
                            Log.d("BridgeWebSocket", "ACK received for ${message.id}")
                            return@launch
                        } else if (message is InboundMessage.SystemNotification) {
                            showSystemNotification(message.title, message.body)
                            return@launch
                        }
                        
                        Log.d("BridgeWebSocket", "Emitting message to flow: ${message.javaClass.simpleName}")
                        _messages.emit(message)
                    } catch (e: Exception) {
                        Log.e("BridgeWebSocket", "Failed to parse message: $text", e)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("BridgeWebSocket", "Closed: $reason")
                isConnecting.set(false)
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                updateConnectionState(ConnectionState.DISCONNECTED)
                if (!manuallyDisconnected) {
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("BridgeWebSocket", "Failure: ${t.message}")
                isConnecting.set(false)
                authTimeoutJob?.cancel()
                authTimeoutJob = null
                updateConnectionState(ConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        })
        } catch (e: Exception) {
            isConnecting.set(false)
            updateConnectionState(ConnectionState.DISCONNECTED)
            Log.e("BridgeWebSocket", "Failed to create request", e)
        }
    }

    private fun scheduleReconnect() {
        if (manuallyDisconnected || currentUrl == null) return
        if (reconnectAttempt >= 20) {
            scope.launch {
                _messages.emit(InboundMessage.Error("Failed to connect after 20 attempts. Please check your network or restart the app."))
            }
            return
        }
        
        reconnectAttempt++
        // Exponential backoff: max 30s
        val delayMs = (2.0.pow(reconnectAttempt.coerceAtMost(5)).toLong() * 1000).coerceAtMost(30000)
        
        Log.d("BridgeWebSocket", "Scheduling reconnect in ${delayMs}ms (Attempt $reconnectAttempt)")
        scope.launch {
            delay(delayMs)
            connectInternal()
        }
    }

    fun sendMessage(message: OutboundMessage) {
        if (isHttpMode()) {
            scope.launch(Dispatchers.IO) {
                try {
                    val url = currentUrl ?: return@launch
                    when (message) {
                        is OutboundMessage.SendMessage -> {
                            val reqCid = message.conversationId?.takeIf { it.isNotBlank() } ?: "new"
                            val payloadMap = mutableMapOf<String, Any>(
                                "text" to message.text,
                                "prompt" to message.text,
                                "conversationId" to reqCid,
                                "agentId" to (message.agentId ?: "antigravity"),
                                "autoApprove" to message.autoApprove,
                                "images" to (message.images ?: emptyList<String>())
                            )
                            if (message.model.isNotBlank()) {
                                payloadMap["model"] = message.model
                            }
                            if (message.source.isNotBlank()) {
                                payloadMap["source"] = message.source
                            }
                            if (!message.questionResponses.isNullOrEmpty()) {
                                payloadMap["questionResponses"] = message.questionResponses.map { r ->
                                    mapOf(
                                        "question" to r.question,
                                        "selectedOptions" to r.selectedOptions,
                                        "writeInResponse" to r.writeInResponse
                                    )
                                }
                            }
                            val reqBody = gson.toJson(payloadMap)
                            val req = Request.Builder()
                                .url("$url/api/agent/prompt")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .post(reqBody.toRequestBody("application/json".toMediaType()))
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                _messages.emit(InboundMessage.Ack(message.id ?: ""))

                                try {
                                    val json = JsonParser.parseString(body).asJsonObject
                                    val cid = json.get("conversationId")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                    val status = json.get("status")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                    val respSource = optJsonString(json, "source") ?: ""
                                    val respModel = optJsonString(json, "model") ?: ""
                                    if (status == "cleared") {
                                        _messages.emit(InboundMessage.SessionState(
                                            conversationId = "new",
                                            stepCount = 0,
                                            model = "default",
                                            agentId = message.agentId ?: "antigravity",
                                            source = respSource
                                        ))
                                        _messages.emit(InboundMessage.ResponseComplete(
                                            conversationId = "new",
                                            agentId = message.agentId ?: "antigravity"
                                        ))
                                    } else if (status == "compacted") {
                                        val parsedSteps = parseTranscriptStepsFromJson(json, message.agentId ?: "antigravity")
                                        val resolvedCompactedCid = cid.ifBlank { reqCid }
                                        val respWorkspace = optJsonString(json, "workspace") ?: ""
                                        _messages.emit(InboundMessage.SessionState(
                                            conversationId = resolvedCompactedCid,
                                            stepCount = parsedSteps.size,
                                            model = respModel.ifBlank { message.model },
                                            agentId = message.agentId ?: "antigravity",
                                            source = respSource,
                                            workspace = respWorkspace
                                        ))
                                        if (parsedSteps.isNotEmpty()) {
                                            _messages.emit(InboundMessage.StepBatch(
                                                steps = parsedSteps,
                                                agentId = message.agentId ?: "antigravity",
                                                conversationId = resolvedCompactedCid.ifBlank { null }
                                            ))
                                        } else if (json.has("response") && json.get("response").isJsonPrimitive) {
                                            val respText = json.get("response").asString
                                            if (respText.isNotBlank()) {
                                                _messages.emit(InboundMessage.Step(
                                                    step = TrajectoryStep(case = "text", value = respText),
                                                    index = -1,
                                                    agentId = message.agentId ?: "antigravity"
                                                ))
                                            }
                                        }
                                        _messages.emit(InboundMessage.ResponseComplete(
                                            conversationId = resolvedCompactedCid,
                                            agentId = message.agentId ?: "antigravity"
                                        ))
                                    } else if (status == "command_response" && json.has("response") && json.get("response").isJsonPrimitive) {
                                        val respText = json.get("response").asString
                                        _messages.emit(InboundMessage.Step(
                                            step = TrajectoryStep(case = "text", value = respText, stepIndex = -1),
                                            index = -1,
                                            agentId = message.agentId ?: "antigravity"
                                        ))
                                    } else {
                                        if (cid.isNotBlank() && cid != "new") {
                                            _messages.emit(InboundMessage.Generating(
                                                conversationId = cid,
                                                agentId = message.agentId ?: "antigravity"
                                            ))
                                        }
                                        if (json.has("steps") && json.get("steps").isJsonArray) {
                                            val parsedSteps = parseTranscriptStepsFromJson(json, message.agentId ?: "antigravity")
                                            if (parsedSteps.isNotEmpty()) {
                                                _messages.emit(InboundMessage.StepBatch(
                                                    steps = parsedSteps,
                                                    agentId = message.agentId ?: "antigravity",
                                                    conversationId = cid.ifBlank { null }
                                                ))
                                            }
                                            _messages.emit(InboundMessage.ResponseComplete(conversationId = cid, agentId = message.agentId ?: "antigravity"))
                                        } else if (json.has("response") && json.get("response").isJsonPrimitive) {
                                            val respText = json.get("response").asString
                                            _messages.emit(InboundMessage.Step(
                                                step = TrajectoryStep(case = "text", value = respText),
                                                index = -1,
                                                agentId = message.agentId ?: "antigravity"
                                            ))
                                            _messages.emit(InboundMessage.ResponseComplete(conversationId = cid, agentId = message.agentId ?: "antigravity"))
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("BridgeWebSocket", "Error parsing prompt response", e)
                                    _messages.emit(InboundMessage.ResponseComplete(agentId = message.agentId ?: "antigravity"))
                                }

                                // Refresh history list
                                fetchHttpConversations("")
                            } else {
                                val errDetail = try {
                                    val errJson = JsonParser.parseString(body).asJsonObject
                                    errJson.get("error")?.takeIf { !it.isJsonNull }?.asString
                                } catch (_: Exception) {
                                    null
                                }
                                val errMsg = if (!errDetail.isNullOrBlank()) {
                                    errDetail
                                } else {
                                    "Failed to send prompt: HTTP ${resp.code}"
                                }
                                _messages.emit(InboundMessage.Error(errMsg, agentId = message.agentId))
                                _messages.emit(InboundMessage.ResponseComplete(agentId = message.agentId ?: "antigravity"))
                            }
                        }
                        is OutboundMessage.ListAgents -> {
                            val req = Request.Builder()
                                .url("$url/api/agent/available")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val agentsMsg = gson.fromJson(body, InboundMessage.AvailableAgents::class.java)
                                _messages.emit(agentsMsg)
                            }
                        }
                        is OutboundMessage.GetHistory -> {
                            fetchHttpConversations("")
                        }
                        is OutboundMessage.SubscribeConversation -> {
                            val sinceParam = if (message.sinceStepIndex != null && message.sinceStepIndex >= 0) {
                                "&since=${message.sinceStepIndex}"
                            } else {
                                ""
                            }
                            val req = Request.Builder()
                                .url("$url/api/agent/transcript?id=${message.conversationId}$sinceParam")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val json = JsonParser.parseString(body).asJsonObject
                                val cid = if (json.has("conversationId") && !json.get("conversationId").isJsonNull) {
                                    json.get("conversationId").asString
                                } else {
                                    message.conversationId
                                }
                                val model = if (json.has("model") && !json.get("model").isJsonNull) {
                                    json.get("model").asString
                                } else {
                                    "gemini-4-argon-medium"
                                }
                                val stepCount = if (json.has("stepCount") && !json.get("stepCount").isJsonNull) {
                                    json.get("stepCount").asInt
                                } else {
                                    0
                                }
                                val source = optJsonString(json, "source") ?: ""
                                val workspace = optJsonString(json, "workspace") ?: ""
                                val steps = parseTranscriptStepsFromJson(json, message.agentId)

                                if (message.sinceStepIndex != null && message.sinceStepIndex >= 0 && stepCount > 0 && stepCount <= message.sinceStepIndex) {
                                    val fullReq = Request.Builder()
                                        .url("$url/api/agent/transcript?id=${message.conversationId}")
                                        .header("User-Agent", "aimon-mobile/1.0")
                                        .apply {
                                            if (!token.isNullOrBlank()) {
                                                header("X-Mobile-Token", token!!)
                                                header("Authorization", "Bearer $token")
                                            }
                                        }
                                        .get()
                                        .build()
                                    val fullResp = client.newCall(fullReq).execute()
                                    val fullBody = fullResp.body?.string() ?: ""
                                    if (fullResp.isSuccessful) {
                                        val fullJson = JsonParser.parseString(fullBody).asJsonObject
                                        val fullCid = if (fullJson.has("conversationId") && !fullJson.get("conversationId").isJsonNull) {
                                            fullJson.get("conversationId").asString
                                        } else {
                                            cid
                                        }
                                        val fullModel = if (fullJson.has("model") && !fullJson.get("model").isJsonNull) {
                                            fullJson.get("model").asString
                                        } else {
                                            model
                                        }
                                        val fullStepCount = if (fullJson.has("stepCount") && !fullJson.get("stepCount").isJsonNull) {
                                            fullJson.get("stepCount").asInt
                                        } else {
                                            0
                                        }
                                        val fullSource = optJsonString(fullJson, "source") ?: source
                                        val fullWorkspace = optJsonString(fullJson, "workspace") ?: workspace
                                        val fullSteps = parseTranscriptStepsFromJson(fullJson, message.agentId)
                                        _messages.emit(InboundMessage.SessionState(
                                            conversationId = fullCid,
                                            model = fullModel,
                                            stepCount = fullStepCount,
                                            agentId = message.agentId,
                                            source = fullSource,
                                            workspace = fullWorkspace
                                        ))
                                        _messages.emit(InboundMessage.StepBatch(
                                            steps = fullSteps,
                                            agentId = message.agentId,
                                            conversationId = fullCid
                                        ))
                                    }
                                } else {
                                    if (message.lastKnownStepCount == 0 && (message.sinceStepIndex == null || message.sinceStepIndex < 0)) {
                                        _messages.emit(InboundMessage.SessionState(
                                            conversationId = cid,
                                            model = model,
                                            stepCount = stepCount,
                                            agentId = message.agentId,
                                            source = source,
                                            workspace = workspace
                                        ))
                                    }
                                    if (steps.isNotEmpty() || message.sinceStepIndex == null || message.sinceStepIndex < 0) {
                                        _messages.emit(InboundMessage.StepBatch(
                                            steps = steps,
                                            agentId = message.agentId,
                                            conversationId = cid
                                        ))
                                    }
                                }
                            }
                        }
                        is OutboundMessage.GetArtifacts -> {
                            val req = Request.Builder()
                                .url("$url/api/agent/artifacts?id=${message.conversationId}")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val artifactsMsg = gson.fromJson(body, InboundMessage.ArtifactsList::class.java)
                                _messages.emit(artifactsMsg)
                            }
                        }
                        is OutboundMessage.ReadArtifact -> {
                            val req = Request.Builder()
                                .url("$url/api/workspace/file?path=${message.path}")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val json = JsonParser.parseString(body).asJsonObject
                                val content = optJsonString(json, "content") ?: ""
                                val lang = optJsonString(json, "language") ?: ""
                                _messages.emit(InboundMessage.ArtifactContent(
                                    path = message.path,
                                    content = content,
                                    language = lang,
                                    agentId = message.agentId
                                ))
                            }
                        }
                        is OutboundMessage.GetFiles -> {
                            val pathQuery = if (!message.path.isNullOrBlank()) "?path=${java.net.URLEncoder.encode(message.path, "UTF-8")}" else ""
                            val req = Request.Builder()
                                .url("$url/api/workspace/tree$pathQuery")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val treeMsg = gson.fromJson(body, InboundMessage.FileTree::class.java)
                                _messages.emit(treeMsg)
                            }
                        }
                        is OutboundMessage.ReadFile -> {
                            val req = Request.Builder()
                                .url("$url/api/workspace/file?path=${java.net.URLEncoder.encode(message.path, "UTF-8")}")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .get()
                                .build()
                            val resp = client.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (resp.isSuccessful) {
                                val fileMsg = gson.fromJson(body, InboundMessage.FileContent::class.java)
                                _messages.emit(fileMsg)
                            }
                        }
                        is OutboundMessage.WriteFile -> {
                            val reqBody = gson.toJson(mapOf(
                                "path" to message.path,
                                "content" to message.content
                            ))
                            val req = Request.Builder()
                                .url("$url/api/workspace/file")
                                .header("User-Agent", "aimon-mobile/1.0")
                                .apply {
                                    if (!token.isNullOrBlank()) {
                                        header("X-Mobile-Token", token!!)
                                        header("Authorization", "Bearer $token")
                                    }
                                }
                                .post(reqBody.toRequestBody("application/json".toMediaType()))
                                .build()
                            val resp = client.newCall(req).execute()
                            if (resp.isSuccessful) {
                                _messages.emit(InboundMessage.Ack(message.id ?: ""))
                            }
                        }
                        is OutboundMessage.NewConversation -> {
                            _messages.emit(InboundMessage.SessionState(
                                conversationId = "new",
                                model = "gemini-4-argon-medium",
                                stepCount = 0,
                                agentId = message.agentId
                            ))
                            _messages.emit(InboundMessage.Ack(message.id ?: ""))
                        }
                        is OutboundMessage.CancelResponse -> {
                            _messages.emit(InboundMessage.ResponseComplete(agentId = message.agentId))
                        }
                        else -> {
                            _messages.emit(InboundMessage.Ack(message.id ?: ""))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BridgeWebSocket", "HTTP REST Error for message: $message", e)
                    _messages.emit(InboundMessage.Error(e.message ?: "Network error"))
                }
            }
            return
        }

        val json = gson.toJson(message)
        val payload = if (e2eeSession != null && message !is OutboundMessage.AuthChallenge && message !is OutboundMessage.Hello) {
            val env = e2eeSession!!.encrypt(json, "cmd:")
            gson.toJson(env)
        } else {
            json
        }
        Log.d("BridgeWebSocket", "Sending: ${payload.take(100)}")
        webSocket?.send(payload)
    }

    fun sendWithRetry(messageBuilder: (String) -> OutboundMessage) {
        val id = UUID.randomUUID().toString()
        val message = messageBuilder(id)
        val pending = PendingMessage(id, message, System.currentTimeMillis())
        pendingAcks[id] = pending
        
        sendMessage(message)
        scheduleAckTimeout(pending)
    }

    private fun scheduleAckTimeout(pending: PendingMessage) {
        pending.timeoutJob.getAndSet(null)?.cancel()
        val newJob = scope.launch {
            delay(5000) // 5 second timeout
            if (pendingAcks.containsKey(pending.id)) {
                val retries = pending.retryCount.incrementAndGet()
                if (retries <= 3) {
                    Log.w("BridgeWebSocket", "No ACK for ${pending.id}, retrying ($retries/3)...")
                    sendMessage(pending.message)
                    scheduleAckTimeout(pending)
                } else {
                    Log.e("BridgeWebSocket", "Failed to deliver message ${pending.id} after 3 retries.")
                    pendingAcks.remove(pending.id)
                    scope.launch {
                        _messages.emit(InboundMessage.Error("Message could not be delivered to the agent."))
                    }
                }
            }
        }
        pending.timeoutJob.set(newJob)
    }

    private fun showSystemNotification(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("BridgeWebSocket", "Missing POST_NOTIFICATIONS permission")
                return
            }
        }

        val channelId = "antimatter_system_notifications"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        // Just use an implicit intent or a simple launch intent
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationIdCounter.incrementAndGet(), notification)
    }

    fun searchHttpConversations(query: String) {
        if (!isHttpMode()) return
        scope.launch(Dispatchers.IO) {
            fetchHttpConversations(query)
        }
    }

    private suspend fun fetchHttpConversations(query: String = "") {
        val base = currentUrl ?: return
        try {
            val qParam = if (query.isNotBlank()) {
                "?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            } else {
                ""
            }
            val req = Request.Builder()
                .url("$base/api/agent/conversations$qParam")
                .header("User-Agent", "aimon-mobile/1.0")
                .apply {
                    if (!token.isNullOrBlank()) {
                        header("X-Mobile-Token", token!!)
                        header("Authorization", "Bearer $token")
                    }
                }
                .get()
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful && body.isNotBlank()) {
                val histMsg = parseHistoryListFromJson(body, isSearchResult = query.isNotBlank())
                _messages.emit(histMsg)
            }
        } catch (e: Exception) {
            Log.w("BridgeWebSocket", "Failed to fetch HTTP conversations", e)
        }
    }

    private fun parseHistoryListFromJson(body: String, isSearchResult: Boolean = false): InboundMessage.HistoryList {
        val parsed = JsonParser.parseString(body)
        var agentId: String? = "antigravity"
        val convArray = when {
            parsed.isJsonObject -> {
                val root = parsed.asJsonObject
                agentId = optJsonString(root, "agentId") ?: "antigravity"
                if (root.has("conversations") && root.get("conversations").isJsonArray) {
                    root.getAsJsonArray("conversations")
                } else {
                    null
                }
            }
            parsed.isJsonArray -> parsed.asJsonArray
            else -> null
        }

        val summaries = mutableListOf<ConversationSummary>()
        if (convArray != null) {
            for (elem in convArray) {
                if (!elem.isJsonObject) continue
                val obj = elem.asJsonObject
                val id = optJsonString(obj, "id")?.takeIf { it.isNotBlank() } ?: continue
                val rawTitle = optJsonString(obj, "title") ?: ""
                val rawTs = optJsonLong(obj, "timestamp", 0L)
                val timestampMs = when {
                    rawTs in 1L..9_999_999_999L -> rawTs * 1000L
                    rawTs > 0L -> rawTs
                    else -> System.currentTimeMillis()
                }
                val source = optJsonString(obj, "source")?.takeIf { it.isNotBlank() } ?: "CLI"
                val workspace = optJsonString(obj, "workspace") ?: ""
                val workspacePath = optJsonString(obj, "workspacePath") ?: ""
                val model = optJsonString(obj, "model") ?: ""
                val active = optJsonBool(obj, "active", false)
                val openInTerminal = optJsonBool(obj, "openInTerminal", false)
                val terminalPts = optJsonString(obj, "terminalPts") ?: ""
                val stepCount = optJsonInt(obj, "stepCount") ?: 0
                val firstPrompt = optJsonString(obj, "firstPrompt") ?: ""
                val lastPrompt = optJsonString(obj, "lastPrompt") ?: ""
                val lastPromptStepIndex = optJsonInt(obj, "lastPromptStepIndex") ?: -1
                val matchedPrompt = optJsonString(obj, "matchedPrompt") ?: ""
                val matchedStepIndex = optJsonInt(obj, "matchedStepIndex") ?: -1

                val prompts = mutableListOf<PromptEntry>()
                if (obj.has("userPrompts") && obj.get("userPrompts").isJsonArray) {
                    val pArr = obj.getAsJsonArray("userPrompts")
                    for (pElem in pArr) {
                        if (!pElem.isJsonObject) continue
                        val pObj = pElem.asJsonObject
                        val pIdx = optJsonInt(pObj, "stepIndex") ?: -1
                        val pText = optJsonString(pObj, "text") ?: ""
                        val pTs = optJsonString(pObj, "timestamp") ?: ""
                        if (pText.isNotBlank()) {
                            prompts.add(PromptEntry(stepIndex = pIdx, text = pText, timestamp = pTs))
                        }
                    }
                }

                val trimmedRawTitle = rawTitle.trim()
                val isPlaceholderTitle = trimmedRawTitle.isEmpty() ||
                    trimmedRawTitle.equals("Chat", ignoreCase = true) ||
                    Regex("^Conversation [0-9a-fA-F]{8}$").matches(trimmedRawTitle)
                val effectiveTitle = trimmedRawTitle.takeIf { !isPlaceholderTitle }
                    ?: firstPrompt.takeIf { it.isNotBlank() }?.take(80)
                    ?: lastPrompt.takeIf { it.isNotBlank() }?.take(80)
                    ?: continue

                summaries.add(
                    ConversationSummary(
                        id = id,
                        title = effectiveTitle,
                        timestamp = timestampMs,
                        source = source,
                        workspace = workspace,
                        workspacePath = workspacePath,
                        model = model,
                        active = active,
                        openInTerminal = openInTerminal,
                        terminalPts = terminalPts,
                        stepCount = stepCount,
                        firstPrompt = firstPrompt,
                        lastPrompt = lastPrompt,
                        lastPromptStepIndex = lastPromptStepIndex,
                        userPrompts = prompts,
                        matchedPrompt = matchedPrompt,
                        matchedStepIndex = matchedStepIndex
                    )
                )
            }
        }
        return InboundMessage.HistoryList(
            conversations = summaries,
            agentId = agentId,
            isSearchResult = isSearchResult
        )
    }

    companion object {
        private val staticGson = Gson()

        fun optJsonBool(obj: com.google.gson.JsonObject?, key: String, defaultVal: Boolean = false): Boolean {
            if (obj == null || !obj.has(key)) return defaultVal
            val el = obj.get(key)
            if (el == null || el.isJsonNull || !el.isJsonPrimitive) return defaultVal
            return try {
                el.asBoolean
            } catch (_: Exception) {
                defaultVal
            }
        }

        fun optJsonLong(obj: com.google.gson.JsonObject?, key: String, defaultVal: Long = 0L): Long {
            if (obj == null || !obj.has(key)) return defaultVal
            val el = obj.get(key)
            if (el == null || el.isJsonNull || !el.isJsonPrimitive) return defaultVal
            return try {
                el.asLong
            } catch (_: Exception) {
                defaultVal
            }
        }

        fun optJsonString(obj: com.google.gson.JsonObject?, key: String): String? {
            if (obj == null || !obj.has(key)) return null
            val el = obj.get(key)
            if (el == null || el.isJsonNull) return null
            return if (el.isJsonPrimitive) el.asString else el.toString()
        }

        fun optJsonInt(obj: com.google.gson.JsonObject?, key: String): Int? {
            if (obj == null || !obj.has(key)) return null
            val el = obj.get(key)
            if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
            return try {
                el.asInt
            } catch (_: Exception) {
                null
            }
        }

        fun parseQuestionsArrayFromJsonElement(
            rawQuestionsElem: com.google.gson.JsonElement?,
            fallbackAnswers: List<String> = emptyList()
        ): List<AskQuestionPrompt> {
            if (rawQuestionsElem == null || rawQuestionsElem.isJsonNull) return emptyList()
            val qArr: com.google.gson.JsonArray = when {
                rawQuestionsElem.isJsonArray -> rawQuestionsElem.asJsonArray
                rawQuestionsElem.isJsonPrimitive && rawQuestionsElem.asJsonPrimitive.isString -> {
                    try {
                        val parsed = JsonParser.parseString(rawQuestionsElem.asString)
                        if (parsed.isJsonArray) parsed.asJsonArray else return emptyList()
                    } catch (_: Exception) {
                        return emptyList()
                    }
                }
                else -> return emptyList()
            }
            val result = mutableListOf<AskQuestionPrompt>()
            for ((qIdx, qElem) in qArr.withIndex()) {
                if (!qElem.isJsonObject) continue
                val qObj = qElem.asJsonObject
                val qText = optJsonString(qObj, "question")?.trim()
                    ?: optJsonString(qObj, "prompt")?.trim()
                    ?: optJsonString(qObj, "text")?.trim()
                    ?: ""
                if (qText.isEmpty()) continue
                val isMulti = optJsonBool(
                    qObj,
                    "is_multi_select",
                    optJsonBool(qObj, "isMultiSelect", false)
                )
                val optionsList = mutableListOf<String>()
                if (qObj.has("options") && qObj.get("options").isJsonArray) {
                    for (optElem in qObj.getAsJsonArray("options")) {
                        when {
                            optElem.isJsonPrimitive -> {
                                val s = optElem.asString.trim()
                                if (s.isNotEmpty()) optionsList.add(s)
                            }
                            optElem.isJsonObject -> {
                                val oObj = optElem.asJsonObject
                                val s = (optJsonString(oObj, "label")
                                    ?: optJsonString(oObj, "text")
                                    ?: optJsonString(oObj, "value")
                                    ?: "").trim()
                                if (s.isNotEmpty()) optionsList.add(s)
                            }
                        }
                    }
                }
                val selectedList = mutableListOf<String>()
                val selArr = when {
                    qObj.has("selected_answers") && qObj.get("selected_answers").isJsonArray ->
                        qObj.getAsJsonArray("selected_answers")
                    qObj.has("selectedAnswers") && qObj.get("selectedAnswers").isJsonArray ->
                        qObj.getAsJsonArray("selectedAnswers")
                    else -> null
                }
                if (selArr != null) {
                    for (sElem in selArr) {
                        if (sElem.isJsonPrimitive) {
                            val s = sElem.asString.trim()
                            if (s.isNotEmpty()) selectedList.add(s)
                        }
                    }
                } else if (qIdx < fallbackAnswers.size && fallbackAnswers[qIdx].isNotBlank()) {
                    selectedList.add(fallbackAnswers[qIdx])
                }
                result.add(
                    AskQuestionPrompt(
                        question = qText,
                        options = optionsList,
                        isMultiSelect = isMulti,
                        selectedAnswers = selectedList
                    )
                )
            }
            return result
        }

        fun parseTranscriptStepsFromJson(
            json: com.google.gson.JsonObject,
            agentId: String? = null
        ): List<InboundMessage.Step> {
            val result = mutableListOf<InboundMessage.Step>()
            if (!json.has("steps") || !json.get("steps").isJsonArray) {
                return result
            }
            val stepsArr = json.getAsJsonArray("steps")
            for (elem in stepsArr) {
                if (!elem.isJsonObject) continue
                val elemObj = elem.asJsonObject
                val rawStepIdx = optJsonInt(elemObj, "stepIndex")
                    ?: optJsonInt(elemObj, "index")
                    ?: -1
                val typeStr = optJsonString(elemObj, "type") ?: ""
                val stepObj = if (elemObj.has("step") && elemObj.get("step").isJsonObject) {
                    elemObj.getAsJsonObject("step")
                } else {
                    null
                }
                val caseVal = optJsonString(stepObj, "case") ?: ""
                val askObj = if (elemObj.has("askQuestion") && elemObj.get("askQuestion").isJsonObject) {
                    elemObj.getAsJsonObject("askQuestion")
                } else {
                    null
                }
                val tcArr = if (elemObj.has("tool_calls") && elemObj.get("tool_calls").isJsonArray) {
                    elemObj.getAsJsonArray("tool_calls")
                } else {
                    null
                }
                val askToolCallObj = tcArr?.firstOrNull { tcEl ->
                    if (!tcEl.isJsonObject) false else {
                        val n = optJsonString(tcEl.asJsonObject, "name") ?: ""
                        n == "ask_question" || n == "AskUserQuestion"
                    }
                }?.asJsonObject
                val hasAskQuestion = typeStr == "ASK_QUESTION" ||
                    caseVal == "askQuestion" ||
                    askObj != null ||
                    askToolCallObj != null

                if (typeStr == "USER_INPUT" || caseVal == "userInput") {
                    val valStr = optJsonString(elemObj, "text")
                        ?: optJsonString(stepObj, "value")
                        ?: ""
                    if (valStr.isNotBlank()) {
                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "userInput",
                                    value = valStr,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    }
                } else if (
                    hasAskQuestion ||
                    typeStr == "PLANNER_RESPONSE" ||
                    typeStr == "TOOL_CALL" ||
                    elemObj.has("thinking") ||
                    elemObj.has("plannerResponse") ||
                    elemObj.has("tool_calls")
                ) {
                    val thinkingStr = optJsonString(elemObj, "thinking")
                        ?: optJsonString(elemObj, "plannerResponse")
                        ?: (if (caseVal == "plannerResponse") optJsonString(stepObj, "value") else null)

                    val textStr = optJsonString(elemObj, "text")
                        ?: (if (caseVal == "text" || caseVal == "markdownChunk") optJsonString(stepObj, "value") else null)

                    val hasNonAskToolCalls = tcArr != null && tcArr.any {
                        it.isJsonObject && (optJsonString(it.asJsonObject, "name") ?: "") !in listOf("ask_question", "AskUserQuestion")
                    }

                    if (hasNonAskToolCalls && !hasAskQuestion && !textStr.isNullOrBlank()) {
                        val combinedThought = if (!thinkingStr.isNullOrBlank()) {
                            "$thinkingStr\n\n$textStr"
                        } else {
                            textStr
                        }
                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "plannerResponse",
                                    value = combinedThought,
                                    thinking = combinedThought,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    } else if (!thinkingStr.isNullOrBlank()) {
                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "plannerResponse",
                                    value = thinkingStr,
                                    thinking = thinkingStr,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    }

                    if (tcArr != null) {
                        for (tcElem in tcArr) {
                            if (!tcElem.isJsonObject) continue
                            val tcObj = tcElem.asJsonObject
                            val name = optJsonString(tcObj, "name") ?: "tool"
                            if (name == "ask_question" || name == "AskUserQuestion") {
                                continue
                            }
                            val tAction = optJsonString(tcObj, "toolAction")
                            val tSummary = optJsonString(tcObj, "toolSummary")
                            val cmd = optJsonString(tcObj, "command")
                            val args = optJsonString(tcObj, "arguments")
                            val displayCmd = cmd?.takeIf { it.isNotBlank() }
                                ?: tAction?.takeIf { it.isNotBlank() }
                                ?: tSummary?.takeIf { it.isNotBlank() }
                                ?: args?.takeIf { it.isNotBlank() }
                                ?: name
                            result.add(
                                InboundMessage.Step(
                                    index = -1,
                                    step = TrajectoryStep(
                                        case = "toolCall",
                                        value = args ?: displayCmd,
                                        tool = name,
                                        command = displayCmd,
                                        toolAction = tAction,
                                        toolSummary = tSummary,
                                        stepIndex = rawStepIdx
                                    ),
                                    agentId = agentId
                                )
                            )
                        }
                    } else if (caseVal == "toolCall" && !hasAskQuestion) {
                        val toolName = optJsonString(stepObj, "tool") ?: "tool"
                        val cmd = optJsonString(stepObj, "command")
                        val valStr = optJsonString(stepObj, "value")
                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "toolCall",
                                    value = valStr,
                                    tool = toolName,
                                    command = cmd,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    }

                    if (!hasNonAskToolCalls && !textStr.isNullOrBlank() && typeStr != "ASK_QUESTION" && caseVal != "askQuestion") {
                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "text",
                                    value = textStr,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    }

                    if (hasAskQuestion) {
                        val fallbackAnswers = mutableListOf<String>()
                        if (askObj != null && askObj.has("answers") && askObj.get("answers").isJsonArray) {
                            for (aEl in askObj.getAsJsonArray("answers")) {
                                if (aEl.isJsonPrimitive) {
                                    val s = aEl.asString.trim()
                                    if (s.isNotEmpty()) fallbackAnswers.add(s)
                                }
                            }
                        }
                        var parsedQuestions = parseQuestionsArrayFromJsonElement(
                            askObj?.get("questions") ?: stepObj?.get("questions"),
                            fallbackAnswers
                        )
                        if (parsedQuestions.isEmpty() && askToolCallObj != null) {
                            val rawArgsEl = askToolCallObj.get("arguments") ?: askToolCallObj.get("args")
                            val argsJsonObj = when {
                                rawArgsEl == null || rawArgsEl.isJsonNull -> null
                                rawArgsEl.isJsonObject -> rawArgsEl.asJsonObject
                                rawArgsEl.isJsonPrimitive && rawArgsEl.asJsonPrimitive.isString -> {
                                    try {
                                        val p = JsonParser.parseString(rawArgsEl.asString)
                                        if (p.isJsonObject) p.asJsonObject else null
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                                else -> null
                            }
                            if (argsJsonObj != null) {
                                parsedQuestions = parseQuestionsArrayFromJsonElement(
                                    argsJsonObj.get("questions"),
                                    fallbackAnswers
                                )
                                if (parsedQuestions.isEmpty() && argsJsonObj.has("question")) {
                                    val singleArr = com.google.gson.JsonArray().apply { add(argsJsonObj) }
                                    parsedQuestions = parseQuestionsArrayFromJsonElement(singleArr, fallbackAnswers)
                                }
                            }
                        }

                        val isAnswered = when {
                            askObj != null && askObj.has("answered") ->
                                optJsonBool(askObj, "answered", false) || parsedQuestions.any { it.selectedAnswers.isNotEmpty() }
                            stepObj != null && stepObj.has("answered") ->
                                optJsonBool(stepObj, "answered", false) || parsedQuestions.any { it.selectedAnswers.isNotEmpty() }
                            elemObj.has("answered") ->
                                optJsonBool(elemObj, "answered", false) || parsedQuestions.any { it.selectedAnswers.isNotEmpty() }
                            else -> parsedQuestions.any { it.selectedAnswers.isNotEmpty() }
                        }

                        val summaryText = if (parsedQuestions.isNotEmpty()) {
                            parsedQuestions.joinToString("\n\n") { q ->
                                buildString {
                                    append(q.question)
                                    q.options.forEachIndexed { idx, opt ->
                                        append("\n${idx + 1}. $opt")
                                    }
                                }
                            }
                        } else {
                            optJsonString(elemObj, "text")?.takeIf { it.isNotBlank() }
                                ?: optJsonString(stepObj, "value")?.takeIf { it.isNotBlank() }
                                ?: "Agent Question"
                        }

                        result.add(
                            InboundMessage.Step(
                                index = -1,
                                step = TrajectoryStep(
                                    case = "askQuestion",
                                    value = summaryText,
                                    questions = parsedQuestions,
                                    isQuestionAnswered = isAnswered,
                                    stepIndex = rawStepIdx
                                ),
                                agentId = agentId
                            )
                        )
                    }
                } else if (typeStr == "ERROR_MESSAGE" || caseVal == "errorMessage") {
                    val errStr = optJsonString(elemObj, "text")
                        ?: optJsonString(stepObj, "value")
                        ?: "Agent execution error"
                    result.add(
                        InboundMessage.Step(
                            index = -1,
                            step = TrajectoryStep(
                                case = "errorMessage",
                                value = errStr,
                                stepIndex = rawStepIdx
                            ),
                            agentId = agentId
                        )
                    )
                } else if (stepObj != null) {
                    val parsedStep = staticGson.fromJson(stepObj, TrajectoryStep::class.java).copy(stepIndex = rawStepIdx)
                    result.add(
                        InboundMessage.Step(
                            index = -1,
                            step = parsedStep,
                            agentId = agentId
                        )
                    )
                }
            }
            return result
        }
    }
}

