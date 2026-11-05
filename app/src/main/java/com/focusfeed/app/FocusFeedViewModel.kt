package com.focusfeed.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FocusFeedViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx get() = getApplication<Application>()

    private val _currentDest = MutableStateFlow(NavDestination.INSTAGRAM_DM)
    val currentDest: StateFlow<NavDestination> = _currentDest

    // Per-tab URL memory
    private val tabUrls = mutableMapOf(
        NavDestination.INSTAGRAM_DM to NavDestination.INSTAGRAM_DM.url,
        NavDestination.INSTAGRAM_HOME to NavDestination.INSTAGRAM_HOME.url,
        NavDestination.YOUTUBE_SUBS to NavDestination.YOUTUBE_SUBS.url
    )

    // ── Stats ─────────────────────────────────────────────────────────
    private val _todayBlocks = MutableStateFlow(0)
    val todayBlocks: StateFlow<Int> = _todayBlocks

    private val _minutesSaved = MutableStateFlow(0L)
    val minutesSaved: StateFlow<Long> = _minutesSaved

    // ── Settings ──────────────────────────────────────────────────────
    private val _allowStories = MutableStateFlow(FocusPrefs.isAllowStories(ctx))
    val allowStories: StateFlow<Boolean> = _allowStories

    private val _strictDmOnly = MutableStateFlow(FocusPrefs.isStrictDmOnly(ctx))
    val strictDmOnly: StateFlow<Boolean> = _strictDmOnly

    private val _hideExplore = MutableStateFlow(FocusPrefs.isHideExplore(ctx))
    val hideExplore: StateFlow<Boolean> = _hideExplore

    private val _injectionDisabled = MutableStateFlow(FocusPrefs.isInjectionDisabled(ctx))
    val injectionDisabled: StateFlow<Boolean> = _injectionDisabled

    // ── Navigation ────────────────────────────────────────────────────
    fun onTabSelected(dest: NavDestination) {
        _currentDest.value = dest
        if (dest == NavDestination.DASHBOARD) refreshStats()
    }

    fun currentUrl(): String = tabUrls[_currentDest.value] ?: _currentDest.value.url

    fun onPageChanged(dest: NavDestination, url: String) {
        tabUrls[dest] = url
    }

    // ── Stats ─────────────────────────────────────────────────────────
    fun refreshStats() {
        _todayBlocks.value = FocusStats.todayCount(ctx)
        _minutesSaved.value = FocusStats.minutesSaved(ctx)
    }

    // ── Settings setters ──────────────────────────────────────────────
    fun setAllowStories(v: Boolean) {
        FocusPrefs.setAllowStories(ctx, v)
        _allowStories.value = v
    }

    fun setStrictDmOnly(v: Boolean) {
        FocusPrefs.setStrictDmOnly(ctx, v)
        _strictDmOnly.value = v
    }

    fun setHideExplore(v: Boolean) {
        FocusPrefs.setHideExplore(ctx, v)
        _hideExplore.value = v
    }

    fun setInjectionDisabled(v: Boolean) {
        FocusPrefs.setInjectionDisabled(ctx, v)
        _injectionDisabled.value = v
    }
}
