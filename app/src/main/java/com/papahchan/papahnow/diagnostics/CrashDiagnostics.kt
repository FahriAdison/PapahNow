package com.papahchan.papahnow.diagnostics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device crash diagnostics. The app can force-close without any visible
 * message and remote debugging is not always available, so the last uncaught
 * exception and the last reached connection stage are persisted synchronously
 * (SharedPreferences.commit) before the process can die.
 *
 * Usage:
 *  - Call [install] in MainActivity.onCreate BEFORE any other init.
 *  - Call [consumeCrash] / [consumeStage] once per app start to read and clear
 *    the persisted results from the previous run (they display in the UI).
 *  - Call [setStage] at every connection milestone.
 */
object CrashDiagnostics {
    private const val TAG = "CrashDiagnostics"
    private const val PREFS_NAME = "crash_diagnostics"
    private const val KEY_CRASH = "last_crash"
    private const val KEY_CRASH_TS = "last_crash_ts"
    private const val KEY_STAGE = "last_connect_stage"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installed = false

    // Guards against recursion if writing the crash record itself throws while
    // the process is already going down.
    private val recording = arrayOf(false)

    // SimpleDateFormat is not thread-safe and stages are written from both the
    // main and IO threads, so a fresh instance is used per call.
    private fun nowTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** Installs the global handler and keeps a context for synchronous I/O. */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordCrash(thread, throwable)
            // Preserve the platform's normal crash behavior (dialog, process death).
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Writes the full stack trace + timestamp synchronously; reads the last stage for context. */
    private fun recordCrash(thread: Thread, throwable: Throwable) {
        synchronized(recording) {
            if (recording[0]) return
            recording[0] = true
        }
        try {
            val prefs = prefs ?: return
            val now = nowTimestamp()
            val payload = buildString {
                append("Crash at $now\n")
                append("Thread: ${thread.name}\n")
                append("Last connect stage: ${prefs.getString(KEY_STAGE, null) ?: "none"}\n")
                append(Log.getStackTraceString(throwable))
            }
            prefs.edit()
                .putString(KEY_CRASH, payload)
                .putString(KEY_CRASH_TS, now)
                .commit()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist crash record", t)
        }
    }

    fun setStage(stage: String) {
        try {
            val prefs = prefs ?: return
            val value = "$stage @ ${nowTimestamp()}"
            prefs.edit().putString(KEY_STAGE, value).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist connect stage", t)
        }
    }

    /** Returns the persisted crash record and clears it (shown once, then gone). */
    fun consumeCrash(): String? {
        val prefs = prefs ?: return null
        val record = prefs.getString(KEY_CRASH, null)
        if (record != null) {
            prefs.edit().remove(KEY_CRASH).remove(KEY_CRASH_TS).commit()
        }
        return record
    }

    /** Returns the persisted last stage and clears it so stale stages do not resurface. */
    fun consumeStage(): String? {
        val prefs = prefs ?: return null
        val stage = prefs.getString(KEY_STAGE, null)
        if (stage != null) {
            prefs.edit().remove(KEY_STAGE).commit()
        }
        return stage
    }

    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
