package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.record
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.SyncStatus
import com.kronokeep.app.testing.FakeAuth
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeSyncScheduler
import com.kronokeep.app.testing.FakeSyncStateStore
import com.kronokeep.app.testing.NOW
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRepositoryImplTest {
    private val db = FakeDb()
    private val store = FakeSyncStateStore()
    private val scheduler = FakeSyncScheduler()
    private val auth = FakeAuth()
    private val repo = SyncRepositoryImpl(db.outboxDao, store, scheduler, auth)

    @Test fun statusSplitsPendingFromRejectedAndReportsLastSync() = runBlocking {
        db.outboxDao.record(SyncTables.ASSETS, "a-1", NOW)
        db.outboxDao.record(SyncTables.ASSETS, "a-2", NOW)
        val rejected = db.outboxDao.entryFor(SyncTables.ASSETS, "a-2")!!
        repeat(SyncEngine.MAX_ATTEMPTS) { db.outboxDao.markAttempt(rejected.seq, "nope") }
        store.setLastSyncedAt(NOW)
        scheduler.running.value = true

        assertEquals(SyncStatus(pending = 1, rejected = 1, lastSyncedAt = NOW, running = true), repo.status.first())
    }

    @Test fun emptyOutboxIsAllSynced() = runBlocking {
        assertTrue(repo.status.first().allSynced)
    }

    @Test fun syncNowOnlyWhenSignedIn() {
        repo.syncNow()
        assertEquals(1, scheduler.manual)

        auth.state.value = AuthState.SignedOut
        repo.syncNow()
        auth.state.value = AuthState.Unconfigured
        repo.syncNow()
        assertEquals(1, scheduler.manual)
        assertFalse(scheduler.scheduled > 0)
    }
}
