package com.kronokeep.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.kronokeep.app.data.reminders.ReminderRunner
import com.kronokeep.app.data.reminders.ReminderWorker
import com.kronokeep.app.data.repository.UploadScheduler
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

class DocumentUploadWorker(
    context: Context,
    params: WorkerParameters,
    private val auth: AuthRepository,
    private val uploader: DocumentUploader,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // A cold start restores the session asynchronously; give it a moment before deciding.
        val state = withTimeoutOrNull(AUTH_WAIT) { auth.state.first { it != AuthState.Loading } }
            ?: return Result.retry()
        // Signed out / offline build: nothing to do now. UploadTrigger re-runs the queue on sign-in.
        val user = state as? AuthState.SignedIn ?: return Result.success()
        val outcome = uploader.uploadPending(user.userId)
        return when {
            !outcome.shouldRetry -> Result.success()
            runAttemptCount >= MAX_ATTEMPTS -> Result.failure()
            else -> Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "document-upload"
        private val AUTH_WAIT = 15.seconds
        private const val MAX_ATTEMPTS = 8
    }
}

@Singleton
class WorkManagerUploadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : UploadScheduler {
    override fun schedule() {
        val request = OneTimeWorkRequestBuilder<DocumentUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: a run already in progress may have missed the newest file, so queue one more pass.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(DocumentUploadWorker.UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}

/** Builds workers with injected dependencies, without pulling in hilt-work. */
@Singleton
class KronoWorkerFactory @Inject constructor(
    private val auth: Provider<AuthRepository>,
    private val uploader: Provider<DocumentUploader>,
    private val reminders: Provider<ReminderRunner>,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        DocumentUploadWorker::class.java.name ->
            DocumentUploadWorker(appContext, workerParameters, auth.get(), uploader.get())
        ReminderWorker::class.java.name -> ReminderWorker(appContext, workerParameters, reminders.get())
        else -> null
    }
}

/** Drains the upload queue whenever a user signs in (including session restore on launch). */
@Singleton
class UploadTrigger @Inject constructor(
    private val auth: AuthRepository,
    private val scheduler: UploadScheduler,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch {
            auth.state
                .filterIsInstance<AuthState.SignedIn>()
                .map { it.userId }
                .distinctUntilChanged()
                .collect { scheduler.schedule() }
        }
    }
}
