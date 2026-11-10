package com.syncit.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendLinksTest {
    private val token = "Abcdefgh2345"

    @Test fun roundTrip() =
        assertEquals(FriendLinks.Invite("riya.k", token), FriendLinks.fromLink(FriendLinks.shareLink("riya.k", token)))

    @Test fun foundInsideShareText() =
        assertEquals(FriendLinks.Invite("riya_k", token), FriendLinks.fromLink(FriendLinks.shareText("riya_k", token)))

    @Test fun appScheme() = assertEquals(FriendLinks.Invite("kabir", token), FriendLinks.fromLink("syncit://add/kabir/$token"))

    @Test fun roomLinkIsNotAFriendLink() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/join/LG8K4V"))

    @Test fun shortTokenRejected() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/add/kabir/Abc123"))

    @Test fun longerTokenRejected() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/add/kabir/${token}X"))

    @Test fun ambiguousCharactersRejected() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/add/kabir/Abcdefgh2340"))

    @Test fun uppercaseUsernameRejected() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/add/Kabir/$token"))

    @Test fun doubleDotRejected() = assertNull(FriendLinks.fromLink("https://syncit-watchparty.web.app/add/a..b/$token"))

    @Test fun otherHostRejected() = assertNull(FriendLinks.fromLink("https://evil.example/add/kabir/$token"))
}
