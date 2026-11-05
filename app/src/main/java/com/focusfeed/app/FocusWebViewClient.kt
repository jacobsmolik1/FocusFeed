package com.focusfeed.app

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class FocusWebViewClient(
    private val dest: NavDestination,
    private val injectionJs: String,
    private val onUrlChanged: (String) -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject before DOM is populated to suppress flash of blocked content
        view.evaluateJavascript(injectionJs, null)
        onUrlChanged(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        // Re-inject after full load for late-rendered elements
        view.evaluateJavascript(injectionJs, null)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return false
        // Keep Instagram and YouTube inside our WebView; open everything else externally
        val allowed = listOf("instagram.com", "www.instagram.com", "youtube.com", "www.youtube.com",
            "accounts.google.com", "accounts.youtube.com")
        return host !in allowed
    }
}
