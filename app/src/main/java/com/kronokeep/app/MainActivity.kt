package com.kronokeep.app

import android.app.UiModeManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kronokeep.app.domain.settings.ThemeMode
import com.kronokeep.app.ui.AppViewModel
import com.kronokeep.app.ui.KronoKeepAppRoot
import com.kronokeep.app.ui.theme.KronoKeepTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Same activity-scoped instance that KronoKeepAppRoot gets from hiltViewModel().
    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            // Before the stored choice loads, follow the system (on API 31+ the system already reflects it).
            val darkTheme = (themeMode ?: ThemeMode.SYSTEM).isDark(isSystemInDarkTheme())

            // Status/nav bar icons follow the app's theme, not the system's.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LightScrim, DarkScrim) { darkTheme },
                )
                onDispose {}
            }
            LaunchedEffect(themeMode) { themeMode?.let(::applyNightModeToWindow) }

            KronoKeepTheme(darkTheme = darkTheme) {
                KronoKeepAppRoot()
            }
        }
    }

    /**
     * API 31+: tell the system our choice so the window background and launch screen match it on the
     * next cold start (the system persists it). Older versions just follow the phone until Compose draws.
     */
    private fun applyNightModeToWindow(mode: ThemeMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mode == lastAppliedMode) return
        lastAppliedMode = mode
        getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (mode) {
                ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    private companion object {
        // Process-wide so a configuration-change recreate doesn't re-apply the same mode in a loop.
        var lastAppliedMode: ThemeMode? = null

        // Same defaults enableEdgeToEdge() uses for 3-button navigation.
        val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
