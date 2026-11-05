package com.focusfeed.app

import android.os.Bundle
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
        setContent {
            FocusFeedTheme {
                FocusFeedApp(vm)
            }
        }
    }
}

@Composable
private fun FocusFeedApp(vm: FocusFeedViewModel) {
    val ctx = LocalContext.current
    val currentDest by vm.currentDest.collectAsState()

    // Load rules JS once; keyed so they reload if assets change between launches
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
                // key() recreates the WebView only when the tab changes,
                // preserving in-tab navigation history within the same session.
                key(currentDest) {
                    val rulesJs = scripts[currentDest.rulesAsset()] ?: ""
                    // CSS-only pre-injection: extracts the injectCSS() call from the rules file
                    val cssJs = "if(typeof injectCSS==='function')injectCSS();"
                    WebContainerScreen(
                        dest = currentDest,
                        startUrl = vm.currentUrl(),
                        cssJs = cssJs,
                        rulesJs = rulesJs,
                        modifier = Modifier.fillMaxSize(),
                        onUrlChanged = { url -> vm.onPageChanged(currentDest, url) }
                    )
                }
            }
        }
    }
}
