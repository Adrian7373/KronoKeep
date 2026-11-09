package com.kronokeep.app.data.repository

import com.kronokeep.app.data.document.LocalFileStore
import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.local.toDomain
import com.kronokeep.app.data.remote.DocumentStorage
import com.kronokeep.app.domain.document.DocumentRules
import com.kronokeep.app.domain.model.Document
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.DocumentSource
import com.kronokeep.app.domain.model.PreparedFile
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val documentDao: DocumentDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val userIds: UserIdProvider,
    private val storage: DocumentStorage,
    private val files: LocalFileStore,
    private val uploads: UploadScheduler,
) : DocumentRepository {

    override fun observeForAsset(assetId: String): Flow<List<Document>> =
        documentDao.observeForAsset(assetId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun get(id: String): Document? =
        documentDao.getById(id)?.takeIf { it.deletedAt == null }?.toDomain()

    override suspend fun add(documentId: String, assetId: String, kind: DocumentKind, file: PreparedFile): String {
        val now = clock.instant()
        val entity = DocumentEntity(
            id = documentId,
            userId = userIds.currentUserId(),
            assetId = assetId,
            serviceLogId = null,
            kind = kind,
            storagePath = null,
            localPath = file.localPath,
            mimeType = file.mimeType,
            sizeBytes = file.sizeBytes,
            sha256 = file.sha256,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            syncState = SyncState.PENDING,
        )
        tx.inTransaction {
            documentDao.upsert(entity)
            // Server requires storage_path, so SyncWorker holds this row back until the upload sets it.
            outboxDao.record(SyncTables.DOCUMENTS, documentId, now)
        }
        uploads.schedule()
        return documentId
    }

    override suspend fun delete(id: String): Boolean {
        val removed = tx.inTransaction {
            val existing = documentDao.getById(id)?.takeIf { it.deletedAt == null } ?: return@inTransaction null
            val now = clock.instant()
            documentDao.upsert(existing.copy(deletedAt = now, updatedAt = now, syncState = SyncState.PENDING))
            outboxDao.record(SyncTables.DOCUMENTS, id, now)
            existing
        } ?: return false
        // Never uploaded means nobody else will ever need the local copy.
        removed.localPath?.let(files::delete)
        return true
    }

    override suspend fun source(id: String): Result<DocumentSource> = runCatching {
        val doc = documentDao.getById(id)?.takeIf { it.deletedAt == null }
            ?: throw NoSuchElementException("Document not found")
        val local = doc.localPath
        val remote = doc.storagePath
        when {
            local != null && files.exists(local) -> DocumentSource.Local(local)
            remote != null -> {
                val ttl = DocumentRules.SIGNED_URL_TTL_SECONDS
                val issuedAt = clock.instant()
                DocumentSource.SignedUrl(storage.signedDownloadUrl(remote, ttl), issuedAt.plusSeconds(ttl))
            }
            else -> throw IllegalStateException("This file is no longer on this phone and was never uploaded")
        }
    }
}
