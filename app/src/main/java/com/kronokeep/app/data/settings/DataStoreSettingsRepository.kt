package com.kronokeep.app.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.ReminderLedger
import com.kronokeep.app.domain.repository.ReminderLedgerStore
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository, ReminderLedgerStore {

    override val themeMode: Flow<ThemeMode> = dataStore.data
        .map { ThemeMode.fromStorage(it[THEME_MODE]) }
        .distinctUntilChanged()

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    override val remindersEnabled: Flow<Boolean> = dataStore.data.map { it[REMINDERS] ?: true }.distinctUntilChanged()

    override suspend fun setRemindersEnabled(enabled: Boolean) {
        dataStore.edit { it[REMINDERS] = enabled }
    }

    override val notificationPromptShown: Flow<Boolean> =
        dataStore.data.map { it[PROMPT_SHOWN] ?: false }.distinctUntilChanged()

    override suspend fun markNotificationPromptShown() {
        dataStore.edit { it[PROMPT_SHOWN] = true }
    }

    override suspend fun load(): ReminderLedger {
        val prefs = dataStore.data.first()
        return ReminderLedger(
            scheduleStatus = decode(prefs[LEDGER_STATUS]) { MaintenanceStatus.entries.firstOrNull { s -> s.name == it } },
            staleOdometer = decode(prefs[LEDGER_ODOMETER]) { it.toLongOrNull()?.let(Instant::ofEpochSecond) },
        )
    }

    override suspend fun save(ledger: ReminderLedger) {
        dataStore.edit {
            it[LEDGER_STATUS] = encode(ledger.scheduleStatus) { s -> s.name }
            it[LEDGER_ODOMETER] = encode(ledger.staleOdometer) { t -> t.epochSecond.toString() }
        }
    }

    // Ids are UUIDs and values are enum names / numbers, so "id=value;id=value" is unambiguous.
    private fun <V> encode(map: Map<String, V>, value: (V) -> String): String =
        map.entries.joinToString(";") { (k, v) -> "$k=${value(v)}" }

    private fun <V : Any> decode(raw: String?, value: (String) -> V?): Map<String, V> =
        raw.orEmpty().split(';').mapNotNull { entry ->
            val (k, v) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            value(v)?.let { k to it }
        }.toMap()

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val REMINDERS = booleanPreferencesKey("reminders_enabled")
        val PROMPT_SHOWN = booleanPreferencesKey("notification_prompt_shown")
        val LEDGER_STATUS = stringPreferencesKey("reminder_ledger_status")
        val LEDGER_ODOMETER = stringPreferencesKey("reminder_ledger_odometer")
    }
}
