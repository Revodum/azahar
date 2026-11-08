// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.os.Environment
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Persistent startup diagnostics for Vanilla builds.
 *
 * The file is written outside the app's private storage so it can be retrieved
 * from Download/Azahar even when the app fails before reaching its UI.
 */
object StartupDiagnostics {
    private const val FILE_NAME = "azahar-startup-diagnostic.txt"
    private const val MAX_LOG_BYTES = 512 * 1024L
    private val lock = Any()

    @Volatile
    private var reportFile: File? = null

    fun startSession() {
        val directory = File(Environment.getExternalStorageDirectory(), "Download/Azahar")
        val externalFile = File(directory, FILE_NAME)
        reportFile = try {
            if (!directory.exists()) directory.mkdirs()
            if (directory.isDirectory && directory.canWrite()) externalFile else null
        } catch (_: Exception) {
            null
        }
        mark("PROCESS_START")
        installExceptionHandler()
    }

    fun mark(stage: String) {
        synchronized(lock) {
            try {
                val file = reportFile ?: return
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    val previous = file.readText()
                    file.writeText(previous.takeLast((MAX_LOG_BYTES / 2).toInt()))
                }
                val timestamp = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS",
                    Locale.ROOT
                ).format(Date())
                FileOutputStream(file, true).bufferedWriter().use {
                    it.append(timestamp)
                    it.append(" | ")
                    it.append(Thread.currentThread().name)
                    it.append(" | ")
                    it.appendLine(stage)
                }
            } catch (_: Exception) {
                // Diagnostics must never interfere with normal app startup.
            }
        }
    }

    private fun installExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is DiagnosticExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(
            DiagnosticExceptionHandler(previous)
        )
    }

    private class DiagnosticExceptionHandler(
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            val stack = StringWriter()
            throwable.printStackTrace(PrintWriter(stack))
            mark("UNCAUGHT_EXCEPTION on ${thread.name}:\n$stack")
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
