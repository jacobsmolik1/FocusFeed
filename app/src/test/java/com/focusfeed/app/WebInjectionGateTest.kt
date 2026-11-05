package com.focusfeed.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WebInjectionGateTest {
    @Test
    fun loginUrl_neverInjects_evenWithSession() {
        val decision = WebInjectionGate.decide(
            "https://www.instagram.com/accounts/login/?next=%2Fdirect%2Finbox%2F",
            WebPlatform.INSTAGRAM,
            injectionDisabled = false,
            instagramSession = true
        )
        assertEquals(WebInjectionGate.AUTH_URL, decision)
    }

    @Test
    fun checkpointConsentAndFacebook_areAuth() {
        assertEquals(
            WebInjectionGate.AUTH_URL,
            WebInjectionGate.decide(
                "https://www.instagram.com/challenge/action/",
                WebPlatform.INSTAGRAM, false, true
            )
        )
        assertEquals(
            WebInjectionGate.AUTH_URL,
            WebInjectionGate.decide(
                "https://www.instagram.com/consent/?next=/",
                WebPlatform.INSTAGRAM, false, true
            )
        )
        assertEquals(
            WebInjectionGate.AUTH_URL,
            WebInjectionGate.decide(
                "https://m.facebook.com/login.php",
                WebPlatform.INSTAGRAM, false, true
            )
        )
        assertEquals(
            WebInjectionGate.AUTH_URL,
            WebInjectionGate.decide(
                "https://accounts.google.com/signin",
                WebPlatform.YOUTUBE, false, false
            )
        )
    }

    @Test
    fun loggedOutInbox_doesNotInject() {
        assertEquals(
            WebInjectionGate.NO_SESSION,
            WebInjectionGate.decide(
                "https://www.instagram.com/direct/inbox/",
                WebPlatform.INSTAGRAM, false, false
            )
        )
        assertEquals(
            WebInjectionGate.NO_SESSION,
            WebInjectionGate.decide(
                "https://www.instagram.com/",
                WebPlatform.INSTAGRAM, false, false
            )
        )
    }

    @Test
    fun loggedInFeedAndInbox_inject() {
        assertEquals(
            WebInjectionGate.INJECT,
            WebInjectionGate.decide(
                "https://www.instagram.com/direct/inbox/",
                WebPlatform.INSTAGRAM, false, true
            )
        )
        assertEquals(
            WebInjectionGate.INJECT,
            WebInjectionGate.decide(
                "https://www.instagram.com/",
                WebPlatform.INSTAGRAM, false, true
            )
        )
    }

    @Test
    fun toggle_disablesEvenWhenLoggedIn() {
        assertEquals(
            WebInjectionGate.DISABLED,
            WebInjectionGate.decide(
                "https://www.instagram.com/",
                WebPlatform.INSTAGRAM, true, true
            )
        )
    }

    @Test
    fun paintProbe_countsControls_withoutReadingText() {
        val raw = """{"textChars":83,"visInputs":2,"inputs":2,"visButtons":4,"bodyH":842,"innerH":842,"innerW":412,"sheets":4,"ffCss":false,"bodyBg":"rgb(255, 255, 255)"}"""
        val line = PagePaintProbe.summarize(raw)
        assertEquals(true, PagePaintProbe.looksPainted(raw))
        assertEquals(true, line.contains("in=2/2"))
        assertEquals(false, line.contains("Username"))
        assertEquals(false, PagePaintProbe.looksPainted("""{"textChars":9,"visInputs":0,"visButtons":0}"""))
    }

    @Test
    fun loadNotice_showsRecoveryOnlyForFailures() {
        assertEquals(false, LoadNotice.isLoadFailure(null))
        assertEquals(false, LoadNotice.isLoadFailure("auth-url · paint=ok · chars=83 · www.instagram.com/accounts/login/"))
        assertEquals(false, LoadNotice.isLoadFailure("no-session · paint=empty · chars=0 in=0/0"))
        assertEquals(false, LoadNotice.isLoadFailure("inject · paint=ok · chars=400"))
        assertEquals(true, LoadNotice.isLoadFailure("network-error · code= -2 · www.instagram.com/"))
        assertEquals(true, LoadNotice.isLoadFailure("http-429 · main frame · www.instagram.com/accounts/login/"))
        assertEquals(false, LoadNotice.isLoadFailure("http-200 · main frame · www.youtube.com/feed/subscriptions"))
    }

    @Test
    fun youtubeSubscriptions_stillInject() {
        assertEquals(
            WebInjectionGate.INJECT,
            WebInjectionGate.decide(
                "https://www.youtube.com/feed/subscriptions",
                WebPlatform.YOUTUBE, false, false
            )
        )
        assertEquals(
            WebInjectionGate.INJECT,
            WebInjectionGate.decide(
                "https://m.youtube.com/shorts/abc",
                WebPlatform.YOUTUBE, false, false
            )
        )
    }
}
