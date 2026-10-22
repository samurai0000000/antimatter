/*
 * AimonApp.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package com.selfso.aimon

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler

@HiltAndroidApp
class AimonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalCrashHandler.install(this)
    }
}
