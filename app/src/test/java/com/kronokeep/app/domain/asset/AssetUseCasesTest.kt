package com.kronokeep.app.domain.asset

import com.kronokeep.app.data.repository.AssetRepositoryImpl
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.FakeUserIds
import com.kronokeep.app.testing.SequentialIds
import com.kronokeep.app.testing.fixedClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AssetUseCasesTest {
    private val db = FakeDb()
    private val clock = fixedClock() // 2026-11-14
    private val repo = AssetRepositoryImpl(db.assetDao, db.odometerDao, db.outboxDao, db, clock, SequentialIds(), FakeUserIds())
    private val create = CreateAssetUseCase(repo, clock)
    private val update = UpdateAssetUseCase(repo, clock)

    @Test fun createNormalizesAndSaves() = runBlocking {
        val result = create(AssetDraft(category = AssetCategory.ELECTRONIC, name = "  Work laptop ", brand = ""))
        val id = (result as SaveAssetResult.Saved).id
        val saved = repo.get(id)!!
        assertEquals("Work laptop", saved.name)
        assertNull(saved.brand)
    }

    @Test fun invalidDraftIsNotSaved() = runBlocking {
        val result = create(
            AssetDraft(category = AssetCategory.VEHICLE, name = "", purchaseDate = LocalDate.of(2027, 1, 1)),
        )
        val errors = (result as SaveAssetResult.Invalid).errors
        assertEquals(AssetError.Required, errors[AssetField.NAME])
        assertEquals(AssetError.InFuture, errors[AssetField.PURCHASE_DATE])
        assertTrue(db.assetRows.isEmpty())
        assertTrue(db.outboxRows.isEmpty())
    }

    @Test fun updateKeepsOriginalCategory() = runBlocking {
        val id = (create(AssetDraft(category = AssetCategory.VEHICLE, name = "Mio", currentOdometerKm = 100)) as SaveAssetResult.Saved).id
        update(id, AssetDraft(category = AssetCategory.ELECTRONIC, name = "Mio i 125", currentOdometerKm = 200))
        val saved = repo.get(id)!!
        assertEquals(AssetCategory.VEHICLE, saved.category)
        assertEquals("Mio i 125", saved.name)
        assertEquals("baselines survive because it's still a vehicle", 200, saved.currentOdometerKm)
    }

    @Test fun updateOfMissingAssetReportsNotFound() = runBlocking {
        assertEquals(SaveAssetResult.NotFound, update("ghost", AssetDraft(category = AssetCategory.VEHICLE, name = "x")))
    }

    @Test fun archiveAndDeleteDelegate() = runBlocking {
        val id = (create(AssetDraft(category = AssetCategory.APPLIANCE, name = "Ref")) as SaveAssetResult.Saved).id
        assertTrue(SetAssetArchivedUseCase(repo)(id, true))
        assertTrue(repo.get(id)!!.isArchived)
        assertTrue(DeleteAssetUseCase(repo)(id))
        assertNull(repo.get(id))
        assertFalse(DeleteAssetUseCase(repo)(id))
    }
}
