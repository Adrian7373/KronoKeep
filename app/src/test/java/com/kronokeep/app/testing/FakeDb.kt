package com.kronokeep.app.testing

import com.kronokeep.app.data.document.LocalFileStore
import com.kronokeep.app.data.local.TransactionRunner
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.DocumentDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.dao.ServiceLogDao
import com.kronokeep.app.data.local.dao.ServiceLogWithTask
import com.kronokeep.app.data.local.entity.AssetEntity
import com.kronokeep.app.data.local.entity.DocumentEntity
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.entity.OdometerReadingEntity
import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.ServiceLogEntity
import com.kronokeep.app.data.repository.ScheduleRecomputer
import com.kronokeep.app.data.remote.DocumentStorage
import com.kronokeep.app.data.repository.UploadScheduler
import com.kronokeep.app.data.repository.UserIdProvider
import com.kronokeep.app.domain.common.IdGenerator
import com.kronokeep.app.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val NOW: Instant = Instant.parse("2026-11-14T02:00:00Z")
fun fixedClock(at: Instant = NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

/** A clock tests can move forward. */
class MutableClock(var now: Instant = NOW) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?): Clock = this
    override fun instant(): Instant = now
}

class SequentialIds(private val prefix: String = "id") : IdGenerator {
    private var n = 0
    override fun newId(): String = "$prefix-${++n}"
}

/**
 * In-memory stand-in for the Room DAOs plus a [TransactionRunner] that really rolls back:
 * a block that throws leaves every table exactly as it was. Writes made outside a transaction
 * are recorded so tests can assert "Room + outbox in one transaction".
 */
class FakeDb : TransactionRunner {
    private val assets = MutableStateFlow<Map<String, AssetEntity>>(emptyMap())
    private val readings = MutableStateFlow<Map<String, OdometerReadingEntity>>(emptyMap())
    private val documents = MutableStateFlow<Map<String, DocumentEntity>>(emptyMap())
    private val schedules = MutableStateFlow<Map<String, MaintenanceScheduleEntity>>(emptyMap())
    private val logs = MutableStateFlow<Map<String, ServiceLogEntity>>(emptyMap())
    private val outbox = MutableStateFlow<List<OutboxEntity>>(emptyList())
    private var seq = 0L
    private var depth = 0

    val writesOutsideTransaction = mutableListOf<String>()
    var transactionCount = 0
        private set

    /** Makes the next outbox write throw, to prove the row write is rolled back with it. */
    var failNextOutboxWrite = false

    val assetRows: Map<String, AssetEntity> get() = assets.value
    val readingRows: List<OdometerReadingEntity> get() = readings.value.values.toList()
    val documentRows: Map<String, DocumentEntity> get() = documents.value
    val scheduleRows: Map<String, MaintenanceScheduleEntity> get() = schedules.value
    val logRows: Map<String, ServiceLogEntity> get() = logs.value
    val outboxRows: List<OutboxEntity> get() = outbox.value

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        if (depth > 0) return block()
        val snapshot = listOf(assets.value, readings.value, documents.value, outbox.value, schedules.value, logs.value)
        depth++
        transactionCount++
        try {
            return block()
        } catch (e: Throwable) {
            @Suppress("UNCHECKED_CAST")
            run {
                assets.value = snapshot[0] as Map<String, AssetEntity>
                readings.value = snapshot[1] as Map<String, OdometerReadingEntity>
                documents.value = snapshot[2] as Map<String, DocumentEntity>
                outbox.value = snapshot[3] as List<OutboxEntity>
                schedules.value = snapshot[4] as Map<String, MaintenanceScheduleEntity>
                logs.value = snapshot[5] as Map<String, ServiceLogEntity>
            }
            throw e
        } finally {
            depth--
        }
    }

    private fun write(what: String) {
        if (depth == 0) writesOutsideTransaction += what
    }

    fun putAsset(entity: AssetEntity) {
        assets.value = assets.value + (entity.id to entity)
    }

    fun putDocument(entity: DocumentEntity) {
        documents.value = documents.value + (entity.id to entity)
    }

    fun putReading(entity: OdometerReadingEntity) {
        readings.value = readings.value + (entity.id to entity)
    }

    fun putLog(entity: ServiceLogEntity) {
        logs.value = logs.value + (entity.id to entity)
    }

    val recomputer: ScheduleRecomputer by lazy { ScheduleRecomputer(assetDao, scheduleDao, odometerDao, outboxDao) }

    val scheduleDao = object : MaintenanceScheduleDao {
        private fun live() = schedules.value.values.filter { it.deletedAt == null }

        override fun observeForAsset(assetId: String): Flow<List<MaintenanceScheduleEntity>> = schedules.map { m ->
            m.values.filter { it.assetId == assetId && it.deletedAt == null }.sortedBy { it.createdAt }
        }

        override fun observeAll(): Flow<List<MaintenanceScheduleEntity>> = schedules.map { m -> m.values.filter { it.deletedAt == null } }

        override suspend fun forAsset(assetId: String): List<MaintenanceScheduleEntity> = live().filter { it.assetId == assetId }

        override suspend fun getById(id: String): MaintenanceScheduleEntity? = schedules.value[id]

        override suspend fun unownedIds(): List<String> = schedules.value.values.filter { it.userId == null }.map { it.id }

        override suspend fun assetIdsWithSchedules(): List<String> = live().map { it.assetId }.distinct()

        override suspend fun upsert(schedule: MaintenanceScheduleEntity) {
            write("maintenance_schedules")
            schedules.value = schedules.value + (schedule.id to schedule)
        }
    }

    val serviceLogDao = object : ServiceLogDao {
        override fun observeForAsset(assetId: String): Flow<List<ServiceLogWithTask>> = logs.map { m ->
            m.values.filter { it.assetId == assetId && it.deletedAt == null }
                .sortedWith(compareByDescending<ServiceLogEntity> { it.performedAt }.thenBy { it.createdAt })
                .map { ServiceLogWithTask(it, it.scheduleId?.let { id -> schedules.value[id]?.task }) }
        }

        override suspend fun getById(id: String): ServiceLogEntity? = logs.value[id]

        override suspend fun unownedIds(): List<String> = logs.value.values.filter { it.userId == null }.map { it.id }

        override suspend fun latestForSchedule(scheduleId: String): ServiceLogEntity? =
            logs.value.values.filter { it.scheduleId == scheduleId && it.deletedAt == null }.maxByOrNull { it.performedAt }

        override suspend fun upsert(log: ServiceLogEntity) {
            write("service_logs")
            logs.value = logs.value + (log.id to log)
        }
    }

    val assetDao = object : AssetDao {
        override suspend fun hasRowsOwnedByOtherThan(userId: String): Boolean =
            assets.value.values.any { it.userId != null && it.userId != userId }

        override fun observeActive(): Flow<List<AssetEntity>> = assets.map { m ->
            m.values.filter { it.deletedAt == null && it.archivedAt == null }.sortedByDescending { it.updatedAt }
        }

        override fun observeArchived(): Flow<List<AssetEntity>> = assets.map { m ->
            m.values.filter { it.deletedAt == null && it.archivedAt != null }.sortedByDescending { it.archivedAt }
        }

        override fun observeById(id: String): Flow<AssetEntity?> = assets.map { m -> m[id]?.takeIf { it.deletedAt == null } }

        override suspend fun getById(id: String): AssetEntity? = assets.value[id]

        override suspend fun unownedIds(): List<String> = assets.value.values.filter { it.userId == null }.map { it.id }

        override suspend fun upsert(asset: AssetEntity) {
            write("assets")
            assets.value = assets.value + (asset.id to asset)
        }
    }

    val odometerDao = object : OdometerReadingDao {
        override suspend fun forAsset(assetId: String): List<OdometerReadingEntity> =
            readings.value.values.filter { it.assetId == assetId && it.deletedAt == null }.sortedBy { it.recordedAt }

        override fun observeForAsset(assetId: String): Flow<List<OdometerReadingEntity>> = readings.map { m ->
            m.values.filter { it.assetId == assetId && it.deletedAt == null }.sortedBy { it.recordedAt }
        }

        override fun observeAll(): Flow<List<OdometerReadingEntity>> = readings.map { m ->
            m.values.filter { it.deletedAt == null }.sortedBy { it.recordedAt }
        }

        override suspend fun getById(id: String): OdometerReadingEntity? = readings.value[id]

        override suspend fun unownedIds(): List<String> = readings.value.values.filter { it.userId == null }.map { it.id }

        override suspend fun upsert(reading: OdometerReadingEntity) {
            write("odometer_readings")
            readings.value = readings.value + (reading.id to reading)
        }
    }

    val documentDao = object : DocumentDao {
        override fun observeForAsset(assetId: String): Flow<List<DocumentEntity>> = documents.map { m ->
            m.values.filter { it.assetId == assetId && it.deletedAt == null }.sortedByDescending { it.createdAt }
        }

        override suspend fun getById(id: String): DocumentEntity? = documents.value[id]

        override suspend fun unownedIds(): List<String> = documents.value.values.filter { it.userId == null }.map { it.id }

        override suspend fun pendingUploads(userId: String): List<DocumentEntity> = documents.value.values.filter {
            it.userId == userId && it.storagePath == null && it.localPath != null &&
                it.deletedAt == null && it.syncState != SyncState.FAILED
        }.sortedBy { it.createdAt }

        override suspend fun upsert(document: DocumentEntity) {
            write("documents")
            documents.value = documents.value + (document.id to document)
        }
    }

    val outboxDao = object : OutboxDao {
        override suspend fun enqueue(entry: OutboxEntity) {
            write("outbox")
            if (failNextOutboxWrite) {
                failNextOutboxWrite = false
                throw IllegalStateException("disk full")
            }
            outbox.value = outbox.value + entry.copy(seq = if (entry.seq == 0L) ++seq else entry.seq)
        }

        override suspend fun pending(maxAttempts: Int): List<OutboxEntity> =
            outbox.value.filter { it.attempts < maxAttempts }.sortedBy { it.seq }

        override suspend fun entryFor(tableName: String, rowId: String): OutboxEntity? =
            outbox.value.firstOrNull { it.tableName == tableName && it.rowId == rowId }

        override suspend fun markAttempt(seq: Long, error: String?) {
            write("outbox")
            outbox.value = outbox.value.map { if (it.seq == seq) it.copy(attempts = it.attempts + 1, lastError = error) else it }
        }

        override suspend fun remove(seq: Long) {
            write("outbox")
            outbox.value = outbox.value.filterNot { it.seq == seq }
        }

        override suspend fun removeFor(tableName: String, rowId: String) {
            write("outbox")
            outbox.value = outbox.value.filterNot { it.tableName == tableName && it.rowId == rowId }
        }

        override fun observeLatestSeq(): Flow<Long?> = outbox.map { rows -> rows.maxOfOrNull { it.seq } }

        override suspend fun latestSeq(): Long? = outbox.value.maxOfOrNull { it.seq }

        override fun observePendingCount(maxAttempts: Int): Flow<Int> = outbox.map { rows -> rows.count { it.attempts < maxAttempts } }

        override fun observeRejectedCount(maxAttempts: Int): Flow<Int> =
            outbox.map { rows -> rows.count { it.attempts >= maxAttempts } }

        override suspend fun countForAsset(assetId: String): Int = outbox.value.count { e ->
            when (e.tableName) {
                "assets" -> e.rowId == assetId
                "maintenance_schedules" -> schedules.value[e.rowId]?.assetId == assetId
                "service_logs" -> logs.value[e.rowId]?.assetId == assetId
                "odometer_readings" -> readings.value[e.rowId]?.assetId == assetId
                else -> false
            }
        }
    }
}

class FakeUserIds(var userId: String? = "user-1") : UserIdProvider {
    override fun currentUserId(): String? = userId
}

class CountingScheduler : UploadScheduler {
    var count = 0
    override fun schedule() {
        count++
    }
}

class FakeFileStore(initial: Map<String, ByteArray> = emptyMap()) : LocalFileStore {
    val files = initial.toMutableMap()
    override fun exists(path: String): Boolean = path in files
    override fun read(path: String): ByteArray? = files[path]
    override fun delete(path: String) {
        files.remove(path)
    }
}

class FakeStorage : DocumentStorage {
    val objects = mutableMapOf<String, ByteArray>()
    val signedUrlRequests = mutableListOf<Pair<String, Long>>()
    val deleted = mutableListOf<String>()
    var failUploads = false

    override suspend fun delete(path: String) {
        deleted += path
        objects.remove(path)
    }

    override suspend fun upload(path: String, bytes: ByteArray, mimeType: String) {
        if (failUploads) throw java.io.IOException("offline")
        objects[path] = bytes
    }

    override suspend fun signedDownloadUrl(path: String, ttlSeconds: Long): String {
        signedUrlRequests += path to ttlSeconds
        return "https://example.supabase.co/storage/v1/object/sign/documents/$path?token=t"
    }
}
