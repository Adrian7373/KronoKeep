package com.kronokeep.app.data.remote

import com.kronokeep.app.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Supabase client, or null when this build has no URL/key in local.properties.
 * The app is offline-first, so every caller must cope with [client] being null.
 */
@Singleton
class SupabaseProvider @Inject constructor() {

    val isConfigured: Boolean =
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    val client: SupabaseClient? by lazy {
        if (!isConfigured) return@lazy null
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
    }
}
