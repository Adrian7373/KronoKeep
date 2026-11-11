package com.kronokeep.app.data.push

import com.kronokeep.app.data.reminders.AndroidReminderNotifier
import com.kronokeep.app.data.reminders.scheduleNotificationId
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.DuePush
import com.kronokeep.app.domain.reminders.LocalPresence
import com.kronokeep.app.domain.reminders.PushOutcome
import com.kronokeep.app.domain.reminders.ReminderLedger
import com.kronokeep.app.domain.reminders.SkipReason
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.ReminderLedgerStore
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import com.kronokeep.app.testing.FakeAuth
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushReminderHandlerTest {
    private val auth = FakeAuth()
    private val settings = Settings()
    private val ledger = Ledger()
    private var presence = LocalPresence.MISSING
    private val notifier = Notifier()
    private val handler = PushReminderHandler(auth, settings, ledger, { _, _ -> presence }, notifier)

    private val payload = mapOf(
        "type" to "due", "schedule_id" to "s-1", "asset_id" to "a-1", "asset_name" to "Click",
        "task" to "Oil", "status" to "overdue", "next_due_at" to "",
    )

    @Test fun `posts and records a reminder not synced down yet`() = runBlocking {
        assertTrue(handler.handle(payload) is PushOutcome.Post)
        assertEquals(listOf("s-1"), notifier.posted.map { it.scheduleId })
        assertEquals(ReminderLedger(mapOf("s-1" to MaintenanceStatus.OVERDUE)), ledger.value)
    }

    @Test fun `unknown messages are ignored`() = runBlocking {
        assertNull(handler.handle(payload + ("type" to "something_new")))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test fun `signed out drops the message`() = runBlocking {
        auth.state.value = AuthState.SignedOut
        assertEquals(PushOutcome.Skip(SkipReason.SIGNED_OUT), handler.handle(payload))
        assertTrue(notifier.posted.isEmpty())
    }

    @Test fun `blocked notifications leave the ledger alone for the daily job`() = runBlocking {
        notifier.allowed = false
        assertEquals(PushOutcome.Skip(SkipReason.CANNOT_POST), handler.handle(payload))
        assertEquals(ReminderLedger(), ledger.value)
    }

    @Test fun `reminders switched off in settings`() = runBlocking {
        settings.reminders.value = false
        assertEquals(PushOutcome.Skip(SkipReason.REMINDERS_OFF), handler.handle(payload))
    }

    @Test fun `schedule notification ids are stable, distinct and never the digest's`() {
        assertEquals(scheduleNotificationId("3f1c6a2e-0000-4000-8000-000000000001"), scheduleNotificationId("3f1c6a2e-0000-4000-8000-000000000001"))
        assertNotEquals(scheduleNotificationId("s-1"), scheduleNotificationId("s-2"))
        val ids = (1..500).map { scheduleNotificationId("schedule-$it") }
        assertTrue(ids.all { it > 0 && it != AndroidReminderNotifier.DIGEST_ID })
    }

    private class Notifier(var allowed: Boolean = true) : DueNotifier {
        val posted = mutableListOf<DuePush>()
        override fun canPost() = allowed
        override fun postDue(push: DuePush) {
            posted += push
        }
    }

    private class Ledger : ReminderLedgerStore {
        var value = ReminderLedger()
        override suspend fun load() = value
        override suspend fun save(ledger: ReminderLedger) {
            value = ledger
        }
    }

    private class Settings : SettingsRepository {
        val reminders = MutableStateFlow(true)
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override val remindersEnabled: Flow<Boolean> = reminders
        override suspend fun setRemindersEnabled(enabled: Boolean) {
            reminders.value = enabled
        }
        override val notificationPromptShown: Flow<Boolean> = flowOf(false)
        override suspend fun markNotificationPromptShown() = Unit
    }
}
