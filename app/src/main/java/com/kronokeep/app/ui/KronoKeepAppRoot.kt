package com.kronokeep.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.kronokeep.app.domain.repository.AuthState
import com.kronokeep.app.navigation.AddAssetRoute
import com.kronokeep.app.navigation.AssetDetailRoute
import com.kronokeep.app.navigation.AssetsRoute
import com.kronokeep.app.navigation.DocumentViewerRoute
import com.kronokeep.app.navigation.EditAssetRoute
import com.kronokeep.app.navigation.DashboardRoute
import com.kronokeep.app.navigation.DeepLink
import com.kronokeep.app.navigation.Navigator
import com.kronokeep.app.navigation.LogServiceRoute
import com.kronokeep.app.navigation.ScheduleEditorRoute
import com.kronokeep.app.navigation.SettingsRoute
import com.kronokeep.app.navigation.ReportViewerRoute
import com.kronokeep.app.navigation.ReportsRoute
import com.kronokeep.app.ui.reports.ReportViewerScreen
import com.kronokeep.app.ui.reports.ReportViewerViewModel
import com.kronokeep.app.ui.reports.ReportsScreen
import com.kronokeep.app.ui.reports.ReportsViewModel
import com.kronokeep.app.ui.schedules.ScheduleEditorScreen
import com.kronokeep.app.ui.schedules.ScheduleEditorViewModel
import com.kronokeep.app.ui.service.LogServiceScreen
import com.kronokeep.app.ui.service.LogServiceViewModel
import com.kronokeep.app.navigation.rememberNavigationState
import com.kronokeep.app.ui.assets.AssetsScreen
import com.kronokeep.app.ui.assets.detail.AssetDetailScreen
import com.kronokeep.app.ui.assets.detail.AssetDetailViewModel
import com.kronokeep.app.ui.assets.editor.AssetEditorScreen
import com.kronokeep.app.ui.assets.editor.AssetEditorViewModel
import com.kronokeep.app.ui.documents.DocumentViewerScreen
import com.kronokeep.app.ui.documents.DocumentViewerViewModel
import com.kronokeep.app.ui.auth.AuthScreen
import com.kronokeep.app.ui.dashboard.DashboardScreen
import com.kronokeep.app.ui.settings.SettingsScreen

private data class TopLevelDestination(
    val route: NavKey,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

private val TOP_LEVEL_DESTINATIONS = listOf(
    TopLevelDestination(DashboardRoute, "Dashboard", Icons.Outlined.Dashboard, Icons.Filled.Dashboard),
    TopLevelDestination(AssetsRoute, "Assets", Icons.Outlined.Inventory2, Icons.Filled.Inventory2),
    TopLevelDestination(SettingsRoute, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

@Composable
fun KronoKeepAppRoot(viewModel: AppViewModel = hiltViewModel()) {
    val authState by viewModel.authState.collectAsStateWithLifecycle()
    when (authState) {
        AuthState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        AuthState.SignedOut, is AuthState.PasswordRecovery -> AuthScreen()
        AuthState.Unconfigured, is AuthState.SignedIn -> {
            val askForNotifications by viewModel.shouldAskForNotifications.collectAsStateWithLifecycle()
            NotificationPermissionGate(askForNotifications, viewModel::onNotificationPromptShown)
            val deepLink by viewModel.pendingDeepLink.collectAsStateWithLifecycle()
            MainShell(
                cloudConfigured = authState != AuthState.Unconfigured,
                deepLink = deepLink,
                onDeepLinkHandled = viewModel::onDeepLinkHandled,
            )
        }
    }
}

@Composable
private fun MainShell(
    cloudConfigured: Boolean,
    deepLink: DeepLink?,
    onDeepLinkHandled: (DeepLink) -> Unit,
) {
    val topLevelRoutes = remember { TOP_LEVEL_DESTINATIONS.map { it.route }.toSet() }
    val navigationState = rememberNavigationState(startRoute = DashboardRoute, topLevelRoutes = topLevelRoutes)
    val navigator = remember(navigationState) { Navigator(navigationState) }

    LaunchedEffect(deepLink) {
        deepLink?.let {
            navigator.open(it)
            onDeepLinkHandled(it)
        }
    }

    val entryProvider = entryProvider<NavKey> {
        entry<DashboardRoute> {
            DashboardScreen(
                cloudConfigured = cloudConfigured,
                onOpenAsset = { navigator.navigate(AssetDetailRoute(it)) },
                onAddAsset = { navigator.navigate(AddAssetRoute) },
            )
        }
        entry<AssetsRoute> {
            AssetsScreen(
                onAddAsset = { navigator.navigate(AddAssetRoute) },
                onOpenAsset = { navigator.navigate(AssetDetailRoute(it)) },
            )
        }
        entry<SettingsRoute> { SettingsScreen() }
        entry<AddAssetRoute> {
            AssetEditorScreen(
                viewModel = hiltViewModel<AssetEditorViewModel, AssetEditorViewModel.Factory> { it.create(null) },
                onSaved = { id -> navigator.replaceTop(AssetDetailRoute(id)) },
                onClose = navigator::goBack,
            )
        }
        entry<EditAssetRoute> { key ->
            AssetEditorScreen(
                viewModel = hiltViewModel<AssetEditorViewModel, AssetEditorViewModel.Factory> { it.create(key.assetId) },
                onSaved = { navigator.goBack() },
                onClose = navigator::goBack,
            )
        }
        entry<AssetDetailRoute> { key ->
            AssetDetailScreen(
                viewModel = hiltViewModel<AssetDetailViewModel, AssetDetailViewModel.Factory> { it.create(key.assetId) },
                onBack = navigator::goBack,
                onEdit = { navigator.navigate(EditAssetRoute(it)) },
                onOpenDocument = { navigator.navigate(DocumentViewerRoute(it)) },
                onAddSchedule = { navigator.navigate(ScheduleEditorRoute(it)) },
                onEditSchedule = { assetId, scheduleId -> navigator.navigate(ScheduleEditorRoute(assetId, scheduleId)) },
                onLogService = { assetId, scheduleId -> navigator.navigate(LogServiceRoute(assetId, scheduleId)) },
                onOpenReports = { navigator.navigate(ReportsRoute(it)) },
            )
        }
        entry<ReportsRoute> { key ->
            ReportsScreen(
                viewModel = hiltViewModel<ReportsViewModel, ReportsViewModel.Factory> { it.create(key.assetId) },
                onBack = navigator::goBack,
                onOpenReport = { navigator.navigate(ReportViewerRoute(key.assetId, it)) },
            )
        }
        entry<ReportViewerRoute> { key ->
            ReportViewerScreen(
                viewModel = hiltViewModel<ReportViewerViewModel, ReportViewerViewModel.Factory> {
                    it.create(key.assetId, key.reportId)
                },
                onBack = navigator::goBack,
            )
        }
        entry<ScheduleEditorRoute> { key ->
            ScheduleEditorScreen(
                viewModel = hiltViewModel<ScheduleEditorViewModel, ScheduleEditorViewModel.Factory> {
                    it.create(key.assetId, key.scheduleId)
                },
                onClose = navigator::goBack,
            )
        }
        entry<LogServiceRoute> { key ->
            LogServiceScreen(
                viewModel = hiltViewModel<LogServiceViewModel, LogServiceViewModel.Factory> {
                    it.create(key.assetId, key.scheduleId)
                },
                onDone = { navigator.goBack() },
                onClose = navigator::goBack,
            )
        }
        entry<DocumentViewerRoute> { key ->
            DocumentViewerScreen(
                viewModel = hiltViewModel<DocumentViewerViewModel, DocumentViewerViewModel.Factory> { it.create(key.documentId) },
                onBack = navigator::goBack,
            )
        }
    }

    // Pushed screens (wizard, detail, viewer) go full-screen: no tab bar, and their own insets stay correct.
    val onTabRoot = navigationState.backStacks[navigationState.topLevelRoute]?.size == 1
    val adaptiveLayout = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfoV2())

    // Selection is teal, never brass (Material's default indicator is secondaryContainer, our brass wash).
    val scheme = MaterialTheme.colorScheme
    val itemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = scheme.primaryContainer,
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onSurface,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = scheme.primaryContainer,
            selectedIconColor = scheme.onPrimaryContainer,
            selectedTextColor = scheme.onSurface,
            unselectedIconColor = scheme.onSurfaceVariant,
            unselectedTextColor = scheme.onSurfaceVariant,
        ),
    )

    NavigationSuiteScaffold(
        layoutType = if (onTabRoot) adaptiveLayout else NavigationSuiteType.None,
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = scheme.surfaceContainerLow,
            navigationRailContainerColor = scheme.surfaceContainerLow,
        ),
        navigationSuiteItems = {
            TOP_LEVEL_DESTINATIONS.forEach { destination ->
                val selected = navigationState.topLevelRoute == destination.route
                item(
                    selected = selected,
                    onClick = { navigator.navigate(destination.route) },
                    icon = {
                        Icon(if (selected) destination.selectedIcon else destination.icon, contentDescription = null)
                    },
                    label = { Text(destination.label) },
                    colors = itemColors,
                )
            }
        },
    ) {
        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryProvider),
            onBack = { navigator.goBack() },
        )
    }
}
