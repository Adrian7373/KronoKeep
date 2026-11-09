package com.kronokeep.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * One back stack per tab, saved across process death. "Exit through home": the start tab's
 * entries always sit at the bottom, so Back from another tab returns to the dashboard.
 */
@Composable
fun rememberNavigationState(
    startRoute: NavKey,
    topLevelRoutes: Set<NavKey>,
): NavigationState {
    val topLevelIndex = rememberSaveable { mutableStateOf(topLevelRoutes.indexOf(startRoute)) }
    val backStacks = topLevelRoutes.associateWith { key -> rememberNavBackStack(key) }

    return remember(startRoute, topLevelRoutes) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoutes = topLevelRoutes.toList(),
            topLevelIndex = topLevelIndex,
            backStacks = backStacks,
        )
    }
}

class NavigationState(
    val startRoute: NavKey,
    private val topLevelRoutes: List<NavKey>,
    topLevelIndex: MutableState<Int>,
    val backStacks: Map<NavKey, NavBackStack<NavKey>>,
) {
    private var topLevelIndex: Int by topLevelIndex

    var topLevelRoute: NavKey
        get() = topLevelRoutes[topLevelIndex]
        set(value) {
            topLevelIndex = topLevelRoutes.indexOf(value)
        }

    @Composable
    fun toDecoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>,
    ): List<NavEntry<NavKey>> {
        val decoratedEntries = backStacks.mapValues { (_, stack) ->
            val decorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
                rememberViewModelStoreNavEntryDecorator<NavKey>(),
            )
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = decorators,
                entryProvider = entryProvider,
            )
        }
        val inUse = if (topLevelRoute == startRoute) listOf(startRoute) else listOf(startRoute, topLevelRoute)
        return inUse.flatMap { decoratedEntries[it].orEmpty() }
    }
}
