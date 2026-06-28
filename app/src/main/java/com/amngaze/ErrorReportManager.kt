package com.amngaze

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Error & Crash Logs Manager for AmnGaze.
 * Stores error reports privately on device so users can manually export them.
 */
class ErrorReportManager private constructor(private val context: Context) {

    private val errorDir = File(context.filesDir, "error_reports")
    private val crashLogFile = File(errorDir, "crash_log.txt")

    init {
        errorDir.mkdirs()
    }

    /**
     * Log a fatal crash with system and memory details
     */
    fun logCrash(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val entry = StringBuilder()

            entry.append("\n\n")
            entry.append("================================================================================\n")
            entry.append("AMNGAZE CRASH REPORT\n")
            entry.append("================================================================================\n")
            entry.append("Timestamp: $timestamp\n")
            
            // Device Information
            entry.append("\n--- DEVICE INFORMATION ---\n")
            entry.append("Manufacturer: ${Build.MANUFACTURER}\n")
            entry.append("Model: ${Build.MODEL}\n")
            entry.append("Brand: ${Build.BRAND}\n")
            entry.append("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            entry.append("Hardware: ${Build.HARDWARE}\n")

            // App Information
            entry.append("\n--- APP INFORMATION ---\n")
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            entry.append("App Version: ${pInfo.versionName} (${pInfo.versionCode})\n")
            entry.append("Package: ${context.packageName}\n")

            // Memory Status
            entry.append("\n--- MEMORY STATUS ---\n")
            val runtime = Runtime.getRuntime()
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            entry.append("Total Memory: ${totalMemory}MB\n")
            entry.append("Free Memory: ${freeMemory}MB\n")
            entry.append("Max Memory: ${maxMemory}MB\n")
            entry.append("Used Memory: ${totalMemory - freeMemory}MB\n")

            // Exception Details
            entry.append("\n--- EXCEPTION DETAILS ---\n")
            entry.append("Thread: ${thread.name} (ID: ${thread.id})\n")
            entry.append("Exception Type: ${throwable.javaClass.name}\n")
            entry.append("Message: ${throwable.message ?: "No error message description available."}\n")
            
            // Stacktrace
            entry.append("\n--- STACK TRACE ---\n")
            entry.append(throwable.stackTraceToString())

            // Causes
            var cause = throwable.cause
            while (cause != null) {
                entry.append("\n--- CAUSED BY ---\n")
                entry.append("Exception Type: ${cause.javaClass.name}\n")
                entry.append("Message: ${cause.message ?: "None"}\n")
                entry.append(cause.stackTraceToString())
                cause = cause.cause
            }

            entry.append("\n================================================================================\n")

            // Append to file
            crashLogFile.appendText(entry.toString())
            Log.e("ErrorReportManager", "Crash details successfully logged locally.")
        } catch (e: Exception) {
            Log.e("ErrorReportManager", "Failed to log crash to file.", e)
        }
    }

    /**
     * Log a non-fatal error manually
     */
    fun logNonFatalError(tag: String, message: String, exception: Throwable? = null) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val entry = StringBuilder()

            entry.append("\n--- NON-FATAL ERROR at $timestamp ---\n")
            entry.append("Tag: $tag\n")
            entry.append("Message: $message\n")
            if (exception != null) {
                entry.append("Exception: ${exception.javaClass.name}\n")
                entry.append(exception.stackTraceToString())
            }
            entry.append("--------------------------------------------------------------------------------\n")

            crashLogFile.appendText(entry.toString())
            Log.w(tag, "Logged non-fatal: $message", exception)
        } catch (e: Exception) {
            Log.e("ErrorReportManager", "Failed to log non-fatal error.", e)
        }
    }

    /**
     * Read all logged reports
     */
    fun getCrashLogContent(): String {
        return try {
            if (crashLogFile.exists()) {
                crashLogFile.readText()
            } else {
                "No crash or error logs registered on this device."
            }
        } catch (e: Exception) {
            "Error reading local logs: ${e.message}"
        }
    }

    /**
     * Clear log entries
     */
    fun clearAllReports() {
        try {
            if (crashLogFile.exists()) {
                crashLogFile.delete()
            }
        } catch (e: Exception) {
            Log.e("ErrorReportManager", "Failed to delete log files.", e)
        }
    }

    /**
     * Build exported text document
     */
    fun exportReportsAsText(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        return buildString {
            append("AMNGAZE ERROR REPORT EXPORT\n")
            append("Export Date: $timestamp\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n")
            append("================================================================================\n\n")
            append(getCrashLogContent())
        }
    }

    /**
     * Package logs into a physical file in cache directory for sharing
     */
    fun createBundledReportFile(): File? {
        return try {
            val shareDir = File(context.cacheDir, "shared_reports").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val reportFile = File(shareDir, "amngaze_error_report_$timestamp.txt")
            reportFile.writeText(exportReportsAsText())
            reportFile
        } catch (e: Exception) {
            Log.e("ErrorReportManager", "Failed to create shareable file.", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: ErrorReportManager? = null

        fun getInstance(context: Context): ErrorReportManager {
            return instance ?: synchronized(this) {
                instance ?: ErrorReportManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
