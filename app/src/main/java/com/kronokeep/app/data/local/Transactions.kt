package com.kronokeep.app.data.local

import androidx.room.withTransaction
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.OutboxEntity
import com.kronokeep.app.data.local.entity.OutboxOp
import java.time.Instant
import javax.inject.Inject

/** Seam over Room transactions so repositories can be unit-tested with in-memory fakes. */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class RoomTransactionRunner @Inject constructor(private val db: KronoDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }
}

/** Postgres table names, as stored in `outbox.tableName`. */
object SyncTables {
    const val ASSETS = "assets"
    const val SCHEDULES = "maintenance_schedules"
    const val SERVICE_LOGS = "service_logs"
    const val ODOMETER_READINGS = "odometer_readings"
    const val DOCUMENTS = "documents"
}

/**
 * Queues a push for [rowId]. Coalesces: the pusher sends the row's current state, so one entry per
 * row is enough. Soft deletes are UPSERTs too, because the row itself carries `deletedAt`.
 * Must be called inside the same transaction as the row write.
 */
suspend fun OutboxDao.record(tableName: String, rowId: String, now: Instant, op: OutboxOp = OutboxOp.UPSERT) {
    removeFor(tableName, rowId)
    enqueue(OutboxEntity(tableName = tableName, rowId = rowId, op = op, enqueuedAt = now))
}
