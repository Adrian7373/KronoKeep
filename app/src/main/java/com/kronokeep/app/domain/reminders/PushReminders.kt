package com.kronokeep.app.domain.reminders

import com.kronokeep.app.domain.model.MaintenanceStatus
import java.time.Instant
import java.time.format.DateTimeParseException

/** A "due" reminder sent by the server's `recompute-due` job. */
data class DuePush(
    val scheduleId: String,
    val assetId: String,
    val assetName: String,
    val task: String,
    val status: MaintenanceStatus,
    /** Null for undated schedules (usage-only with no rate yet). */
    val nextDueAt: Instant?,
)

/**
 * Parses an FCM data payload. Returns null for anything that isn't a well-formed `due` message, including
 * unknown `type`s, so newer servers can add message kinds without breaking older apps.
 */
object PushPayloadParser {
    const val TYPE_DUE = "due"

    fun parse(data: Map<String, String>): DuePush? {
        if (data["type"] != TYPE_DUE) return null
        val scheduleId = data["schedule_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val assetId = data["asset_id"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val task = data["task"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val status = when (data["status"]) {
            "due_soon" -> MaintenanceStatus.DUE_SOON
            "overdue" -> MaintenanceStatus.OVERDUE
            else -> return null
        }
        val nextDueAt = data["next_due_at"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                // A date we can't read shouldn't cost the user the reminder itself.
                null
            }
        }
        return DuePush(
            scheduleId = scheduleId,
            assetId = assetId,
            task = task,
            assetName = data["asset_name"]?.trim().orEmpty(),
            status = status,
            nextDueAt = nextDueAt,
        )
    }
}

/** What this phone knows about the pushed schedule. */
enum class LocalPresence {
    /** Live schedule on a live, unarchived asset. */
    LIVE,
    /** The schedule or its asset was deleted or archived here (maybe not pushed yet): the push is stale. */
    REMOVED,
    /** Not synced down yet. The server knows best, so the reminder is still shown. */
    MISSING,
}

sealed interface PushOutcome {
    /** Post the notification, then save [ledger]. */
    data class Post(val ledger: ReminderLedger) : PushOutcome
    data class Skip(val reason: SkipReason) : PushOutcome
}

enum class SkipReason { SIGNED_OUT, REMINDERS_OFF, CANNOT_POST, REMOVED_LOCALLY, ALREADY_NOTIFIED }

/**
 * Decides whether a pushed reminder is shown. Shares the on-device [ReminderLedger] with the daily
 * [ReminderPlanner], so a status change is notified once whether the phone or the server noticed it first.
 */
object PushReminderPolicy {
    fun decide(
        push: DuePush,
        ledger: ReminderLedger,
        presence: LocalPresence,
        signedOut: Boolean,
        remindersEnabled: Boolean,
        canPost: Boolean,
    ): PushOutcome {
        // A token left on the server after a failed unregister must not show another account's reminders.
        if (signedOut) return PushOutcome.Skip(SkipReason.SIGNED_OUT)
        if (!remindersEnabled) return PushOutcome.Skip(SkipReason.REMINDERS_OFF)
        if (presence == LocalPresence.REMOVED) return PushOutcome.Skip(SkipReason.REMOVED_LOCALLY)
        val known = ledger.scheduleStatus[push.scheduleId]
        // OVERDUE already shown: a late DUE_SOON for the same cycle adds nothing.
        if (known == push.status || (known == MaintenanceStatus.OVERDUE && push.status == MaintenanceStatus.DUE_SOON)) {
            return PushOutcome.Skip(SkipReason.ALREADY_NOTIFIED)
        }
        // Not recorded, so the daily job still notifies once notifications are allowed again.
        if (!canPost) return PushOutcome.Skip(SkipReason.CANNOT_POST)
        return PushOutcome.Post(ledger.copy(scheduleStatus = ledger.scheduleStatus + (push.scheduleId to push.status)))
    }
}
