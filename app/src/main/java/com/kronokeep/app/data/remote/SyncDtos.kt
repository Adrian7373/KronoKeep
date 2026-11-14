package com.kronokeep.app.data.remote

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

/*
 * PostgREST row shapes for the synced tables, one-to-one with supabase/migrations. Enums travel as their
 * Postgres labels (`wire`). `updated_at` (and `locked_at` on service_logs) are server-owned: they're read
 * on pull but stripped before a push, so the server trigger stays the only writer.
 */

val SyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
}

/** Postgres renders timestamptz as `2026-11-14T02:00:00.123456+00:00`; API 26's Instant.parse rejects offsets. */
object PgInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("PgInstant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = parsePgInstant(decoder.decodeString())
}

fun parsePgInstant(value: String): Instant = OffsetDateTime.parse(value).toInstant()

object PgDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("PgDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

private typealias PgInstant = @Serializable(PgInstantSerializer::class) Instant
private typealias PgDate = @Serializable(PgDateSerializer::class) LocalDate

@Serializable
data class AssetDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val category: String,
    val name: String,
    val brand: String?,
    val model: String?,
    @SerialName("serial_no") val serialNo: String?,
    @SerialName("purchase_date") val purchaseDate: PgDate?,
    @SerialName("price_centavos") val priceCentavos: Long?,
    @SerialName("warranty_expires_on") val warrantyExpiresOn: PgDate?,
    @SerialName("current_odometer_km") val currentOdometerKm: Int?,
    @SerialName("weekly_km_baseline") val weeklyKmBaseline: Int?,
    val notes: String?,
    @SerialName("archived_at") val archivedAt: PgInstant?,
    @SerialName("created_at") val createdAt: PgInstant,
    @SerialName("updated_at") val updatedAt: PgInstant? = null,
    @SerialName("deleted_at") val deletedAt: PgInstant?,
)

@Serializable
data class ScheduleDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("asset_id") val assetId: String,
    val task: String,
    @SerialName("interval_days") val intervalDays: Int?,
    @SerialName("interval_km") val intervalKm: Int?,
    @SerialName("last_done_at") val lastDoneAt: PgInstant?,
    @SerialName("last_done_km") val lastDoneKm: Int?,
    @SerialName("next_due_at") val nextDueAt: PgInstant?,
    @SerialName("next_due_km") val nextDueKm: Int?,
    val confidence: Double?,
    val status: String,
    @SerialName("created_at") val createdAt: PgInstant,
    @SerialName("updated_at") val updatedAt: PgInstant? = null,
    @SerialName("deleted_at") val deletedAt: PgInstant?,
)

@Serializable
data class ServiceLogDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("schedule_id") val scheduleId: String?,
    @SerialName("performed_at") val performedAt: PgInstant,
    @SerialName("odometer_km") val odometerKm: Int?,
    @SerialName("cost_centavos") val costCentavos: Long?,
    val shop: String?,
    val notes: String?,
    @SerialName("locked_at") val lockedAt: PgInstant? = null,
    @SerialName("created_at") val createdAt: PgInstant,
    @SerialName("updated_at") val updatedAt: PgInstant? = null,
    @SerialName("deleted_at") val deletedAt: PgInstant?,
)

@Serializable
data class OdometerReadingDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("asset_id") val assetId: String,
    val km: Int,
    @SerialName("recorded_at") val recordedAt: PgInstant,
    @SerialName("created_at") val createdAt: PgInstant,
    @SerialName("updated_at") val updatedAt: PgInstant? = null,
    @SerialName("deleted_at") val deletedAt: PgInstant?,
)

@Serializable
data class DocumentDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("asset_id") val assetId: String,
    @SerialName("service_log_id") val serviceLogId: String?,
    val kind: String,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    @SerialName("created_at") val createdAt: PgInstant,
    @SerialName("updated_at") val updatedAt: PgInstant? = null,
    @SerialName("deleted_at") val deletedAt: PgInstant?,
)
