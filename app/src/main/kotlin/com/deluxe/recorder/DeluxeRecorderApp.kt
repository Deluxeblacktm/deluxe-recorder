package com.deluxe.recorder

import android.annotation.SuppressLint
import android.app.Application
import android.app.UiModeManager
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeluxeRecorderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RecordingState.loadSettings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val uiModeManager = getSystemService(UiModeManager::class.java)
                uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
                Log.d("DeluxeApp", "setApplicationNightMode OK")
            } catch (e: Exception) {
                Log.e("DeluxeApp", "setApplicationNightMode falhou", e)
            }
        }
        setupCrashHandler()
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            saveCrashLog(thread, throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            val crashDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "DeluxeRecorder/logs"
            )
            crashDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val crashFile = File(crashDir, "crash_$timestamp.txt")

            val fw = FileWriter(crashFile)
            val pw = PrintWriter(fw)
            pw.println("==========================================")
            pw.println("  CRASH LOG - Deluxe Recorder")
            pw.println("  Data: $timestamp")
            pw.println("==========================================")
            pw.println()
            pw.println("--- Device ---")
            pw.println("  Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            pw.println("  Android: ${Build.VERSION.SDK_INT}")
            pw.println("  Board: ${Build.BOARD}")
            pw.println("  Brand: ${Build.BRAND}")
            pw.println("  Device: ${Build.DEVICE}")
            pw.println("  Hardware: ${Build.HARDWARE}")
            pw.println()
            pw.println("--- Thread ---")
            pw.println("  Name: ${thread.name}")
            pw.println("  Priority: ${thread.priority}")
            pw.println("  ID: ${thread.id}")
            pw.println()
            pw.println("--- Exception ---")
            pw.println("  Type: ${throwable.javaClass.name}")
            pw.println("  Message: ${throwable.message}")
            pw.println()
            pw.println("--- Stack Trace ---")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            pw.println(sw.toString())
            pw.flush()
            pw.close()
            fw.close()

            Log.e("CrashHandler", "Crash log saved: ${crashFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("CrashHandler", "Failed to save crash log: ${e.message}")
        }
    }
}