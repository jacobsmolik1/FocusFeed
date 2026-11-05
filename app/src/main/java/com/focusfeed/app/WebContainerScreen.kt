package com.focusfeed.app

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebContainerScreen(
    dest: NavDestination,
    startUrl: String,
    injectionJs: String,
    onUrlChanged: (String) -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = webView?.canGoBack() == true) {
        webView?.goBack()
    }

    AndroidView(
        factory = { ctx ->
            WebView(ctx).also { wv ->
                webView = wv
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, false)
                wv.webViewClient = FocusWebViewClient(dest, injectionJs, onUrlChanged)
                wv.loadUrl(startUrl)
            }
        },
        update = { wv ->
            // If tab was re-selected with a different startUrl, only load if WebView is idle
            if (wv.url == null) wv.loadUrl(startUrl)
        }
    )
}
