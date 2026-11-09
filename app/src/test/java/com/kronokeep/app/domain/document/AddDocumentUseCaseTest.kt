package com.kronokeep.app.domain.document

import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.data.repository.DocumentRepositoryImpl
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.PreparedFile
import com.kronokeep.app.domain.repository.DocumentImporter
import com.kronokeep.app.domain.repository.ImportResult
import com.kronokeep.app.testing.CountingScheduler
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeFileStore
import com.kronokeep.app.testing.FakeStorage
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.SequentialIds
import com.kronokeep.app.testing.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddDocumentUseCaseTest {
    private val db = FakeDb()
    private val files = FakeFileStore()
    private val assets = AssetRepositoryImpl(
        db.assetDao, db.odometerDao, db.outboxDao, db, fixedClock(), SequentialIds("asset"), FakeUserIds(),
    )
    private val documents = DocumentRepositoryImpl(
        db.documentDao, db.outboxDao, db, fixedClock(), FakeUserIds(), FakeStorage(), files, CountingScheduler(),
    )

    private class FakeImporter(var result: (String) -> ImportResult) : DocumentImporter {
        val discarded = mutableListOf<String>()
        override suspend fun import(sourceUri: String, documentId: String, declaredMimeType: String?) = result(documentId)
        override suspend fun discard(localPath: String) {
            discarded += localPath
        }
    }

    private fun prepared(id: String, mime: String = "image/jpeg", size: Long = 1000) =
        ImportResult.Success(PreparedFile("/files/documents/$id.jpg", mime, size, "c".repeat(64)))

    private val importer = FakeImporter { prepared(it) }
    private val useCase = AddDocumentUseCase(assets, documents, importer, SequentialIds("doc"))

    private fun assetId() = runBlocking { assets.create(AssetDraft(category = AssetCategory.ELECTRONIC, name = "Laptop")) }

    @Test fun addsImportedFileUnderNewId() = runBlocking {
        val asset = assetId()
        val result = useCase(asset, DocumentKind.WARRANTY, "content://picked/1", null)
        assertEquals(AddDocumentResult.Added("doc-1"), result)
        val row = db.documentRows.getValue("doc-1")
        assertEquals(DocumentKind.WARRANTY, row.kind)
        assertEquals(asset, row.assetId)
    }

    @Test fun unknownAssetIsRejectedBeforeImporting() = runBlocking {
        var imported = false
        importer.result = { imported = true; prepared(it) }
        assertEquals(AddDocumentResult.AssetNotFound, useCase("ghost", DocumentKind.RECEIPT, "content://x", null))
        assertTrue(!imported)
    }

    @Test fun importerRejectionsPassThrough() = runBlocking {
        val asset = assetId()
        importer.result = { ImportResult.UnsupportedType }
        assertEquals(AddDocumentResult.UnsupportedType, useCase(asset, DocumentKind.RECEIPT, "content://x", "text/plain"))
        importer.result = { ImportResult.TooLarge(20_000_000) }
        assertEquals(AddDocumentResult.TooLarge(20_000_000), useCase(asset, DocumentKind.RECEIPT, "content://x", null))
        assertTrue(db.documentRows.isEmpty())
    }

    @Test fun oversizedOrDisallowedResultIsDiscarded() = runBlocking {
        val asset = assetId()
        importer.result = { prepared(it, size = DocumentRules.MAX_BYTES + 1) }
        assertEquals(AddDocumentResult.TooLarge(DocumentRules.MAX_BYTES + 1), useCase(asset, DocumentKind.RECEIPT, "u", null))
        importer.result = { prepared(it, mime = "image/gif") }
        assertEquals(AddDocumentResult.UnsupportedType, useCase(asset, DocumentKind.RECEIPT, "u", null))
        assertEquals(2, importer.discarded.size)
        assertTrue(db.documentRows.isEmpty())
    }

    @Test fun failedSaveDiscardsTheLocalCopy() = runBlocking {
        val asset = assetId()
        db.failNextOutboxWrite = true
        try {
            useCase(asset, DocumentKind.RECEIPT, "u", null)
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf("/files/documents/doc-1.jpg"), importer.discarded)
        assertTrue(db.documentRows.isEmpty())
    }
}
