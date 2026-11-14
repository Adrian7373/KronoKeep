package com.kronokeep.app.data.sync

import com.kronokeep.app.data.document.LocalFileStore
import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.remote.DocumentStorage
import com.kronokeep.app.domain.document.DocumentRules
import com.kronokeep.app.domain.model.SyncState
import kotlinx.coroutines.CancellationException
import java.time.Clock
import javax.inject.Inject

data class UploadOutcome(val uploaded: Int, val failed: Int, val lost: Int) {
    val shouldRetry: Boolean get() = failed > 0
}

/**
 * Drains the upload queue: every document of the signed-in user that has a local file but no storage path.
 * Bytes go to `{user_id}/{asset_id}/{document_id}.{ext}` through a signed upload URL; then the row
 * gets its storage path (Room + outbox, one transaction) and the local copy is deleted.
 */
class DocumentUploader @Inject constructor(
    private val documentDao: DocumentDao,
    private val outboxDao: OutboxDao,
    private val tx: TransactionRunner,
    private val storage: DocumentStorage,
    private val files: LocalFileStore,
    private val clock: Clock,
) {
    suspend fun uploadPending(userId: String): UploadOutcome {
        var uploaded = 0
        var failed = 0
        var lost = 0
        for (doc in documentDao.pendingUploads(userId)) {
            val localPath = doc.localPath ?: continue
            val bytes = files.read(localPath)
            if (bytes == null) {
                markLost(doc)
                lost++
                continue
            }
            val path = DocumentRules.storagePath(userId, doc.assetId, doc.id, doc.mimeType)
            try {
                storage.upload(path, bytes, doc.mimeType)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
                continue
            }
            if (recordUploaded(doc.id, path)) files.delete(localPath)
            uploaded++
        }
        return UploadOutcome(uploaded, failed, lost)
    }

    /** Returns false if the row was deleted meanwhile; the stored object then just stays orphaned with the soft delete. */
    private suspend fun recordUploaded(id: String, path: String): Boolean = tx.inTransaction {
        val current = documentDao.getById(id)?.takeIf { it.deletedAt == null } ?: return@inTransaction false
        val now = clock.instant()
        documentDao.upsert(
            current.copy(storagePath = path, localPath = null, updatedAt = now, syncState = SyncState.PENDING),
        )
        outboxDao.record(SyncTables.DOCUMENTS, id, now)
        true
    }

    /** The local copy vanished before upload; nothing can recover it. Local-only state, so no outbox row. */
    private suspend fun markLost(doc: DocumentEntity) = tx.inTransaction {
        documentDao.getById(doc.id)?.let { documentDao.upsert(it.copy(syncState = SyncState.FAILED)) }
    }
}
