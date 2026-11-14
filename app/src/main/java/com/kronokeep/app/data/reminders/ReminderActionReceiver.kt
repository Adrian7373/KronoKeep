package com.kronokeep.app.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kronokeep.app.di.ApplicationScope
import com.kronokeep.app.domain.maintenance.LogServiceResult
import com.kronokeep.app.domain.maintenance.MarkScheduleDoneUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Mark done" on a reminder. Android 12+ forbids starting an activity from a notification action's receiver,
 * so when logging needs the user (no odometer yet, say) the notification turns into a tap-to-log prompt.
 */
class ReminderActionReceiver : BroadcastReceiver() {

    /** Entry point instead of @AndroidEntryPoint: Kotlin can't call the abstract super.onReceive Hilt needs. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun reminderActions(): ReminderActions
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MARK_DONE) return
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val actions = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).reminderActions()
        val pending = goAsync()
        actions.markDone(
            notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, AndroidReminderNotifier.DIGEST_ID),
            scheduleId = scheduleId,
            assetId = intent.getStringExtra(EXTRA_ASSET_ID).orEmpty(),
            task = intent.getStringExtra(EXTRA_TASK).orEmpty(),
            assetName = intent.getStringExtra(EXTRA_ASSET_NAME).orEmpty(),
            onFinished = pending::finish,
        )
    }

    companion object {
        const val ACTION_MARK_DONE = "com.kronokeep.app.action.MARK_DONE"
        const val EXTRA_NOTIFICATION_ID = "com.kronokeep.app.extra.NOTIFICATION_ID"
        const val EXTRA_SCHEDULE_ID = "com.kronokeep.app.extra.SCHEDULE_ID"
        const val EXTRA_ASSET_ID = "com.kronokeep.app.extra.ASSET_ID"
        const val EXTRA_TASK = "com.kronokeep.app.extra.TASK"
        const val EXTRA_ASSET_NAME = "com.kronokeep.app.extra.ASSET_NAME"
    }
}

/** Runs notification actions on the app scope, so they outlive the receiver's short window. */
@Singleton
class ReminderActions @Inject constructor(
    private val markScheduleDone: MarkScheduleDoneUseCase,
    private val notifier: AndroidReminderNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun markDone(
        notificationId: Int,
        scheduleId: String,
        assetId: String,
        task: String,
        assetName: String,
        onFinished: () -> Unit,
    ) {
        scope.launch {
            try {
                val result = runCatching { markScheduleDone(scheduleId) }.getOrNull()
                when (result) {
                    is LogServiceResult.Saved -> notifier.cancel(notificationId)
                    // Deleted here since the reminder was posted: nothing left to do.
                    LogServiceResult.NotFound -> notifier.cancel(notificationId)
                    is LogServiceResult.Invalid, null ->
                        notifier.postMarkDoneFailed(notificationId, scheduleId, assetId, task, assetName)
                }
            } finally {
                onFinished()
            }
        }
    }
}
