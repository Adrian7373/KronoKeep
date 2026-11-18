package com.kronokeep.app.data.remote

import com.kronokeep.app.domain.repository.AuthFailureReason
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reads the `{"error": code, "message": text}` bodies our Edge Functions answer with. */
object FunctionErrors {
    private val json = Json { ignoreUnknownKeys = true }

    /** The `error` code, or null when [body] isn't one of our error bodies. */
    fun code(body: String?): String? = runCatching {
        json.parseToJsonElement(body.orEmpty()).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Maps a failed delete-account call. supabase-kt sometimes hands over the raw body and sometimes just the
     * code, so a bare code is accepted too.
     */
    fun deleteAccountReason(status: Int, body: String?): AuthFailureReason {
        if (status == 429) return AuthFailureReason.RATE_LIMITED
        return AuthFailureReason.fromCode(code(body) ?: body)
    }
}
