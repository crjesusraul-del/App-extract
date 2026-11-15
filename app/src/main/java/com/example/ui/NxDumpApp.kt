package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.FolderCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Downloading
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.data.AttachedUpdate
import com.example.data.ExtractionTarget
import com.example.data.IndexedGame
import com.example.ui.screens.GameDetailSheet
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SetupWizardScreen
import com.example.ui.screens.TasksScreen

const val ROUTE_SETUP = "setup"
const val ROUTE_LIBRARY = "library"
const val ROUTE_TASKS = "tasks"
const val ROUTE_SETTINGS = "settings"

@Composable
fun NxDumpApp(viewModel: MainViewModel) {
    val context = LocalContext.current
    val setupConfig by viewModel.setupConfig.collectAsStateWithLifecycle()
    val keySummary by viewModel.keySummary.collectAsStateWithLifecycle()
    val libraryState by viewModel.libraryState.collectAsStateWithLifecycle()
    val queueState by viewModel.extractionQueue.collectAsStateWithLifecycle()
    val selectedGame by viewModel.selectedGame.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(statusMessage) {
        statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeStatusMessage()
        }
    }

    // SAF Pickers
    val prodKeysPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onProdKeysPicked(it) } }

    val titleKeysPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onTitleKeysPicked(it) } }

    val gameDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.onGameDirPicked(it) } }

    val outputDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.onOutputDirPicked(it) } }

    val looseFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onLooseFileAdded(it) } }

    // Pending extraction payload if waiting for POST_NOTIFICATIONS runtime permission on Android 13+
    var pendingExtraction by remember {
        mutableStateOf<Triple<IndexedGame, ExtractionTarget, Pair<AttachedUpdate?, Boolean>>?>(null)
    }
    val navController = rememberNavController()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        pendingExtraction?.let { (game, target, updPair) ->
            viewModel.startExtraction(game, target, updPair.first, updPair.second)
            pendingExtraction = null
            navController.navigate(ROUTE_TASKS) {
                launchSingleTop = true
            }
        }
    }

    val startExtractionWithNotificationCheck: (IndexedGame, ExtractionTarget, AttachedUpdate?, Boolean) -> Unit =
        { game, target, upd, merge ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingExtraction = Triple(game, target, upd to merge)
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startExtraction(game, target, upd, merge)
                navController.navigate(ROUTE_TASKS) {
                    launchSingleTop = true
                }
            }
        }

    val startRoute = remember(setupConfig.isMinimumSetupComplete) {
        if (setupConfig.isMinimumSetupComplete) ROUTE_LIBRARY else ROUTE_SETUP
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: startRoute
    val showChromeNav = currentRoute != ROUTE_SETUP

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (showChromeNav && !useRail) {
                    NavigationBar(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        val activeJobsCount = if (queueState.activeJob != null) 1 + queueState.pendingCount else 0
                        navItems().forEach { (route, label, icons) ->
                            val selected = currentRoute == route
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo(ROUTE_LIBRARY) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    NavTabIcon(
                                        route = route,
                                        label = label,
                                        selected = selected,
                                        filledIcon = icons.first,
                                        outlinedIcon = icons.second,
                                        activeJobsCount = activeJobsCount
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_${route}_tab")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (showChromeNav && useRail) {
                    NavigationRail {
                        val activeJobsCount = if (queueState.activeJob != null) 1 + queueState.pendingCount else 0
                        navItems().forEach { (route, label, icons) ->
                            val selected = currentRoute == route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(route) {
                                        popUpTo(ROUTE_LIBRARY) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    NavTabIcon(
                                        route = route,
                                        label = label,
                                        selected = selected,
                                        filledIcon = icons.first,
                                        outlinedIcon = icons.second,
                                        activeJobsCount = activeJobsCount
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_${route}_tab")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    NavHost(
                        navController = navController,
                        startDestination = startRoute
                    ) {
                        composable(ROUTE_SETUP) {
                            SetupWizardScreen(
                                config = setupConfig,
                                keySummary = keySummary,
                                onPickProdKeys = { prodKeysPicker.launch(arrayOf("*/*")) },
                                onPickTitleKeys = { titleKeysPicker.launch(arrayOf("*/*")) },
                                onClearTitleKeys = { viewModel.clearTitleKeys() },
                                onPickGameDir = { gameDirPicker.launch(null) },
                                onPickOutputDir = { outputDirPicker.launch(null) },
                                onContinue = {
                                    viewModel.refreshLibrary()
                                    navController.navigate(ROUTE_LIBRARY) {
                                        popUpTo(ROUTE_SETUP) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(ROUTE_LIBRARY) {
                            LibraryScreen(
                                state = libraryState,
                                config = setupConfig,
                                onSearchChange = viewModel::updateSearchQuery,
                                onFormatFilterChange = viewModel::updateFormatFilter,
                                onRefresh = viewModel::refreshLibrary,
                                onAddLooseFile = { looseFilePicker.launch(arrayOf("*/*")) },
                                onOpenSetup = { navController.navigate(ROUTE_SETUP) },
                                onGameClick = viewModel::selectGame
                            )
                        }
                        composable(ROUTE_TASKS) {
                            TasksScreen(
                                queueState = queueState,
                                onCancelActive = viewModel::cancelActiveExtraction,
                                onClearHistory = viewModel::clearExtractionHistory
                            )
                        }
                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                config = setupConfig,
                                keySummary = keySummary,
                                onPickProdKeys = { prodKeysPicker.launch(arrayOf("*/*")) },
                                onPickTitleKeys = { titleKeysPicker.launch(arrayOf("*/*")) },
                                onPickGameDir = { gameDirPicker.launch(null) },
                                onPickOutputDir = { outputDirPicker.launch(null) },
                                onThemeModeChange = viewModel::setThemeMode,
                                onDynamicColorChange = viewModel::setDynamicColor,
                                onClearCache = viewModel::clearLibraryCache,
                                onOpenSetupWizard = { navController.navigate(ROUTE_SETUP) }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedGame?.let { game ->
        GameDetailSheet(
            game = game,
            onDismiss = { viewModel.selectGame(null) },
            onStartExtraction = { target, update, merge ->
                startExtractionWithNotificationCheck(game, target, update, merge)
            }
        )
    }
}

@Composable
private fun navItems(): List<Triple<String, String, Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.vector.ImageVector>>> {
    val libLabel = stringResource(R.string.nav_library)
    val tasksLabel = stringResource(R.string.nav_tasks)
    val settingsLabel = stringResource(R.string.nav_settings)
    return listOf(
        Triple(ROUTE_LIBRARY, libLabel, Icons.Filled.FolderCopy to Icons.Outlined.FolderCopy),
        Triple(ROUTE_TASKS, tasksLabel, Icons.Filled.Downloading to Icons.Outlined.Downloading),
        Triple(ROUTE_SETTINGS, settingsLabel, Icons.Filled.Settings to Icons.Outlined.Settings)
    )
}

@Composable
private fun NavTabIcon(
    route: String,
    label: String,
    selected: Boolean,
    filledIcon: androidx.compose.ui.graphics.vector.ImageVector,
    outlinedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    activeJobsCount: Int
) {
    if (route == ROUTE_TASKS && activeJobsCount > 0) {
        BadgedBox(badge = { Badge { Text("$activeJobsCount") } }) {
            Icon(
                imageVector = if (selected) filledIcon else outlinedIcon,
                contentDescription = label
            )
        }
    } else {
        Icon(
            imageVector = if (selected) filledIcon else outlinedIcon,
            contentDescription = label
        )
    }
}
