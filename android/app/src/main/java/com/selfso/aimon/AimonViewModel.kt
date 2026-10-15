/*
 * AimonViewModel.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class AimonViewModel @Inject constructor() : ViewModel() {

    private val okHttpClient = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private val _quotaStatus = MutableStateFlow<QuotaStatus?>(null)
    val quotaStatus: StateFlow<QuotaStatus?> = _quotaStatus.asStateFlow()

    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    private val _satelliteStatus = MutableStateFlow<SatelliteStatus?>(SatelliteStatus())
    val satelliteStatus: StateFlow<SatelliteStatus?> = _satelliteStatus.asStateFlow()

    private val _isPaired = MutableStateFlow(false)
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _mobileToken = MutableStateFlow("")
    val mobileToken: StateFlow<String> = _mobileToken.asStateFlow()

    private val _deviceIdFlow = MutableStateFlow("")
    val deviceId: StateFlow<String> = _deviceIdFlow.asStateFlow()

    private val _baseUrlFlow = MutableStateFlow("http://127.0.0.1:3883")
    val baseUrlFlow: StateFlow<String> = _baseUrlFlow.asStateFlow()

    private var appContext: Context? = null
    private var prefs: SharedPreferences? = null
    private var baseUrl: String = "http://127.0.0.1:3883"
    private var sessionToken: String? = null
    private var currentDeviceId: String = "android-" + UUID.randomUUID().toString().take(12)
    private var pollingJob: Job? = null

    private val sseListener = object : SseEventListener {
        override fun onIncomingApproval(approval: PendingApproval) {
            val current = _pendingApprovals.value.toMutableList()
            current.removeAll { it.approvalId == approval.approvalId }
            current.add(0, approval)
            _pendingApprovals.value = current
        }

        override fun onApprovalResolved(approvalId: String) {
            _pendingApprovals.value = _pendingApprovals.value.filterNot { it.approvalId == approvalId }
        }

        override fun onRefreshRequested() {
            refreshAll()
        }
    }

    init {
        registerSseListener(sseListener)
        startPolling()
    }

    override fun onCleared() {
        unregisterSseListener(sseListener)
        pollingJob?.cancel()
        super.onCleared()
    }

    fun initPreferences(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp

        val storedUrl = sp.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }
        val storedToken = sp.getString(KEY_MOBILE_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: sp.getString(KEY_TOKEN_LEGACY, null)?.takeIf { it.isNotBlank() }
        val storedDeviceId = sp.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }

        if (storedDeviceId != null) {
            currentDeviceId = storedDeviceId
        } else {
            sp.edit().putString(KEY_DEVICE_ID, currentDeviceId).apply()
        }
        _deviceIdFlow.value = currentDeviceId

        if (storedUrl != null) {
            baseUrl = storedUrl.trimEnd('/')
            _baseUrlFlow.value = baseUrl
            currentBaseUrl = baseUrl
        }
        if (!storedToken.isNullOrBlank()) {
            sessionToken = storedToken
            _mobileToken.value = storedToken
            _isPaired.value = true
            currentMobileToken = storedToken
            AimonSseService.start(ctx, baseUrl, storedToken)
        }
        refreshAll()
    }

    fun getStoredBaseUrl(): String = baseUrl

    fun getStoredToken(): String = sessionToken ?: _mobileToken.value

    fun configureEndpoint(url: String, token: String? = null) {
        val normalized = if (url.endsWith("/")) url.dropLast(1) else url
        baseUrl = normalized
        _baseUrlFlow.value = normalized
        currentBaseUrl = normalized

        if (!token.isNullOrBlank()) {
            sessionToken = token
            _mobileToken.value = token
            _isPaired.value = true
            currentMobileToken = token
        }

        prefs?.edit()?.apply {
            putString(KEY_BASE_URL, baseUrl)
            if (!token.isNullOrBlank()) {
                putString(KEY_MOBILE_TOKEN, token)
                putString(KEY_TOKEN_LEGACY, token)
            }
            apply()
        }

        if (!sessionToken.isNullOrBlank()) {
            appContext?.let { AimonSseService.start(it, baseUrl, sessionToken) }
        }
        startPolling()
    }

    fun pairWithServer(
        host: String,
        port: Int,
        secret: String,
        deviceName: String = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Companion"
        } catch (_: Throwable) {
            "Android Companion"
        },
        onResult: (Boolean, String?, String?) -> Unit = { _, _, _ -> }
    ) {
        viewModelScope.launch {
            val pairResult = pairWithServerSuspend(host, port, secret, deviceName)
            if (pairResult.first) {
                onResult(true, pairResult.second, null)
            } else {
                onResult(false, null, pairResult.second)
            }
        }
    }

    suspend fun pairWithServerSuspend(
        host: String,
        port: Int,
        secret: String,
        deviceName: String = try {
            Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android Companion"
        } catch (_: Throwable) {
            "Android Companion"
        }
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .ifBlank { "127.0.0.1" }
        val resolvedPort = if (port in 1..65535) port else 3883
        val targetBaseUrl = "http://$cleanHost:$resolvedPort"
        val cleanSecret = secret.trim()

        if (cleanSecret.isEmpty()) {
            val err = "Pairing secret cannot be empty"
            _pairingError.value = err
            return@withContext Pair(false, err)
        }

        val payload = mapOf(
            "secret" to cleanSecret,
            "device_id" to currentDeviceId,
            "device_name" to deviceName
        )
        val jsonBody = gson.toJson(payload)
        val req = Request.Builder()
            .url("$targetBaseUrl/api/mobile/pair")
            .header("User-Agent", "aimon-mobile/1.0")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            okHttpClient.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val jsonObj = JsonParser.parseString(body).asJsonObject
                    val grantedToken = jsonObj.get("token")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val grantedDeviceId = jsonObj.get("device_id")?.takeIf { !it.isJsonNull }?.asString
                        ?.takeIf { it.isNotBlank() } ?: currentDeviceId

                    if (grantedToken.isNotBlank()) {
                        baseUrl = targetBaseUrl
                        sessionToken = grantedToken
                        currentDeviceId = grantedDeviceId
                        _baseUrlFlow.value = targetBaseUrl
                        _mobileToken.value = grantedToken
                        _deviceIdFlow.value = grantedDeviceId
                        _isPaired.value = true
                        _pairingError.value = null
                        currentBaseUrl = targetBaseUrl
                        currentMobileToken = grantedToken

                        prefs?.edit()?.apply {
                            putString(KEY_BASE_URL, targetBaseUrl)
                            putString(KEY_MOBILE_TOKEN, grantedToken)
                            putString(KEY_TOKEN_LEGACY, grantedToken)
                            putString(KEY_DEVICE_ID, grantedDeviceId)
                            apply()
                        }

                        appContext?.let { AimonSseService.start(it, targetBaseUrl, grantedToken) }
                        refreshAllInternal()
                        return@withContext Pair(true, grantedToken)
                    } else {
                        val err = "Server response missing session token"
                        _pairingError.value = err
                        return@withContext Pair(false, err)
                    }
                } else {
                    val serverErr = try {
                        val errObj = JsonParser.parseString(body).asJsonObject
                        errObj.get("error")?.takeIf { !it.isJsonNull }?.asString
                    } catch (_: Exception) {
                        null
                    }
                    val err = serverErr?.takeIf { it.isNotBlank() } ?: "Pairing failed (HTTP ${res.code})"
                    _pairingError.value = err
                    return@withContext Pair(false, err)
                }
            }
        } catch (e: Exception) {
            val err = e.message ?: "Network error while pairing with $targetBaseUrl"
            Log.e("AimonViewModel", "pairWithServer failed: $err", e)
            _pairingError.value = err
            return@withContext Pair(false, err)
        }
    }

    private fun buildAuthenticatedRequest(url: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "aimon-mobile/1.0")
        val token = sessionToken?.takeIf { it.isNotBlank() } ?: _mobileToken.value.takeIf { it.isNotBlank() }
        if (!token.isNullOrBlank()) {
            builder.header("X-Mobile-Token", token)
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                refreshAllInternal()
                delay(3000)
            }
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            refreshAllInternal()
        }
    }

    internal suspend fun refreshAllInternal() {
        fetchQuotasInternal()
        fetchApprovalsInternal()
        fetchSatelliteTelemetryInternal()
    }

    fun fetchQuotas() {
        viewModelScope.launch {
            fetchQuotasInternal()
        }
    }

    internal suspend fun fetchQuotasInternal() {
        withContext(Dispatchers.IO) {
            try {
                val req = buildAuthenticatedRequest("$baseUrl/api/status").get().build()
                okHttpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        val parsed = parseQuotaStatus(body)
                        if (parsed != null) {
                            _quotaStatus.value = parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Failed to fetch quotas from $baseUrl: ${e.message}")
            }
        }
    }

    fun fetchApprovals() {
        viewModelScope.launch {
            fetchApprovalsInternal()
        }
    }

    internal suspend fun fetchApprovalsInternal() {
        withContext(Dispatchers.IO) {
            try {
                val req = buildAuthenticatedRequest("$baseUrl/api/approvals/pending").get().build()
                okHttpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: ""
                        _pendingApprovals.value = parsePendingApprovals(body)
                    }
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Failed to fetch approvals from $baseUrl: ${e.message}")
            }
        }
    }

    fun fetchSatelliteTelemetry() {
        viewModelScope.launch {
            fetchSatelliteTelemetryInternal()
        }
    }

    internal suspend fun fetchSatelliteTelemetryInternal() {
        withContext(Dispatchers.IO) {
            var monitorsBody: String? = null
            var overviewBody: String? = null

            try {
                val monReq = buildAuthenticatedRequest("$baseUrl/api/monitors").get().build()
                okHttpClient.newCall(monReq).execute().use { res ->
                    if (res.isSuccessful) {
                        monitorsBody = res.body?.string()
                    }
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Failed to fetch /api/monitors from $baseUrl: ${e.message}")
            }

            try {
                val ovReq = buildAuthenticatedRequest("$baseUrl/api/telemetry/overview").get().build()
                okHttpClient.newCall(ovReq).execute().use { res ->
                    if (res.isSuccessful) {
                        overviewBody = res.body?.string()
                    }
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Failed to fetch /api/telemetry/overview from $baseUrl: ${e.message}")
            }

            _satelliteStatus.value = parseSatelliteTelemetry(monitorsBody, overviewBody)
        }
    }

    fun parseQuotaStatus(body: String): QuotaStatus? {
        if (body.isBlank()) return null
        return try {
            val base = gson.fromJson(body, QuotaStatus::class.java) ?: return null
            val rootElem = JsonParser.parseString(body)
            if (!rootElem.isJsonObject) return base
            val rootObj = rootElem.asJsonObject

            val agObj = when {
                rootObj.has("antigravity") && rootObj.get("antigravity").isJsonObject ->
                    rootObj.getAsJsonObject("antigravity")
                rootObj.has("ag") && rootObj.get("ag").isJsonObject ->
                    rootObj.getAsJsonObject("ag")
                else -> null
            }

            var updatedAg = base.antigravity
            if (agObj != null && updatedAg != null) {
                var pUsed = updatedAg.promptCreditsUsed
                var pLimit = updatedAg.monthlyPromptCredits
                var pAvail = updatedAg.availablePromptCredits
                if (agObj.has("promptCredits") && agObj.get("promptCredits").isJsonObject) {
                    val pc = agObj.getAsJsonObject("promptCredits")
                    pUsed = pc.get("used")?.takeIf { !it.isJsonNull }?.asInt ?: pUsed
                    pLimit = pc.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: pLimit
                    pAvail = pc.get("remaining")?.takeIf { !it.isJsonNull }?.asInt
                        ?: pc.get("available")?.takeIf { !it.isJsonNull }?.asInt
                        ?: pAvail
                } else if (agObj.has("promptCredits") && agObj.get("promptCredits").isJsonPrimitive) {
                    pAvail = agObj.get("promptCredits").asInt
                }

                var fUsed = updatedAg.flowCreditsUsed
                var fLimit = updatedAg.monthlyFlowCredits
                var fAvail = updatedAg.availableFlowCredits
                if (agObj.has("flowCredits") && agObj.get("flowCredits").isJsonObject) {
                    val fc = agObj.getAsJsonObject("flowCredits")
                    fUsed = fc.get("used")?.takeIf { !it.isJsonNull }?.asInt ?: fUsed
                    fLimit = fc.get("limit")?.takeIf { !it.isJsonNull }?.asInt ?: fLimit
                    fAvail = fc.get("remaining")?.takeIf { !it.isJsonNull }?.asInt
                        ?: fc.get("available")?.takeIf { !it.isJsonNull }?.asInt
                        ?: fAvail
                } else if (agObj.has("flowCredits") && agObj.get("flowCredits").isJsonPrimitive) {
                    fAvail = agObj.get("flowCredits").asInt
                }

                updatedAg = updatedAg.copy(
                    availablePromptCredits = pAvail,
                    availableFlowCredits = fAvail,
                    monthlyPromptCredits = pLimit,
                    monthlyFlowCredits = fLimit,
                    promptCreditsUsed = pUsed,
                    flowCreditsUsed = fUsed
                )
            }

            base.copy(antigravity = updatedAg)
        } catch (e: Exception) {
            Log.w("AimonViewModel", "Error parsing QuotaStatus: ${e.message}")
            null
        }
    }

    fun parsePendingApprovals(body: String): List<PendingApproval> {
        if (body.isBlank()) return emptyList()
        return try {
            val elem = JsonParser.parseString(body)
            val listType = object : com.google.gson.reflect.TypeToken<List<PendingApproval>>() {}.type
            when {
                elem.isJsonArray -> gson.fromJson<List<PendingApproval>>(elem, listType) ?: emptyList()
                elem.isJsonObject -> {
                    val obj = elem.asJsonObject
                    val arr = when {
                        obj.has("pending") && obj.get("pending").isJsonArray -> obj.getAsJsonArray("pending")
                        obj.has("approvals") && obj.get("approvals").isJsonArray -> obj.getAsJsonArray("approvals")
                        else -> null
                    }
                    if (arr != null) {
                        gson.fromJson<List<PendingApproval>>(arr, listType) ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }
        } catch (e: Exception) {
            Log.w("AimonViewModel", "Error parsing PendingApprovals: ${e.message}")
            emptyList()
        }
    }

    fun parseSatelliteTelemetry(monitorsBody: String?, overviewBody: String?): SatelliteStatus {
        val discoveredMonitors = mutableListOf<DiscoveredMonitorItem>()
        var meshmon = MeshmonStatus()
        var netmon = NetmonStatus()
        var embdevenv = EmbdevenvStatus()
        var abc80 = Abc80Status()

        if (!monitorsBody.isNullOrBlank()) {
            try {
                val elem = JsonParser.parseString(monitorsBody)
                val listType = object : com.google.gson.reflect.TypeToken<List<DiscoveredMonitorItem>>() {}.type

                if (elem.isJsonArray) {
                    val list: List<DiscoveredMonitorItem> = gson.fromJson(elem, listType) ?: emptyList()
                    discoveredMonitors.addAll(list)
                } else if (elem.isJsonObject) {
                    val obj = elem.asJsonObject
                    if (obj.has("monitors") && obj.get("monitors").isJsonArray) {
                        val list: List<DiscoveredMonitorItem> =
                            gson.fromJson(obj.getAsJsonArray("monitors"), listType) ?: emptyList()
                        discoveredMonitors.addAll(list)
                    }
                    if (obj.has("meshmon") && obj.get("meshmon").isJsonObject) {
                        meshmon = gson.fromJson(obj.getAsJsonObject("meshmon"), MeshmonStatus::class.java) ?: meshmon
                    }
                    if (obj.has("netmon") && obj.get("netmon").isJsonObject) {
                        netmon = gson.fromJson(obj.getAsJsonObject("netmon"), NetmonStatus::class.java) ?: netmon
                    }
                    if (obj.has("embdevenv") && obj.get("embdevenv").isJsonObject) {
                        embdevenv = gson.fromJson(obj.getAsJsonObject("embdevenv"), EmbdevenvStatus::class.java) ?: embdevenv
                    }
                    if (obj.has("abc80") && obj.get("abc80").isJsonObject) {
                        abc80 = gson.fromJson(obj.getAsJsonObject("abc80"), Abc80Status::class.java) ?: abc80
                    }
                }

                for (mon in discoveredMonitors) {
                    val key = mon.subsystem.ifBlank { mon.id }.lowercase()
                    when {
                        key.contains("meshmon") -> {
                            meshmon = meshmon.copy(
                                online = mon.online,
                                endpoint = mon.endpoint,
                                lastSeenEpoch = mon.lastSeenEpoch
                            )
                        }
                        key.contains("netmon") -> {
                            netmon = netmon.copy(
                                online = mon.online,
                                endpoint = mon.endpoint,
                                lastSeenEpoch = mon.lastSeenEpoch
                            )
                        }
                        key.contains("embdevenv") -> {
                            embdevenv = embdevenv.copy(
                                online = mon.online,
                                endpoint = mon.endpoint,
                                lastSeenEpoch = mon.lastSeenEpoch
                            )
                        }
                        key.contains("abc80") || key.contains("abc-80") -> {
                            abc80 = abc80.copy(
                                online = mon.online,
                                endpoint = mon.endpoint,
                                lastSeenEpoch = mon.lastSeenEpoch
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Error parsing /api/monitors: ${e.message}")
            }
        }

        var overview = AgentTelemetryOverview()
        if (!overviewBody.isNullOrBlank()) {
            try {
                overview = gson.fromJson(overviewBody, AgentTelemetryOverview::class.java) ?: AgentTelemetryOverview()
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Error parsing /api/telemetry/overview: ${e.message}")
            }
        }

        return SatelliteStatus(
            meshmon = meshmon,
            netmon = netmon,
            embdevenv = embdevenv,
            abc80 = abc80,
            monitors = discoveredMonitors,
            overview = overview
        )
    }

    fun submitDecision(approvalId: String, verdict: String) {
        viewModelScope.launch {
            submitDecisionSuspend(approvalId, verdict)
        }
    }

    suspend fun submitDecisionSuspend(approvalId: String, verdict: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val lower = verdict.lowercase()
            val approved = lower == "allow" || lower == "approve" || lower == "approved"
            val payload = mapOf(
                "approval_id" to approvalId,
                "request_id" to approvalId,
                "approved" to approved,
                "decision" to verdict
            )
            val jsonBody = gson.toJson(payload)
            val req = buildAuthenticatedRequest("$baseUrl/api/approvals/decision")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            okHttpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    _pendingApprovals.value = _pendingApprovals.value.filterNot { it.approvalId == approvalId }
                    appContext?.let { AimonNotificationManager(it).dismissApprovalNotification(approvalId) }
                    return@withContext true
                }
            }
        } catch (e: Exception) {
            Log.e("AimonViewModel", "Failed to submit decision: ${e.message}")
        }
        return@withContext false
    }

    interface SseEventListener {
        fun onIncomingApproval(approval: PendingApproval)
        fun onApprovalResolved(approvalId: String)
        fun onRefreshRequested()
    }

    companion object {
        const val PREFS_NAME = "aimon_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MOBILE_TOKEN = "mobile_token"
        const val KEY_TOKEN_LEGACY = "token"
        const val KEY_DEVICE_ID = "device_id"

        @Volatile
        var currentBaseUrl: String = "http://127.0.0.1:3883"
        @Volatile
        var currentMobileToken: String = ""

        private val listeners = CopyOnWriteArrayList<SseEventListener>()

        fun registerSseListener(listener: SseEventListener) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }

        fun unregisterSseListener(listener: SseEventListener) {
            listeners.remove(listener)
        }

        fun notifyIncomingApproval(approval: PendingApproval) {
            for (l in listeners) {
                l.onIncomingApproval(approval)
            }
        }

        fun notifyApprovalResolved(approvalId: String) {
            for (l in listeners) {
                l.onApprovalResolved(approvalId)
            }
        }

        fun notifyRefreshRequested() {
            for (l in listeners) {
                l.onRefreshRequested()
            }
        }
    }
}



