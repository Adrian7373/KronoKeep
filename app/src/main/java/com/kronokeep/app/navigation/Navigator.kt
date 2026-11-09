package com.kronokeep.app.navigation

import androidx.navigation3.runtime.NavKey

/** The only place that mutates [NavigationState]. */
class Navigator(val state: NavigationState) {

    /** Switches tabs if [route] is a tab key, otherwise pushes onto the current tab. */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            state.backStacks[state.topLevelRoute]?.add(route)
        }
    }

    fun goBack() {
        val stack = state.backStacks[state.topLevelRoute]
            ?: error("Stack for ${state.topLevelRoute} not found")
        if (stack.last() == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            stack.removeLastOrNull()
        }
    }
}
