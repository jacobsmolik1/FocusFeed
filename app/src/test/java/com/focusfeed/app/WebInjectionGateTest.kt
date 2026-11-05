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
