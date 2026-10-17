/*
 * LocalCrashHandler.kt
 *
 * Copyright (C) 2026, Charles Chiou
 */

package dev.saifmukhtar.antimatter.core.ui.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Build
import android.content.Intent
import android.content.ClipData
import androidx.core.content.FileProvider

class LocalCrashHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            val logsDir = File(context.filesDir, "crash_logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val crashFile = File(logsDir, "crash_$timestamp.txt")
            
            // Storage Bloat Protection: keep only the 5 most recent logs
            val existingLogs = logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }
            if (existingLogs != null && existingLogs.size >= 5) {
                // Sort by last modified ascending
                val sortedLogs = existingLogs.sortedBy { it.lastModified() }
                // Delete oldest so that we have at most 4 left
                val toDeleteCount = sortedLogs.size - 4
                for (i in 0 until toDeleteCount) {
                    sortedLogs[i].delete()
                }
            }

            FileWriter(crashFile, true).use { fw ->
                PrintWriter(fw).use { pw ->
                    pw.println("--- Antimatter Crash Log ---")
                    pw.println("Time: ${Date()}")
                    pw.println("Android Version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    pw.println("Thread: ${thread.name}")
                    pw.println("Exception: ${exception.javaClass.name}: ${exception.message ?: ""}")
                    pw.println("Stacktrace:")
                    exception.printStackTrace(pw)
                }
            }
            Log.e("LocalCrashHandler", "Crash log saved to ${crashFile.absolutePath}")
        } catch (e: IOException) {
            Log.e("LocalCrashHandler", "Failed to save crash log", e)
        } finally {
            // Let the default handler do its job (e.g. show the 'App has stopped' dialog)
            defaultHandler?.uncaughtException(thread, exception)
        }
    }

    companion object {
        fun install(context: Context) {
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (currentHandler !is LocalCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(LocalCrashHandler(context, currentHandler))
            }
        }
        
        fun hasUnsentLogs(context: Context): Boolean {
            return hasUnsentLogs(File(context.filesDir, "crash_logs"))
        }

        fun hasUnsentLogs(logsDir: File): Boolean {
            return logsDir.exists() &&
                logsDir.listFiles()?.any { it.isFile && it.name.startsWith("crash_") } == true
        }

        fun getLatestCrashSummary(context: Context): String {
            return getLatestCrashSummary(File(context.filesDir, "crash_logs"))
        }

        fun getLatestCrashSummary(logsDir: File): String {
            if (!logsDir.exists()) return ""
            val files = logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }
            if (files.isNullOrEmpty()) return ""

            val latestLog = files.maxWithOrNull(
                compareBy<File> { it.lastModified() }.thenBy { it.name }
            ) ?: return ""

            return try {
                val lines = latestLog.readLines()
                val timeLine = lines.firstOrNull { it.startsWith("Time:") }?.trim()
                val exceptionLine = lines.firstOrNull { it.startsWith("Exception:") }?.trim()
                val stackHeaderIdx = lines.indexOfFirst { it.trim() == "Stacktrace:" }
                val throwableHeaderLine = if (stackHeaderIdx >= 0 && stackHeaderIdx + 1 < lines.size) {
                    lines[stackHeaderIdx + 1].trim().takeIf { !it.startsWith("at ") }
                } else {
                    null
                }
                val appStackFrame = lines.firstOrNull { line ->
                    val trimmed = line.trim()
                    trimmed.startsWith("at ") && (
                        trimmed.contains("dev.saifmukhtar.antimatter") ||
                            trimmed.contains("com.selfso.aimon")
                        )
                }?.trim() ?: lines.firstOrNull { it.trim().startsWith("at ") }?.trim()

                buildString {
                    if (!timeLine.isNullOrBlank()) {
                        appendLine(timeLine)
                    }
                    when {
                        !throwableHeaderLine.isNullOrBlank() -> appendLine(throwableHeaderLine)
                        !exceptionLine.isNullOrBlank() -> appendLine(exceptionLine)
                    }
                    if (!appStackFrame.isNullOrBlank()) {
                        append(appStackFrame)
                    }
                }.trim()
            } catch (_: Exception) {
                ""
            }
        }
        
        fun shareLatestCrashLog(context: Context) {
            val logsDir = File(context.filesDir, "crash_logs")
            val files = logsDir.listFiles()?.filter { it.isFile && it.name.startsWith("crash_") }
            if (files.isNullOrEmpty()) return
            
            // Get the most recent file
            val latestLog = files.maxByOrNull { it.lastModified() } ?: return
            
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", latestLog)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Antimatter Crash Log")
                putExtra(Intent.EXTRA_TEXT, "Please find the attached crash log from the Antimatter Android app.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("Crash Log", uri)
            }
            
            context.startActivity(Intent.createChooser(intent, "Share Crash Log").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            
            // Delay deletion by 60 seconds to ensure the receiving app has time to read the FileProvider URI
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                files.forEach { it.delete() }
            }, 60000)
        }

        fun clearCrashLogs(context: Context) {
            clearCrashLogs(File(context.filesDir, "crash_logs"))
        }

        fun clearCrashLogs(logsDir: File) {
            logsDir.listFiles()?.forEach { it.delete() }
        }

        fun clearLogs(context: Context) {
            clearCrashLogs(context)
        }
    }
}
