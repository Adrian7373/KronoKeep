package com.kronokeep.app.domain.report

import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.OutboxOp
import com.kronokeep.app.data.remote.ReportRemoteException
import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.data.repository.MaintenanceRepositoryImpl
import com.kronokeep.app.data.repository.ReportRepositoryImpl
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.testing.CountingSyncRepository
import com.kronokeep.app.testing.FakeAuth
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeReportRemote
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.MutableClock
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.SequentialIds
import com.kronokeep.app.testing.syncedAsset
import com.kronokeep.app.testing.syncedLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerateReportUseCaseTest {
    private val db = FakeDb()
    private val clock = MutableClock()
    private val ids = SequentialIds()
    private val remote = FakeReportRemote()
    private val auth = FakeAuth()
    private val sync = CountingSyncRepository()
    private val assets = AssetRepositoryImpl(db.assetDao, db.odometerDao, db.outboxDao, db, clock, ids, FakeUserIds(), db.recomputer)
    private val maintenance = MaintenanceRepositoryImpl(
        db.assetDao, db.scheduleDao, db.serviceLogDao, db.odometerDao, db.outboxDao, db, clock, ids, db.recomputer,
    )
    private val generate = GenerateReportUseCase(ReportRepositoryImpl(remote, db.outboxDao), assets, maintenance, auth, sync)

    private fun seedSynced() {
        db.putAsset(syncedAsset())
        db.putLog(syncedLog("log-1"))
    }

    @Test fun `a fully synced asset is generated and a pull is requested for the locks`() = runBlocking {
        seedSynced()
        val result = generate("asset-1") as GenerateReportResult.Generated
        assertEquals("report-1", result.report.id)
        assertEquals(1, remote.generateCalls)
        assertEquals(1, sync.syncRequests)
    }

    @Test fun `a reused report doesn't trigger a sync`() = runBlocking {
        seedSynced()
        remote.reuse = true
        val result = generate("asset-1") as GenerateReportResult.Generated
        assertTrue(result.reused)
        assertEquals(0, sync.syncRequests)
    }

    @Test fun `pending local edits block generation and kick a sync`() = runBlocking {
        seedSynced()
        db.outboxDao.enqueue(OutboxEntity(tableName = "service_logs", rowId = "log-1", op = OutboxOp.UPSERT, enqueuedAt = NOW))
        assertEquals(GenerateReportResult.NeedsSync(1), generate("asset-1"))
        assertEquals(0, remote.generateCalls)
        assertEquals(1, sync.syncRequests)
    }

    @Test fun `an asset never pushed counts as needing sync even without outbox rows`() = runBlocking {
        db.putAsset(syncedAsset(syncState = SyncState.PENDING))
        db.putLog(syncedLog("log-1"))
        assertEquals(GenerateReportResult.NeedsSync(1), generate("asset-1"))
    }

    @Test fun `no service history is refused before calling the server`() = runBlocking {
        db.putAsset(syncedAsset())
        assertEquals(GenerateReportResult.NoServiceLogs, generate("asset-1"))
        assertEquals(0, remote.generateCalls)
    }

    @Test fun `auth state and missing assets are reported`() = runBlocking {
        seedSynced()
        auth.state.value = AuthState.Unconfigured
        assertEquals(GenerateReportResult.CloudNotConfigured, generate("asset-1"))
        auth.state.value = AuthState.SignedOut
        assertEquals(GenerateReportResult.NotSignedIn, generate("asset-1"))
        auth.state.value = AuthState.SignedIn("user-1", null)
        assertEquals(GenerateReportResult.AssetMissing, generate("nope"))
    }

    @Test fun `server failures map to results`() = runBlocking {
        seedSynced()
        remote.failWith = ReportRemoteException(409, "changed", "changed")
        assertEquals(GenerateReportResult.Changed, generate("asset-1"))

        // The server doesn't know the asset although Room thinks it's synced: ask for a sync.
        remote.failWith = ReportRemoteException(404, "not_found", "nope")
        assertEquals(GenerateReportResult.NeedsSync(1), generate("asset-1"))
        assertEquals(1, sync.syncRequests)

        remote.failWith = java.io.IOException("offline")
        assertEquals(GenerateReportResult.Failed("offline"), generate("asset-1"))
    }
}
