package com.focusfeed.app

import android.content.Context

object FocusPrefs {
    private const val PREFS = "focusfeed_prefs"

    private const val KEY_ALLOW_STORIES = "allow_stories"
    private const val KEY_STRICT_DM_ONLY = "strict_dm_only"
    private const val KEY_HIDE_EXPLORE = "hide_explore"

    fun isAllowStories(ctx: Context): Boolean = get(ctx, KEY_ALLOW_STORIES, true)
    fun setAllowStories(ctx: Context, v: Boolean) = set(ctx, KEY_ALLOW_STORIES, v)

    fun isStrictDmOnly(ctx: Context): Boolean = get(ctx, KEY_STRICT_DM_ONLY, false)
    fun setStrictDmOnly(ctx: Context, v: Boolean) = set(ctx, KEY_STRICT_DM_ONLY, v)

    fun isHideExplore(ctx: Context): Boolean = get(ctx, KEY_HIDE_EXPLORE, true)
    fun setHideExplore(ctx: Context, v: Boolean) = set(ctx, KEY_HIDE_EXPLORE, v)

    private fun get(ctx: Context, key: String, default: Boolean): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key, default)

    private fun set(ctx: Context, key: String, value: Boolean) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
}
