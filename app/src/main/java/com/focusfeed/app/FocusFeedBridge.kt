package com.focusfeed.app

import android.content.Context
import android.webkit.JavascriptInterface

class FocusFeedBridge(private val context: Context) {

    @JavascriptInterface
    fun recordBlock(platform: String) {
        FocusStats.recordBlock(context)
    }

    @JavascriptInterface
    fun isAllowStories(): Boolean = FocusPrefs.isAllowStories(context)

    @JavascriptInterface
    fun isStrictDmOnly(): Boolean = FocusPrefs.isStrictDmOnly(context)

    @JavascriptInterface
    fun isHideExplore(): Boolean = FocusPrefs.isHideExplore(context)

    @JavascriptInterface
    fun isInjectionDisabled(): Boolean = FocusPrefs.isInjectionDisabled(context)
}
