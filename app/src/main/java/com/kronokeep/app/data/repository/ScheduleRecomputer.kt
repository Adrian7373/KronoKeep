package com.kronokeep.app.data.repository

import com.kronokeep.app.data.local.SyncTables
import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.local.dao.OdometerReadingDao
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.local.entity.MaintenanceScheduleEntity
import com.kronokeep.app.data.local.record
import com.kronokeep.app.data.local.withPrediction
import com.kronokeep.app.domain.engine.MaintenanceEngine
import com.kronokeep.app.domain.engine.OdometerPoint
import com.kronokeep.app.domain.engine.ScheduleTerms
import com.kronokeep.app.domain.engine.UsageInput
import com.kronokeep.app.domain.model.SyncState
import java.time.Instant
import javax.inject.Inject
import kotlin.math.abs

/**
 * Runs the engine for one asset and stores changed due fields (with an outbox entry, since they sync).
 * Callers invoke it inside their own write transaction, so a reading, a log or a schedule edit and the
 * new due date land together.
 */
class ScheduleRecomputer @Inject constructor(
    private val assetDao: AssetDao,
    private val scheduleDao: MaintenanceScheduleDao,
    private val odometerDao: OdometerReadingDao,
    private val outboxDao: OutboxDao,
) {
    /** Returns the number of schedules whose stored prediction changed. */
    suspend fun recompute(assetId: String, now: Instant): Int {
        val asset = assetDao.getById(assetId)?.takeIf { it.deletedAt == null } ?: return 0
        val schedules = scheduleDao.forAsset(assetId)
        if (schedules.isEmpty()) return 0
        val usage = UsageInput(
            readings = odometerDao.forAsset(assetId).map { OdometerPoint(it.km, it.recordedAt) },
            currentOdometerKm = asset.currentOdometerKm,
            weeklyKmBaseline = asset.weeklyKmBaseline,
        )
        val rate = MaintenanceEngine.usageRate(usage, now)
        var changed = 0
        for (s in schedules) {
            val prediction = MaintenanceEngine.evaluate(
                ScheduleTerms(s.intervalDays, s.intervalKm, s.lastDoneAt, s.lastDoneKm),
                usage,
                rate,
                now,
            )
            val updated = s.withPrediction(prediction)
            if (!meaningfullyDifferent(s, updated)) continue
            scheduleDao.upsert(updated.copy(updatedAt = now, syncState = SyncState.PENDING))
            outboxDao.record(SyncTables.SCHEDULES, s.id, now)
            changed++
        }
        return changed
    }

    /**
     * Confidence decays a little every day as readings age; pushing every tiny change would churn sync.
     * Status and due point changes always count; confidence only once it moves by [CONFIDENCE_STEP].
     */
    private fun meaningfullyDifferent(old: MaintenanceScheduleEntity, new: MaintenanceScheduleEntity): Boolean {
        if (old.status != new.status || old.nextDueAt != new.nextDueAt || old.nextDueKm != new.nextDueKm) return true
        val before = old.confidence ?: return true
        return abs(before - (new.confidence ?: 0.0)) >= CONFIDENCE_STEP
    }

    companion object {
        const val CONFIDENCE_STEP = 0.05
    }
}
