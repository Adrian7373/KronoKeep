package com.kronokeep.app.domain.maintenance

import com.kronokeep.app.domain.engine.MaintenanceEngine
import com.kronokeep.app.domain.engine.OdometerPoint
import com.kronokeep.app.domain.engine.UsageInput
import com.kronokeep.app.domain.engine.UsageRate
import com.kronokeep.app.domain.model.Asset
import com.kronokeep.app.domain.model.AssetCategory
import com.kronokeep.app.domain.model.MaintenanceSchedule
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.model.OdometerReading
import com.kronokeep.app.domain.model.ScheduleDraft
import com.kronokeep.app.domain.model.ScheduleWithPrediction
import com.kronokeep.app.domain.model.ServiceVisit
import com.kronokeep.app.domain.repository.AssetRepository
import com.kronokeep.app.domain.repository.DeleteLogResult
import com.kronokeep.app.domain.repository.MaintenanceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

sealed interface SaveSchedulesResult {
    data class Saved(val ids: List<String>) : SaveSchedulesResult
    /** Errors per draft, keyed by the draft's index. */
    data class Invalid(val errors: Map<Int, Map<ScheduleField, MaintenanceError>>) : SaveSchedulesResult
    data object NotFound : SaveSchedulesResult
    /** Electronics track the warranty only. */
    data object NotSupported : SaveSchedulesResult
}

sealed interface LogServiceResult {
    data class Saved(val ids: List<String>) : LogServiceResult
    data class Invalid(val errors: Map<VisitField, MaintenanceError>) : LogServiceResult
    data object NotFound : LogServiceResult
}

sealed interface OdometerResult {
    data object Saved : OdometerResult
    data class Invalid(val error: MaintenanceError) : OdometerResult
    data object NotFound : OdometerResult
}

/** Latest known odometer value: the asset field or the newest reading, whichever is higher. */
internal fun currentKm(asset: Asset, readings: List<OdometerReading>): Int? =
    listOfNotNull(asset.currentOdometerKm, readings.maxByOrNull { it.recordedAt }?.km).maxOrNull()

internal fun usageInput(asset: Asset, readings: List<OdometerReading>) = UsageInput(
    readings = readings.map { OdometerPoint(it.km, it.recordedAt) },
    currentOdometerKm = asset.currentOdometerKm,
    weeklyKmBaseline = asset.weeklyKmBaseline,
)

/** OVERDUE, then DUE_SOON, then OK; soonest first within each, undated last. */
internal val URGENCY: Comparator<ScheduleWithPrediction> = compareBy<ScheduleWithPrediction>(
    {
        when (it.prediction.status) {
            MaintenanceStatus.OVERDUE -> 0
            MaintenanceStatus.DUE_SOON -> 1
            MaintenanceStatus.OK -> 2
        }
    },
    { it.prediction.nextDueAt ?: Instant.MAX },
    { it.schedule.task },
)

class AddSchedulesUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(assetId: String, drafts: List<ScheduleDraft>): SaveSchedulesResult {
        val asset = assets.get(assetId) ?: return SaveSchedulesResult.NotFound
        if (asset.category == AssetCategory.ELECTRONIC) return SaveSchedulesResult.NotSupported
        val km = currentKm(asset, maintenance.observeReadings(assetId).first())
        val clean = drafts.map { it.normalized(asset.category) }
        val errors = clean.mapIndexedNotNull { i, d ->
            MaintenanceValidator.validateSchedule(d, asset.category, km, clock.instant()).takeIf { it.isNotEmpty() }?.let { i to it }
        }.toMap()
        if (errors.isNotEmpty()) return SaveSchedulesResult.Invalid(errors)
        return SaveSchedulesResult.Saved(maintenance.addSchedules(assetId, clean))
    }
}

class UpdateScheduleUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(scheduleId: String, draft: ScheduleDraft): SaveSchedulesResult {
        val existing = maintenance.getSchedule(scheduleId) ?: return SaveSchedulesResult.NotFound
        val asset = assets.get(existing.assetId) ?: return SaveSchedulesResult.NotFound
        val km = currentKm(asset, maintenance.observeReadings(asset.id).first())
        val clean = draft.normalized(asset.category)
        val errors = MaintenanceValidator.validateSchedule(clean, asset.category, km, clock.instant())
        if (errors.isNotEmpty()) return SaveSchedulesResult.Invalid(mapOf(0 to errors))
        return if (maintenance.updateSchedule(scheduleId, clean)) {
            SaveSchedulesResult.Saved(listOf(scheduleId))
        } else {
            SaveSchedulesResult.NotFound
        }
    }
}

class DeleteScheduleUseCase @Inject constructor(private val maintenance: MaintenanceRepository) {
    suspend operator fun invoke(scheduleId: String): Boolean = maintenance.deleteSchedule(scheduleId)
}

class LogServiceUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(assetId: String, visit: ServiceVisit): LogServiceResult {
        val asset = assets.get(assetId) ?: return LogServiceResult.NotFound
        val schedules = maintenance.observeSchedules(assetId).first().associateBy { it.id }
        val clean = visit.normalized(asset.category).let { v -> v.copy(scheduleIds = v.scheduleIds.filter { it in schedules }) }
        val kmIds = schedules.values.filter { it.intervalKm != null }.map { it.id }.toSet()
        val errors = MaintenanceValidator.validateVisit(clean, asset.category, kmIds, clock.instant())
        if (errors.isNotEmpty()) return LogServiceResult.Invalid(errors)
        return LogServiceResult.Saved(maintenance.logService(assetId, clean))
    }
}

/**
 * "Mark done" from a reminder: logs the task as serviced now, at the asset's current odometer when the schedule
 * counts km. Goes through [LogServiceUseCase], so the same validation applies; Invalid means the user has to fill
 * in the details on the Log service screen (e.g. no odometer known yet).
 */
class MarkScheduleDoneUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val logService: LogServiceUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(scheduleId: String): LogServiceResult {
        val schedule = maintenance.getSchedule(scheduleId) ?: return LogServiceResult.NotFound
        val asset = assets.get(schedule.assetId) ?: return LogServiceResult.NotFound
        val km = if (schedule.intervalKm != null) currentKm(asset, maintenance.observeReadings(asset.id).first()) else null
        val visit = ServiceVisit(
            performedAt = clock.instant(),
            odometerKm = km,
            cost = null,
            shop = null,
            notes = null,
            scheduleIds = listOf(scheduleId),
        )
        return logService(asset.id, visit)
    }
}

class DeleteServiceLogUseCase @Inject constructor(private val maintenance: MaintenanceRepository) {
    suspend operator fun invoke(logId: String): DeleteLogResult = maintenance.deleteServiceLog(logId)
}

class UpdateOdometerUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
) {
    suspend operator fun invoke(assetId: String, km: Int): OdometerResult {
        val asset = assets.get(assetId)?.takeIf { it.category == AssetCategory.VEHICLE } ?: return OdometerResult.NotFound
        val last = currentKm(asset, maintenance.observeReadings(assetId).first())
        MaintenanceValidator.validateOdometer(km, last)?.let { return OdometerResult.Invalid(it) }
        return if (maintenance.recordOdometer(assetId, km)) OdometerResult.Saved else OdometerResult.NotFound
    }
}

/** Everything the Schedule tab needs, with live engine predictions. */
data class AssetMaintenance(
    val schedules: List<ScheduleWithPrediction>,
    val rate: UsageRate,
    val lastReading: OdometerReading?,
    val currentKm: Int?,
)

class ObserveAssetMaintenanceUseCase @Inject constructor(
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    operator fun invoke(asset: Flow<Asset?>, assetId: String): Flow<AssetMaintenance?> = combine(
        asset,
        maintenance.observeSchedules(assetId),
        maintenance.observeReadings(assetId),
    ) { a, schedules, readings ->
        a ?: return@combine null
        val now = clock.instant()
        val usage = usageInput(a, readings)
        val rate = MaintenanceEngine.usageRate(usage, now)
        AssetMaintenance(
            schedules = schedules
                .map { ScheduleWithPrediction(it, MaintenanceEngine.evaluate(it.terms, usage, rate, now)) }
                .sortedWith(URGENCY),
            rate = rate,
            lastReading = readings.maxByOrNull { it.recordedAt },
            currentKm = currentKm(a, readings),
        )
    }
}

/** Live predictions for every schedule of every active asset. Shared by the dashboard and the reminder job. */
data class AssetSchedules(val asset: Asset, val schedules: List<ScheduleWithPrediction>, val lastReading: OdometerReading?)

class ObserveAllMaintenanceUseCase @Inject constructor(
    private val assets: AssetRepository,
    private val maintenance: MaintenanceRepository,
    private val clock: Clock,
) {
    operator fun invoke(): Flow<List<AssetSchedules>> = combine(
        assets.observeActive(),
        maintenance.observeAllSchedules(),
        maintenance.observeAllReadings(),
    ) { active, schedules, readings ->
        val now = clock.instant()
        val byAsset = schedules.groupBy(MaintenanceSchedule::assetId)
        val readingsByAsset = readings.groupBy(OdometerReading::assetId)
        active.map { asset ->
            val r = readingsByAsset[asset.id].orEmpty()
            val usage = usageInput(asset, r)
            val rate = MaintenanceEngine.usageRate(usage, now)
            AssetSchedules(
                asset = asset,
                schedules = byAsset[asset.id].orEmpty()
                    .map { ScheduleWithPrediction(it, MaintenanceEngine.evaluate(it.terms, usage, rate, now)) }
                    .sortedWith(URGENCY),
                lastReading = r.maxByOrNull { it.recordedAt },
            )
        }
    }
}
