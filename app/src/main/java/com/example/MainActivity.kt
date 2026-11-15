package com.example

import android.os.Bundle
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
}
