/*
 * MainActivity.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import dev.saifmukhtar.antimatter.core.ui.theme.AntimatterTheme

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val currentIntentState = mutableStateOf<Intent?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AimonAppPreferences.init(this)
        currentIntentState.value = intent
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            AimonAppPreferences.notificationsEnabled.value
        ) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            val biometricLockRequired by AimonAppPreferences.biometricLockEnabled.collectAsState()
            var isAuthenticated by remember { mutableStateOf(!biometricLockRequired) }
            val activeIntent by currentIntentState

            LaunchedEffect(biometricLockRequired) {
                if (!biometricLockRequired) {
                    isAuthenticated = true
                } else if (!isAuthenticated) {
                    showBiometricPrompt { success ->
                        isAuthenticated = success
                    }
                }
            }

            AntimatterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isAuthenticated) {
                        AimonNavigation(intent = activeIntent)
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .systemBarsPadding(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Locked",
                                    modifier = Modifier.size(64.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("AIMon Locked", style = MaterialTheme.typography.titleLarge)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = {
                                    showBiometricPrompt { success ->
                                        isAuthenticated = success
                                    }
                                }) {
                                    Text("Authenticate to Unlock")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        currentIntentState.value = intent
    }

    override fun onResume() {
        super.onResume()
        AimonAppPreferences.isAppInForeground = true
    }

    override fun onPause() {
        AimonAppPreferences.isAppInForeground = false
        super.onPause()
    }

    fun showBiometricPrompt(onResult: (Boolean) -> Unit) {
        try {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        if (errorCode == BiometricPrompt.ERROR_NO_BIOMETRICS ||
                            errorCode == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                            errorCode == BiometricPrompt.ERROR_HW_UNAVAILABLE
                        ) {
                            onResult(true)
                        } else {
                            onResult(false)
                        }
                    }
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onResult(true)
                    }
                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onResult(false)
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock AIMon")
                .setSubtitle("Securely access your zero-trust gateway")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()

            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "BiometricPrompt failed to authenticate: ${e.message}", e)
            onResult(true)
        }
    }

    fun promptBiometric(onResult: (Boolean) -> Unit) = showBiometricPrompt(onResult)
}
