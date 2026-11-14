package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.remote.AssetDto
import com.kronokeep.app.data.remote.PullCursor
import com.kronokeep.app.data.remote.ScheduleDto
import com.kronokeep.app.data.remote.SyncJson
import com.kronokeep.app.data.remote.parsePgInstant
import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.data.repository.MaintenanceRepositoryImpl
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.testing.CountingScheduler
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeStorage
import com.kronokeep.app.testing.FakeSyncRemote
import com.kronokeep.app.testing.FakeSyncStateStore
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.MutableClock
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.SequentialIds
import com.kronokeep.app.testing.pgTimestamp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncEngineTest {
    private val db = FakeDb()
    private val clock = MutableClock()
    private val users = FakeUserIds()
    private val ids = SequentialIds("row")
    private val remote = FakeSyncRemote()
    private val storage = FakeStorage()
    private val store = FakeSyncStateStore()
    private val uploads = CountingScheduler()

    private val registry = SyncTableRegistry(
        AssetSyncTable(db.assetDao),
        ScheduleSyncTable(db.scheduleDao, db.assetDao),
        OdometerSyncTable(db.odometerDao, db.assetDao),
        ServiceLogSyncTable(db.serviceLogDao, db.assetDao, db.scheduleDao),
        DocumentSyncTable(db.documentDao, db.assetDao, db.serviceLogDao),
    )
    private val engine = SyncEngine(registry, db.outboxDao, db, remote, storage, store, db.recomputer, uploads, clock)
    private val assets = AssetRepositoryImpl(db.assetDao, db.odometerDao, db.outboxDao, db, clock, ids, users, db.recomputer)
    private val maintenance = MaintenanceRepositoryImpl(
        db.assetDao, db.scheduleDao, db.serviceLogDao, db.odometerDao, db.outboxDao, db, clock, ids, db.recomputer,
    )

    private val scooterDraft = AssetDraft(
        category = AssetCategory.VEHICLE,
        name = "Honda Click 125i",
        currentOdometerKm = 12_400,
        weeklyKmBaseline = 150,
    )

    private suspend fun scooter(): String = assets.create(scooterDraft)
    private suspend fun sync() = engine.sync("user-1")

    private fun entry(table: String, id: String) = db.outboxRows.firstOrNull { it.tableName == table && it.rowId == id }
    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it != JsonNull }?.jsonPrimitive?.content
    private fun JsonObject.with(key: String, value: String?) = JsonObject(this + (key to (value?.let(::JsonPrimitive) ?: JsonNull)))

    // ------------------------------------------------------------------ push

    @Test fun pushesParentsFirstThenMarksRowsSyncedWithServerTimestamps() = runBlocking {
        val assetId = scooter()
        val scheduleId = maintenance.addSchedules(assetId, listOf(ScheduleDraft("Oil change", 60, 1_500, NOW, 12_400))).single()

        val outcome = sync()

        assertFalse(outcome.shouldRetry)
        assertEquals(listOf(SyncTables.ASSETS, SyncTables.SCHEDULES, SyncTables.ODOMETER_READINGS), remote.upsertedTables)
        assertTrue("updated_at is server-owned", remote.upsertBodies.none { "updated_at" in it })
        assertEquals("vehicle", remote.row(SyncTables.ASSETS, assetId).str("category"))
        assertEquals("ok", remote.row(SyncTables.SCHEDULES, scheduleId).str("status"))

        val asset = db.assetRows.getValue(assetId)
        assertEquals(SyncState.SYNCED, asset.syncState)
        val serverUpdatedAt = parsePgInstant(remote.row(SyncTables.ASSETS, assetId).str("updated_at")!!)
        assertEquals(serverUpdatedAt.truncatedTo(ChronoUnit.MILLIS), asset.updatedAt)
        assertEquals(SyncState.SYNCED, db.scheduleRows.getValue(scheduleId).syncState)
        assertTrue(db.readingRows.all { it.syncState == SyncState.SYNCED })
        assertTrue(db.outboxRows.isEmpty())
        assertEquals(NOW, store.lastSyncedAtValue)
        assertTrue(db.writesOutsideTransaction.isEmpty())
    }

    @Test fun anEditMadeWhileItsPushIsInFlightStaysPending() = runBlocking {
        val id = scooter()
        remote.beforeUpsert = {
            clock.now = remote.serverNow.plusSeconds(5)
            assets.update(id, scooterDraft.copy(name = "Renamed mid-push"))
        }

        sync()

        val row = db.assetRows.getValue(id)
        assertEquals("Renamed mid-push", row.name)
        assertEquals(SyncState.PENDING, row.syncState)
        assertNotNull("the newer edit is pushed next run", entry(SyncTables.ASSETS, id))

        sync()
        assertEquals("Renamed mid-push", remote.row(SyncTables.ASSETS, id).str("name"))
        assertEquals(SyncState.SYNCED, db.assetRows.getValue(id).syncState)
    }

    @Test fun serverCopyWrittenAfterTheLocalEditWins() = runBlocking {
        val id = scooter()
        sync()
        clock.now = remote.serverNow
        assets.update(id, scooterDraft.copy(name = "Mine"))
        remote.seed(SyncTables.ASSETS, remote.row(SyncTables.ASSETS, id).with("name", "Theirs"), updatedAt = clock.now.plusSeconds(30))
        val upserts = remote.upsertedTables.size

        val outcome = sync()

        assertEquals(1, outcome.conflictsLost)
        assertEquals(upserts, remote.upsertedTables.size)
        assertEquals("Theirs", db.assetRows.getValue(id).name)
        assertEquals(SyncState.SYNCED, db.assetRows.getValue(id).syncState)
        assertNull(entry(SyncTables.ASSETS, id))
    }

    @Test fun localEditMadeAfterTheServerCopyWins() = runBlocking {
        val id = scooter()
        sync()
        remote.seed(SyncTables.ASSETS, remote.row(SyncTables.ASSETS, id).with("name", "Theirs"))
        clock.now = remote.serverNow.plusSeconds(60)
        assets.update(id, scooterDraft.copy(name = "Mine"))

        val outcome = sync()

        assertEquals(0, outcome.conflictsLost)
        assertEquals("Mine", remote.row(SyncTables.ASSETS, id).str("name"))
        assertEquals("Mine", db.assetRows.getValue(id).name)
        assertEquals(SyncState.SYNCED, db.assetRows.getValue(id).syncState)
    }

    @Test fun aLogLockedByAReportBeatsALaterLocalDelete() = runBlocking {
        val assetId = scooter()
        val logId = maintenance.logService(assetId, ServiceVisit(NOW, 12_500, Peso(45_000), "Motoshop", null, emptyList())).single()
        sync()
        val lockedAt = remote.serverNow
        remote.seed(SyncTables.SERVICE_LOGS, remote.row(SyncTables.SERVICE_LOGS, logId).with("locked_at", pgTimestamp(lockedAt)), lockedAt)
        clock.now = lockedAt.plus(Duration.ofHours(1))
        assertEquals(DeleteLogResult.Deleted, maintenance.deleteServiceLog(logId))

        sync()

        val log = db.logRows.getValue(logId)
        assertNull("the delete is discarded", log.deletedAt)
        assertEquals(lockedAt.truncatedTo(ChronoUnit.MICROS), log.lockedAt)
        assertEquals(SyncState.SYNCED, log.syncState)
        assertNull(remote.row(SyncTables.SERVICE_LOGS, logId).str("deleted_at"))
        assertTrue("locked_at is never sent", remote.upsertBodies.none { "locked_at" in it })
    }

    @Test fun aRejectedRowDoesNotBlockItsBatchAndFailsAfterMaxAttempts() = runBlocking {
        val good = scooter()
        val bad = assets.create(AssetDraft(category = AssetCategory.APPLIANCE, name = "Bad row"))
        remote.rejectIds += bad

        val outcome = sync()

        assertEquals(1, outcome.rejected)
        assertFalse(outcome.shouldRetry)
        assertEquals(SyncState.SYNCED, db.assetRows.getValue(good).syncState)
        val e = entry(SyncTables.ASSETS, bad)!!
        assertEquals(1, e.attempts)
        assertTrue(e.lastError!!.contains("check constraint"))
        assertEquals(SyncState.PENDING, db.assetRows.getValue(bad).syncState)

        repeat(SyncEngine.MAX_ATTEMPTS - 1) { sync() }
        assertEquals(SyncState.FAILED, db.assetRows.getValue(bad).syncState)
        assertEquals(SyncEngine.MAX_ATTEMPTS, entry(SyncTables.ASSETS, bad)!!.attempts)
        assertTrue(db.outboxDao.pending(SyncEngine.MAX_ATTEMPTS).none { it.rowId == bad })

        // Editing the item queues a fresh attempt.
        remote.rejectIds.clear()
        clock.now = remote.serverNow.plusSeconds(1)
        assets.update(bad, AssetDraft(category = AssetCategory.APPLIANCE, name = "Fixed"))
        sync()
        assertEquals(SyncState.SYNCED, db.assetRows.getValue(bad).syncState)
        assertEquals("Fixed", remote.row(SyncTables.ASSETS, bad).str("name"))
    }

    @Test fun outageStopsTheRunAndKeepsEverythingQueued() = runBlocking {
        val id = scooter()
        remote.offline = true

        val outcome = sync()

        assertTrue(outcome.shouldRetry)
        assertEquals(SyncState.PENDING, db.assetRows.getValue(id).syncState)
        assertNotNull(entry(SyncTables.ASSETS, id))
        assertEquals(0, entry(SyncTables.ASSETS, id)!!.attempts)
        assertNull(store.lastSyncedAtValue)
    }

    // ------------------------------------------------------------------ claim and documents

    @Test fun offlineRowsAreClaimedOnSignInAndTheUploaderIsKicked() = runBlocking {
        users.userId = null
        val assetId = scooter()
        db.putDocument(document("doc-1", assetId, userId = null, storagePath = null))
        db.outboxDao.record(SyncTables.DOCUMENTS, "doc-1", NOW)

        sync()

        assertEquals("user-1", db.assetRows.getValue(assetId).userId)
        assertEquals("user-1", remote.row(SyncTables.ASSETS, assetId).str("user_id"))
        assertTrue(db.readingRows.all { it.userId == "user-1" && it.syncState == SyncState.SYNCED })
        assertEquals("user-1", db.documentRows.getValue("doc-1").userId)
        assertEquals(1, uploads.count)
        assertTrue("held back until uploaded", remote.rows(SyncTables.DOCUMENTS).isEmpty())
        assertNotNull(entry(SyncTables.DOCUMENTS, "doc-1"))
    }

    @Test fun anotherAccountsRowsStayOnThePhoneUnpushed() = runBlocking {
        users.userId = "user-2"
        val id = scooter()

        sync()

        assertTrue(remote.rows(SyncTables.ASSETS).isEmpty())
        assertNotNull(entry(SyncTables.ASSETS, id))
    }

    @Test fun documentDeletedBeforeUploadNeverReachesTheServer() = runBlocking {
        val assetId = scooter()
        db.putDocument(document("doc-1", assetId, storagePath = null, deletedAt = NOW))
        db.outboxDao.record(SyncTables.DOCUMENTS, "doc-1", NOW)

        sync()

        assertNull(entry(SyncTables.DOCUMENTS, "doc-1"))
        assertTrue(remote.rows(SyncTables.DOCUMENTS).isEmpty())
    }

    @Test fun uploadedDocumentDeletePropagatesAndRemovesTheStoredObject() = runBlocking {
        val assetId = scooter()
        val path = "user-1/$assetId/doc-1.jpg"
        db.putDocument(document("doc-1", assetId, storagePath = path))
        db.outboxDao.record(SyncTables.DOCUMENTS, "doc-1", NOW)
        sync()
        assertEquals(path, remote.row(SyncTables.DOCUMENTS, "doc-1").str("storage_path"))
        assertTrue(storage.deleted.isEmpty())

        clock.now = remote.serverNow.plusSeconds(1)
        db.putDocument(db.documentRows.getValue("doc-1").copy(deletedAt = clock.now, updatedAt = clock.now, syncState = SyncState.PENDING))
        db.outboxDao.record(SyncTables.DOCUMENTS, "doc-1", clock.now)
        sync()

        assertNotNull(remote.row(SyncTables.DOCUMENTS, "doc-1").str("deleted_at"))
        assertEquals(listOf(path), storage.deleted)
    }

    // ------------------------------------------------------------------ pull

    @Test fun pullsAnotherDevicesRowsAndRecomputesDueDates() = runBlocking {
        remote.seed(SyncTables.ASSETS, assetJson("a-1", "Carrier aircon"))
        remote.seed(
            SyncTables.SCHEDULES,
            json(ScheduleDto.serializer(), ScheduleDto(
                id = "s-1", userId = "user-1", assetId = "a-1", task = "Aircon cleaning", intervalDays = 60,
                intervalKm = null, lastDoneAt = NOW.minus(Duration.ofDays(50)), lastDoneKm = null, nextDueAt = null,
                nextDueKm = null, confidence = null, status = "ok", createdAt = NOW, deletedAt = null,
            )),
        )

        val outcome = sync()

        assertEquals(2, outcome.pulled)
        assertEquals(SyncState.SYNCED, db.assetRows.getValue("a-1").syncState)
        assertEquals(AssetCategory.APPLIANCE, db.assetRows.getValue("a-1").category)
        val schedule = db.scheduleRows.getValue("s-1")
        assertEquals("10 days left of 60", MaintenanceStatus.DUE_SOON, schedule.status)
        assertEquals(NOW.plus(Duration.ofDays(10)), schedule.nextDueAt)
        assertNotNull(store.cursors["user-1" to SyncTables.SCHEDULES])
    }

    @Test fun pagingDoesNotSkipRowsThatShareOneTimestamp() = runBlocking {
        val sameInstant = NOW.plusSeconds(10).plusNanos(123_456_000)
        val count = SyncEngine.PULL_PAGE * 2 + 100
        for (i in 1..count) remote.seed(SyncTables.ASSETS, assetJson("a-%04d".format(i), "Asset $i"), sameInstant)

        sync()

        assertEquals(count, db.assetRows.size)
        assertEquals(PullCursor(sameInstant, "a-%04d".format(count)), store.cursors["user-1" to SyncTables.ASSETS])
        val pullsBefore = remote.pullCalls
        sync()
        assertEquals("nothing new: one empty page per table", pullsBefore + registry.ordered.size, remote.pullCalls)
    }

    @Test fun softDeleteFromAnotherDeviceHidesTheAsset() = runBlocking {
        val id = scooter()
        sync()
        remote.seed(SyncTables.ASSETS, remote.row(SyncTables.ASSETS, id).with("deleted_at", pgTimestamp(remote.serverNow)))

        sync()

        assertNotNull(db.assetRows.getValue(id).deletedAt)
        assertTrue(db.assetDao.observeActive().first().isEmpty())
    }

    @Test fun pullKeepsAPendingLocalEditNewerThanTheServerCopy() = runBlocking {
        val id = scooter()
        sync()
        remote.seed(SyncTables.ASSETS, remote.row(SyncTables.ASSETS, id).with("name", "Theirs"))
        clock.now = remote.serverNow.plusSeconds(60)
        assets.update(id, scooterDraft.copy(name = "Mine"))
        remote.rejectIds += id // the push fails, so the pull meets a still-pending row

        sync()

        assertEquals("Mine", db.assetRows.getValue(id).name)
        assertEquals(SyncState.PENDING, db.assetRows.getValue(id).syncState)
        assertEquals(1, entry(SyncTables.ASSETS, id)!!.attempts)
    }

    @Test fun childWhoseParentIsNotLocalYetWaitsForIt() = runBlocking {
        val schedule = json(ScheduleDto.serializer(), ScheduleDto(
            id = "s-1", userId = "user-1", assetId = "a-late", task = "Water filter", intervalDays = 180,
            intervalKm = null, lastDoneAt = NOW, lastDoneKm = null, nextDueAt = null, nextDueKm = null,
            confidence = null, status = "ok", createdAt = NOW, deletedAt = null,
        ))
        remote.seed(SyncTables.SCHEDULES, schedule)

        val first = sync()

        assertFalse(first.shouldRetry)
        assertTrue(db.scheduleRows.isEmpty())
        assertNull("cursor stays before the blocked row", store.cursors["user-1" to SyncTables.SCHEDULES])

        remote.seed(SyncTables.ASSETS, assetJson("a-late", "Water filter"))
        sync()

        assertNotNull(db.assetRows["a-late"])
        // Present now; the recompute that fills its empty due fields re-queues it, which is expected.
        assertEquals("a-late", db.scheduleRows.getValue("s-1").assetId)
        assertNotNull(db.scheduleRows.getValue("s-1").nextDueAt)
    }

    // ------------------------------------------------------------------ helpers

    private fun <T> json(serializer: kotlinx.serialization.KSerializer<T>, value: T): JsonObject =
        SyncJson.encodeToJsonElement(serializer, value).jsonObject

    private fun assetJson(id: String, name: String) = json(AssetDto.serializer(), AssetDto(
        id = id, userId = "user-1", category = "appliance", name = name, brand = null, model = null, serialNo = null,
        purchaseDate = null, priceCentavos = null, warrantyExpiresOn = null, currentOdometerKm = null,
        weeklyKmBaseline = null, notes = null, archivedAt = null, createdAt = NOW, deletedAt = null,
    ))

    private fun document(
        id: String,
        assetId: String,
        userId: String? = "user-1",
        storagePath: String?,
        deletedAt: Instant? = null,
    ) = DocumentEntity(
        id = id, userId = userId, assetId = assetId, serviceLogId = null, kind = DocumentKind.RECEIPT,
        storagePath = storagePath, localPath = if (storagePath == null) "/files/documents/$id.jpg" else null,
        mimeType = "image/jpeg", sizeBytes = 2_048, sha256 = "a".repeat(64), createdAt = NOW, updatedAt = NOW,
        deletedAt = deletedAt, syncState = SyncState.PENDING,
    )
}
