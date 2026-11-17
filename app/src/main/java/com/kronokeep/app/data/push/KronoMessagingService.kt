package com.kronokeep.app.data.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** Receives the server's data-only reminders and token rotations. */
@AndroidEntryPoint
class KronoMessagingService : FirebaseMessagingService() {
    @Inject lateinit var handler: PushReminderHandler
    @Inject lateinit var registrar: PushTokenRegistrar

    // Already on a background thread with a ~10 s budget; blocking keeps the process alive until we've posted.
    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking {
            runCatching { handler.handle(message.data) }
                // Outcome only (Post / Skip reason); no payload, since it carries asset names.
                .onSuccess { Log.i(TAG, "push ${message.data["type"]}: ${it?.javaClass?.simpleName ?: "ignored"} $it") }
                .onFailure { Log.w(TAG, "push handling failed", it) }
        }
    }

    override fun onNewToken(token: String) {
        registrar.onNewTokenInBackground(token)
    }

    private companion object {
        const val TAG = "KronoPush"
    }
}
