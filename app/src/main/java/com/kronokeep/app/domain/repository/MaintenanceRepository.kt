package com.kronokeep.app.domain.repository

import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ServiceLog
import com.kronokeep.app.domain.model.ServiceVisit
import kotlinx.coroutines.flow.Flow

sealed interface DeleteLogResult {
    data object Deleted : DeleteLogResult
    /** Part of a verified report. */
    data object Locked : DeleteLogResult
    data object NotFound : DeleteLogResult
}

/**
 * Schedules, service logs and odometer readings. Reads come from Room. Each write goes to Room plus the outbox in
 * one transaction, and in that same transaction re-runs the engine for the asset and persists any changed due
 * fields (which are synced too).
 */
interface MaintenanceRepository {
    fun observeSchedules(assetId: String): Flow<List<MaintenanceSchedule>>

    /** Every live schedule. Callers filter out archived assets. */
    fun observeAllSchedules(): Flow<List<MaintenanceSchedule>>

    fun observeReadings(assetId: String): Flow<List<OdometerReading>>

    fun observeAllReadings(): Flow<List<OdometerReading>>

    /** Newest first. */
    fun observeServiceLogs(assetId: String): Flow<List<ServiceLog>>

    suspend fun getSchedule(id: String): MaintenanceSchedule?

    /** Drafts must be normalized and valid. Returns the new ids in order. */
    suspend fun addSchedules(assetId: String, drafts: List<ScheduleDraft>): List<String>

    suspend fun updateSchedule(id: String, draft: ScheduleDraft): Boolean

    /** Soft delete. Logs keep their link so history still shows the task name. */
    suspend fun deleteSchedule(id: String): Boolean

    /**
     * Writes the visit's log rows, moves each ticked schedule's "last done" forward, and records the odometer
     * value as a reading when it fits the existing readings. Returns the new log ids.
     */
    suspend fun logService(assetId: String, visit: ServiceVisit): List<String>

    suspend fun deleteServiceLog(id: String): DeleteLogResult

    /** Adds a reading at `now` and updates the asset's odometer. [km] must already be validated. */
    suspend fun recordOdometer(assetId: String, km: Int): Boolean

    /** Re-runs the engine for every asset with schedules and persists changes. Returns how many rows changed. */
    suspend fun recomputeAll(): Int
}
