package com.kronokeep.app.domain.repository

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** What the user needs to know about sync: is everything on the server, and if not, why. */
data class SyncStatus(
    /** Local changes not yet on the server (including files still uploading). */
    val pending: Int,
    /** Changes the server keeps refusing; they wait for the next edit of that item. */
    val rejected: Int,
    val lastSyncedAt: Instant?,
    val running: Boolean,
) {
    val allSynced: Boolean get() = pending == 0 && rejected == 0
}

interface SyncRepository {
    val status: Flow<SyncStatus>

    /** Asks for a sync as soon as the device is online. No-op when signed out or offline-only. */
    fun syncNow()
}
