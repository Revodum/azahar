// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import java.io.File
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.preference.PreferenceManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.model.CheapDocument
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
        val gamesDir = if (BuildUtil.isGooglePlayBuild) {
            preferences.getString(KEY_GAME_PATH, "")
        } else {
            File(Environment.getExternalStorageDirectory(), "Download").apply {
                if (!exists()) mkdirs()
            }.absolutePath
        }
        val gamesUri = Uri.parse(gamesDir)

        val visitedDirectories = mutableSetOf<String>()
        addGamesRecursive(games, FileUtil.listFiles(gamesUri), visitedDirectories)
        NativeLibrary.getInstalledGamePaths().forEach {
            games.add(
                getGame(Uri.parse(it.path), isInstalled = true, addedToLibrary = true, it.mediaType)
            )
        }

        // Cache list of games found on disk
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
        files: Array<CheapDocument>,
        visitedDirectories: MutableSet<String>
    ) {
        files.forEach { document ->
            val filename = document.filename
            if (filename.startsWith(".")) return@forEach
            if (document.isDirectory) {
                val directoryKey = if (document.uri.scheme == "file") {
                    runCatching { File(document.uri.path!!).canonicalPath }
                        .getOrDefault(document.uri.toString())
                } else {
                    document.uri.toString()
                }
                if (visitedDirectories.add(directoryKey)) {
                    addGamesRecursive(
                        games,
                        FileUtil.listFiles(document.uri),
                        visitedDirectories
                    )
                }
            } else if (Game.extensions.contains(FileUtil.getExtension(document.uri))) {
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

    fun getGame(
        uri: Uri,
        isInstalled: Boolean,
        addedToLibrary: Boolean,
        mediaType: Game.MediaType
    ): Game {
        val filePath = if (uri.scheme == "file") uri.path ?: uri.toString() else uri.toString()
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

    fun getGameFromIntent(context: Context, intent: Intent): Game? {
        val parcelable = try {
            intent.extras?.let { BundleCompat.getParcelable(it, "game", Game::class.java) }
        } catch (e: Exception) {
            Log.error("[GameHelper] Failed to retrieve game data: ${e.message}")
            null
        }
        if (parcelable != null) return parcelable

        val uri = getLaunchUri(intent) ?: return null
        return try {
            if (BuildUtil.isGooglePlayBuild || uri.scheme == "file" ||
                uri.toString().startsWith("!")
            ) {
                getGame(
                    uri,
                    isInstalled = false,
                    addedToLibrary = false,
                    mediaType = Game.MediaType.GAME_CARD
                )
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
                    getGame(
                        "fd://${fd.fd}".toUri(),
                        isInstalled = false,
                        addedToLibrary = false,
                        mediaType = Game.MediaType.GAME_CARD
                    )
                }
            }
        } catch (e: Exception) {
            Log.error("[GameHelper] Failed to resolve game from intent: ${e.message}")
            null
        }
    }

    fun getLaunchUri(intent: Intent): Uri? = try {
        val game = intent.extras?.let { extras ->
            BundleCompat.getParcelable(extras, "game", Game::class.java)
        }
        if (game != null) {
            game.launchIntent.data
        } else {
            intent.data ?: intent.getStringExtra("SelectedGame")?.toUri()
        }
    } catch (e: Exception) {
        Log.error("[GameHelper] Failed to parse URI from intent: ${e.message}")
        null
    }
}
