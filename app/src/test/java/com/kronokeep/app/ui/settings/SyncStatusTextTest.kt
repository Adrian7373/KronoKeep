package com.kronokeep.app.ui.settings

import com.kronokeep.app.domain.repository.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SyncStatusTextTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val at = Instant.parse("2026-11-14T02:42:00Z")

    private fun status(pending: Int = 0, rejected: Int = 0, last: Instant? = at, running: Boolean = false) =
        SyncStatus(pending, rejected, last, running).summary(manila)

    @Test fun allSyncedShowsLocalTime() = assertEquals("All changes synced · 14 Nov 2026, 10:42 AM", status().replace(' ', ' '))

    @Test fun neverSynced() = assertEquals("Not synced yet", status(last = null))

    @Test fun pendingIsCountedAndPluralised() {
        assertEquals("1 change saved on this phone, waiting to sync", status(pending = 1))
        assertEquals("3 changes saved on this phone, waiting to sync", status(pending = 3))
    }

    @Test fun rejectionsOutrankPending() =
        assertEquals("2 changes couldn't sync. Editing the item tries again.", status(pending = 4, rejected = 2))

    @Test fun runningWins() = assertEquals("Syncing…", status(pending = 2, rejected = 1, running = true))

    @Test fun signOutNeedsNoWarningWhenEverythingSynced() {
        assertEquals(null, SyncStatus(0, 0, at, false).signOutWarning())
        assertEquals(null, (null as SyncStatus?).signOutWarning())
    }

    @Test fun signOutWarnsAboutPendingAndRejectedChanges() {
        assertEquals(
            "1 change on this phone hasn't reached the cloud. Signing out removes your vault from this phone, " +
                "so it will be lost. Connect to the internet and sync first to keep it.",
            SyncStatus(1, 0, at, false).signOutWarning(),
        )
        assertEquals(
            "3 changes on this phone haven't reached the cloud. Signing out removes your vault from this phone, " +
                "so they will be lost. Connect to the internet and sync first to keep them.",
            SyncStatus(2, 1, at, false).signOutWarning(),
        )
    }
}
