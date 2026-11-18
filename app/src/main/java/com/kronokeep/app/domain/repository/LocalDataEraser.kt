package com.kronokeep.app.domain.repository

/**
 * Erases everything this phone holds for the vault: the database (including unsynced edits), document
 * files, sync cursors, the reminder ledger and the push registration, and stops background sync and uploads.
 * Device preferences (theme, reminder toggle) are kept. Best effort: one failed step doesn't stop the rest.
 */
interface LocalDataEraser {
    suspend fun eraseAll()
}
