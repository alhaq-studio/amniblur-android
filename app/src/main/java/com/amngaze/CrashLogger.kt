package com.amngaze

import android.content.Context
import android.util.Log

/**
 * Uncaught Exception Handler that intercepts fatal app crashes
 * and writes them to local storage before the OS kills the process.
 */
class CrashLogger(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val errorManager = ErrorReportManager.getInstance(context)
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            errorManager.logCrash(thread, throwable)
            Log.e("CrashLogger", "Fatal exception captured and logged locally.", throwable)
        } catch (e: Exception) {
            Log.e("CrashLogger", "Failed to log uncaught exception.", e)
        }

        // Allow default system dialog / crash handler to execute
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
