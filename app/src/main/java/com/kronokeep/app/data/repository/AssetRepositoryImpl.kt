package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.toDomain
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.repository.AssetRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssetRepositoryImpl @Inject constructor(
    private val assetDao: AssetDao,
) : AssetRepository {
    override fun observeActive(): Flow<List<Asset>> =
        assetDao.observeActive().map { rows -> rows.map { it.toDomain() } }
}
