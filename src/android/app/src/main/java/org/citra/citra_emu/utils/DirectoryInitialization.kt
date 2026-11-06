// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.utils.PermissionsHandler.hasManageExternalStorageAccess
import org.citra.citra_emu.utils.PermissionsHandler.hasWriteAccess

/**
 * A service that spawns its own thread in order to copy several binary and shader files
 * from the Citra APK to the external file system.
 */
object DirectoryInitialization {
    private const val SYS_DIR_VERSION = "sysDirectoryVersion"

    @Volatile
    private var directoryState: DirectoryInitializationState? = null
    var userPath: String? = null
    val internalUserPath: String
        get() = CitraApplication.appContext.filesDir.canonicalPath
    private val isCitraDirectoryInitializationRunning = AtomicBoolean(false)

    val context: Context get() = CitraApplication.appContext

    @JvmStatic
    @Synchronized
    fun start(): DirectoryInitializationState? {
        if (!isCitraDirectoryInitializationRunning.compareAndSet(false, true)) {
            return null
        }

        StartupDiagnostics.record(
            "DirectoryInitialization.start: entered; " +
                "manageExternalStorage=${hasManageExternalStorageAccess()}"
        )
        try {
            if (directoryState != DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED) {
                directoryState = if (hasManageExternalStorageAccess()) {
                    StartupDiagnostics.record(
                        "DirectoryInitialization: preparing filesystem user directory"
                    )
                    if (setCitraUserDirectory()) {
                        StartupDiagnostics.record(
                            "DirectoryInitialization: user path selected: $userPath"
                        )
                        val directory = File(userPath!!)
                        CitraApplication.documentsTree.setFilesystemRoot(directory)
                        StartupDiagnostics.record(
                            "DirectoryInitialization: filesystem DocumentsTree root set"
                        )
                        NativeLibrary.setUserDirectory(userPath!!)
                        StartupDiagnostics.record(
                            "DirectoryInitialization: native user directory set"
                        )
                        NativeLibrary.createLogFile()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: native log initialized"
                        )
                        NativeLibrary.logUserDirectory(userPath.toString())
                        StartupDiagnostics.record(
                            "DirectoryInitialization: user directory logged"
                        )
                        NativeLibrary.createConfigFile()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: native config initialized"
                        )
                        GpuDriverHelper.initializeDriverParameters()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: GPU driver parameters initialized"
                        )
                        DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
                    } else {
                        DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
                    }
                } else if (hasWriteAccess(context)) {
                    StartupDiagnostics.record(
                        "DirectoryInitialization: preparing SAF user directory"
                    )
                    if (setCitraUserDirectory()) {
                        StartupDiagnostics.record(
                            "DirectoryInitialization: SAF user path selected: $userPath"
                        )
                        CitraApplication.documentsTree.setRoot(Uri.parse(userPath))
                        StartupDiagnostics.record(
                            "DirectoryInitialization: SAF DocumentsTree root set"
                        )
                        NativeLibrary.createLogFile()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: native log initialized"
                        )
                        NativeLibrary.logUserDirectory(userPath.toString())
                        StartupDiagnostics.record(
                            "DirectoryInitialization: user directory logged"
                        )
                        NativeLibrary.createConfigFile()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: native config initialized"
                        )
                        GpuDriverHelper.initializeDriverParameters()
                        StartupDiagnostics.record(
                            "DirectoryInitialization: GPU driver parameters initialized"
                        )
                        DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED
                    } else {
                        DirectoryInitializationState.CANT_FIND_EXTERNAL_STORAGE
                    }
                } else {
                    DirectoryInitializationState.EXTERNAL_STORAGE_PERMISSION_NEEDED
                }
            }

            StartupDiagnostics.record(
                "DirectoryInitialization.start: completed with state=$directoryState"
            )
            return directoryState
        } finally {
            isCitraDirectoryInitializationRunning.set(false)
            StartupDiagnostics.record("DirectoryInitialization.start: running flag cleared")
        }
    }

    @JvmStatic
    fun areCitraDirectoriesReady(): Boolean =
        directoryState == DirectoryInitializationState.CITRA_DIRECTORIES_INITIALIZED

    @Synchronized
    fun resetCitraDirectoryState() {
        directoryState = null
    }

    val userDirectory: String?
        get() {
            checkNotNull(directoryState) {
                "DirectoryInitialization has to run at least once!"
            }
            check(!isCitraDirectoryInitializationRunning.get()) {
                "DirectoryInitialization has to finish running first!"
            }
            return userPath
        }

    fun setCitraUserDirectory(): Boolean {
        if (hasManageExternalStorageAccess()) {
            val downloadsDirectory =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val directory = File(downloadsDirectory, "Azahar")

            if (!directory.exists() && !directory.mkdirs()) {
                return false
            }

            if (!directory.isDirectory) {
                return false
            }

            userPath = directory.canonicalPath + File.separator
            android.util.Log.d(
                "[Azahar Frontend]",
                "[DirectoryInitialization] User Dir: $userPath"
            )
            return true
        }

        val dataPath = PermissionsHandler.citraDirectory
        if (dataPath.toString().isNotEmpty()) {
            userPath = dataPath.toString()
            android.util.Log.d(
                "[Azahar Frontend]",
                "[DirectoryInitialization] User Dir: $userPath"
            )
            return true
        }

        return false
    }

    private fun copyAsset(asset: String, output: File, overwrite: Boolean, context: Context) {
        Log.debug("[DirectoryInitialization] Copying File $asset to $output")
        try {
            if (!output.exists() || overwrite) {
                val inputStream = context.assets.open(asset)
                val outputStream = FileOutputStream(output)
                copyFile(inputStream, outputStream)
                inputStream.close()
                outputStream.close()
            }
        } catch (e: IOException) {
            Log.error("[DirectoryInitialization] Failed to copy asset file: $asset" + e.message)
        }
    }

    private fun copyAssetFolder(
        assetFolder: String,
        outputFolder: File,
        overwrite: Boolean,
        context: Context
    ) {
        Log.debug("[DirectoryInitialization] Copying Folder $assetFolder to $outputFolder")
        try {
            var createdFolder = false
            for (file in context.assets.list(assetFolder)!!) {
                if (!createdFolder) {
                    outputFolder.mkdir()
                    createdFolder = true
                }
                copyAssetFolder(
                    assetFolder + File.separator + file,
                    File(outputFolder, file),
                    overwrite,
                    context
                )
                copyAsset(
                    assetFolder + File.separator + file,
                    File(outputFolder, file),
                    overwrite,
                    context
                )
            }
        } catch (e: IOException) {
            Log.error(
                "[DirectoryInitialization] Failed to copy asset folder: $assetFolder" +
                    e.message
            )
        }
    }

    @Throws(IOException::class)
    private fun copyFile(inputStream: InputStream, outputStream: OutputStream) {
        val buffer = ByteArray(1024)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            outputStream.write(buffer, 0, read)
        }
    }

    enum class DirectoryInitializationState {
        CITRA_DIRECTORIES_INITIALIZED,
        EXTERNAL_STORAGE_PERMISSION_NEEDED,
        CANT_FIND_EXTERNAL_STORAGE
    }
}
