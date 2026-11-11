package com.kronokeep.app.domain.repository

/** This phone's push token on the server. Registration itself happens automatically on sign-in. */
interface PushRegistration {
    /** Best effort, bounded in time; call while the session is still valid. Never throws. */
    suspend fun unregister()
}
