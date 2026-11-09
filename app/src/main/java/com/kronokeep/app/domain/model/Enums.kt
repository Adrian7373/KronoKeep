package com.kronokeep.app.domain.model

/** Mirrors the Postgres enum `asset_category`. Stored by [wire] name on the server, by enum name in Room. */
enum class AssetCategory(val wire: String) {
    VEHICLE("vehicle"),
    APPLIANCE("appliance"),
    ELECTRONIC("electronic");

    companion object {
        fun fromWire(value: String): AssetCategory =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown asset category: $value")
    }
}

/** Mirrors the Postgres enum `maintenance_status`. */
enum class MaintenanceStatus(val wire: String) {
    OK("ok"),
    DUE_SOON("due_soon"),
    OVERDUE("overdue");

    companion object {
        fun fromWire(value: String): MaintenanceStatus =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown maintenance status: $value")
    }
}

/** Mirrors the Postgres enum `document_kind`. */
enum class DocumentKind(val wire: String) {
    RECEIPT("receipt"),
    WARRANTY("warranty"),
    MANUAL("manual"),
    PHOTO("photo"),
    OTHER("other");

    companion object {
        fun fromWire(value: String): DocumentKind =
            entries.firstOrNull { it.wire == value } ?: throw IllegalArgumentException("Unknown document kind: $value")
    }
}

/** Local-only: whether a row's latest change has reached Supabase. */
enum class SyncState { PENDING, SYNCED, FAILED }
