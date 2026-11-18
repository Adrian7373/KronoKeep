package com.kronokeep.app.domain.repository

/** Why an auth call failed, without the transport details (URLs, headers) a raw exception carries. */
enum class AuthFailureReason {
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    ACCOUNT_EXISTS,
    WEAK_PASSWORD,
    INVALID_EMAIL,
    RATE_LIMITED,
    SIGNUP_DISABLED,
    NETWORK,
    UNCONFIGURED,
    UNKNOWN;

    companion object {
        /** Maps a Supabase Auth `error_code` (e.g. `invalid_credentials`) to a reason. */
        fun fromCode(code: String?): AuthFailureReason = when (code?.trim()?.lowercase()) {
            "invalid_credentials", "invalid_grant" -> INVALID_CREDENTIALS
            "email_not_confirmed" -> EMAIL_NOT_CONFIRMED
            "user_already_exists", "email_exists" -> ACCOUNT_EXISTS
            "weak_password" -> WEAK_PASSWORD
            "email_address_invalid", "validation_failed" -> INVALID_EMAIL
            "over_request_rate_limit", "over_email_send_rate_limit" -> RATE_LIMITED
            "signup_disabled", "email_provider_disabled" -> SIGNUP_DISABLED
            else -> UNKNOWN
        }
    }
}

class AuthFailure(val reason: AuthFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)
