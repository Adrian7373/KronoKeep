package com.kronokeep.app.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import com.kronokeep.app.ui.theme.KronoTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ThemeToggleViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {
    fun set(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }
}

object ThemeToggleTags {
    const val BUTTON = "theme_toggle"
}

/**
 * One-tap light/dark switch for top bars. It pins an explicit mode (the opposite of what's on screen);
 * Settings still offers "System" to go back to following the phone.
 */
@Composable
fun ThemeToggleButton(modifier: Modifier = Modifier, viewModel: ThemeToggleViewModel = hiltViewModel()) {
    val dark = KronoTheme.isDark
    IconButton(
        onClick = { viewModel.set(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
        modifier = modifier.testTag(ThemeToggleTags.BUTTON),
    ) {
        AnimatedContent(
            targetState = dark,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith fadeOut() },
            label = "theme-icon",
        ) { isDark ->
            Icon(
                if (isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                contentDescription = if (isDark) "Switch to light theme" else "Switch to dark theme",
            )
        }
    }
}
