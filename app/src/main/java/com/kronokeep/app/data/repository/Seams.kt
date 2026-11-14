package com.kronokeep.app.data.repository

import kotlinx.coroutines.flow.Flow

/** The signed-in Supabase user id, or null offline / signed out. New rows are stamped with it. */
fun interface UserIdProvider {
    fun currentUserId(): String?
}

/** Kicks the document upload queue. Implemented with WorkManager. */
fun interface UploadScheduler {
    fun schedule()
}

/** Queues sync runs. Implemented with WorkManager (network constraint, exponential backoff). */
interface SyncScheduler {
    /** Soon, once online. Keeps a run that's already queued or running. */
    fun schedule()

    /** User asked: replaces a run waiting out its backoff so it starts now. */
    fun syncNow()

    /** A slow background pull, so changes from other devices arrive without opening the app. */
    fun schedulePeriodic()

    val running: Flow<Boolean>
}
