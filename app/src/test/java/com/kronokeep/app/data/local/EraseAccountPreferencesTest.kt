package com.kronokeep.app.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kronokeep.app.data.settings.DataStoreSettingsRepository
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.ReminderLedger
import com.kronokeep.app.domain.settings.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EraseAccountPreferencesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") }
    }
    private val settings by lazy { DataStoreSettingsRepository(dataStore) }

    @After fun tearDown() = scope.cancel()

    @Test fun `keeps device preferences and drops everything tied to the account`() = runBlocking {
        settings.setThemeMode(ThemeMode.DARK)
        settings.setRemindersEnabled(false)
        settings.markNotificationPromptShown()
        settings.save(ReminderLedger(scheduleStatus = mapOf("s1" to MaintenanceStatus.OVERDUE), staleOdometer = emptyMap()))
        dataStore.edit {
            it[stringPreferencesKey("sync_cursor_user-1_assets")] = "2026-11-18T00:00:00Z|id"
            it[longPreferencesKey("sync_last_synced_at")] = 1L
            it[stringPreferencesKey("push_registered_token")] = "tok"
            it[stringPreferencesKey("push_registered_user")] = "user-1"
        }

        dataStore.eraseAccountPreferences()

        val left = dataStore.data.first().asMap().keys.map { it.name }.toSet()
        assertEquals(DataStoreSettingsRepository.DEVICE_PREFERENCE_NAMES, left)
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
        assertFalse(settings.remindersEnabled.first())
        assertTrue(settings.notificationPromptShown.first())
        assertTrue(settings.load().scheduleStatus.isEmpty())
    }

    @Test fun `erasing an empty or device-only store changes nothing`() = runBlocking {
        dataStore.eraseAccountPreferences()
        assertTrue(dataStore.data.first().asMap().isEmpty())

        dataStore.edit { it[booleanPreferencesKey("reminders_enabled")] = true }
        dataStore.eraseAccountPreferences()
        assertEquals(setOf("reminders_enabled"), dataStore.data.first().asMap().keys.map { it.name }.toSet())
    }
}
