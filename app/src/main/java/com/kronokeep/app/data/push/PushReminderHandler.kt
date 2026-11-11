package com.kronokeep.app.data.push

import com.kronokeep.app.data.local.dao.AssetDao
import com.kronokeep.app.data.local.dao.MaintenanceScheduleDao
import com.kronokeep.app.data.reminders.ReminderLedgerLock
import com.kronokeep.app.domain.reminders.DuePush
import com.kronokeep.app.domain.reminders.LocalPresence
import com.kronokeep.app.domain.reminders.PushOutcome
import com.kronokeep.app.domain.reminders.PushPayloadParser
import com.kronokeep.app.domain.reminders.PushReminderPolicy
import com.kronokeep.app.domain.repository.AuthRepository
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.domain.repository.ReminderLedgerStore
import com.kronokeep.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/** Posts one pushed reminder. Seam so [PushReminderHandler] is testable without Android. */
interface DueNotifier {
    fun canPost(): Boolean
    fun postDue(push: DuePush)
}

/** Whether the pushed schedule is still live in Room. */
fun interface LocalScheduleLookup {
    suspend fun presence(scheduleId: String, assetId: String): LocalPresence
}

class RoomLocalScheduleLookup @Inject constructor(
    private val schedules: MaintenanceScheduleDao,
    private val assets: AssetDao,
) : LocalScheduleLookup {
    override suspend fun presence(scheduleId: String, assetId: String): LocalPresence {
        val asset = assets.getById(assetId)
        if (asset != null && (asset.deletedAt != null || asset.archivedAt != null)) return LocalPresence.REMOVED
        val schedule = schedules.getById(scheduleId) ?: return LocalPresence.MISSING
        return if (schedule.deletedAt != null) LocalPresence.REMOVED else LocalPresence.LIVE
    }
}

/** Handles an FCM data message: parse, decide against the shared ledger, post, record. */
class PushReminderHandler @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val ledger: ReminderLedgerStore,
    private val lookup: LocalScheduleLookup,
    private val notifier: DueNotifier,
) {
    /** Null when the message isn't one we handle (unknown type or malformed). */
    suspend fun handle(data: Map<String, String>): PushOutcome? {
        val push = PushPayloadParser.parse(data) ?: return null
        // A push can cold-start the process while the session is still being restored.
        val state = withTimeoutOrNull(AUTH_WAIT) { auth.state.first { it != AuthState.Loading } }
        val signedOut = state == AuthState.SignedOut || state == AuthState.Unconfigured
        val presence = lookup.presence(push.scheduleId, push.assetId)
        return ReminderLedgerLock.withLock {
            val outcome = PushReminderPolicy.decide(
                push = push,
                ledger = ledger.load(),
                presence = presence,
                signedOut = signedOut,
                remindersEnabled = settings.remindersEnabled.first(),
                canPost = notifier.canPost(),
            )
            if (outcome is PushOutcome.Post) {
                notifier.postDue(push)
                ledger.save(outcome.ledger)
            }
            outcome
        }
    }

    private companion object {
        val AUTH_WAIT = 3.seconds
    }
}
