package com.focusfeed.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class FocusWebViewManager(
    private val context: Context,
    private val rulesJs: String,
    private val onUrlChanged: (String) -> Unit
) {

    private val allowedHosts = setOf(
        "instagram.com", "www.instagram.com", "i.instagram.com",
        "cdninstagram.com", "fbcdn.net",
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtu.be", "yt3.ggpht.com", "ytimg.com", "i.ytimg.com",
        "accounts.google.com", "accounts.youtube.com",
        "googleusercontent.com", "gstatic.com"
    )

    private val blockedDomainFragments = listOf(
        "analytics.google.com",
        "doubleclick.net",
        "googleadservices.com"
    )

    inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Inject early: the JS guard (window.__ff_ig_registered) prevents
            // double-registration of MutationObserver and history patches on the
            // subsequent onPageFinished call. This call gets CSS shielding in place
            // before the browser paints any blocked elements.
            view.evaluateJavascript(rulesJs, null)
            onUrlChanged(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Second injection: the guard skips re-registration; only injectCSS(),
            // sweepFeed()/sweepShorts(), and onNavigate() run again to catch any
            // late-rendered elements that weren't present at page start.
            view.evaluateJavascript(rulesJs, null)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host?.removePrefix("www.") ?: return false
            if (allowedHosts.any { it.removePrefix("www.") == host }) return false
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, request.url).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()
            if (blockedDomainFragments.any { url.contains(it) }) {
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            val scheme = request.url.scheme ?: ""
            if (scheme == "file" || scheme == "content") {
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return null
        }
    }

    inner class ChromeClient : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage): Boolean = true
    }

    fun configure(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/AP2A.240805.005) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/128.0.6613.88 Mobile Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, false)
        }

        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.addJavascriptInterface(FocusFeedBridge(context), "FocusFeedBridge")
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
    }

    fun flush() {
        CookieManager.getInstance().flush()
    }
}
