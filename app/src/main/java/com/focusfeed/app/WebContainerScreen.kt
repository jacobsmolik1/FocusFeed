package com.focusfeed.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.WeakHashMap

@Composable
fun WebContainerScreen(
    dest: NavDestination,
    startUrl: String,
    rulesJs: String,
    modifier: Modifier = Modifier,
    onUrlChanged: (String) -> Unit
) {
    val savedState = remember { Bundle() }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    val latestUrl = remember(dest) { mutableStateOf(startUrl) }
    val crashGate = remember { CrashGate() }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    key(generation) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val manager = FocusWebViewManager(
                    context = ctx,
                    dest = dest,
                    rulesJs = rulesJs,
                    onUrlChanged = { url ->
                        latestUrl.value = url
                        onUrlChanged(url)
                    },
                    onRendererGone = {
                        if (crashGate.open) {
                            crashGate.open = false
                            Handler(Looper.getMainLooper()).post {
                                generation++
                                crashGate.open = true
                            }
                        }
                    }
                )
                WebView(ctx).also { wv ->
                    attachedManagers[wv] = manager
                    manager.configure(wv)
                    webViewRef = wv
                    if (generation == 0 && !savedState.isEmpty) {
                        wv.restoreState(savedState)
                    } else {
                        wv.loadUrl(latestUrl.value)
                    }
                }
            },
            update = { wv ->
                if (wv.url == null) wv.loadUrl(latestUrl.value)
            },
            onRelease = { wv ->
                runCatching { wv.saveState(savedState) }
                attachedManagers.remove(wv)?.release()
                if (webViewRef === wv) webViewRef = null
                runCatching {
                    wv.stopLoading()
                    wv.onPause()
                    wv.webChromeClient = null
                    wv.webViewClient = WebViewClient()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
        )
    }
}

private val attachedManagers = WeakHashMap<WebView, FocusWebViewManager>()

private class CrashGate {
    var open: Boolean = true
}
