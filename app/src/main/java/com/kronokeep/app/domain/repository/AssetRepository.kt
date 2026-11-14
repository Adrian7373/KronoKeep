package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetDraft
import kotlinx.coroutines.flow.Flow

/**
 * Reads always come from Room. Every write goes to Room plus an outbox row in one transaction
 * and leaves the row `PENDING` until SyncWorker pushes it.
 */
interface AssetRepository {
    /** Active (not archived, not deleted) assets, newest change first. */
    fun observeActive(): Flow<List<Asset>>

    fun observeArchived(): Flow<List<Asset>>

    /** Emits null once the asset is deleted. */
    fun observe(id: String): Flow<Asset?>

    suspend fun get(id: String): Asset?

    /** [draft] must already be normalized and valid. Returns the new asset id. */
    suspend fun create(draft: AssetDraft): String

    /** Returns false if the asset no longer exists. */
    suspend fun update(id: String, draft: AssetDraft): Boolean

    suspend fun setArchived(id: String, archived: Boolean): Boolean

    /** Soft delete: sets `deletedAt`, never removes the row. */
    suspend fun delete(id: String): Boolean
}
