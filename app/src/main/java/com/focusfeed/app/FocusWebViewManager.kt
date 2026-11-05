package com.focusfeed.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Message
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
        "api.instagram.com", "graph.instagram.com",
        "cdninstagram.com", "fbcdn.net",
        "facebook.com", "www.facebook.com", "accounts.facebook.com",
        "m.facebook.com", "connect.facebook.net",
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

    private val documentStartOrigins: Set<String> get() = when (dest) {
        NavDestination.INSTAGRAM_DM,
        NavDestination.INSTAGRAM_HOME -> setOf("https://www.instagram.com", "https://instagram.com")
        NavDestination.YOUTUBE_SUBS   -> setOf("https://www.youtube.com", "https://m.youtube.com")
        NavDestination.DASHBOARD      -> emptySet()
    }

    inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view.evaluateJavascript(rulesJs, null)
            onUrlChanged(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(rulesJs, null)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase() ?: return true

            // Only allow WebView to navigate http/https — everything else must be
            // dispatched externally or blocked to prevent crashes on intent:// etc.
            if (scheme != "https" && scheme != "http") {
                if (scheme == "intent") {
                    // Android intent URL — try to start the target activity
                    runCatching {
                        val intent = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                // All other non-http schemes (market://, fb://, etc.) are blocked silently
                return true
            }

            val host = url.host?.removePrefix("www.") ?: return true
            if (allowedHosts.any { it.removePrefix("www.") == host }) return false

            // External http/https link — open in system browser
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url).apply {
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

        // Instagram's Facebook OAuth login opens in a new window via window.open().
        // Without this handler the popup silently fails and login never completes.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val popup = WebView(context).also { wv ->
                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                        // Once the OAuth popup finishes, it navigates the opener page
                        view.loadUrl(r.url.toString())
                        wv.destroy()
                        return true
                    }
                }
                wv.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(w: WebView) { w.destroy() }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }

    fun configure(webView: WebView) {
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

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.addJavascriptInterface(FocusFeedBridge(context), "FocusFeedBridge")
        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

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
