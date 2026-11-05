package com.focusfeed.app

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebContainerScreen(
    dest: NavDestination,
    startUrl: String,
    cssJs: String,
    rulesJs: String,
    modifier: Modifier = Modifier,
    onUrlChanged: (String) -> Unit
) {
    // Survives recompose within the same tab; cleared when composable leaves composition
    val savedState = remember { Bundle() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val manager = FocusWebViewManager(ctx, cssJs, rulesJs, onUrlChanged)
            WebView(ctx).also { wv ->
                manager.configure(wv)
                webViewRef = wv
                if (!savedState.isEmpty) {
                    wv.restoreState(savedState)
                } else {
                    wv.loadUrl(startUrl)
                }
            }
        },
        update = { wv ->
            // Only reload if the WebView has no URL yet (e.g. after process recreation)
            if (wv.url == null) wv.loadUrl(startUrl)
        },
        onRelease = { wv ->
            wv.saveState(savedState)
            webViewRef = null
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.saveState(savedState)
        }
    }
}
