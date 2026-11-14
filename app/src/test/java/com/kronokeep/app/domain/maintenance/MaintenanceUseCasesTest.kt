package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.data.push.DueNotifier
import com.kronokeep.app.data.push.PushReminderHandler
import com.kronokeep.app.data.push.RoomLocalScheduleLookup
import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.domain.reminders.DuePush
import com.kronokeep.app.domain.reminders.PushOutcome
import com.kronokeep.app.domain.reminders.SkipReason
import com.kronokeep.app.testing.FakeAuth
import com.kronokeep.app.data.repository.MaintenanceRepositoryImpl
import com.kronokeep.app.data.reminders.ReminderNotifier
import com.kronokeep.app.data.reminders.ReminderRunner
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.reminders.Reminder
import com.kronokeep.app.domain.reminders.ReminderLedger
import com.kronokeep.app.domain.repository.ReminderLedgerStore
import com.kronokeep.app.domain.repository.SettingsRepository
import com.kronokeep.app.domain.settings.ThemeMode
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.MutableClock
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.SequentialIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class MaintenanceUseCasesTest {
    private val db = FakeDb()
    private val clock = MutableClock()
    private val ids = SequentialIds()
    private val assets = AssetRepositoryImpl(db.assetDao, db.odometerDao, db.outboxDao, db, clock, ids, FakeUserIds(), db.recomputer)
    private val maintenance = MaintenanceRepositoryImpl(
        db.assetDao, db.scheduleDao, db.serviceLogDao, db.odometerDao, db.outboxDao, db, clock, ids, db.recomputer,
    )
    private val addSchedules = AddSchedulesUseCase(assets, maintenance, clock)
    private val logService = LogServiceUseCase(assets, maintenance, clock)
    private val updateOdometer = UpdateOdometerUseCase(assets, maintenance)
    private val observeAll = ObserveAllMaintenanceUseCase(assets, maintenance, clock)

    private fun scooter() = runBlocking {
        assets.create(AssetDraft(AssetCategory.VEHICLE, "Click 125i", currentOdometerKm = 10_000, weeklyKmBaseline = 140))
    }

    private val oil = ScheduleDraft("  Engine oil change ", 60, 1_500, NOW, 10_000)

    @Test fun `adding schedules trims and validates every draft`() = runBlocking {
        val id = scooter()
        val bad = addSchedules(id, listOf(oil, oil.copy(intervalDays = null, intervalKm = null)))
        assertEquals(setOf(1), (bad as SaveSchedulesResult.Invalid).errors.keys)

        val ok = addSchedules(id, listOf(oil)) as SaveSchedulesResult.Saved
        assertEquals("Engine oil change", maintenance.getSchedule(ok.ids.single())!!.task)
    }

    @Test fun `last done km above the odometer is rejected`() = runBlocking {
        val id = scooter()
        val result = addSchedules(id, listOf(oil.copy(lastDoneKm = 10_500))) as SaveSchedulesResult.Invalid
        assertEquals(MaintenanceError.AboveOdometer(10_000), result.errors.getValue(0)[ScheduleField.LAST_DONE_KM])
    }

    @Test fun `electronics can't have schedules`() = runBlocking {
        val tv = assets.create(AssetDraft(AssetCategory.ELECTRONIC, "TV"))
        assertEquals(SaveSchedulesResult.NotSupported, addSchedules(tv, listOf(oil)))
    }

    @Test fun `appliance schedules drop km fields`() = runBlocking {
        val aircon = assets.create(AssetDraft(AssetCategory.APPLIANCE, "Aircon"))
        val saved = addSchedules(aircon, listOf(ScheduleDraft("Cleaning", 90, 5_000, NOW, 100))) as SaveSchedulesResult.Saved
        val s = maintenance.getSchedule(saved.ids.single())!!
        assertEquals(null, s.intervalKm)
        assertEquals(null, s.lastDoneKm)
    }

    @Test fun `logging needs an odometer for km tasks and ignores unknown schedule ids`() = runBlocking {
        val id = scooter()
        val oilId = (addSchedules(id, listOf(oil)) as SaveSchedulesResult.Saved).ids.single()
        val missingKm = logService(id, ServiceVisit(NOW, null, null, null, null, listOf(oilId)))
        assertEquals(MaintenanceError.OdometerNeeded, (missingKm as LogServiceResult.Invalid).errors[VisitField.ODOMETER])

        // An id from another asset is dropped, leaving nothing ticked and no description.
        val foreign = logService(id, ServiceVisit(NOW, 10_100, null, null, null, listOf("someone-else")))
        assertEquals(MaintenanceError.NothingLogged, (foreign as LogServiceResult.Invalid).errors[VisitField.NOTES])

        assertTrue(logService(id, ServiceVisit(NOW, 10_100, null, null, null, listOf(oilId))) is LogServiceResult.Saved)
    }

    @Test fun `odometer can't go below the last reading`() = runBlocking {
        val id = scooter()
        assertEquals(OdometerResult.Invalid(MaintenanceError.BelowLastReading(10_000)), updateOdometer(id, 9_000))
        assertEquals(OdometerResult.Saved, updateOdometer(id, 10_250))
    }

    @Test fun `live predictions follow the clock`() = runBlocking {
        val id = scooter()
        addSchedules(id, listOf(oil))
        assertEquals(MaintenanceStatus.OK, observeAll().first().single().schedules.single().prediction.status)
        clock.now = NOW.plus(Duration.ofDays(50))
        assertEquals(MaintenanceStatus.DUE_SOON, observeAll().first().single().schedules.single().prediction.status)
    }

    @Test fun `reminder runner posts once and remembers`() = runBlocking {
        val id = scooter()
        addSchedules(id, listOf(oil.copy(lastDoneAt = NOW.minus(Duration.ofDays(59)))))
        val notifier = RecordingNotifier()
        val ledger = MemoryLedger()
        val settings = FakeSettings()
        val runner = ReminderRunner(maintenance, observeAll, ledger, settings, notifier, clock)

        runner.run()
        assertEquals(1, notifier.posted.size)
        assertEquals("Engine oil change", (notifier.posted.single().single() as Reminder.Due).task)

        runner.run()
        assertEquals("nothing new to say", 1, notifier.posted.size)
    }

    @Test fun `reminder runner stays quiet when disabled or blocked, without consuming the reminder`() = runBlocking {
        val id = scooter()
        addSchedules(id, listOf(oil.copy(lastDoneAt = NOW.minus(Duration.ofDays(59)))))
        val notifier = RecordingNotifier(allowed = false)
        val ledger = MemoryLedger()
        val settings = FakeSettings()
        val runner = ReminderRunner(maintenance, observeAll, ledger, settings, notifier, clock)

        runner.run()
        assertTrue(notifier.posted.isEmpty())
        assertEquals(ReminderLedger(), ledger.value)

        notifier.allowed = true
        settings.reminders.value = false
        runner.run()
        assertTrue(notifier.posted.isEmpty())

        settings.reminders.value = true
        runner.run()
        assertEquals(1, notifier.posted.single().size)
    }

    private val markDone = MarkScheduleDoneUseCase(assets, maintenance, logService, clock)

    @Test fun `mark done logs the task now at the current odometer`() = runBlocking {
        val id = scooter()
        val oilId = (addSchedules(id, listOf(oil.copy(lastDoneAt = NOW.minus(Duration.ofDays(59))))) as SaveSchedulesResult.Saved).ids.single()
        updateOdometer(id, 11_400)
        clock.now = NOW.plus(Duration.ofHours(3))

        assertTrue(markDone(oilId) is LogServiceResult.Saved)
        val log = maintenance.observeServiceLogs(id).first().single()
        assertEquals(oilId, log.scheduleId)
        assertEquals(11_400, log.odometerKm)
        assertEquals(clock.now, log.performedAt)
        val schedule = maintenance.getSchedule(oilId)!!
        assertEquals(clock.now, schedule.lastDoneAt)
        assertEquals(11_400, schedule.lastDoneKm)
    }

    @Test fun `mark done on a time-only schedule needs no odometer`() = runBlocking {
        val aircon = assets.create(AssetDraft(AssetCategory.APPLIANCE, "Aircon"))
        val cleaning = (addSchedules(aircon, listOf(ScheduleDraft("Cleaning", 90, null, NOW, null))) as SaveSchedulesResult.Saved).ids.single()

        assertTrue(markDone(cleaning) is LogServiceResult.Saved)
        assertEquals(null, maintenance.observeServiceLogs(aircon).first().single().odometerKm)
    }

    @Test fun `mark done without a known odometer falls back to the form`() = runBlocking {
        val bike = assets.create(AssetDraft(AssetCategory.VEHICLE, "Mio", weeklyKmBaseline = 100))
        val oilId = (addSchedules(bike, listOf(oil.copy(lastDoneKm = 0))) as SaveSchedulesResult.Saved).ids.single()

        val result = markDone(oilId) as LogServiceResult.Invalid
        assertEquals(MaintenanceError.OdometerNeeded, result.errors[VisitField.ODOMETER])
        assertTrue(maintenance.observeServiceLogs(bike).first().isEmpty())
    }

    @Test fun `mark done on a deleted schedule is not found`() = runBlocking {
        val id = scooter()
        val oilId = (addSchedules(id, listOf(oil)) as SaveSchedulesResult.Saved).ids.single()
        maintenance.deleteSchedule(oilId)
        assertEquals(LogServiceResult.NotFound, markDone(oilId))
        assertEquals(LogServiceResult.NotFound, markDone("nope"))
    }

    @Test fun `a pushed reminder and the daily job notify once between them`() = runBlocking {
        val id = scooter()
        val oilId = (addSchedules(id, listOf(oil.copy(lastDoneAt = NOW.minus(Duration.ofDays(59))))) as SaveSchedulesResult.Saved).ids.single()
        val digest = RecordingNotifier()
        val pushes = RecordingDueNotifier()
        val ledger = MemoryLedger()
        val settings = FakeSettings()
        val handler = PushReminderHandler(FakeAuth(), settings, ledger, RoomLocalScheduleLookup(db.scheduleDao, db.assetDao), pushes)
        val runner = ReminderRunner(maintenance, observeAll, ledger, settings, digest, clock)
        val payload = mapOf(
            "type" to "due", "schedule_id" to oilId, "asset_id" to id, "asset_name" to "Click 125i",
            "task" to "Engine oil change", "status" to "due_soon", "next_due_at" to "",
        )

        assertTrue(handler.handle(payload) is PushOutcome.Post)
        runner.run()
        assertTrue("the push already covered it", digest.posted.isEmpty())
        assertTrue(handler.handle(payload) is PushOutcome.Skip)
        assertEquals(1, pushes.posted.size)
    }

    @Test fun `a push for a schedule deleted on this phone is dropped`() = runBlocking {
        val id = scooter()
        val oilId = (addSchedules(id, listOf(oil)) as SaveSchedulesResult.Saved).ids.single()
        maintenance.deleteSchedule(oilId)
        val pushes = RecordingDueNotifier()
        val handler = PushReminderHandler(FakeAuth(), FakeSettings(), MemoryLedger(), RoomLocalScheduleLookup(db.scheduleDao, db.assetDao), pushes)
        val payload = mapOf(
            "type" to "due", "schedule_id" to oilId, "asset_id" to id, "task" to "Engine oil change", "status" to "overdue",
        )

        assertEquals(PushOutcome.Skip(SkipReason.REMOVED_LOCALLY), handler.handle(payload))
        assertTrue(pushes.posted.isEmpty())
    }

    private class RecordingDueNotifier : DueNotifier {
        val posted = mutableListOf<DuePush>()
        override fun canPost() = true
        override fun postDue(push: DuePush) {
            posted += push
        }
    }

    private class RecordingNotifier(var allowed: Boolean = true) : ReminderNotifier {
        val posted = mutableListOf<List<Reminder>>()
        override fun canPost() = allowed
        override fun post(reminders: List<Reminder>) {
            posted += reminders
        }
    }

    private class MemoryLedger : ReminderLedgerStore {
        var value = ReminderLedger()
        override suspend fun load() = value
        override suspend fun save(ledger: ReminderLedger) {
            value = ledger
        }
    }

    private class FakeSettings : SettingsRepository {
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
