package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.reminders.ReminderLedger
import com.kronokeep.app.domain.settings.ThemeMode
import kotlinx.coroutines.flow.Flow

/** Device-local preferences. Not synced: appearance and notifications are per-phone choices. */
interface SettingsRepository {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    /** Daily maintenance reminders. On by default; the OS permission is a separate gate. */
    val remindersEnabled: Flow<Boolean>
    suspend fun setRemindersEnabled(enabled: Boolean)

    /** Whether we've already asked for POST_NOTIFICATIONS (asked once, right after the first schedule exists). */
    val notificationPromptShown: Flow<Boolean>
    suspend fun markNotificationPromptShown()
}

/** What the reminder job already notified about. Device-local. */
interface ReminderLedgerStore {
    suspend fun load(): ReminderLedger
    suspend fun save(ledger: ReminderLedger)
}
