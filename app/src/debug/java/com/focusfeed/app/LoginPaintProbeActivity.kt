package com.focusfeed.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView

/** Debug-only full-screen WebView used by the login paint instrumentation test. */
class LoginPaintProbeActivity : Activity() {
    lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(
            web,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
}
