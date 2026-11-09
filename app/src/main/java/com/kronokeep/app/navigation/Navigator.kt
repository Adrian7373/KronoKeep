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

    /** Swaps the top of the current tab's stack, e.g. the add wizard for the asset it just created. */
    fun replaceTop(route: NavKey) {
        val stack = state.backStacks[state.topLevelRoute] ?: return
        if (stack.size > 1) stack.removeLastOrNull()
        stack.add(route)
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
