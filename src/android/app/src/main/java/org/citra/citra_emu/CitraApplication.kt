// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.DocumentsTree
import org.citra.citra_emu.utils.GraphicsUtil
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.MemoryUtil
import org.citra.citra_emu.utils.PermissionsHandler

object StartupDiagnostics {
    private const val FILE_NAME = "azahar_startup_diagnostic.txt"

    @Volatile
    private var context: Context? = null

    @Synchronized
    fun begin(appContext: Context) {
        context = appContext.applicationContext
        val header = "Azahar startup diagnostic\nStarted: ${System.currentTimeMillis()}\n"
        writeToFiles(appContext.applicationContext, header, append = false)
        android.util.Log.i("AzaharStartup", "Startup diagnostics initialized")
    }

    @Synchronized
    fun record(message: String, error: Throwable? = null) {
        val appContext = context ?: return
        val entry = buildString {
            append("[${System.currentTimeMillis()}] ")
            append(message)
            append('\n')
            if (error != null) {
                append(android.util.Log.getStackTraceString(error))
                append('\n')
            }
        }
        if (error == null) {
            android.util.Log.i("AzaharStartup", message)
        } else {
            android.util.Log.e("AzaharStartup", message, error)
        }
        writeToFiles(appContext, entry, append = true)
    }

    private fun writeToFiles(appContext: Context, content: String, append: Boolean) {
        try {
            val internalFile = File(appContext.filesDir, FILE_NAME)
            if (append) internalFile.appendText(content) else internalFile.writeText(content)
        } catch (error: Exception) {
            android.util.Log.e("AzaharStartup", "Cannot write internal startup report", error)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()
            ) {
                return
            }

            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            val directory = File(downloads, "Azahar")
            if (!directory.exists() && !directory.mkdirs()) {
                return
            }
            if (!directory.isDirectory) {
                return
            }

            val report = File(directory, FILE_NAME)
            if (append) report.appendText(content) else report.writeText(content)
        } catch (error: Exception) {
            android.util.Log.e("AzaharStartup", "Cannot write public startup report", error)
        }
    }
}

class CitraApplication : Application() {
    private fun createNotificationChannel() {
        with(getSystemService(NotificationManager::class.java)) {
            // General notification
            val name: CharSequence = getString(R.string.app_notification_channel_name)
            val description = getString(R.string.app_notification_channel_description)
            val generalChannel = NotificationChannel(
                getString(R.string.app_notification_channel_id),
                name,
                NotificationManager.IMPORTANCE_LOW
            )
            generalChannel.description = description
            generalChannel.setSound(null, null)
            generalChannel.vibrationPattern = null
            createNotificationChannel(generalChannel)

            // CIA Install notifications
            val ciaChannel = NotificationChannel(
                getString(R.string.cia_install_notification_channel_id),
                getString(R.string.cia_install_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            ciaChannel.description =
                getString(R.string.cia_install_notification_channel_description)
            ciaChannel.setSound(null, null)
            ciaChannel.vibrationPattern = null
            createNotificationChannel(ciaChannel)
        }
    }

    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.begin(applicationContext)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            StartupDiagnostics.record("Uncaught exception on thread ${thread.name}", error)
            previousHandler?.uncaughtException(thread, error)
        }

        StartupDiagnostics.record("CitraApplication.onCreate: setting application context")
        application = this
        documentsTree = DocumentsTree()

        if (PermissionsHandler.hasWriteAccess(applicationContext)) {
            StartupDiagnostics.record("CitraApplication.onCreate: before DirectoryInitialization.start")
            val state = DirectoryInitialization.start()
            StartupDiagnostics.record(
                "CitraApplication.onCreate: DirectoryInitialization.start returned $state"
            )
        } else {
            StartupDiagnostics.record("CitraApplication.onCreate: storage permission not granted")
        }

        StartupDiagnostics.record("CitraApplication.onCreate: before NativeLibrary.logDeviceInfo")
        NativeLibrary.logDeviceInfo()
        StartupDiagnostics.record("CitraApplication.onCreate: after NativeLibrary.logDeviceInfo")
        logDeviceInfo()
        StartupDiagnostics.record("CitraApplication.onCreate: after frontend device logging")
        createNotificationChannel()
        StartupDiagnostics.record("CitraApplication.onCreate: after createNotificationChannel")
        NativeLibrary.playTimeManagerInit()
        StartupDiagnostics.record("CitraApplication.onCreate: completed")
    }

    fun logDeviceInfo() {
        Log.info("Device Manufacturer - ${Build.MANUFACTURER}")
        Log.info("Device Model - ${Build.MODEL}")
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) {
            Log.info("SoC Manufacturer - ${Build.SOC_MANUFACTURER}")
            Log.info("SoC Model - ${Build.SOC_MODEL}")
        }
        Log.info("Total System Memory - ${MemoryUtil.getDeviceRAM()}")
        Log.info("OpenGL ES Renderer - ${GraphicsUtil.openGLRendererString}")
    }

    companion object {
        private var application: CitraApplication? = null

        val appContext: Context get() = application!!.applicationContext

        @SuppressLint("StaticFieldLeak")
        lateinit var documentsTree: DocumentsTree
    }
}
