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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class FocusWebViewManager(
    private val context: Context,
    private val dest: NavDestination,
    private val rulesJs: String,
    private val onUrlChanged: (String) -> Unit
) {

    private val allowedHosts = setOf(
        // Instagram / Meta
        "instagram.com", "www.instagram.com", "i.instagram.com",
        "cdninstagram.com", "fbcdn.net",
        "facebook.com", "www.facebook.com", "accounts.facebook.com",
        "meta.com", "www.meta.com",
        // YouTube / Google
        "youtube.com", "www.youtube.com", "m.youtube.com",
        "youtu.be", "yt3.ggpht.com", "ytimg.com", "i.ytimg.com",
        "accounts.google.com", "accounts.youtube.com",
        "googleusercontent.com", "gstatic.com", "googleapis.com"
    )

    private val blockedDomainFragments = listOf(
        "analytics.google.com",
        "doubleclick.net",
        "googleadservices.com"
    )

    // Origins for document-start JS injection (platform-specific)
    private val documentStartOrigins: Set<String> get() = when (dest) {
        NavDestination.INSTAGRAM_DM,
        NavDestination.INSTAGRAM_HOME -> setOf("https://www.instagram.com", "https://instagram.com")
        NavDestination.YOUTUBE_SUBS   -> setOf("https://www.youtube.com", "https://m.youtube.com")
        NavDestination.DASHBOARD      -> emptySet()
    }

    inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Fallback injection for devices where DOCUMENT_START_SCRIPT isn't supported
            view.evaluateJavascript(rulesJs, null)
            onUrlChanged(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            // Second pass: guard in JS prevents double-registration of observers/patches
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
        // Build UA from the device's actual WebView string, stripping the "; wv" marker.
        // Instagram and YouTube detect "; wv" and serve a degraded / login-broken UI.
        val baseUa = WebSettings.getDefaultUserAgent(context)
        val cleanUa = baseUa
            .replace("; wv", "")
            .replace(Regex(" Version/[\\d.]+ "), " ")

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
            mediaPlaybackRequiresUserGesture = false
            userAgentString = cleanUa
        }

        // Instagram login and session require cross-domain cookies (Meta CDN, fbcdn.net)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.addJavascriptInterface(FocusFeedBridge(context), "FocusFeedBridge")
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        // addDocumentStartJavaScript runs before ANY page JS — the gold standard.
        // The fallback onPageStarted + onPageFinished path above covers older WebViews.
        val origins = documentStartOrigins
        if (origins.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addDocumentStartJavaScript(webView, rulesJs, origins)
        }
    }

    fun flush() {
        CookieManager.getInstance().flush()
    }
}
