package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.OutboxOp
import com.kronokeep.app.data.remote.CloudNotConfiguredException
import com.kronokeep.app.data.remote.ReportRemoteException
import com.kronokeep.app.data.remote.SupabaseReportRemote
import com.kronokeep.app.domain.repository.ReportFailure
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeReportRemote
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.syncedAsset
import com.kronokeep.app.testing.syncedLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ReportRepositoryImplTest {
    private val db = FakeDb()
    private val remote = FakeReportRemote()
    private val repo = ReportRepositoryImpl(remote, db.outboxDao)

    @Test fun `generate maps the response and parses Postgres timestamps`() = runBlocking {
        val generated = repo.generate("asset-1")
        assertFalse(generated.reused)
        assertEquals(Instant.parse("2026-11-14T02:00:00.123456Z"), generated.report.generatedAt)
        assertEquals(2, generated.report.logCount)
        assertNull(generated.report.revokedAt)
        assertEquals("abab abab abab abab", generated.report.shortHash)
    }

    @Test fun `share builds a verify-report link carrying the token`() = runBlocking {
        val link = repo.share("r1")
        assertEquals("https://proj.supabase.co/functions/v1/verify-report?id=r1&t=tok123", link.url)
        assertEquals(Instant.parse("2026-11-21T02:00:00.5Z"), link.expiresAt)
    }

    @Test fun `pdf links stay within the 10 minute limit`() = runBlocking {
        val report = repo.generate("asset-1").report
        repo.pdfUrl(report)
        assertEquals(report.storagePath to 600L, remote.signedUrls.single())
    }

    @Test fun `backend errors map to domain failures`() {
        fun map(status: Int, code: String?) = ReportRemoteException(status, code, "msg").toFailure()
        assertTrue(map(401, null) is ReportFailure.NotSignedIn)
        assertTrue(map(422, "no_service_logs") is ReportFailure.NoServiceLogs)
        assertTrue(map(409, "changed") is ReportFailure.Changed)
        assertTrue(map(400, "40001") is ReportFailure.Changed)
        assertTrue(map(400, "22023") is ReportFailure.Revoked)
        assertTrue(map(400, "P0002") is ReportFailure.NotFound)
        assertTrue(map(404, "not_found") is ReportFailure.NotFound)
        val other = map(500, "internal")
        assertTrue(other is ReportFailure.Unavailable)
        assertEquals("msg", other.message)
    }

    @Test fun `missing cloud and network errors become failures`() = runBlocking {
        remote.failWith = CloudNotConfiguredException()
        assertTrue(runCatching { repo.list("asset-1") }.exceptionOrNull() is ReportFailure.CloudNotConfigured)
        remote.failWith = java.io.IOException("timeout")
        val e = runCatching { repo.revoke("r1") }.exceptionOrNull()
        assertTrue(e is ReportFailure.Unavailable)
        assertEquals("timeout", e!!.message)
    }

    @Test fun `error bodies from functions and PostgREST are both understood`() {
        val fn = SupabaseReportRemote.errorFromBody(422, """{"error":"no_service_logs","message":"Log a service first"}""")
        assertEquals("no_service_logs", fn.code)
        assertEquals("Log a service first", fn.message)
        val rpc = SupabaseReportRemote.errorFromBody(400, """{"code":"22023","message":"report revoked"}""")
        assertEquals("22023", rpc.code)
        val plain = SupabaseReportRemote.errorFromBody(502, "Bad gateway")
        assertNull(plain.code)
        assertEquals("Bad gateway", plain.message)
    }

    @Test fun `unsynced changes count only rows of this asset that a report covers`() = runBlocking {
        db.putAsset(syncedAsset("asset-1"))
        db.putAsset(syncedAsset("asset-2"))
        db.putLog(syncedLog("log-1", "asset-1"))
        db.putLog(syncedLog("log-2", "asset-2"))
        fun entry(table: String, row: String) = OutboxEntity(tableName = table, rowId = row, op = OutboxOp.UPSERT, enqueuedAt = NOW)
        db.outboxDao.enqueue(entry("service_logs", "log-1"))
        db.outboxDao.enqueue(entry("service_logs", "log-2"))
        db.outboxDao.enqueue(entry("documents", "doc-1"))
        assertEquals(1, repo.unsyncedChanges("asset-1"))
        db.outboxDao.enqueue(entry("assets", "asset-1"))
        assertEquals(2, repo.unsyncedChanges("asset-1"))
    }

    @Test fun `links trim a trailing slash from the project url`() {
        assertEquals("https://p.co/functions/v1/verify-report?id=r", ReportLinks.verify("https://p.co/", "r"))
    }
}
