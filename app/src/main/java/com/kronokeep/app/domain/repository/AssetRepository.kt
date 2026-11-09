package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.model.Asset
import kotlinx.coroutines.flow.Flow

interface AssetRepository {
    /** Active (not archived, not deleted) assets, newest change first. Always served from Room. */
    fun observeActive(): Flow<List<Asset>>
}
