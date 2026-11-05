package com.focusfeed.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
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

/**
 * One WebView, no popup window, no script until Instagram auth is finished.
 *
 * onPageStarted does not call evaluateJavascript. Doing that on
 * www.instagram.com/accounts/login (the previous build did) runs the blocker
 * before the login document exists and leaves a white page. window.open is
 * ignored so an OAuth popup cannot replace this view with about:blank.
 */
class FocusWebViewManager(
    private val context: Context,
    private val dest: NavDestination,
    private val rulesJs: String,
    private val onUrlChanged: (String) -> Unit,
    private val onRendererGone: () -> Unit = {},
    private val onDiag: (String) -> Unit = {}
) {

    private val allowedBaseDomains = setOf(
        "instagram.com", "cdninstagram.com", "fbcdn.net",
        "facebook.com", "facebook.net", "fb.com", "fbsbx.com",
        "messenger.com",
        "meta.com", "threads.net",
        "youtube.com", "youtu.be", "youtube-nocookie.com",
        "ggpht.com", "ytimg.com", "googlevideo.com",
        "google.com", "googleusercontent.com", "gstatic.com", "googleapis.com",
        "recaptcha.net",
        "apple.com"
    )

    private val blockedDomainFragments = listOf(
        "analytics.google.com",
        "doubleclick.net",
        "googleadservices.com"
    )

    private val handler = Handler(Looper.getMainLooper())
    private var cleanedUserAgent: String? = null
    private var mainWebView: WebView? = null
    private var pageReady = false
    private var loggedInjectUrl: String? = null
    private var lastDecision: String? = null
    private var blankToken = 0

    private val poll = Runnable { pollInject() }

    private fun platform(): WebPlatform = when (dest) {
        NavDestination.INSTAGRAM_DM, NavDestination.INSTAGRAM_HOME -> WebPlatform.INSTAGRAM
        NavDestination.YOUTUBE_SUBS -> WebPlatform.YOUTUBE
        NavDestination.DASHBOARD -> WebPlatform.NONE
    }

    private fun hostMatches(host: String, base: String): Boolean =
        WebInjectionGate.hostMatches(host, base)

    private fun isAllowedHost(host: String): Boolean =
        allowedBaseDomains.any { base -> hostMatches(host, base) }

    private fun hasInstagramSession(): Boolean {
        val jar = CookieManager.getInstance().getCookie("https://www.instagram.com")
            ?: CookieManager.getInstance().getCookie("https://instagram.com")
            ?: return false
        return jar.split(';').any { part ->
            val token = part.trim()
            if (!token.startsWith("sessionid=")) return@any false
            val value = token.substringAfter("sessionid=").trim().trim('"')
            value.isNotEmpty() && value != "deleted"
        }
    }

    private fun decide(url: String): String = WebInjectionGate.decide(
        url = url,
        platform = platform(),
        injectionDisabled = FocusPrefs.isInjectionDisabled(context),
        instagramSession = hasInstagramSession()
    )

    private fun publish(url: String, decision: String, extra: String) {
        val line = "$decision · $extra · $url"
        Log.i("FF_NAV", "DECISION $line")
        Log.i("FF_BLANK", line)
        if (lastDecision == line) return
        lastDecision = line
        onDiag(line)
    }

    private fun maybeInject(view: WebView) {
        val url = view.url ?: return
        val decision = decide(url)
        val height = view.contentHeight
        val title = view.title ?: ""
        publish(url, decision, "h=$height title=${title.take(40)}")
        if (decision != WebInjectionGate.INJECT) {
            loggedInjectUrl = null
            return
        }
        // Re-run is idempotent. Repeating it lets a login wall on "/" pick up
        // the blocker only after the password form is gone.
        if (loggedInjectUrl != url) {
            loggedInjectUrl = url
            Log.i("FF_NAV", "INJECT $url")
        }
        view.evaluateJavascript(rulesJs, null)
    }

    private fun pollInject() {
        val view = mainWebView ?: return
        if (pageReady) maybeInject(view)
        handler.postDelayed(poll, 2000)
    }

    private fun scheduleBlankCheck(view: WebView) {
        val token = ++blankToken
        val url = view.url ?: return
        handler.postDelayed({
            if (token != blankToken) return@postDelayed
            val live = mainWebView ?: return@postDelayed
            if (live !== view) return@postDelayed
            maybeInject(live)
            val height = live.contentHeight
            val decision = decide(live.url ?: url)
            if (height in 0..40) {
                val line = "$decision · BLANK h=$height title=${live.title ?: ""} · ${live.url}"
                Log.i("FF_BLANK", line)
                lastDecision = null
                publish(live.url ?: url, decision, "BLANK h=$height title=${(live.title ?: "").take(40)}")
            }
        }, 2000)
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

    private fun handleIntentUri(view: WebView, raw: String) {
        runCatching {
            val intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME)
            val fallback = intent.getStringExtra("browser_fallback_url")
            val candidate = listOfNotNull(fallback, intent.dataString).firstOrNull {
                it.startsWith("https://") || it.startsWith("http://")
            } ?: run {
                Log.i("FF_NAV", "INTENT consumed (no http fallback)")
                return
            }
            val host = Uri.parse(candidate).host
            if (host != null && isAllowedHost(host)) {
                Log.i("FF_NAV", "INTENT→WEBVIEW $candidate")
                view.loadUrl(candidate)
            } else {
                Log.i("FF_NAV", "INTENT consumed (external app suppressed) $candidate")
            }
        }.onFailure {
            Log.w("FF_NAV", "INTENT parse failed: $raw")
        }
    }

    private fun applySettings(webView: WebView) {
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
            // Popups were stealing Instagram login into a second WebView and
            // leaving this one white. Same-tab navigation only.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            userAgentString = ua
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        mainWebView = null
    }

    inner class Client : WebViewClient() {

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            pageReady = false
            blankToken++
            Log.i("FF_NAV", "START $url decision=${decide(url)} (script withheld until finish)")
            onUrlChanged(url)
            publish(url, decide(url), "loading")
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            pageReady = true
            flushCookiesAsync()
            Log.i("FF_NAV", "FINISH $url title=${view.title} h=${view.contentHeight}")
            maybeInject(view)
            scheduleBlankCheck(view)
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val line = "network-error ${error.errorCode} ${error.description} ${request.url}"
            Log.e("FF_BLANK", line)
            lastDecision = null
            publish(request.url.toString(), "network-error", error.description?.toString() ?: "error")
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse
        ) {
            if (!request.isForMainFrame) return
            Log.e("FF_BLANK", "HTTP ${errorResponse.statusCode} ${request.url}")
            lastDecision = null
            publish(request.url.toString(), "http-${errorResponse.statusCode}", "main frame")
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.e("FF_BLANK", "renderer gone crashed=${detail.didCrash()}")
            onRendererGone()
            return true
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) {
                val scheme = request.url?.scheme?.lowercase() ?: return true
                return scheme != "https" && scheme != "http" &&
                    scheme != "about" && scheme != "blob" && scheme != "data"
            }

            val url = request.url ?: return true
            val scheme = url.scheme?.lowercase() ?: return true
            if (scheme == "intent") {
                handleIntentUri(view, url.toString())
                return true
            }
            if (scheme != "https" && scheme != "http") {
                Log.i("FF_NAV", "BLOCK SCHEME $url")
                return true
            }
            val host = url.host ?: return true
            if (isAllowedHost(host)) {
                Log.i("FF_NAV", "ALLOW $url")
                return false
            }
            Log.w("FF_NAV", "BLOCK→BROWSER $url")
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
                msg.messageLevel() == ConsoleMessage.MessageLevel.WARNING
            ) {
                Log.w("FF_JS", "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
            }
            return true
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            // Never attach a second WebView. Instagram login calls window.open
            // (often about:blank) and the visible page stays white if we take it.
            Log.i("FF_BLANK", "ignored window.open so login stays in this WebView")
            return false
        }
    }

    fun configure(webView: WebView) {
        mainWebView = webView
        applySettings(webView)
        webView.webViewClient = Client()
        webView.webChromeClient = ChromeClient()
        webView.addJavascriptInterface(FocusFeedBridge(context), "FocusFeedBridge")
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 1500)
        // addDocumentStartJavaScript stays off. It blanked login.
    }

    companion object {
        private val cookieIo = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ff-cookie-flush").apply { isDaemon = true }
        }

        fun flushCookiesAsync() {
            cookieIo.execute {
                runCatching { CookieManager.getInstance().flush() }
            }
        }
    }
}
