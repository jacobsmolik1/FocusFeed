package com.focusfeed.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavDestination(
    val url: String,
    val label: String,
    val icon: ImageVector
) {
    INSTAGRAM_DM(
        url = "https://www.instagram.com/direct/inbox/",
        label = "DMs",
        icon = Icons.Default.Email
    ),
    INSTAGRAM_HOME(
        url = "https://www.instagram.com/",
        label = "Instagram",
        icon = Icons.Default.Home
    ),
    YOUTUBE_SUBS(
        url = "https://www.youtube.com/feed/subscriptions",
        label = "YouTube",
        icon = Icons.Default.PlayCircle
    ),
    DASHBOARD(
        url = "",
        label = "Focus",
        icon = Icons.Default.Shield
    );

    fun rulesAsset(): String? = when (this) {
        INSTAGRAM_DM, INSTAGRAM_HOME -> "rules_instagram.js"
        YOUTUBE_SUBS -> "rules_youtube.js"
        DASHBOARD -> null
    }

    val isWebTab: Boolean get() = this != DASHBOARD
}
