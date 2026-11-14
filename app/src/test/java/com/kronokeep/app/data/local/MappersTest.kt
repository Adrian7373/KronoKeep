package com.kronokeep.app.data.local

import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.AssetDraft
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.Peso
import com.kronokeep.app.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class MappersTest {
    private val now = Instant.parse("2026-11-14T02:00:00Z")
    private val draft = AssetDraft(
        category = AssetCategory.VEHICLE,
        name = "Toyota Vios",
        brand = "Toyota",
        model = "Vios XLE",
        serialNo = "NBC 1234",
        purchaseDate = LocalDate.of(2024, 6, 1),
        price = Peso(85_000_000),
        warrantyExpiresOn = LocalDate.of(2027, 6, 1),
        currentOdometerKm = 31_000,
        weeklyKmBaseline = 250,
        notes = "Casa PMS",
    )

    @Test fun draftToNewEntityAndBackRoundTrips() {
        val entity = draft.toNewEntity(id = "a-1", userId = "u-1", now = now)
        assertEquals("u-1", entity.userId)
        assertEquals(85_000_000L, entity.priceCentavos)
        assertEquals(SyncState.PENDING, entity.syncState)
        assertNull(entity.archivedAt)
        assertNull(entity.deletedAt)

        val asset = entity.toDomain()
        assertEquals("a-1", asset.id)
        assertEquals(draft, asset.toDraft())
        assertFalse(asset.isArchived)
    }

    @Test fun withDraftKeepsIdentityOwnershipAndLifecycle() {
        val original = draft.toNewEntity("a-1", "u-1", now).copy(archivedAt = now, syncState = SyncState.SYNCED)
        val later = now.plusSeconds(30)
        val updated = original.withDraft(draft.copy(name = "Vios", price = null), later)
        assertEquals("a-1", updated.id)
        assertEquals("u-1", updated.userId)
        assertEquals(now, updated.createdAt)
        assertEquals(now, updated.archivedAt)
        assertEquals(later, updated.updatedAt)
        assertEquals(SyncState.PENDING, updated.syncState)
        assertEquals("Vios", updated.name)
        assertNull(updated.priceCentavos)
        assertTrue(updated.toDomain().isArchived)
    }

    @Test fun documentEntityToDomain() {
        val entity = DocumentEntity(
            id = "d-1", userId = "u-1", assetId = "a-1", serviceLogId = null, kind = DocumentKind.MANUAL,
            storagePath = "u-1/a-1/d-1.pdf", localPath = null, mimeType = "application/pdf", sizeBytes = 42,
            sha256 = "f".repeat(64), createdAt = now, updatedAt = now, deletedAt = null, syncState = SyncState.SYNCED,
        )
        val doc = entity.toDomain()
        assertEquals(DocumentKind.MANUAL, doc.kind)
        assertTrue(doc.isUploaded)
        assertFalse(doc.isImage)
        assertEquals(42L, doc.sizeBytes)
        assertEquals(SyncState.SYNCED, doc.syncState)
    }
}
