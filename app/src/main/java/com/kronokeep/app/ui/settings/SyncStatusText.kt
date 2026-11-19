package com.kronokeep.app.ui.settings

import com.kronokeep.app.domain.repository.SyncStatus
import com.kronokeep.app.ui.components.Formats
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SYNCED_AT = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Formats.LOCALE)

private fun changes(n: Int) = if (n == 1) "1 change" else "$n changes"

/** One honest line for the Settings sync row. Rejections come first: they need the user, pending ones don't. */
fun SyncStatus.summary(zone: ZoneId = ZoneId.systemDefault()): String = when {
    running -> "Syncing…"
    rejected > 0 -> "${changes(rejected)} couldn't sync. Editing the item tries again."
    pending > 0 -> "${changes(pending)} saved on this phone, waiting to sync"
    lastSyncedAt != null -> "All changes synced · ${SYNCED_AT.format(lastSyncedAt.atZone(zone))}"
    else -> "Not synced yet"
}

/**
 * Signing out erases this phone's vault, so changes that never reached the server would be lost. Null when
 * nothing would be (sign out straight away).
 */
fun SyncStatus?.signOutWarning(): String? {
    val unsynced = (this?.pending ?: 0) + (this?.rejected ?: 0)
    if (unsynced == 0) return null
    return "${changes(unsynced).replaceFirstChar { it.uppercase() }} on this phone " +
        (if (unsynced == 1) "hasn't" else "haven't") + " reached the cloud. " +
        "Signing out removes your vault from this phone, so ${if (unsynced == 1) "it" else "they"} will be lost. " +
        "Connect to the internet and sync first to keep ${if (unsynced == 1) "it" else "them"}."
}
