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
    private const val KEY_ANTIGRAVITY_SESSIONS_ENABLED = "antigravity_sessions_enabled"
    private const val KEY_CLAUDE_SESSIONS_ENABLED = "claude_sessions_enabled"
    private const val KEY_CURSOR_SESSIONS_ENABLED = "cursor_sessions_enabled"
    private const val KEY_ANTIGRAVITY_NOTIFS_ENABLED = "antigravity_notifications_enabled"
    private const val KEY_CLAUDE_NOTIFS_ENABLED = "claude_notifications_enabled"
    private const val KEY_CURSOR_NOTIFS_ENABLED = "cursor_notifications_enabled"
    private const val KEY_AUTO_APPROVE_MOBILE_TOOLS = "auto_approve_mobile_tools"
    private const val KEY_SESSION_CUTOFF_HOURS = "session_cutoff_hours"
    private const val KEY_DEFAULT_PROVIDER = "default_provider"
    private const val KEY_DEFAULT_CLAUDE_MODEL = "default_claude_model_id"
    private const val KEY_DEFAULT_CURSOR_MODEL = "default_cursor_model_id"

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

    private val _antigravitySessionsEnabled = MutableStateFlow(true)
    val antigravitySessionsEnabled: StateFlow<Boolean> = _antigravitySessionsEnabled.asStateFlow()

    private val _claudeSessionsEnabled = MutableStateFlow(true)
    val claudeSessionsEnabled: StateFlow<Boolean> = _claudeSessionsEnabled.asStateFlow()

    private val _cursorSessionsEnabled = MutableStateFlow(true)
    val cursorSessionsEnabled: StateFlow<Boolean> = _cursorSessionsEnabled.asStateFlow()

    private val _antigravityNotificationsEnabled = MutableStateFlow(true)
    val antigravityNotificationsEnabled: StateFlow<Boolean> = _antigravityNotificationsEnabled.asStateFlow()

    private val _claudeNotificationsEnabled = MutableStateFlow(true)
    val claudeNotificationsEnabled: StateFlow<Boolean> = _claudeNotificationsEnabled.asStateFlow()

    private val _cursorNotificationsEnabled = MutableStateFlow(true)
    val cursorNotificationsEnabled: StateFlow<Boolean> = _cursorNotificationsEnabled.asStateFlow()

    private val _autoApproveMobileTools = MutableStateFlow(true)
    val autoApproveMobileTools: StateFlow<Boolean> = _autoApproveMobileTools.asStateFlow()

    private val _sessionCutoffHours = MutableStateFlow(24)
    val sessionCutoffHours: StateFlow<Int> = _sessionCutoffHours.asStateFlow()

    private val _defaultProvider = MutableStateFlow("antigravity")
    val defaultProvider: StateFlow<String> = _defaultProvider.asStateFlow()

    private val _defaultClaudeModelId = MutableStateFlow("claude-sonnet-4-6")
    val defaultClaudeModelId: StateFlow<String> = _defaultClaudeModelId.asStateFlow()

    private val _defaultCursorModelId = MutableStateFlow("auto")
    val defaultCursorModelId: StateFlow<String> = _defaultCursorModelId.asStateFlow()

    // Runtime foreground/navigation tracking for intelligent alert routing
    @Volatile
    var isAppInForeground: Boolean = false

    @Volatile
    var currentTab: Int = 1

    @Volatile
    var activeConversationId: String? = null

    fun init(context: Context) {
        val sp = (context.applicationContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (initialized && prefs === sp) return
        synchronized(this) {
            if (initialized && prefs === sp) return
            prefs = sp
            _notificationsEnabled.value = sp.getBoolean(KEY_NOTIFICATIONS_ENABLED, true)
            _soundEnabled.value = sp.getBoolean(KEY_SOUND_ENABLED, true)
            _vibrationEnabled.value = sp.getBoolean(KEY_VIBRATION_ENABLED, true)
            _defaultModelId.value = sp.getString(KEY_DEFAULT_MODEL, "default")?.ifBlank { "default" } ?: "default"
            _biometricLockEnabled.value = sp.getBoolean(KEY_BIOMETRIC_LOCK_ENABLED, false)
            _antigravitySessionsEnabled.value = sp.getBoolean(KEY_ANTIGRAVITY_SESSIONS_ENABLED, true)
            _claudeSessionsEnabled.value = sp.getBoolean(KEY_CLAUDE_SESSIONS_ENABLED, true)
            _cursorSessionsEnabled.value = sp.getBoolean(KEY_CURSOR_SESSIONS_ENABLED, true)
            _antigravityNotificationsEnabled.value = sp.getBoolean(KEY_ANTIGRAVITY_NOTIFS_ENABLED, true)
            _claudeNotificationsEnabled.value = sp.getBoolean(KEY_CLAUDE_NOTIFS_ENABLED, true)
            _cursorNotificationsEnabled.value = sp.getBoolean(KEY_CURSOR_NOTIFS_ENABLED, true)
            _autoApproveMobileTools.value = sp.getBoolean(KEY_AUTO_APPROVE_MOBILE_TOOLS, true)
            _sessionCutoffHours.value = sp.getInt(KEY_SESSION_CUTOFF_HOURS, 24).coerceIn(1, 168)
            _defaultProvider.value = sp.getString(KEY_DEFAULT_PROVIDER, "antigravity")?.ifBlank { "antigravity" } ?: "antigravity"
            _defaultClaudeModelId.value = sp.getString(KEY_DEFAULT_CLAUDE_MODEL, "claude-sonnet-4-6")?.ifBlank { "claude-sonnet-4-6" } ?: "claude-sonnet-4-6"
            _defaultCursorModelId.value = sp.getString(KEY_DEFAULT_CURSOR_MODEL, "auto")?.ifBlank { "auto" } ?: "auto"
            initialized = true
        }
    }

    fun isProviderSessionEnabled(source: String): Boolean {
        return when (source.trim().lowercase()) {
            "claude" -> _claudeSessionsEnabled.value
            "cursor" -> _cursorSessionsEnabled.value
            else -> _antigravitySessionsEnabled.value
        }
    }

    fun isProviderNotificationEnabled(source: String): Boolean {
        return when (source.trim().lowercase()) {
            "claude" -> _claudeNotificationsEnabled.value
            "cursor" -> _cursorNotificationsEnabled.value
            else -> _antigravityNotificationsEnabled.value
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

    fun setAntigravitySessionsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _antigravitySessionsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ANTIGRAVITY_SESSIONS_ENABLED, enabled)?.apply()
    }

    fun setClaudeSessionsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _claudeSessionsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_CLAUDE_SESSIONS_ENABLED, enabled)?.apply()
    }

    fun setCursorSessionsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _cursorSessionsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_CURSOR_SESSIONS_ENABLED, enabled)?.apply()
    }

    fun setAntigravityNotificationsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _antigravityNotificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ANTIGRAVITY_NOTIFS_ENABLED, enabled)?.apply()
    }

    fun setClaudeNotificationsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _claudeNotificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_CLAUDE_NOTIFS_ENABLED, enabled)?.apply()
    }

    fun setCursorNotificationsEnabled(context: Context, enabled: Boolean) {
        init(context)
        _cursorNotificationsEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_CURSOR_NOTIFS_ENABLED, enabled)?.apply()
    }

    fun setAutoApproveMobileTools(context: Context, enabled: Boolean) {
        init(context)
        _autoApproveMobileTools.value = enabled
        prefs?.edit()?.putBoolean(KEY_AUTO_APPROVE_MOBILE_TOOLS, enabled)?.apply()
    }

    fun setSessionCutoffHours(context: Context, hours: Int) {
        init(context)
        val clamped = hours.coerceIn(1, 168)
        _sessionCutoffHours.value = clamped
        prefs?.edit()?.putInt(KEY_SESSION_CUTOFF_HOURS, clamped)?.apply()
    }

    fun setDefaultProvider(context: Context, provider: String) {
        init(context)
        val normalized = when (provider.trim().lowercase()) {
            "claude" -> "claude"
            "cursor" -> "cursor"
            else -> "antigravity"
        }
        _defaultProvider.value = normalized
        prefs?.edit()?.putString(KEY_DEFAULT_PROVIDER, normalized)?.apply()
    }

    fun setDefaultClaudeModelId(context: Context, modelId: String) {
        init(context)
        val normalized = modelId.ifBlank { "claude-sonnet-4-6" }
        _defaultClaudeModelId.value = normalized
        prefs?.edit()?.putString(KEY_DEFAULT_CLAUDE_MODEL, normalized)?.apply()
    }

    fun setDefaultCursorModelId(context: Context, modelId: String) {
        init(context)
        val normalized = modelId.ifBlank { "auto" }
        _defaultCursorModelId.value = normalized
        prefs?.edit()?.putString(KEY_DEFAULT_CURSOR_MODEL, normalized)?.apply()
    }
}
