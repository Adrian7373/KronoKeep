package com.kronokeep.app.data.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kronokeep.app.MainActivity
import com.kronokeep.app.R
import com.kronokeep.app.data.push.DueNotifier
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.DuePush
import com.kronokeep.app.domain.reminders.Reminder
import com.kronokeep.app.navigation.DeepLink
import com.kronokeep.app.ui.components.Formats
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the daily reminder digest. Seam so the worker logic can be tested without Android. */
interface ReminderNotifier {
    fun canPost(): Boolean
    fun post(reminders: List<Reminder>)
}

/** The schedule a notification is about, so its actions know what to log. */
private data class ActionTarget(val scheduleId: String, val assetId: String, val task: String, val assetName: String)

@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier, DueNotifier {
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName("Maintenance reminders")
                .setDescription("Tasks that are due soon or overdue, and odometer update nudges")
                .build(),
        )
    }

    /** Covers both the Android 13+ runtime permission and the user switching notifications off. */
    override fun canPost(): Boolean = manager.areNotificationsEnabled()

    @SuppressLint("MissingPermission") // canPost() is checked first.
    override fun post(reminders: List<Reminder>) {
        if (reminders.isEmpty() || !canPost()) return
        val lines = reminders.map(::line)
        val title = if (reminders.size == 1) lines.single().first else "${reminders.size} things need attention"
        val text = if (reminders.size == 1) lines.single().second else lines.joinToString(" · ") { it.first }
        val builder = base(DIGEST_ID, title, text, DeepLink.forReminders(reminders))
        if (reminders.size > 1) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine("${it.first} — ${it.second}") } })
        }
        (reminders.singleOrNull() as? Reminder.Due)?.let {
            addActions(builder, DIGEST_ID, ActionTarget(it.scheduleId, it.assetId, it.task, it.assetName))
        }
        manager.notify(DIGEST_ID, builder.build())
    }

    /** One notification per schedule, so a later push for the same task replaces it instead of stacking. */
    @SuppressLint("MissingPermission")
    override fun postDue(push: DuePush) {
        if (!canPost()) return
        val id = scheduleNotificationId(push.scheduleId)
        val due = push.nextDueAt?.let { " · due ${Formats.date(it)}" }.orEmpty()
        val builder = base(id, "${push.task} ${verb(push.status)}", push.assetName + due, DeepLink.Asset(push.assetId))
        addActions(builder, id, ActionTarget(push.scheduleId, push.assetId, push.task, push.assetName))
        manager.notify(id, builder.build())
    }

    fun cancel(notificationId: Int) = manager.cancel(notificationId)

    /** "Mark done" couldn't log on its own (e.g. no odometer yet): swap in a prompt that opens Log service. */
    @SuppressLint("MissingPermission")
    fun postMarkDoneFailed(notificationId: Int, scheduleId: String, assetId: String, task: String, assetName: String) {
        if (!canPost()) return
        val builder = base(
            notificationId,
            "Couldn't mark $task done",
            "Tap to add the details for $assetName",
            DeepLink.LogService(assetId, scheduleId),
        )
        manager.notify(notificationId, builder.build())
    }

    private fun base(notificationId: Int, title: String, text: String, link: DeepLink) =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kronokeep)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(activityIntent(notificationId, ACTION_OPEN, link))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun addActions(builder: NotificationCompat.Builder, notificationId: Int, target: ActionTarget) {
        builder.addAction(
            0,
            "Log service",
            activityIntent(notificationId, ACTION_LOG, DeepLink.LogService(target.assetId, target.scheduleId)),
        )
        val markDone = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderActionReceiver.ACTION_MARK_DONE)
            .setData(uniqueUri(notificationId, ACTION_MARK_DONE))
            .putExtra(ReminderActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            .putExtra(ReminderActionReceiver.EXTRA_SCHEDULE_ID, target.scheduleId)
            .putExtra(ReminderActionReceiver.EXTRA_ASSET_ID, target.assetId)
            .putExtra(ReminderActionReceiver.EXTRA_TASK, target.task)
            .putExtra(ReminderActionReceiver.EXTRA_ASSET_NAME, target.assetName)
        builder.addAction(
            0,
            "Mark done",
            PendingIntent.getBroadcast(context, requestCode(notificationId, ACTION_MARK_DONE), markDone, PENDING_FLAGS),
        )
    }

    private fun activityIntent(notificationId: Int, action: String, link: DeepLink): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(uniqueUri(notificationId, action))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        link.toExtras().forEach { (k, v) -> intent.putExtra(k, v) }
        return PendingIntent.getActivity(context, requestCode(notificationId, action), intent, PENDING_FLAGS)
    }

    // Extras don't count when Android matches PendingIntents; a distinct data URI keeps each one separate.
    private fun uniqueUri(notificationId: Int, action: String): Uri =
        Uri.parse("kronokeep://notification/$notificationId/$action")

    private fun requestCode(notificationId: Int, action: String) = 31 * notificationId + action.hashCode()

    private fun verb(status: MaintenanceStatus) = if (status == MaintenanceStatus.OVERDUE) "is overdue" else "is due soon"

    private fun line(r: Reminder): Pair<String, String> = when (r) {
        is Reminder.Due -> "${r.task} ${verb(r.status)}" to r.assetName
        is Reminder.UpdateOdometer -> "Update the odometer" to "${r.assetName}: last reading ${r.daysSince} days ago"
    }

    companion object {
        const val CHANNEL_ID = "maintenance_reminders"
        const val DIGEST_ID = 1001
        private const val ACTION_OPEN = "open"
        private const val ACTION_LOG = "log"
        private const val ACTION_MARK_DONE = "mark_done"
        private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}

/** Stable across launches (String.hashCode is specified), positive, and never the digest's id. */
fun scheduleNotificationId(scheduleId: String): Int {
    val id = (scheduleId.hashCode() and Int.MAX_VALUE).coerceAtLeast(DIGEST_FLOOR)
    return if (id == AndroidReminderNotifier.DIGEST_ID) id + 1 else id
}

/** Ids below this are reserved for fixed notifications like the digest. */
private const val DIGEST_FLOOR = 10_000
