package com.kronokeep.app.data.sync

import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import com.kronokeep.app.data.remote.parsePgInstant
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.DocumentKind
import com.kronokeep.app.domain.model.SyncState
import com.kronokeep.app.testing.FakeDb
import com.kronokeep.app.testing.NOW
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class SyncTableTest {
    private val db = FakeDb()
    private val assets = AssetSyncTable(db.assetDao)
    private val logs = ServiceLogSyncTable(db.serviceLogDao, db.assetDao, db.scheduleDao)
    private val documents = DocumentSyncTable(db.documentDao, db.assetDao, db.serviceLogDao)

    private val asset = AssetEntity(
        id = "3f1c2d4e-0000-4000-8000-000000000001", userId = "user-1", category = AssetCategory.VEHICLE,
        name = "Yamaha NMAX", brand = "Yamaha", model = null, serialNo = "MH3SG", purchaseDate = LocalDate.of(2025, 3, 1),
        priceCentavos = 15_990_000, warrantyExpiresOn = null, currentOdometerKm = 8_200, weeklyKmBaseline = 120,
        notes = null, archivedAt = null, createdAt = NOW, updatedAt = NOW, deletedAt = null, syncState = SyncState.PENDING,
    )

    @Test fun parsesPostgresTimestampsWithOffsetAndMicros() {
        assertEquals(Instant.parse("2026-11-14T02:00:00.123456Z"), parsePgInstant("2026-11-14T02:00:00.123456+00:00"))
        assertEquals(Instant.parse("2026-11-14T02:00:00Z"), parsePgInstant("2026-11-14T10:00:00+08:00"))
    }

    @Test fun pushJsonUsesPostgresNamesAndLabelsWithExplicitNullsAndNoServerOwnedColumns() = runBlocking {
        db.putAsset(asset)

        val json = assets.loadForPush(asset.id)!!.json

        assertEquals("vehicle", json.getValue("category").jsonPrimitive.content)
        assertEquals("2025-03-01", json.getValue("purchase_date").jsonPrimitive.content)
        assertEquals(15_990_000L, json.getValue("price_centavos").jsonPrimitive.content.toLong())
        assertEquals("2026-11-14T02:00:00Z", json.getValue("created_at").jsonPrimitive.content)
        // A cleared field must be sent as null, or an upsert would leave the old server value in place.
        assertEquals(JsonNull, json["model"])
        assertFalse("updated_at" in json)
    }

    @Test fun pulledRowIsSyncedAndUpdatedAtIsStoredAtMillis() = runBlocking {
        val server = Json.parseToJsonElement(
            """
            {"id":"a-1","user_id":"user-1","category":"electronic","name":"MacBook Air","brand":"Apple","model":"M3",
             "serial_no":null,"purchase_date":"2026-01-05","price_centavos":7499000,"warranty_expires_on":"2027-01-05",
             "current_odometer_km":null,"weekly_km_baseline":null,"notes":null,"archived_at":null,
             "created_at":"2026-01-05T08:00:00+00:00","updated_at":"2026-11-14T02:00:00.123456+00:00",
             "deleted_at":null,"some_future_column":true}
            """,
        ).jsonObject

        val result = assets.applyRemote(server)

        assertEquals(ApplyResult.Applied("a-1"), result)
        val row = db.assetRows.getValue("a-1")
        assertEquals(AssetCategory.ELECTRONIC, row.category)
        assertEquals(LocalDate.of(2027, 1, 5), row.warrantyExpiresOn)
        assertEquals(Instant.parse("2026-11-14T02:00:00.123Z"), row.updatedAt)
        assertEquals(SyncState.SYNCED, row.syncState)
    }

    @Test fun serviceLogPushNeverCarriesLockedAtAndALockedRemoteAlwaysWins() = runBlocking {
        db.putAsset(asset)
        val log = ServiceLogEntity(
            id = "l-1", userId = "user-1", assetId = asset.id, scheduleId = null, performedAt = NOW, odometerKm = 8_300,
            costCentavos = 50_000, shop = null, notes = null, lockedAt = NOW, createdAt = NOW, updatedAt = NOW,
            deletedAt = null, syncState = SyncState.PENDING,
        )
        db.putLog(log)

        val json = logs.loadForPush("l-1")!!.json

        assertFalse("locked_at" in json)
        assertTrue(logs.remoteAlwaysWins(Json.parseToJsonElement("""{"locked_at":"2026-11-14T02:00:00+00:00"}""").jsonObject))
        assertFalse(logs.remoteAlwaysWins(Json.parseToJsonElement("""{"locked_at":null}""").jsonObject))
    }

    @Test fun documentWaitsForItsUploadAndPullKeepsTheLocalCopyPath() = runBlocking {
        db.putAsset(asset)
        val doc = DocumentEntity(
            id = "d-1", userId = "user-1", assetId = asset.id, serviceLogId = null, kind = DocumentKind.WARRANTY,
            storagePath = null, localPath = "/files/documents/d-1.pdf", mimeType = "application/pdf", sizeBytes = 10,
            sha256 = "c".repeat(64), createdAt = NOW, updatedAt = NOW, deletedAt = null, syncState = SyncState.PENDING,
        )
        db.putDocument(doc)
        assertEquals(PushReadiness.WAIT, documents.loadForPush("d-1")!!.readiness)
        db.putDocument(doc.copy(syncState = SyncState.FAILED))
        assertEquals("lost before upload", PushReadiness.LOCAL_ONLY, documents.loadForPush("d-1")!!.readiness)

        db.putDocument(doc)
        val server = Json.parseToJsonElement(
            """
            {"id":"d-1","user_id":"user-1","asset_id":"${asset.id}","service_log_id":null,"kind":"warranty",
             "storage_path":"user-1/${asset.id}/d-1.pdf","mime_type":"application/pdf","size_bytes":10,
             "sha256":"${"c".repeat(64)}","created_at":"2026-11-14T02:00:00+00:00",
             "updated_at":"2026-11-14T03:00:00+00:00","deleted_at":null}
            """,
        ).jsonObject
        documents.applyRemote(server)

        val row = db.documentRows.getValue("d-1")
        assertEquals("user-1/${asset.id}/d-1.pdf", row.storagePath)
        assertEquals("/files/documents/d-1.pdf", row.localPath)
        assertEquals(DocumentKind.WARRANTY, row.kind)
    }

    @Test fun childWithoutLocalParentIsReportedNotWritten() = runBlocking {
        val server = Json.parseToJsonElement(
            """
            {"id":"r-1","user_id":"user-1","asset_id":"nope","km":100,"recorded_at":"2026-11-14T02:00:00+00:00",
             "created_at":"2026-11-14T02:00:00+00:00","updated_at":"2026-11-14T02:00:00+00:00","deleted_at":null}
            """,
        ).jsonObject

        val result = OdometerSyncTable(db.odometerDao, db.assetDao).applyRemote(server)

        assertEquals(ApplyResult.MissingParent, result)
        assertTrue(db.readingRows.isEmpty())
    }

    @Test fun lwwComparesAtMillisecondPrecision() {
        val local = Instant.parse("2026-11-14T02:00:00.123Z")
        assertFalse("same version echoed back", remoteIsNewer(Instant.parse("2026-11-14T02:00:00.123456Z"), local))
        assertTrue(remoteIsNewer(Instant.parse("2026-11-14T02:00:00.124Z"), local))
    }
}
