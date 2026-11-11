package com.kronokeep.app.data.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.kronokeep.app.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase is configured from local.properties (via BuildConfig) instead of google-services.json, so a build
 * without the keys simply has no push. FirebaseInitProvider is removed and auto-init is off in the manifest;
 * [initialize] is the only place Firebase starts.
 */
@Singleton
class FirebaseSetup @Inject constructor() {

    val isConfigured: Boolean = listOf(
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_APPLICATION_ID,
        BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_GCM_SENDER_ID,
    ).all { it.isNotBlank() }

    @Volatile
    var isInitialized: Boolean = false
        private set

    fun initialize(context: Context) {
        if (!isConfigured || isInitialized) return
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APPLICATION_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setGcmSenderId(BuildConfig.FIREBASE_GCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(context, options)
            }
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            isInitialized = true
        } catch (e: RuntimeException) {
            // Bad keys must not take the offline-first app down with them.
            Log.w(TAG, "Firebase init failed; push reminders are off", e)
        }
    }

    private companion object {
        const val TAG = "FirebaseSetup"
    }
}
