/*
 * LocalCrashHandlerTest.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.utils

import android.content.Context
import dev.saifmukhtar.antimatter.core.ui.utils.LocalCrashHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

class LocalCrashHandlerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun getLatestCrashSummary_extractsTimeExceptionAndAppStackFrameFromNewestLog() {
        val filesDir = tempFolder.newFolder("files")
        val logsDir = File(filesDir, "crash_logs").apply { mkdirs() }

        val olderLog = File(logsDir, "crash_20261017_070000.txt")
        olderLog.writeText(
            """
            --- Antimatter Crash Log ---
            Time: Sat Oct 17 07:00:00 CST 2026
            Android Version: 15 (SDK 35)
            Device: Google Pixel 9 Pro
            Thread: main
            Exception: java.lang.IllegalStateException: Older error
            Stacktrace:
            java.lang.IllegalStateException: Older error
            	at dev.saifmukhtar.antimatter.MainActivity.onCreate(MainActivity.kt:42)
            """.trimIndent()
        )
        olderLog.setLastModified(1000L)

        val newerLog = File(logsDir, "crash_20261017_073000.txt")
        newerLog.writeText(
            """
            --- Antimatter Crash Log ---
            Time: Sat Oct 17 07:30:00 CST 2026
            Android Version: 15 (SDK 35)
            Device: Google Pixel 9 Pro
            Thread: main
            Exception: Context.startForegroundService() did not then call Service.startForeground()
            Stacktrace:
            android.app.RemoteServiceException${'$'}ForegroundServiceDidNotStartInTimeException: Context.startForegroundService() did not then call Service.startForeground()
            	at android.app.ActivityThread.generateForegroundServiceDidNotStartInTimeException(ActivityThread.java:2104)
            	at dev.saifmukhtar.antimatter.core.network.BridgeService.onCreate(BridgeService.kt:60)
            	at android.app.ActivityThread.handleCreateService(ActivityThread.java:4651)
            """.trimIndent()
        )
        newerLog.setLastModified(2000L)

        val mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(filesDir)

        assertTrue(LocalCrashHandler.hasUnsentLogs(mockContext))
        val summary = LocalCrashHandler.getLatestCrashSummary(mockContext)
        assertTrue("Summary must include timestamp", summary.contains("Time: Sat Oct 17 07:30:00 CST 2026"))
        assertTrue(
            "Summary must include exception class and message",
            summary.contains("ForegroundServiceDidNotStartInTimeException")
        )
        assertTrue(
            "Summary must include first app stack frame",
            summary.contains("at dev.saifmukhtar.antimatter.core.network.BridgeService.onCreate(BridgeService.kt:60)")
        )
    }

    @Test
    fun clearCrashLogs_removesLogsAndResetsHasUnsentLogs() {
        val filesDir = tempFolder.newFolder("files_clear")
        val logsDir = File(filesDir, "crash_logs").apply { mkdirs() }
        val logFile = File(logsDir, "crash_20261017_074000.txt")
        logFile.writeText("--- Antimatter Crash Log ---\nTime: Now\nException: Test\n")

        val mockContext = mock(Context::class.java)
        `when`(mockContext.filesDir).thenReturn(filesDir)

        assertTrue(LocalCrashHandler.hasUnsentLogs(mockContext))
        LocalCrashHandler.clearCrashLogs(mockContext)
        assertFalse(LocalCrashHandler.hasUnsentLogs(mockContext))
        assertEquals("", LocalCrashHandler.getLatestCrashSummary(mockContext))
    }
}
