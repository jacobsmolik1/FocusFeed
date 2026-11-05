package com.focusfeed.app

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FocusFeedViewModel : ViewModel() {

    private val _currentDest = MutableStateFlow(NavDestination.INSTAGRAM_DM)
    val currentDest: StateFlow<NavDestination> = _currentDest

    // Per-tab URL state so navigating back to a tab restores position
    private val tabUrls = mutableMapOf(
        NavDestination.INSTAGRAM_DM to NavDestination.INSTAGRAM_DM.url,
        NavDestination.INSTAGRAM_HOME to NavDestination.INSTAGRAM_HOME.url,
        NavDestination.YOUTUBE_SUBS to NavDestination.YOUTUBE_SUBS.url
    )

    fun currentUrl(): String = tabUrls[_currentDest.value] ?: _currentDest.value.url

    fun onTabSelected(dest: NavDestination) {
        _currentDest.value = dest
    }

    fun onPageChanged(dest: NavDestination, url: String) {
        tabUrls[dest] = url
    }
}
