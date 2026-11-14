package com.kronokeep.app.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kronokeep.app.domain.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private val dataStore by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val repo by lazy { DataStoreSettingsRepository(dataStore) }

    @After fun tearDown() = scope.cancel()

    @Test fun `defaults to system when nothing is stored`() = runBlocking {
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }

    @Test fun `stores and reads back each mode`() = runBlocking {
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            repo.setThemeMode(mode)
            assertEquals(mode, repo.themeMode.first())
        }
    }

    @Test fun `a corrupt stored value falls back to system`() = runBlocking {
        dataStore.edit { it[stringPreferencesKey("theme_mode")] = "NEON" }
        assertEquals(ThemeMode.SYSTEM, repo.themeMode.first())
    }
}
