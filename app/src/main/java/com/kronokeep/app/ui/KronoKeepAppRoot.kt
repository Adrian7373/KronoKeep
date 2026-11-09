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
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
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
import com.kronokeep.app.navigation.AssetsRoute
import com.kronokeep.app.navigation.DashboardRoute
import com.kronokeep.app.navigation.Navigator
import com.kronokeep.app.navigation.SettingsRoute
import com.kronokeep.app.navigation.rememberNavigationState
import com.kronokeep.app.ui.assets.AssetsScreen
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
        AuthState.SignedOut -> AuthScreen()
        AuthState.Unconfigured, is AuthState.SignedIn -> MainShell(cloudConfigured = authState != AuthState.Unconfigured)
    }
}

@Composable
private fun MainShell(cloudConfigured: Boolean) {
    val topLevelRoutes = remember { TOP_LEVEL_DESTINATIONS.map { it.route }.toSet() }
    val navigationState = rememberNavigationState(startRoute = DashboardRoute, topLevelRoutes = topLevelRoutes)
    val navigator = remember(navigationState) { Navigator(navigationState) }

    val entryProvider = entryProvider<NavKey> {
        entry<DashboardRoute> { DashboardScreen(cloudConfigured = cloudConfigured) }
        entry<AssetsRoute> { AssetsScreen() }
        entry<SettingsRoute> { SettingsScreen() }
    }

    NavigationSuiteScaffold(
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
