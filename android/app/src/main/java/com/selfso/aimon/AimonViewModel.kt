/*
 * AimonViewModel.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.google.gson.Gson
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class AimonViewModel @Inject constructor() : ViewModel() {

    private val okHttpClient = OkHttpClient.Builder()
        .proxy(java.net.Proxy.NO_PROXY)
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

    private var baseUrl: String = "http://127.0.0.1:3883"
    private var sessionToken: String? = null
    private var pollingJob: Job? = null

    init {
        startPolling()
    }

    fun configureEndpoint(url: String, token: String? = null) {
        baseUrl = if (url.endsWith("/")) url.dropLast(1) else url
        if (!token.isNullOrBlank()) {
            sessionToken = token
        }
        startPolling()
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

    private suspend fun refreshAllInternal() {
        fetchQuotasInternal()
        fetchApprovalsInternal()
        fetchSatelliteTelemetryInternal()
    }

    fun fetchQuotas() {
        viewModelScope.launch {
            fetchQuotasInternal()
        }
    }

    private suspend fun fetchQuotasInternal() {
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$baseUrl/api/status")
                    .header("User-Agent", "aimon-mobile/1.0")
                if (!sessionToken.isNullOrBlank()) {
                    reqBuilder.header("X-Mobile-Token", sessionToken!!)
                }
                val res = okHttpClient.newCall(reqBuilder.build()).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val parsed = gson.fromJson(body, QuotaStatus::class.java)
                    _quotaStatus.value = parsed
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

    private suspend fun fetchApprovalsInternal() {
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$baseUrl/api/approvals/pending")
                    .header("User-Agent", "aimon-mobile/1.0")
                if (!sessionToken.isNullOrBlank()) {
                    reqBuilder.header("X-Mobile-Token", sessionToken!!)
                }
                val res = okHttpClient.newCall(reqBuilder.build()).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val listType = object : com.google.gson.reflect.TypeToken<List<PendingApproval>>() {}.type
                    val list: List<PendingApproval> = gson.fromJson(body, listType) ?: emptyList()
                    _pendingApprovals.value = list
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

    private suspend fun fetchSatelliteTelemetryInternal() {
        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$baseUrl/api/telemetry/overview")
                    .header("User-Agent", "aimon-mobile/1.0")
                if (!sessionToken.isNullOrBlank()) {
                    reqBuilder.header("X-Mobile-Token", sessionToken!!)
                }
                val res = okHttpClient.newCall(reqBuilder.build()).execute()
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val parsed = gson.fromJson(body, SatelliteStatus::class.java)
                    _satelliteStatus.value = parsed ?: SatelliteStatus()
                } else {
                    _satelliteStatus.value = SatelliteStatus()
                }
            } catch (e: Exception) {
                Log.w("AimonViewModel", "Failed to fetch telemetry from $baseUrl: ${e.message}")
                _satelliteStatus.value = SatelliteStatus()
            }
        }
    }

    fun submitDecision(approvalId: String, verdict: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val payload = mapOf("approval_id" to approvalId, "decision" to verdict)
                    val jsonBody = gson.toJson(payload)
                    val reqBuilder = Request.Builder()
                        .url("$baseUrl/api/approvals/decision")
                        .header("User-Agent", "aimon-mobile/1.0")
                    if (!sessionToken.isNullOrBlank()) {
                        reqBuilder.header("X-Mobile-Token", sessionToken!!)
                    }
                    val req = reqBuilder.post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .build()
                    val res = okHttpClient.newCall(req).execute()
                    if (res.isSuccessful) {
                        _pendingApprovals.value = _pendingApprovals.value.filterNot { it.approvalId == approvalId }
                    }
                } catch (e: Exception) {
                    Log.e("AimonViewModel", "Failed to submit decision: ${e.message}")
                }
            }
        }
    }
}


