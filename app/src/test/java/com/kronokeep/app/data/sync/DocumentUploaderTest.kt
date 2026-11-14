package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeFileStore
import com.kronokeep.app.testing.FakeStorage
import com.kronokeep.app.testing.NOW
import com.kronokeep.app.testing.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentUploaderTest {
    private val db = FakeDb()
    private val files = FakeFileStore()
    private val storage = FakeStorage()
    private val uploader = DocumentUploader(db.documentDao, db.outboxDao, db, storage, files, fixedClock())

    private fun doc(id: String, userId: String? = "user-1", mime: String = "image/jpeg", ext: String = "jpg") = DocumentEntity(
        id = id,
        userId = userId,
        assetId = "asset-1",
        serviceLogId = null,
        kind = DocumentKind.RECEIPT,
        storagePath = null,
        localPath = "/files/documents/$id.$ext",
        mimeType = mime,
        sizeBytes = 3,
        sha256 = "b".repeat(64),
        createdAt = NOW.minusSeconds(100),
        updatedAt = NOW.minusSeconds(100),
        deletedAt = null,
        syncState = SyncState.PENDING,
    ).also {
        db.putDocument(it)
        files.files[it.localPath!!] = byteArrayOf(7, 8, 9)
    }

    @Test fun uploadsToOwnerFolderThenRecordsPathAndDropsLocalCopy() = runBlocking {
        doc("doc-1")
        doc("doc-2", mime = "application/pdf", ext = "pdf")

        val outcome = uploader.uploadPending("user-1")

        assertEquals(UploadOutcome(uploaded = 2, failed = 0, lost = 0), outcome)
        assertArrayEquals(byteArrayOf(7, 8, 9), storage.objects["user-1/asset-1/doc-1.jpg"])
        assertTrue(storage.objects.containsKey("user-1/asset-1/doc-2.pdf"))
        val row = db.documentRows.getValue("doc-1")
        assertEquals("user-1/asset-1/doc-1.jpg", row.storagePath)
        assertNull(row.localPath)
        assertEquals(NOW, row.updatedAt)
        assertEquals(SyncState.PENDING, row.syncState)
        assertTrue(db.outboxRows.any { it.tableName == SyncTables.DOCUMENTS && it.rowId == "doc-1" })
        assertTrue(files.files.isEmpty())
        assertTrue(db.writesOutsideTransaction.isEmpty())
    }

    @Test fun skipsOtherUsersAndUnclaimedRows() = runBlocking {
        doc("mine")
        doc("theirs", userId = "user-2")
        doc("offline", userId = null)

        uploader.uploadPending("user-1")

        assertEquals(setOf("user-1/asset-1/mine.jpg"), storage.objects.keys)
        assertNull(db.documentRows.getValue("theirs").storagePath)
        assertNull(db.documentRows.getValue("offline").storagePath)
    }

    @Test fun networkFailureLeavesEverythingForRetry() = runBlocking {
        doc("doc-1")
        storage.failUploads = true

        val outcome = uploader.uploadPending("user-1")

        assertEquals(1, outcome.failed)
        assertTrue(outcome.shouldRetry)
        val row = db.documentRows.getValue("doc-1")
        assertNull(row.storagePath)
        assertTrue(files.exists(row.localPath!!))
        assertTrue(db.outboxRows.isEmpty())
    }

    @Test fun missingLocalFileIsMarkedFailedAndNotRetried() = runBlocking {
        val d = doc("doc-1")
        files.files.remove(d.localPath)

        val outcome = uploader.uploadPending("user-1")

        assertEquals(1, outcome.lost)
        assertFalse(outcome.shouldRetry)
        assertEquals(SyncState.FAILED, db.documentRows.getValue("doc-1").syncState)
        assertTrue("FAILED rows leave the queue", db.documentDao.pendingUploads("user-1").isEmpty())
    }

    @Test fun deletedDocumentsAreNotUploaded() = runBlocking {
        val d = doc("doc-1")
        db.putDocument(d.copy(deletedAt = NOW))
        uploader.uploadPending("user-1")
        assertTrue(storage.objects.isEmpty())
    }
}
