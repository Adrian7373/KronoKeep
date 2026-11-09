package com.kronokeep.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// ---- Top-level tabs ----
@Serializable data object DashboardRoute : NavKey
@Serializable data object AssetsRoute : NavKey
@Serializable data object SettingsRoute : NavKey

// ---- Sub-routes (pushed onto the current tab's back stack) ----
@Serializable data object AddAssetRoute : NavKey
@Serializable data class AssetDetailRoute(val assetId: String) : NavKey
@Serializable data class EditAssetRoute(val assetId: String) : NavKey
@Serializable data class DocumentViewerRoute(val documentId: String) : NavKey

/** Add (scheduleId = null) or edit a maintenance schedule. */
@Serializable data class ScheduleEditorRoute(val assetId: String, val scheduleId: String? = null) : NavKey

/** Log a service visit, optionally with one task pre-ticked. */
@Serializable data class LogServiceRoute(val assetId: String, val scheduleId: String? = null) : NavKey
