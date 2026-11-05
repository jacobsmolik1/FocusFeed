package com.focusfeed.app

/**
 * Decides whether the blocker script is allowed to run.
 *
 * Instagram login stays a plain WebView: no CSS, no observers, no history
 * patches. Blocking starts only after a session cookie exists and the URL is
 * not an auth, checkpoint, consent, or OAuth page.
 */
object WebInjectionGate {
    const val INJECT = "inject"
    const val DISABLED = "disabled"
    const val AUTH_URL = "auth-url"
    const val NO_SESSION = "no-session"
    const val FOREIGN = "foreign-host"

    fun decide(
        url: String,
        platform: WebPlatform,
        injectionDisabled: Boolean,
        instagramSession: Boolean
    ): String {
        if (injectionDisabled) return DISABLED
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return FOREIGN
        val scheme = uri.scheme?.lowercase() ?: return FOREIGN
        if (scheme != "https" && scheme != "http") return FOREIGN
        val host = uri.host?.lowercase() ?: return FOREIGN
        val path = (uri.rawPath ?: uri.path ?: "").lowercase()
        if (isAuthUrl(host, path)) return AUTH_URL
        return when (platform) {
            WebPlatform.INSTAGRAM -> when {
                !hostMatches(host, "instagram.com") -> FOREIGN
                !instagramSession -> NO_SESSION
                else -> INJECT
            }
            WebPlatform.YOUTUBE -> when {
                hostMatches(host, "youtube.com") || hostMatches(host, "youtu.be") -> INJECT
                else -> FOREIGN
            }
            WebPlatform.NONE -> FOREIGN
        }
    }

    fun isAuthUrl(host: String, path: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        if (h.startsWith("accounts.") || h.startsWith("consent.")) return true
        if (hostMatches(host, "facebook.com") || hostMatches(host, "fb.com") ||
            hostMatches(host, "facebook.net") || hostMatches(host, "messenger.com")
        ) return true
        if (h.contains("recaptcha.")) return true
        val p = path.lowercase()
        if (p.startsWith("/accounts/")) return true
        if (p.startsWith("/challenge")) return true
        if (p.startsWith("/checkpoint")) return true
        if (p.startsWith("/auth_platform")) return true
        if (p.startsWith("/consent")) return true
        if (p.startsWith("/privacy/consent")) return true
        if (p.startsWith("/login") || p.startsWith("/signin")) return true
        if (p.contains("/two_factor") || p.contains("/oauth")) return true
        return false
    }

    fun hostMatches(host: String, base: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        return h == base || h.endsWith(".$base")
    }
}

enum class WebPlatform { INSTAGRAM, YOUTUBE, NONE }
