package com.focusfeed.app

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    val scripts = remember {
        val load: (String) -> String = { name ->
            ctx.assets.open(name).bufferedReader().readText()
        }
        mapOf(
            "rules_instagram.js" to load("rules_instagram.js"),
            "rules_youtube.js"   to load("rules_youtube.js")
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentDest == dest,
                        onClick = { vm.onTabSelected(dest) },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) }
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
                key(currentDest) {
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
