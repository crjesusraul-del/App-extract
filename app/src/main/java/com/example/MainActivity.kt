package com.example

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.ThemeMode
import com.example.ui.MainViewModel
import com.example.ui.NxDumpApp
import com.example.ui.theme.NxDumpTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureHighRefreshAndCutout()
        setContent {
            val config by viewModel.setupConfig.collectAsStateWithLifecycle()
            val darkTheme = when (config.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            NxDumpTheme(
                darkTheme = darkTheme,
                dynamicColor = config.dynamicColor
            ) {
                NxDumpApp(viewModel = viewModel)
            }
        }
    }

    private fun configureHighRefreshAndCutout() {
        runCatching {
            val lp = window.attributes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            }
            if (currentDisplay != null) {
                val activeMode = currentDisplay.mode
                val bestMode = currentDisplay.supportedModes
                    .filter {
                        it.physicalWidth == activeMode.physicalWidth &&
                            it.physicalHeight == activeMode.physicalHeight
                    }
                    .maxByOrNull { it.refreshRate }
                if (bestMode != null && bestMode.refreshRate > activeMode.refreshRate) {
                    lp.preferredDisplayModeId = bestMode.modeId
                }
            }
            window.attributes = lp
        }
    }
}
