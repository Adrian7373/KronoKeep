package com.kronokeep.app.data.reminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kronokeep.app.MainActivity
import com.kronokeep.app.R
import com.kronokeep.app.domain.model.MaintenanceStatus
import com.kronokeep.app.domain.reminders.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Posts the daily reminder digest. Seam so the worker logic can be tested without Android. */
interface ReminderNotifier {
    fun canPost(): Boolean
    fun post(reminders: List<Reminder>)
}

@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier {
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

    @SuppressLint("MissingPermission") // canPost() is checked by the caller.
    override fun post(reminders: List<Reminder>) {
        if (reminders.isEmpty() || !canPost()) return
        val lines = reminders.map(::line)
        val title = if (reminders.size == 1) lines.single().first else "${reminders.size} things need attention"
        val text = if (reminders.size == 1) lines.single().second else lines.joinToString(" · ") { it.first }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kronokeep)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
        if (reminders.size > 1) {
            builder.setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine("${it.first} — ${it.second}") } })
        }
        manager.notify(DIGEST_ID, builder.build())
    }

    private fun line(r: Reminder): Pair<String, String> = when (r) {
        is Reminder.Due -> {
            val verb = if (r.status == MaintenanceStatus.OVERDUE) "is overdue" else "is due soon"
            "${r.task} $verb" to r.assetName
        }
        is Reminder.UpdateOdometer -> "Update the odometer" to "${r.assetName}: last reading ${r.daysSince} days ago"
    }

    companion object {
        const val CHANNEL_ID = "maintenance_reminders"
        private const val DIGEST_ID = 1001
    }
}
