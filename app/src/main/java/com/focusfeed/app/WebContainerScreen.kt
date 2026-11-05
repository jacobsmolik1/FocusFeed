package com.focusfeed.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    var diag by remember { mutableStateOf<String?>(null) }
    val latestUrl = remember(dest) { mutableStateOf(startUrl) }
    val crashGate = remember { CrashGate() }
    val showRecovery = LoadNotice.isLoadFailure(diag)

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Box(modifier) {
        key(generation) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                        },
                        onDiag = { line ->
                            Handler(Looper.getMainLooper()).post { diag = line }
                        }
                    )
                    WebView(ctx).also { wv ->
                        // Keep MATCH_PARENT. Without it, Instagram's percentage-height
                        // shell reports a full content height and draws blank in Compose.
                        wv.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
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

        // Overlay only. When this is absent it does not reserve space under the page.
        if (showRecovery) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Couldn't load this page",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Check your connection, then try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { webViewRef?.reload() }) { Text("Reload") }
                        TextButton(onClick = {
                            val wv = webViewRef ?: return@TextButton
                            CookieManager.getInstance().removeAllCookies {
                                FocusWebViewManager.flushCookiesAsync()
                                wv.post { wv.loadUrl(startUrl) }
                            }
                            WebStorage.getInstance().deleteAllData()
                            wv.clearCache(true)
                        }) { Text("Clear session") }
                    }
                }
            }
        }
    }
}

private val attachedManagers = WeakHashMap<WebView, FocusWebViewManager>()

private class CrashGate {
    var open: Boolean = true
}
