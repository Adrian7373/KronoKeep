package com.kronokeep.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kronokeep.app.data.local.dao.OutboxDao
import com.kronokeep.app.data.repository.SyncScheduler
import com.kronokeep.app.data.repository.UploadScheduler
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.SyncRepository
import com.kronokeep.app.domain.repository.SyncStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

class SyncWorker(
    context: Context,
    params: WorkerParameters,
    private val auth: AuthRepository,
    private val engine: SyncEngine,
    private val outboxDao: OutboxDao,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A cold start restores the session asynchronously; give it a moment before deciding.
        val state = withTimeoutOrNull(AUTH_WAIT) { auth.state.first { it != AuthState.Loading } }
            ?: return Result.retry()
        // Signed out / offline build: nothing to do. SyncTrigger schedules again on sign-in.
        val user = state as? AuthState.SignedIn ?: return Result.success()

        // Unique work uses KEEP, so writes made during this run don't queue another; loop here instead.
        var passes = 0
        while (true) {
            val seqBefore = outboxDao.latestSeq()
            val outcome = engine.sync(user.userId)
            passes++
            if (outcome.shouldRetry) {
                return if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
            }
            if (passes >= MAX_PASSES || outboxDao.latestSeq() == seqBefore) return Result.success()
        }
    }

    companion object {
        const val UNIQUE_NAME = "sync"
        const val PERIODIC_NAME = "sync-periodic"
        private val AUTH_WAIT = 15.seconds
        private const val MAX_ATTEMPTS = 10
        private const val MAX_PASSES = 3
    }
}

@Singleton
class WorkManagerSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncScheduler {
    private val workManager get() = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun request() = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(online)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    override fun schedule() {
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.KEEP, request())
    }

    // Safe to replace even mid-run: every engine step is its own transaction and pushes are idempotent upserts.
    override fun syncNow() {
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request())
    }

    override fun schedulePeriodic() {
        val periodic = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(online).build()
        workManager.enqueueUniquePeriodicWork(SyncWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, periodic)
    }

    override val running: Flow<Boolean> = workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_NAME)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
        .distinctUntilChanged()
}

/**
 * Starts sync and uploads whenever a user signs in (including session restore on launch), and syncs a
 * couple of seconds after local writes settle while signed in.
 */
@Singleton
class SyncTrigger @Inject constructor(
    private val auth: AuthRepository,
    private val outboxDao: OutboxDao,
    private val sync: SyncScheduler,
    private val uploads: UploadScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            auth.state
                .filterIsInstance<AuthState.SignedIn>()
                .map { it.userId }
                .distinctUntilChanged()
                .collect {
                    uploads.schedule()
                    sync.schedule()
                    sync.schedulePeriodic()
                }
        }
        scope.launch {
            outboxDao.observeLatestSeq()
                .filterNotNull()
                .distinctUntilChanged()
                .debounce(WRITE_SETTLE)
                .collect { if (auth.state.value is AuthState.SignedIn) sync.schedule() }
        }
    }

    private companion object {
        val WRITE_SETTLE = 2.seconds
    }
}

class SyncRepositoryImpl @Inject constructor(
    outboxDao: OutboxDao,
    store: SyncStateStore,
    private val scheduler: SyncScheduler,
    private val auth: AuthRepository,
) : SyncRepository {

    override val status: Flow<SyncStatus> = combine(
        outboxDao.observePendingCount(SyncEngine.MAX_ATTEMPTS),
        outboxDao.observeRejectedCount(SyncEngine.MAX_ATTEMPTS),
        store.lastSyncedAt,
        scheduler.running,
    ) { pending, rejected, lastSyncedAt, running -> SyncStatus(pending, rejected, lastSyncedAt, running) }

    override fun syncNow() {
        if (auth.state.value is AuthState.SignedIn) scheduler.syncNow()
    }
}
