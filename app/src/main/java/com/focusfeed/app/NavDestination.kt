package com.focusfeed.app

enum class NavDestination(val url: String, val label: String, val iconRes: Int) {
    INSTAGRAM_DM(
        url = "https://www.instagram.com/direct/inbox/",
        label = "DMs",
        iconRes = android.R.drawable.ic_dialog_email
    ),
    INSTAGRAM_HOME(
        url = "https://www.instagram.com/",
        label = "Instagram",
        iconRes = android.R.drawable.ic_menu_gallery
    ),
    YOUTUBE_SUBS(
        url = "https://www.youtube.com/feed/subscriptions",
        label = "YouTube",
        iconRes = android.R.drawable.ic_media_play
    );

    fun injectionAsset(): String? = when (this) {
        INSTAGRAM_DM, INSTAGRAM_HOME -> "injection_instagram.js"
        YOUTUBE_SUBS -> "injection_youtube.js"
    }
}
