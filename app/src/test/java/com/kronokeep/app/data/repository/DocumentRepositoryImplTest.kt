package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.domain.document.DocumentRules
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.DocumentSource
import com.kronokeep.app.domain.model.PreparedFile
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.testing.CountingScheduler
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeFileStore
import com.kronokeep.app.testing.FakeStorage
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRepositoryImplTest {
    private val db = FakeDb()
    private val files = FakeFileStore(mapOf("/files/documents/doc-1.jpg" to byteArrayOf(1, 2, 3)))
    private val storage = FakeStorage()
    private val scheduler = CountingScheduler()
    private val repo = DocumentRepositoryImpl(
        db.documentDao, db.outboxDao, db, fixedClock(), FakeUserIds(), storage, files, scheduler,
    )
    private val file = PreparedFile("/files/documents/doc-1.jpg", "image/jpeg", 3, "a".repeat(64))

    @Test fun addRecordsRowWithOutboxAndKicksUploadQueue() = runBlocking {
        repo.add("doc-1", "asset-1", DocumentKind.RECEIPT, file)

        val row = db.documentRows.getValue("doc-1")
        assertEquals("asset-1", row.assetId)
        assertEquals("user-1", row.userId)
        assertNull("not uploaded yet", row.storagePath)
        assertEquals(file.localPath, row.localPath)
        assertEquals(file.sha256, row.sha256)
        assertEquals(SyncState.PENDING, row.syncState)
        assertTrue(db.outboxRows.any { it.tableName == SyncTables.DOCUMENTS && it.rowId == "doc-1" })
        assertTrue(db.writesOutsideTransaction.isEmpty())
        assertEquals(1, scheduler.count)
    }

    @Test fun sourcePrefersTheLocalCopy() = runBlocking {
        repo.add("doc-1", "asset-1", DocumentKind.RECEIPT, file)
        assertEquals(DocumentSource.Local(file.localPath), repo.source("doc-1").getOrThrow())
        assertTrue(storage.signedUrlRequests.isEmpty())
    }

    @Test fun uploadedDocumentGetsShortLivedSignedUrl() = runBlocking {
        repo.add("doc-1", "asset-1", DocumentKind.RECEIPT, file)
        val path = "user-1/asset-1/doc-1.jpg"
        db.putDocument(db.documentRows.getValue("doc-1").copy(storagePath = path, localPath = null))

        val source = repo.source("doc-1").getOrThrow() as DocumentSource.SignedUrl
        assertTrue(source.url.contains(path))
        assertEquals(path to DocumentRules.SIGNED_URL_TTL_SECONDS, storage.signedUrlRequests.single())
        assertEquals(NOW.plusSeconds(DocumentRules.SIGNED_URL_TTL_SECONDS), source.expiresAt)
    }

    @Test fun sourceFailsWhenNeitherCopyExists() = runBlocking {
        repo.add("doc-1", "asset-1", DocumentKind.RECEIPT, file)
        files.files.clear()
        assertTrue(repo.source("doc-1").isFailure)
        assertTrue(repo.source("missing").isFailure)
    }

    @Test fun deleteIsSoftAndRemovesUnuploadedLocalFile() = runBlocking {
        repo.add("doc-1", "asset-1", DocumentKind.RECEIPT, file)
        assertTrue(repo.delete("doc-1"))

        assertEquals(NOW, db.documentRows.getValue("doc-1").deletedAt)
        assertFalse(files.exists(file.localPath))
        assertNull(repo.get("doc-1"))
        assertEquals(1, db.outboxRows.count { it.rowId == "doc-1" })
        assertFalse(repo.delete("doc-1"))
    }
}
