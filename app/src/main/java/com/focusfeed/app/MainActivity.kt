package com.focusfeed.app

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.focusfeed.app.ui.theme.FocusFeedTheme

class MainActivity : ComponentActivity() {

    private val vm: FocusFeedViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first WebView. Instagram drops the session if this is off.
        CookieManager.getInstance().setAcceptCookie(true)
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setContent {
            FocusFeedTheme {
                FocusFeedApp(vm)
            }
        }
    }

    override fun onPause() {
        // Login cookies are kept in memory until flush. Without this, a tab
        // switch or backgrounding returns the user to a logged-out blank wall.
        FocusWebViewManager.flushCookiesAsync()
        super.onPause()
    }
}

@Composable
private fun FocusFeedApp(vm: FocusFeedViewModel) {
    val ctx = LocalContext.current
    val currentDest by vm.currentDest.collectAsState()
    val injectionDisabled by vm.injectionDisabled.collectAsState()

    val scripts = remember {
        val load: (String) -> String = { name ->
            ctx.assets.open(name).bufferedReader().readText()
        }
        mapOf(
            "rules_instagram.js" to load("rules_instagram.js"),
            "rules_youtube.js" to load("rules_youtube.js")
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NavDestination.entries.forEach { dest ->
                    val selected = currentDest == dest
                    NavigationBarItem(
                        selected = selected,
                        onClick = { vm.onTabSelected(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = {
                            Text(
                                dest.label,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        },
                        alwaysShowLabel = true,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (currentDest == NavDestination.DASHBOARD) {
                DashboardScreen(vm)
            } else {
                key(currentDest, injectionDisabled) {
                    WebContainerScreen(
                        dest = currentDest,
                        startUrl = vm.currentUrl(),
                        rulesJs = scripts[currentDest.rulesAsset()] ?: "",
                        modifier = Modifier.fillMaxSize(),
                        onUrlChanged = { url -> vm.onPageChanged(currentDest, url) }
                    )
                }
            }
        }
    }
}
