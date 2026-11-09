package com.kronokeep.app.data.reminders

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.maintenance.ObserveAllMaintenanceUseCase
import com.kronokeep.app.domain.reminders.ReminderPlanner
import com.kronokeep.app.domain.reminders.ReminderTiming
import com.kronokeep.app.domain.repository.MaintenanceRepository
import com.kronokeep.app.domain.repository.ReminderLedgerStore
import com.kronokeep.app.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** The daily job's logic, without WorkManager, so it's unit-testable. */
class ReminderRunner @Inject constructor(
    private val maintenance: MaintenanceRepository,
    private val observeAll: ObserveAllMaintenanceUseCase,
    private val ledger: ReminderLedgerStore,
    private val settings: SettingsRepository,
    private val notifier: ReminderNotifier,
    private val clock: Clock,
) {
    suspend fun run() {
        // Status drifts with time even when nothing is edited; keep the stored (synced) due fields current.
        maintenance.recomputeAll()
        if (!settings.remindersEnabled.first() || !notifier.canPost()) return
        val plan = ReminderPlanner.plan(observeAll().first(), ledger.load(), clock.instant())
        if (plan.reminders.isNotEmpty()) notifier.post(plan.reminders)
        // Only after posting: if notifications are blocked today, they still fire once they're allowed.
        ledger.save(plan.ledger)
    }
}

/** Runs offline: everything it reads is in Room. */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
    private val runner: ReminderRunner,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runner.run()
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "maintenance-reminders"
    }
}

@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val maintenance: MaintenanceRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Idempotent: KEEP leaves an already-scheduled job (and its 08:00 phase) alone. */
    fun start() {
        val delay = ReminderTiming.delayUntilNext(ZonedDateTime.now())
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(ReminderWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        // Statuses may have changed since the app last ran.
        scope.launch { maintenance.recomputeAll() }
    }
}
