package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.MutableClock
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.SequentialIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant

class MaintenanceRepositoryImplTest {
    private val db = FakeDb()
    private val clock = MutableClock()
    private val repo = MaintenanceRepositoryImpl(
        db.assetDao, db.scheduleDao, db.serviceLogDao, db.odometerDao, db.outboxDao, db, clock, SequentialIds("m"), db.recomputer,
    )

    private fun days(n: Long): Duration = Duration.ofDays(n)

    private fun seedScooter(km: Int = 10_000, weekly: Int? = 140, readingAt: Instant = NOW): String {
        db.putAsset(
            AssetEntity(
                id = "click", userId = "user-1", category = AssetCategory.VEHICLE, name = "Click 125i",
                brand = "Honda", model = "Click", serialNo = null, purchaseDate = null, priceCentavos = null,
                warrantyExpiresOn = null, currentOdometerKm = km, weeklyKmBaseline = weekly, notes = null,
                archivedAt = null, createdAt = NOW, updatedAt = NOW, deletedAt = null, syncState = SyncState.SYNCED,
            ),
        )
        db.putReading(OdometerReadingEntity("r0", "user-1", "click", km, readingAt, readingAt, readingAt, null, SyncState.SYNCED))
        return "click"
    }

    private val oil = ScheduleDraft("Engine oil change", intervalDays = 60, intervalKm = 1_500, lastDoneAt = NOW, lastDoneKm = 10_000)
    private val cvt = ScheduleDraft("CVT cleaning", intervalDays = null, intervalKm = 5_000, lastDoneAt = NOW, lastDoneKm = 10_000)

    private fun outboxFor(table: String) = db.outboxRows.filter { it.tableName == table }.map { it.rowId }.toSet()

    @Test fun addSchedulesWritesRowsOutboxAndPredictionInOneTransaction() = runBlocking {
        val asset = seedScooter()
        val before = db.transactionCount
        val ids = repo.addSchedules(asset, listOf(oil))

        val row = db.scheduleRows.getValue(ids.single())
        assertEquals("user-1", row.userId)
        assertEquals(11_500, row.nextDueKm)
        assertEquals(MaintenanceStatus.OK, row.status)
        assertNotNull(row.nextDueAt)
        assertNotNull(row.confidence)
        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(setOf(row.id), outboxFor(SyncTables.SCHEDULES))
        assertEquals(1, db.transactionCount - before)
        assertTrue(db.writesOutsideTransaction.isEmpty())
    }

    @Test fun oneVisitWritesOneLogPerTaskWithCostOnTheFirst() = runBlocking {
        val asset = seedScooter()
        val (oilId, cvtId) = repo.addSchedules(asset, listOf(oil, cvt))
        clock.now = NOW.plus(days(20))

        val visit = ServiceVisit(clock.now, odometerKm = 11_200, cost = Peso(185_000), shop = "Motoworks", notes = null, listOf(oilId, cvtId))
        val logIds = repo.logService(asset, visit)

        val logs = logIds.map { db.logRows.getValue(it) }
        assertEquals(listOf(oilId, cvtId), logs.map { it.scheduleId })
        assertEquals(listOf(185_000L, null), logs.map { it.costCentavos })
        assertTrue(logs.all { it.odometerKm == 11_200 && it.performedAt == clock.now && it.shop == "Motoworks" })

        for (id in listOf(oilId, cvtId)) {
            val s = db.scheduleRows.getValue(id)
            assertEquals(clock.now, s.lastDoneAt)
            assertEquals(11_200, s.lastDoneKm)
        }
        assertEquals(12_700, db.scheduleRows.getValue(oilId).nextDueKm)
        assertEquals(11_200, db.assetRows.getValue(asset).currentOdometerKm)
        assertTrue(db.readingRows.any { it.km == 11_200 && it.recordedAt == clock.now })

        assertEquals(logIds.toSet(), outboxFor(SyncTables.SERVICE_LOGS))
        assertTrue(outboxFor(SyncTables.ASSETS).contains(asset))
        assertTrue(db.writesOutsideTransaction.isEmpty())
    }

    @Test fun adHocRepairIsOneUnlinkedLog() = runBlocking {
        val asset = seedScooter()
        val ids = repo.logService(asset, ServiceVisit(NOW, null, Peso(50_000), null, "Replaced side mirror", emptyList()))
        val log = db.logRows.getValue(ids.single())
        assertNull(log.scheduleId)
        assertEquals("Replaced side mirror", log.notes)
        assertEquals(50_000L, log.costCentavos)
    }

    @Test fun backDatedLogDoesNotMoveLastDoneBackOrRecordAnInconsistentReading() = runBlocking {
        val asset = seedScooter()
        val oilId = repo.addSchedules(asset, listOf(oil)).single()
        val readingsBefore = db.readingRows.size

        // 30 days ago at a km higher than today's reading: a typo or another vehicle's number.
        repo.logService(asset, ServiceVisit(NOW.minus(days(30)), 12_000, null, null, null, listOf(oilId)))

        val s = db.scheduleRows.getValue(oilId)
        assertEquals(NOW, s.lastDoneAt)
        assertEquals(10_000, s.lastDoneKm)
        assertEquals(readingsBefore, db.readingRows.size)
        assertEquals(10_000, db.assetRows.getValue(asset).currentOdometerKm)
    }

    @Test fun backDatedConsistentKmBecomesAReadingButNotTheCurrentOdometer() = runBlocking {
        val asset = seedScooter()
        repo.logService(asset, ServiceVisit(NOW.minus(days(30)), 9_000, null, null, "Tire change", emptyList()))
        assertTrue(db.readingRows.any { it.km == 9_000 })
        assertEquals(10_000, db.assetRows.getValue(asset).currentOdometerKm)
    }

    @Test fun lockedLogCannotBeDeleted() = runBlocking {
        val asset = seedScooter()
        val id = repo.logService(asset, ServiceVisit(NOW, null, null, null, "Brake pads", emptyList())).single()
        db.putLog(db.logRows.getValue(id).copy(lockedAt = NOW))
        val outboxBefore = db.outboxRows

        assertEquals(DeleteLogResult.Locked, repo.deleteServiceLog(id))
        assertNull(db.logRows.getValue(id).deletedAt)
        assertEquals(outboxBefore, db.outboxRows)
    }

    @Test fun deletingTheLatestLogFallsBackToThePreviousOne() = runBlocking {
        val asset = seedScooter(readingAt = NOW.minus(days(60)))
        val oilId = repo.addSchedules(asset, listOf(oil.copy(lastDoneAt = NOW.minus(days(60)), lastDoneKm = 10_000))).single()
        repo.logService(asset, ServiceVisit(NOW.minus(days(30)), 10_800, null, null, null, listOf(oilId)))
        val latest = repo.logService(asset, ServiceVisit(NOW, 11_600, null, null, null, listOf(oilId))).single()
        assertEquals(11_600, db.scheduleRows.getValue(oilId).lastDoneKm)

        assertEquals(DeleteLogResult.Deleted, repo.deleteServiceLog(latest))

        val s = db.scheduleRows.getValue(oilId)
        assertEquals(NOW.minus(days(30)), s.lastDoneAt)
        assertEquals(10_800, s.lastDoneKm)
        assertNotNull(db.logRows.getValue(latest).deletedAt)
        assertEquals(1, repo.observeServiceLogs(asset).first().size)
    }

    @Test fun historyCarriesTheTaskName() = runBlocking {
        val asset = seedScooter()
        val oilId = repo.addSchedules(asset, listOf(oil)).single()
        repo.logService(asset, ServiceVisit(NOW, 10_000, null, null, null, listOf(oilId)))
        repo.deleteSchedule(oilId)
        assertEquals("Engine oil change", repo.observeServiceLogs(asset).first().single().task)
    }

    @Test fun odometerUpdateMovesTheUsageDueDate() = runBlocking {
        val asset = seedScooter(readingAt = NOW.minus(days(10)))
        val cvtId = repo.addSchedules(asset, listOf(cvt.copy(lastDoneAt = NOW.minus(days(10))))).single()
        val before = db.scheduleRows.getValue(cvtId).nextDueAt!!

        // 1,000 km in 10 days is far above the 20 km/day baseline, so the due date pulls in.
        assertTrue(repo.recordOdometer(asset, 11_000))

        val after = db.scheduleRows.getValue(cvtId)
        assertTrue("due $before should move earlier, got ${after.nextDueAt}", after.nextDueAt!!.isBefore(before))
        assertEquals(11_000, db.assetRows.getValue(asset).currentOdometerKm)
        assertTrue(db.readingRows.any { it.km == 11_000 && it.recordedAt == NOW })
    }

    @Test fun failedOutboxWriteRollsTheWholeVisitBack() = runBlocking {
        val asset = seedScooter()
        val oilId = repo.addSchedules(asset, listOf(oil)).single()
        val schedule = db.scheduleRows.getValue(oilId)
        val readings = db.readingRows
        db.failNextOutboxWrite = true
        try {
            repo.logService(asset, ServiceVisit(NOW, 10_500, null, null, null, listOf(oilId)))
            fail("expected the write to throw")
        } catch (_: IllegalStateException) {
        }
        assertTrue(db.logRows.isEmpty())
        assertEquals(schedule, db.scheduleRows.getValue(oilId))
        assertEquals(readings, db.readingRows)
    }

    @Test fun recomputeAllPersistsStatusChangesOnce() = runBlocking {
        val asset = seedScooter(weekly = null)
        val oilId = repo.addSchedules(asset, listOf(oil)).single()
        assertEquals(MaintenanceStatus.OK, db.scheduleRows.getValue(oilId).status)

        clock.now = NOW.plus(days(61))
        assertEquals(1, repo.recomputeAll())
        assertEquals(MaintenanceStatus.OVERDUE, db.scheduleRows.getValue(oilId).status)
        assertEquals(0, repo.recomputeAll())
    }

    @Test fun deletedScheduleIsHiddenAndQueued() = runBlocking {
        val asset = seedScooter()
        val id = repo.addSchedules(asset, listOf(oil)).single()
        assertTrue(repo.deleteSchedule(id))
        assertNotNull(db.scheduleRows.getValue(id).deletedAt)
        assertTrue(repo.observeSchedules(asset).first().isEmpty())
        assertNull(repo.getSchedule(id))
        assertFalse(repo.deleteSchedule(id))
        assertTrue(outboxFor(SyncTables.SCHEDULES).contains(id))
    }
}
