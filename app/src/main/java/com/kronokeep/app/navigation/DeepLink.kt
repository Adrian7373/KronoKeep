package com.kronokeep.app.navigation

import androidx.navigation3.runtime.NavKey
import com.kronokeep.app.domain.reminders.Reminder

/**
 * Where a notification tap lands. Carried as plain Intent extras ([toExtras] / [fromExtras]) so the mapping
 * stays testable without Android.
 */
sealed interface DeepLink {
    data object Dashboard : DeepLink
    data class Asset(val assetId: String) : DeepLink
    data class LogService(val assetId: String, val scheduleId: String?) : DeepLink

    /** The tab to select and its full back stack, root first (Back walks up to the tab root). */
    fun target(): DeepLinkTarget = when (this) {
        Dashboard -> DeepLinkTarget(DashboardRoute, listOf(DashboardRoute))
        is Asset -> DeepLinkTarget(AssetsRoute, listOf(AssetsRoute, AssetDetailRoute(assetId)))
        is LogService -> DeepLinkTarget(
            AssetsRoute,
            listOf(AssetsRoute, AssetDetailRoute(assetId), LogServiceRoute(assetId, scheduleId)),
        )
    }

    fun toExtras(): Map<String, String> = when (this) {
        Dashboard -> mapOf(EXTRA_TARGET to TARGET_DASHBOARD)
        is Asset -> mapOf(EXTRA_TARGET to TARGET_ASSET, EXTRA_ASSET_ID to assetId)
        is LogService -> buildMap {
            put(EXTRA_TARGET, TARGET_LOG_SERVICE)
            put(EXTRA_ASSET_ID, assetId)
            scheduleId?.let { put(EXTRA_SCHEDULE_ID, it) }
        }
    }

    companion object {
        const val EXTRA_TARGET = "com.kronokeep.app.extra.DEEP_LINK"
        const val EXTRA_ASSET_ID = "com.kronokeep.app.extra.ASSET_ID"
        const val EXTRA_SCHEDULE_ID = "com.kronokeep.app.extra.SCHEDULE_ID"
        private const val TARGET_DASHBOARD = "dashboard"
        private const val TARGET_ASSET = "asset"
        private const val TARGET_LOG_SERVICE = "log_service"

        /** Null for a plain launch or a malformed intent. */
        fun fromExtras(extra: (String) -> String?): DeepLink? {
            val assetId = extra(EXTRA_ASSET_ID)?.takeIf { it.isNotBlank() }
            return when (extra(EXTRA_TARGET)) {
                TARGET_DASHBOARD -> Dashboard
                TARGET_ASSET -> assetId?.let(::Asset)
                TARGET_LOG_SERVICE -> assetId?.let { LogService(it, extra(EXTRA_SCHEDULE_ID)?.takeIf(String::isNotBlank)) }
                else -> null
            }
        }

        /** A digest about one asset opens it; anything wider opens the dashboard. */
        fun forReminders(reminders: List<Reminder>): DeepLink {
            val assets = reminders.map {
                when (it) {
                    is Reminder.Due -> it.assetId
                    is Reminder.UpdateOdometer -> it.assetId
                }
            }.distinct()
            return assets.singleOrNull()?.let(::Asset) ?: Dashboard
        }
    }
}

data class DeepLinkTarget(val tab: NavKey, val stack: List<NavKey>)
