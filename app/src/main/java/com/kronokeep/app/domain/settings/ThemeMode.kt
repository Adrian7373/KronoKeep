package com.kronokeep.app.domain.settings

/** The user's appearance choice. [SYSTEM] follows the phone's dark theme setting. */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** Unknown or missing stored values fall back to [SYSTEM] instead of crashing on an old/edited file. */
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}
