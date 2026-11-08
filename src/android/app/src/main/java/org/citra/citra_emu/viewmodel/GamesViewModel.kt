// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.viewmodel

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameHelper
import org.citra.citra_emu.utils.Log
import org.citra.citra_emu.utils.StartupDiagnostics

class GamesViewModel : ViewModel() {
    val games get() = _games.asStateFlow()
    private val _games = MutableStateFlow(emptyList<Game>())

    val searchedGames get() = _searchedGames.asStateFlow()
    private val _searchedGames = MutableStateFlow(emptyList<Game>())

    val isReloading get() = _isReloading.asStateFlow()
    private val _isReloading = MutableStateFlow(false)

    val shouldSwapData get() = _shouldSwapData.asStateFlow()
    private val _shouldSwapData = MutableStateFlow(false)

    val shouldScrollToTop get() = _shouldScrollToTop.asStateFlow()
    private val _shouldScrollToTop = MutableStateFlow(false)

    val searchFocused get() = _searchFocused.asStateFlow()
    private val _searchFocused = MutableStateFlow(false)

    init {
        StartupDiagnostics.mark("GamesViewModel.init: entered")
        // Retrieve list of cached games
        val storedGames = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .getStringSet(GameHelper.KEY_GAMES, emptySet())
        StartupDiagnostics.mark("GamesViewModel.init: cached game count=${storedGames?.size ?: -1}")
        if (storedGames!!.isNotEmpty()) {
            val deserializedGames = mutableSetOf<Game>()
            storedGames.forEach {
                val game: Game
                try {
                    game = Json.decodeFromString(it)
                } catch (ignored: Exception) {
                    return@forEach
                }

                val gameExists = if (BuildUtil.isGooglePlayBuild) {
                    DocumentFile.fromSingleUri(
                        CitraApplication.appContext,
                        Uri.parse(game.path)
                    )?.exists()
                } else {
                    // Vanilla stores ordinary filesystem paths, not SAF document URIs.
                    runCatching { FileUtil.exists(game.path) }.getOrDefault(false)
                }
                if (gameExists == true) {
                    deserializedGames.add(game)
                } else if (game.isInstalled) {
                    deserializedGames.add(game)
                }
            }
            setGames(deserializedGames.toList())
        }
        StartupDiagnostics.mark("GamesViewModel.init: cache loaded; starting first reload")
        reloadGames(false)
        StartupDiagnostics.mark("GamesViewModel.init: first reload scheduled")
    }

    fun setGames(games: List<Game>) {
        val sortedList = games.sortedWith(
            compareBy(
                { it.title.lowercase(Locale.getDefault()) },
                { it.path }
            )
        )
        val filteredList = sortedList.filter {
            if (it.isSystemTitle) {
                it.isVisibleSystemTitle
            } else {
                true
            }
        }

        _games.value = filteredList
    }

    fun setSearchedGames(games: List<Game>) {
        _searchedGames.value = games
    }

    fun setShouldSwapData(shouldSwap: Boolean) {
        _shouldSwapData.value = shouldSwap
    }

    fun setShouldScrollToTop(shouldScroll: Boolean) {
        _shouldScrollToTop.value = shouldScroll
    }

    fun setSearchFocused(searchFocused: Boolean) {
        _searchFocused.value = searchFocused
    }

    fun reloadGames(directoryChanged: Boolean) {
        if (isReloading.value) {
            return
        }
        _isReloading.value = true
        StartupDiagnostics.mark("GamesViewModel.reloadGames: scheduled directoryChanged=$directoryChanged")

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    StartupDiagnostics.mark("GamesViewModel.reloadGames: calling GameHelper.getGames")
                    val discoveredGames = GameHelper.getGames()
                    StartupDiagnostics.mark("GamesViewModel.reloadGames: GameHelper returned ${discoveredGames.size} games")
                    setGames(discoveredGames)

                    if (directoryChanged) {
                        setShouldSwapData(true)
                    }
                }
            } catch (e: Exception) {
                StartupDiagnostics.mark("GamesViewModel.reloadGames: exception ${e.javaClass.name}: ${e.message}")
                Log.error("[GamesViewModel] Failed to reload games: ${e.message}")
            } finally {
                StartupDiagnostics.mark("GamesViewModel.reloadGames: finished")
                _isReloading.value = false
            }
        }
    }
}
