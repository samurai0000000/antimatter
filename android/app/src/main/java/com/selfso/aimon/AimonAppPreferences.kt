/*
 * AimonAppPreferences.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AimonAppPreferences {
    const val PREFS_NAME = "aimon_prefs"
    private const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    private const val KEY_SOUND_ENABLED = "sound_enabled"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_DEFAULT_MODEL = "default_model_id"
    private const val KEY_BIOMETRIC_LOCK_ENABLED = "biometric_lock_enabled"

    @Volatile
    private var initialized = false
    private var prefs: SharedPreferences? = null

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _soundEnabled = MutableStateFlow(true)
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    private val _vibrationEnabled = MutableStateFlow(true)
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _defaultModelId = MutableStateFlow("default")
    val defaultModelId: StateFlow<String> = _defaultModelId.asStateFlow()

    private val _biometricLockEnabled = MutableStateFlow(false)
    val biometricLockEnabled: StateFlow<Boolean> = _biometricLockEnabled.asStateFlow()

    // Runtime foreground/navigation tracking for intelligent alert routing
    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var currentTab: Int = 1

    @Volatile
    var activeConversationId: String? = null

    fun init(context: Context) {
        if (initialized && prefs != null) return
        synchronized(this) {
            if (initialized && prefs != null) return
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = sp
            _notificationsEnabled.value = sp.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
            _soundEnabled.value = sp.getBoolean(KEY_SOUND_ENABLED, true)
            _vibrationEnabled.value = sp.getBoolean(KEY_VIBRATION_ENABLED, true)
            _defaultModelId.value = sp.getString(KEY_DEFAULT_MODEL, "default")?.ifBlank { "default" } ?: "default"
            _biometricLockEnabled.value = sp.getBoolean(KEY_BIOMETRIC_LOCK_ENABLED, false)
            initialized = true
        }
    }

    fun setNotificationsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _notificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled)?.apply()
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        init(context)
        _soundEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_SOUND_ENABLED, enabled)?.apply()
    }

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        init(context)
        _vibrationEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_VIBRATION_ENABLED, enabled)?.apply()
    }

    fun setDefaultModelId(context: Context, modelId: String) {
        init(context)
        val normalized = modelId.ifBlank { "default" }
        _defaultModelId.value = normalized
        prefs?.edit()?.putString(KEY_DEFAULT_MODEL, normalized)?.apply()
    }

    fun setBiometricLockEnabled(context: Context, enabled: Boolean) {
        init(context)
        _biometricLockEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_BIOMETRIC_LOCK_ENABLED, enabled)?.apply()
    }
}
