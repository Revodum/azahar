// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.utils

import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File
import java.util.ArrayDeque
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.model.GameInfo

object GameHelper {
    const val KEY_GAME_PATH = "game_path"
    const val KEY_GAMES = "Games"

    // Since the game val in GetGame is tied to the JNI we need to cache the game list in order to use it elsewhere
    var cachedGameList = mutableListOf<Game>()
    private lateinit var preferences: SharedPreferences

        fun getGames(): List<Game> {
        val games = mutableListOf<Game>()
        val context = CitraApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)

        val directories = mutableListOf<Uri>()
        val gamesDir = preferences.getString(KEY_GAME_PATH, "")

        if (!gamesDir.isNullOrEmpty()) {
            directories.add(Uri.parse(gamesDir))
        }

        if (!BuildUtil.isGooglePlayBuild &&
            PermissionsHandler.hasManageExternalStorageAccess()
        ) {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )

            if (downloadsDir.isDirectory) {
                directories.add(Uri.fromFile(downloadsDir))
            }
        }

        addGamesRecursive(games, directories)

        NativeLibrary.getInstalledGamePaths().forEach {
            games.add(
                getGame(Uri.parse(it.path), isInstalled = true, addedToLibrary = true, it.mediaType)
            )
        }

        val serializedGames = mutableSetOf<String>()
        games.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit()
            .remove(KEY_GAMES)
            .putStringSet(KEY_GAMES, serializedGames)
            .apply()

        cachedGameList = games.toMutableList()
        return games.toList()
    }

    private fun addGamesRecursive(
        games: MutableList<Game>,
        directories: List<Uri>
    ) {
        val pendingDirectories = ArrayDeque<Uri>()
        directories.forEach { pendingDirectories.addLast(it) }

        val visitedDirectories = mutableSetOf<String>()
        val addedFiles = mutableSetOf<String>()

        while (pendingDirectories.isNotEmpty()) {
            val directory = pendingDirectories.removeFirst()

            val directoryKey = if (directory.scheme == "file") {
                directory.path?.let {
                    runCatching { File(it).canonicalPath }
                        .getOrDefault(directory.toString())
                } ?: directory.toString()
            } else {
                directory.toString()
            }

            if (!visitedDirectories.add(directoryKey)) {
                continue
            }

            FileUtil.listFiles(directory).forEach { document ->
                val filename = FileUtil.getFilename(document.uri)

                if (filename.startsWith(".")) {
                    return@forEach
                }

                if (document.isDirectory) {
                    pendingDirectories.addLast(document.uri)
                } else if (Game.extensions.contains(FileUtil.getExtension(document.uri))) {
                    val fileKey = if (document.uri.scheme == "file") {
                        document.uri.path?.let {
                            runCatching { File(it).canonicalPath }
                                .getOrDefault(document.uri.toString())
                        } ?: document.uri.toString()
                    } else {
                        document.uri.toString()
                    }

                    if (addedFiles.add(fileKey)) {
                        games.add(
                            getGame(
                                document.uri,
                                isInstalled = false,
                                addedToLibrary = true,
                                Game.MediaType.GAME_CARD
                            )
                        )
                    }
                }
            }
        }
    }

    



    fun getGame(
        uri: Uri,
        isInstalled: Boolean,
        addedToLibrary: Boolean,
        mediaType: Game.MediaType
        ): Game {
        val filePath = if (uri.scheme == "file") {
            uri.path ?: uri.toString()
        } else {
            uri.toString()
        }

        var nativePath: String? = null
        var gameInfo: GameInfo?
        if (BuildUtil.isGooglePlayBuild || FileUtil.isNativePath(filePath) ||
            filePath.startsWith("!")
        ) {
            gameInfo = GameInfo(filePath)
        } else {
            nativePath = if (uri.scheme == "fd") {
                uri.toString()
            } else {
                "!" + NativeLibrary.getNativePath(uri)
            }
            gameInfo = GameInfo(nativePath)
        }

        val valid = gameInfo.isValid()
        if (!valid) {
            gameInfo = null
        }

        val isEncrypted = gameInfo?.isEncrypted() == true

        val newGame = Game(
            valid,
            (
                gameInfo?.getTitle() ?: FileUtil.getFilename(
                    uri
                )
                ).replace("[\\t\\n\\r]+".toRegex(), " "),
            filePath.replace("\n", " "),
            // TODO: This next line can be deduplicated but I don't want to right now -OS
            if (BuildUtil.isGooglePlayBuild || FileUtil.isNativePath(filePath) ||
                filePath.startsWith("!")
            ) {
                filePath
            } else {
                nativePath!!
            },
            gameInfo?.getTitleID() ?: 0,
            mediaType,
            gameInfo?.getCompany() ?: "",
            if (isEncrypted) {
                CitraApplication.appContext.getString(R.string.unsupported_encrypted)
            } else {
                gameInfo?.getRegions()
                    ?: ""
            },
            isInstalled,
            gameInfo?.isSystemTitle() ?: false,
            gameInfo?.getIsVisibleSystemTitle() ?: false,
            gameInfo?.getIsInsertable() ?: false,
            gameInfo?.getIcon(),
            gameInfo?.getFileType() ?: "",
            gameInfo?.getFileType()?.contains("(Z)") ?: false,
            if (FileUtil.isNativePath(filePath)) {
                CitraApplication.documentsTree.getFilename(filePath)
            } else {
                FileUtil.getFilename(Uri.parse(filePath))
            }
        )

        if (addedToLibrary) {
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
