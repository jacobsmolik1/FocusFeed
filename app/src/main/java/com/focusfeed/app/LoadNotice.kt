package com.focusfeed.app

/**
 * Which diagnostic lines deserve a user-facing recovery card.
 * Auth, no-session, and paint probes stay in logcat only.
 */
object LoadNotice {
    fun isLoadFailure(line: String?): Boolean {
        if (line.isNullOrBlank()) return false
        if (line.startsWith("network-error")) return true
        if (!line.startsWith("http-")) return false
        val code = line.removePrefix("http-").takeWhile { it.isDigit() }.toIntOrNull() ?: return false
        return code >= 400
    }
}
