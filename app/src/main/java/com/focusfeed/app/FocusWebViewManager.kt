package com.focusfeed.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

class FocusWebViewManager(
    private val context: Context,
    private val dest: NavDestination,
    private val rulesJs: String,
    private val onUrlChanged: (String) -> Unit,
    private val onRendererGone: () -> Unit = {}
) {

    // Suffix-based: any subdomain of these base domains is allowed in-WebView.
    // This covers accounts.instagram.com, scontent-*.cdninstagram.com, OAuth, reCAPTCHA.
    private val allowedBaseDomains = setOf(
        // Instagram / Meta
        "instagram.com", "cdninstagram.com", "fbcdn.net",
        "facebook.com", "facebook.net", "fb.com", "fbsbx.com",
        "messenger.com",
        "meta.com", "threads.net",
        // YouTube / Google
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "ggpht.com", "ytimg.com", "googlevideo.com",
        "google.com", "googleusercontent.com", "gstatic.com", "googleapis.com",
        "recaptcha.net",
        // Sign in with Apple (Instagram offers it on the web login)
        "apple.com"
    )

    private val blockedDomainFragments = listOf(
        "analytics.google.com",
        "doubleclick.net",
        "googleadservices.com"
    )

    private var cleanedUserAgent: String? = null
    private var mainWebView: WebView? = null
    private var activeDialog: Dialog? = null
    private var activePopup: WebView? = null
    private var authFlowStarted = false
    private var handedOff = false
    private var popupVisible = false

    private fun normalizedHost(host: String): String =
        host.lowercase().removePrefix("www.")

    private fun matchesBase(host: String, base: String): Boolean {
        val h = normalizedHost(host)
        return h == base || h.endsWith(".$base")
    }

    private fun isAllowedHost(host: String): Boolean =
        allowedBaseDomains.any { base -> matchesBase(host, base) }

    private fun isInstagramHost(host: String): Boolean = matchesBase(host, "instagram.com")

    private fun isYouTubeHost(host: String): Boolean =
        matchesBase(host, "youtube.com") || matchesBase(host, "youtu.be")

    /** Product pages we inject rules into. OAuth and consent hosts are excluded. */
    private fun shouldInjectRules(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        val bare = normalizedHost(host)
        if (bare.startsWith("accounts.") || bare.startsWith("consent.")) return false
        return when (dest) {
            NavDestination.INSTAGRAM_DM,
            NavDestination.INSTAGRAM_HOME -> isInstagramHost(host)
            NavDestination.YOUTUBE_SUBS -> isYouTubeHost(host)
            NavDestination.DASHBOARD -> false
        }
    }

    private fun isProductHost(host: String): Boolean = when (dest) {
        NavDestination.INSTAGRAM_DM,
        NavDestination.INSTAGRAM_HOME -> isInstagramHost(host)
        NavDestination.YOUTUBE_SUBS -> isYouTubeHost(host)
        NavDestination.DASHBOARD -> false
    }

    private fun isAuthProviderHost(host: String): Boolean {
        val bases = listOf(
            "facebook.com", "fb.com", "facebook.net", "messenger.com",
            "google.com", "recaptcha.net", "apple.com", "meta.com"
        )
        return bases.any { matchesBase(host, it) }
    }

    private fun findActivity(ctx: Context): Activity? {
        var current: Context? = ctx
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    private fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null
        if (blockedDomainFragments.any { url.contains(it) }) return emptyResponse()
        val scheme = request.url.scheme?.lowercase() ?: return null
        if (scheme == "file" || scheme == "content") return emptyResponse()
        return null
    }

    /**
     * intent:// used to be handed to the system, which opens the official
     * Instagram/YouTube app and abandons the WebView (the page behind it looks
     * blank when the user returns). Keep http(s) fallbacks inside the WebView.
     */
    private fun handleIntentUri(view: WebView, raw: String) {
        runCatching {
            val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
            val fallback = intent.getStringExtra("browser_fallback_url")
            val candidate = listOfNotNull(fallback, intent.dataString).firstOrNull {
                it.startsWith("https://") || it.startsWith("http://")
            } ?: run {
                Log.d("FF_NAV", "INTENT consumed (no http fallback)")
                return
            }
            val host = Uri.parse(candidate).host
            if (host != null && isAllowedHost(host)) {
                Log.d("FF_NAV", "INTENT→WEBVIEW $candidate")
                view.loadUrl(candidate)
            } else {
                Log.d("FF_NAV", "INTENT consumed (external app suppressed) $candidate")
            }
        }.onFailure {
            Log.w("FF_NAV", "INTENT parse failed: $raw")
        }
    }

    private fun applyCommonSettings(webView: WebView) {
        val ua = cleanedUserAgent ?: WebSettings.getDefaultUserAgent(context)
            .replace("; wv", "")
            .replace(Regex(" Version/[\\d.]+ "), " ")
            .also { cleanedUserAgent = it }

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
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = ua
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    private fun dismissPopup(reloadAfterAuth: Boolean) {
        val dialog = activeDialog
        val popup = activePopup
        val reload = reloadAfterAuth && authFlowStarted && !handedOff
        activeDialog = null
        activePopup = null
        if (reload) handedOff = true
        if (popup != null) {
            popup.webChromeClient = null
            runCatching { popup.stopLoading() }
        }
        if (dialog != null) {
            dialog.setOnDismissListener(null)
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
        if (popup != null) {
            runCatching { popup.destroy() }
        }
        if (reload) {
            flushCookiesAsync()
            mainWebView?.reload()
        }
    }

    private fun handOffToMain(url: String) {
        if (handedOff) return
        handedOff = true
        val main = mainWebView ?: return
        main.post {
            flushCookiesAsync()
            dismissPopup(reloadAfterAuth = false)
            main.loadUrl(url)
        }
    }

    /** Close any OAuth dialog. Safe to call more than once. */
    fun release() {
        handedOff = true
        dismissPopup(reloadAfterAuth = false)
    }

    inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            Log.d("FF_NAV", "START: $url")
            if (shouldInjectRules(url)) {
                view.evaluateJavascript(rulesJs, null)
            } else {
                Log.d("FF_NAV", "SKIP_INJECT $url")
            }
            onUrlChanged(url)
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            Log.d("FF_NAV", "FINISH: $url")
            flushCookiesAsync()
            if (shouldInjectRules(url)) {
                view.evaluateJavascript(rulesJs, null)
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            Log.e("FF_NAV", "ERROR ${error.errorCode} '${error.description}' at ${request.url}")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e("FF_NAV", "renderer gone crashed=${detail.didCrash()}")
            onRendererGone()
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Subframes (reCAPTCHA, video, CDN) must not be bounced to the system browser.
            if (!request.isForMainFrame) {
                val scheme = request.url.scheme?.lowercase() ?: return true
                return scheme != "https" && scheme != "http" &&
                    scheme != "about" && scheme != "blob" && scheme != "data"
            }

            val url = request.url ?: return true
            val scheme = url.scheme?.lowercase() ?: return true

            if (scheme == "intent") {
                handleIntentUri(view, url.toString())
                return true
            }

            // Only allow WebView to navigate http/https — everything else must be
            // blocked so WebView does not throw on market://, fb://, etc.
            if (scheme != "https" && scheme != "http") {
                Log.d("FF_NAV", "BLOCK SCHEME $url")
                return true
            }

            val host = url.host ?: return true
            if (isAllowedHost(host)) {
                Log.d("FF_NAV", "ALLOW: $url")
                return false
            }

            Log.w("FF_NAV", "BLOCK→BROWSER: $url")
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
        ): WebResourceResponse? = intercept(request)
    }

    inner class ChromeClient : WebChromeClient() {

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            if (msg.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                msg.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                Log.w("FF_JS", "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
            }
            return true
        }

        override fun onCloseWindow(window: WebView) {
            if (window == activePopup) {
                val reload = authFlowStarted && !handedOff
                mainWebView?.post { dismissPopup(reloadAfterAuth = reload) }
            } else {
                runCatching { window.destroy() }
            }
        }

        // Instagram Facebook login and YouTube Google login call window.open().
        // The first URL is often about:blank. Loading that into the opener blanks
        // the WebView, and destroying the popup immediately drops the OAuth page.
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            dismissPopup(reloadAfterAuth = false)
            authFlowStarted = false
            handedOff = false
            popupVisible = false

            val activity = findActivity(context)
            val popup = WebView(activity ?: context)
            applyCommonSettings(popup)
            popup.webViewClient = PopupClient()
            popup.webChromeClient = this

            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            transport.webView = popup
            resultMsg.sendToTarget()

            if (activity == null) {
                Log.e("FF_NAV", "OAuth popup has no Activity; leaving it hidden rather than navigating the opener to about:blank")
                activePopup = popup
                return true
            }

            val dialog = Dialog(activity).apply {
                setTitle("Sign in")
                setCancelable(true)
                setContentView(popup)
                setOnDismissListener {
                    if (activeDialog === this) dismissPopup(reloadAfterAuth = true)
                }
            }
            activeDialog = dialog
            activePopup = popup
            runCatching {
                dialog.show()
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                popupVisible = dialog.isShowing
            }.onFailure { error ->
                Log.e("FF_NAV", "Failed to show OAuth dialog", error)
                popupVisible = false
            }
            return true
        }
    }

    private inner class PopupClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val uri = request.url ?: return true
            val scheme = uri.scheme?.lowercase() ?: return true
            if (scheme == "about" || scheme == "javascript" || scheme == "blob" || scheme == "data") {
                // Never forward about:blank into the opener. That is a blank page.
                return false
            }
            if (!popupVisible && (scheme == "http" || scheme == "https")) {
                val host = uri.host
                if (host != null && isAllowedHost(host)) {
                    Log.w("FF_NAV", "POPUP hidden, handoff $uri")
                    handOffToMain(uri.toString())
                } else {
                    Log.w("FF_NAV", "POPUP hidden, block $uri")
                }
                return true
            }
            if (scheme == "intent") {
                handleIntentUri(mainWebView ?: view, uri.toString())
                return true
            }
            if (scheme != "http" && scheme != "https") return true
            val host = uri.host ?: return true
            if (!isAllowedHost(host)) {
                Log.w("FF_NAV", "POPUP BLOCK $uri")
                return true
            }
            if (isProductHost(host)) {
                Log.d("FF_NAV", "POPUP HANDOFF $uri")
                handOffToMain(uri.toString())
                return true
            }
            if (isAuthProviderHost(host)) authFlowStarted = true
            Log.d("FF_NAV", "POPUP ALLOW $uri")
            return false
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? = intercept(request)
    }

    fun configure(webView: WebView) {
        mainWebView = webView
        applyCommonSettings(webView)
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.addJavascriptInterface(FocusFeedBridge(context), "FocusFeedBridge")

        // addDocumentStartJavaScript stays disabled. It injected before Instagram
        // created a document and left login / DM as a blank page. onPageStarted
        // and onPageFinished are the only injection points.
    }

    fun flush() = flushCookiesAsync()

    companion object {
        private val cookieIo = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ff-cookie-flush").apply { isDaemon = true }
        }

        /** CookieManager.flush() writes disk and must not run on the UI thread. */
        fun flushCookiesAsync() {
            cookieIo.execute {
                runCatching { CookieManager.getInstance().flush() }
            }
        }
    }
}
