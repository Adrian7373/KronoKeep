package com.kronokeep.app.data.repository

/** The signed-in Supabase user id, or null offline / signed out. New rows are stamped with it. */
fun interface UserIdProvider {
    fun currentUserId(): String?
}

/** Kicks the document upload queue. Implemented with WorkManager. */
fun interface UploadScheduler {
    fun schedule()
}
