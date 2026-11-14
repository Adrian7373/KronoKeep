package com.kronokeep.app.domain.reminders

import com.kronokeep.app.domain.model.MaintenanceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PushPayloadParserTest {
    private val full = mapOf(
        "type" to "due",
        "schedule_id" to "s-1",
        "asset_id" to "a-1",
        "asset_name" to "Click 125i",
        "task" to "Engine oil change",
        "status" to "overdue",
        "next_due_at" to "2026-11-20T00:00:00Z",
    )

    @Test fun `parses a full due message`() {
        assertEquals(
            DuePush("s-1", "a-1", "Click 125i", "Engine oil change", MaintenanceStatus.OVERDUE, Instant.parse("2026-11-20T00:00:00Z")),
            PushPayloadParser.parse(full),
        )
    }

    @Test fun `due soon and offset timestamps parse`() {
        val push = PushPayloadParser.parse(full + mapOf("status" to "due_soon", "next_due_at" to "2026-11-20T08:00:00.123456+08:00"))!!
        assertEquals(MaintenanceStatus.DUE_SOON, push.status)
        assertEquals(Instant.parse("2026-11-20T00:00:00.123456Z"), push.nextDueAt)
    }

    @Test fun `empty, missing or unreadable due date is undated, not dropped`() {
        assertNull(PushPayloadParser.parse(full + ("next_due_at" to ""))!!.nextDueAt)
        assertNull(PushPayloadParser.parse(full - "next_due_at")!!.nextDueAt)
        assertNull(PushPayloadParser.parse(full + ("next_due_at" to "next week"))!!.nextDueAt)
    }

    @Test fun `missing asset name is tolerated`() {
        assertEquals("", PushPayloadParser.parse(full - "asset_name")!!.assetName)
    }

    @Test fun `unknown types and malformed messages are ignored`() {
        assertNull(PushPayloadParser.parse(full + ("type" to "report_ready")))
        assertNull(PushPayloadParser.parse(full - "type"))
        assertNull(PushPayloadParser.parse(full + ("status" to "ok")))
        assertNull(PushPayloadParser.parse(full - "schedule_id"))
        assertNull(PushPayloadParser.parse(full + ("asset_id" to " ")))
        assertNull(PushPayloadParser.parse(full - "task"))
        assertNull(PushPayloadParser.parse(emptyMap()))
    }
}

class PushReminderPolicyTest {
    private val push = DuePush("s-1", "a-1", "Click", "Oil", MaintenanceStatus.DUE_SOON, null)

    private fun decide(
        p: DuePush = push,
        ledger: ReminderLedger = ReminderLedger(),
        presence: LocalPresence = LocalPresence.LIVE,
        signedOut: Boolean = false,
        enabled: Boolean = true,
        canPost: Boolean = true,
    ) = PushReminderPolicy.decide(p, ledger, presence, signedOut, enabled, canPost)

    @Test fun `new status is posted and recorded without touching other entries`() {
        val ledger = ReminderLedger(mapOf("other" to MaintenanceStatus.OVERDUE))
        assertEquals(
            PushOutcome.Post(ReminderLedger(mapOf("other" to MaintenanceStatus.OVERDUE, "s-1" to MaintenanceStatus.DUE_SOON))),
            decide(ledger = ledger),
        )
    }

    @Test fun `same status already in the ledger is skipped`() {
        assertEquals(
            PushOutcome.Skip(SkipReason.ALREADY_NOTIFIED),
            decide(ledger = ReminderLedger(mapOf("s-1" to MaintenanceStatus.DUE_SOON))),
        )
    }

    @Test fun `due soon to overdue notifies again, but a late due soon after overdue does not`() {
        val overdue = push.copy(status = MaintenanceStatus.OVERDUE)
        val afterDueSoon = decide(p = overdue, ledger = ReminderLedger(mapOf("s-1" to MaintenanceStatus.DUE_SOON)))
        assertEquals(PushOutcome.Post(ReminderLedger(mapOf("s-1" to MaintenanceStatus.OVERDUE))), afterDueSoon)

        assertEquals(
            PushOutcome.Skip(SkipReason.ALREADY_NOTIFIED),
            decide(ledger = ReminderLedger(mapOf("s-1" to MaintenanceStatus.OVERDUE))),
        )
    }

    @Test fun `missing locally is still shown, removed locally is not`() {
        assertEquals(true, decide(presence = LocalPresence.MISSING) is PushOutcome.Post)
        assertEquals(PushOutcome.Skip(SkipReason.REMOVED_LOCALLY), decide(presence = LocalPresence.REMOVED))
    }

    @Test fun `gates - signed out, reminders off, notifications blocked`() {
        assertEquals(PushOutcome.Skip(SkipReason.SIGNED_OUT), decide(signedOut = true))
        assertEquals(PushOutcome.Skip(SkipReason.REMINDERS_OFF), decide(enabled = false))
        assertEquals(PushOutcome.Skip(SkipReason.CANNOT_POST), decide(canPost = false))
    }
}
