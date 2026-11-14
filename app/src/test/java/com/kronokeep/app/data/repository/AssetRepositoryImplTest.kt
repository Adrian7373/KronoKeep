package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.entity.OutboxOp
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.SequentialIds
import com.kronokeep.app.testing.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class AssetRepositoryImplTest {
    private val db = FakeDb()
    private val users = FakeUserIds()
    private val clock = MutableClock()
    private fun repo() = AssetRepositoryImpl(
        db.assetDao, db.odometerDao, db.outboxDao, db, clock, SequentialIds(), users, db.recomputer,
    )

    private val scooter = AssetDraft(
        category = AssetCategory.VEHICLE,
        name = "Honda Click 125i",
        brand = "Honda",
        purchaseDate = LocalDate.of(2025, 3, 1),
        price = Peso(8_990_000),
        currentOdometerKm = 12_400,
        weeklyKmBaseline = 150,
    )

    @Test fun createWritesRowAndOutboxInOneTransaction() = runBlocking {
        val repo = repo()
        val id = repo.create(scooter)

        val row = db.assetRows.getValue(id)
        assertEquals("Honda Click 125i", row.name)
        assertEquals(8_990_000L, row.priceCentavos)
        assertEquals("user-1", row.userId)
        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(NOW, row.createdAt)
        assertEquals(NOW, row.updatedAt)

        assertTrue(db.outboxRows.any { it.tableName == SyncTables.ASSETS && it.rowId == id && it.op == OutboxOp.UPSERT })
        assertTrue("all writes inside the transaction", db.writesOutsideTransaction.isEmpty())
        assertEquals(1, db.transactionCount)
    }

    @Test fun createSeedsFirstOdometerReading() = runBlocking {
        val id = repo().create(scooter)
        val reading = db.readingRows.single()
        assertEquals(id, reading.assetId)
        assertEquals(12_400, reading.km)
        assertEquals(NOW, reading.recordedAt)
        assertTrue(db.outboxRows.any { it.tableName == SyncTables.ODOMETER_READINGS && it.rowId == reading.id })
    }

    @Test fun createWithoutOdometerAddsNoReading() = runBlocking {
        repo().create(AssetDraft(category = AssetCategory.APPLIANCE, name = "Bedroom aircon"))
        assertTrue(db.readingRows.isEmpty())
        assertEquals(1, db.outboxRows.size)
    }

    @Test fun offlineRowsHaveNoOwnerYet() = runBlocking {
        users.userId = null
        val id = repo().create(scooter)
        assertNull(db.assetRows.getValue(id).userId)
    }

    @Test fun failedOutboxWriteRollsBackTheRow() = runBlocking {
        db.failNextOutboxWrite = true
        try {
            repo().create(scooter)
            fail("expected the outbox failure to propagate")
        } catch (_: IllegalStateException) {
        }
        assertTrue(db.assetRows.isEmpty())
        assertTrue(db.readingRows.isEmpty())
        assertTrue(db.outboxRows.isEmpty())
    }

    @Test fun updateKeepsIdentityAndCoalescesOutbox() = runBlocking {
        val repo = repo()
        val id = repo.create(scooter)
        clock.now = NOW.plusSeconds(60)
        assertTrue(repo.update(id, scooter.copy(name = "Click", currentOdometerKm = 12_400)))

        val row = db.assetRows.getValue(id)
        assertEquals("Click", row.name)
        assertEquals(NOW, row.createdAt)
        assertEquals(NOW.plusSeconds(60), row.updatedAt)
        assertEquals(1, db.outboxRows.count { it.tableName == SyncTables.ASSETS && it.rowId == id })
        assertEquals("unchanged odometer adds no reading", 1, db.readingRows.size)
    }

    @Test fun updateWithNewOdometerAddsReading() = runBlocking {
        val repo = repo()
        val id = repo.create(scooter)
        repo.update(id, scooter.copy(currentOdometerKm = 13_000))
        assertEquals(listOf(12_400, 13_000), db.readingRows.map { it.km }.sorted())
        assertEquals(13_000, db.assetRows.getValue(id).currentOdometerKm)
    }

    @Test fun updateOfMissingOrDeletedAssetFails() = runBlocking {
        val repo = repo()
        assertFalse(repo.update("nope", scooter))
        val id = repo.create(scooter)
        repo.delete(id)
        assertFalse(repo.update(id, scooter))
    }

    @Test fun archiveAndUnarchive() = runBlocking {
        val repo = repo()
        val id = repo.create(scooter)
        assertTrue(repo.setArchived(id, true))
        assertEquals(NOW, db.assetRows.getValue(id).archivedAt)
        assertTrue(repo.observeActive().first().isEmpty())
        assertEquals(id, repo.observeArchived().first().single().id)

        assertTrue(repo.setArchived(id, false))
        assertNull(db.assetRows.getValue(id).archivedAt)
        assertEquals(id, repo.observeActive().first().single().id)
    }

    @Test fun deleteIsSoftAndQueuesSync() = runBlocking {
        val repo = repo()
        val id = repo.create(scooter)
        clock.now = NOW.plusSeconds(5)
        assertTrue(repo.delete(id))

        val row = db.assetRows[id]
        assertNotNull("row is kept (soft delete)", row)
        assertEquals(NOW.plusSeconds(5), row!!.deletedAt)
        assertEquals(SyncState.PENDING, row.syncState)
        assertNull(repo.get(id))
        assertNull(repo.observe(id).first())
        assertTrue(repo.observeActive().first().isEmpty())
        assertEquals(1, db.outboxRows.count { it.rowId == id })
        assertFalse("second delete is a no-op", repo.delete(id))
    }
}
