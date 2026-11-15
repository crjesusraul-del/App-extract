package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.NxDumpApplication
import com.example.core.crypto.KeyValidationSummary
import com.example.data.AttachedUpdate
import com.example.data.CachedLibraryPayload
import com.example.data.ExtractionRequest
import com.example.data.ExtractionTarget
import com.example.data.IndexedGame
import com.example.data.SafStorageHelper
import com.example.data.SetupConfig
import com.example.data.ThemeMode
import com.example.service.ExtractionQueueState
import com.example.service.ExtractionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val isScanning: Boolean = false,
    val searchQuery: String = "",
    val selectedFormatFilter: String = "ALL",
    val games: List<IndexedGame> = emptyList(),
    val unsupportedFiles: List<com.example.data.UnsupportedFileEntry> = emptyList(),
    val lastScannedEpochMs: Long = 0L,
    val scanError: String? = null
) {
    val filteredGames: List<IndexedGame>
        get() = games.filter { game ->
            val matchesQuery = searchQuery.isBlank() ||
                game.titleName.contains(searchQuery, ignoreCase = true) ||
                game.titleIdHex.contains(searchQuery, ignoreCase = true) ||
                game.fileName.contains(searchQuery, ignoreCase = true)
            val matchesFormat = selectedFormatFilter == "ALL" || game.format.name == selectedFormatFilter
            matchesQuery && matchesFormat
        }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as NxDumpApplication
    private val prefs = app.preferencesRepository
    private val repo = app.dumpRepository
    private val extractionManager = app.extractionManager

    val setupConfig: StateFlow<SetupConfig> = prefs.setupConfigFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SetupConfig())

    val extractionQueue: StateFlow<ExtractionQueueState> = extractionManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, ExtractionQueueState())

    private val _keySummary = MutableStateFlow(KeyValidationSummary())
    val keySummary: StateFlow<KeyValidationSummary> = _keySummary.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    private val _formatFilter = MutableStateFlow("ALL")
    private val _isScanning = MutableStateFlow(false)
    private val _scanError = MutableStateFlow<String?>(null)

    val libraryState: StateFlow<LibraryUiState> = combine(
        prefs.cachedLibraryFlow,
        _isScanning,
        _searchQuery,
        _formatFilter,
        _scanError
    ) { cached: CachedLibraryPayload, scanning: Boolean, query: String, formatFilter: String, error: String? ->
        LibraryUiState(
            isScanning = scanning,
            searchQuery = query,
            selectedFormatFilter = formatFilter,
            games = cached.games,
            unsupportedFiles = cached.unsupported,
            lastScannedEpochMs = cached.lastScannedEpochMs,
            scanError = error
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    private val _selectedGame = MutableStateFlow<IndexedGame?>(null)
    val selectedGame: StateFlow<IndexedGame?> = _selectedGame.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        viewModelScope.launch {
            setupConfig.collectLatest { cfg ->
                _keySummary.value = repo.validateKeys(cfg)
            }
        }
    }

    fun onProdKeysPicked(uri: Uri) {
        viewModelScope.launch {
            SafStorageHelper.takePersistableReadPermission(app, uri)
            val name = SafStorageHelper.resolveDisplayName(app, uri)
            prefs.setProdKeys(uri.toString(), name)
            _statusMessage.value = "Loaded $name"
        }
    }

    fun onTitleKeysPicked(uri: Uri) {
        viewModelScope.launch {
            SafStorageHelper.takePersistableReadPermission(app, uri)
            val name = SafStorageHelper.resolveDisplayName(app, uri)
            prefs.setTitleKeys(uri.toString(), name)
            _statusMessage.value = "Loaded $name"
        }
    }

    fun clearTitleKeys() {
        viewModelScope.launch {
            prefs.setTitleKeys(null, null)
        }
    }

    fun onGameDirPicked(uri: Uri) {
        viewModelScope.launch {
            SafStorageHelper.takePersistableReadPermission(app, uri)
            val name = SafStorageHelper.resolveDisplayName(app, uri)
            prefs.setGameDir(uri.toString(), name)
            refreshLibrary()
        }
    }

    fun onOutputDirPicked(uri: Uri) {
        viewModelScope.launch {
            SafStorageHelper.takePersistableReadWritePermission(app, uri)
            val name = SafStorageHelper.resolveDisplayName(app, uri)
            val warning = SafStorageHelper.validateOutputTreeUri(app, uri)
            prefs.setOutputDir(uri.toString(), name, warning)
            if (warning == null) {
                _statusMessage.value = "Output folder set to $name"
            }
        }
    }

    fun onLooseFileAdded(uri: Uri) {
        viewModelScope.launch {
            SafStorageHelper.takePersistableReadPermission(app, uri)
            val name = SafStorageHelper.resolveDisplayName(app, uri)
            prefs.addLooseFileUri(uri.toString())
            _statusMessage.value = "Added $name to library"
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanError.value = null
            try {
                repo.scanLibrary(setupConfig.value)
            } catch (e: Exception) {
                _scanError.value = e.message ?: "Scan failed"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateFormatFilter(filter: String) {
        _formatFilter.value = filter
    }

    fun selectGame(game: IndexedGame?) {
        _selectedGame.value = game
    }

    fun startExtraction(
        game: IndexedGame,
        target: ExtractionTarget,
        selectedUpdate: AttachedUpdate?,
        mergeUpdate: Boolean
    ) {
        val cfg = setupConfig.value
        if (!cfg.isMinimumSetupComplete) {
            _statusMessage.value = "Complete setup (prod.keys, game folder, output folder) first."
            return
        }
        extractionManager.enqueue(
            ExtractionRequest(
                game = game,
                target = target,
                selectedUpdate = selectedUpdate,
                mergeUpdate = mergeUpdate
            )
        )
        ExtractionService.start(app)
        _selectedGame.value = null
        _statusMessage.value = "Started ${target.label} extraction for ${game.titleName}"
    }

    fun cancelActiveExtraction() {
        extractionManager.cancelActive()
    }

    fun clearExtractionHistory() {
        extractionManager.clearHistory()
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    fun clearLibraryCache() {
        viewModelScope.launch {
            prefs.clearCache()
            _statusMessage.value = "Library cache cleared"
        }
    }

    fun consumeStatusMessage() {
        _statusMessage.value = null
    }
}
