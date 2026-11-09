package com.kronokeep.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// ---- Top-level tabs ----
@Serializable data object DashboardRoute : NavKey
@Serializable data object AssetsRoute : NavKey
@Serializable data object SettingsRoute : NavKey

// ---- Sub-routes (pushed onto the current tab's back stack) ----
// Milestone 2+: AssetDetailRoute(assetId), AddAssetRoute, LogServiceRoute(assetId), ...
