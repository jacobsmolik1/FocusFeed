package com.focusfeed.app

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the real Instagram login URL in the production WebView configuration
 * and in a bare WebView. Records privacy-safe paint metrics and a screenshot.
 * Requires a device/emulator with network. Not a unit test.
 */
@RunWith(AndroidJUnit4::class)
class InstagramLoginPaintTest {

    @Test
    fun loginPaint_productionVsBare() {
        val instr = InstrumentationRegistry.getInstrumentation()
        val pkg = WebView.getCurrentWebViewPackage()
        Log.i(TAG, "webview ${pkg?.packageName} ${pkg?.versionName}")

        val scenario = ActivityScenario.launch(LoginPaintProbeActivity::class.java)
        scenario.onActivity { activity ->
            CookieManager.getInstance().setAcceptCookie(true)
        }

        val production = loadAndProbe(scenario, "production") { web ->
            val mgr = FocusWebViewManager(
                context = web.context,
                dest = NavDestination.INSTAGRAM_DM,
                rulesJs = "",
                onUrlChanged = {},
                onRendererGone = {},
                onDiag = {}
            )
            mgr.configure(web)
            web
        }
        val bare = loadAndProbe(scenario, "bare") { web ->
            web.webViewClient = android.webkit.WebViewClient()
            web.webChromeClient = null
            web.removeJavascriptInterface("FocusFeedBridge")
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web
        }

        Log.i(TAG, "RESULT production $production")
        Log.i(TAG, "RESULT bare $bare")

        val outDir = instr.targetContext.getExternalFilesDir(null)
        assertTrue("probe dir", outDir != null && outDir.exists())
        // The assertion is informational: both probes must return before timeout.
        assertTrue("production probe", production.contains("chars="))
        assertTrue("bare probe", bare.contains("chars="))
    }

    private fun loadAndProbe(
        scenario: ActivityScenario<LoginPaintProbeActivity>,
        name: String,
        setup: (WebView) -> WebView
    ): String {
        val instr = InstrumentationRegistry.getInstrumentation()
        val webRef = AtomicReference<WebView>()
        scenario.onActivity { activity ->
            val web = activity.web
            web.stopLoading()
            web.loadUrl("about:blank")
            setup(web)
            webRef.set(web)
            web.loadUrl(LOGIN)
        }
        SystemClock.sleep(12_000)
        val summary = evalProbe(webRef.get())
        Log.i(TAG, "PROBE $name $summary url=${onMain(webRef.get()) { it.url }} title=${onMain(webRef.get()) { it.title }} h=${onMain(webRef.get()) { it.contentHeight }}")
        val shot = instr.uiAutomation.takeScreenshot()
        if (shot != null) {
            val file = File(instr.targetContext.getExternalFilesDir(null), "login-$name.png")
            FileOutputStream(file).use { shot.compress(Bitmap.CompressFormat.PNG, 90, it) }
            Log.i(TAG, "SHOT $name ${file.absolutePath} ${shot.width}x${shot.height}")
        }
        return summary
    }

    private fun evalProbe(web: WebView): String {
        val latch = CountDownLatch(1)
        val raw = AtomicReference("timeout")
        web.post {
            web.evaluateJavascript(PagePaintProbe.JS) { value ->
                raw.set(value ?: "null")
                latch.countDown()
            }
        }
        latch.await(8, TimeUnit.SECONDS)
        return PagePaintProbe.summarize(raw.get())
    }

    private fun <T> onMain(web: WebView, block: (WebView) -> T): T {
        val latch = CountDownLatch(1)
        val out = AtomicReference<T>()
        web.post {
            out.set(block(web))
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return out.get()
    }

    companion object {
        private const val TAG = "FF_PAINT"
        private const val LOGIN = "https://www.instagram.com/accounts/login/"
    }
}
