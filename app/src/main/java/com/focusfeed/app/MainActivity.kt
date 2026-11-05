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
    val currentDest by vm.currentDest.collectAsState()
    val context = LocalContext.current

    val injectionScripts = remember {
        mapOf(
            "injection_instagram.js" to context.assets.open("injection_instagram.js")
                .bufferedReader().readText(),
            "injection_youtube.js" to context.assets.open("injection_youtube.js")
                .bufferedReader().readText()
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentDest == dest,
                        onClick = { vm.onTabSelected(dest) },
                        icon = {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(dest.iconRes),
                                contentDescription = dest.label
                            )
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // key() forces a fresh WebView only when the destination changes,
            // so the WebView isn't recreated on recompose within the same tab.
            key(currentDest) {
                val js = injectionScripts[currentDest.injectionAsset()] ?: ""
                WebContainerScreen(
                    dest = currentDest,
                    startUrl = vm.currentUrl(),
                    injectionJs = js,
                    onUrlChanged = { url -> vm.onPageChanged(currentDest, url) }
                )
            }
        }
    }
}
