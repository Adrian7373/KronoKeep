package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Device-local preferences. Not synced: appearance is a per-phone choice. */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)
}
