package com.syncit.app.ui

import com.syncit.app.ui.account.AuthViewModel
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.nav.Gate
import com.syncit.app.ui.nav.ProfileLoad
import com.syncit.app.ui.nav.computeGate
import com.syncit.app.ui.social.SocialEventsViewModel
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.AuthState
import com.syncit.core.model.Friend
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.Presence
import com.syncit.core.model.SignInProvider
import com.syncit.core.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GateAndRowsTest {
    private val signedIn = AuthState.SignedIn("u1", "alex@example.com", null, null, SignInProvider.EMAIL_LINK)
    private val profile = UserProfile("u1", "alex", "Alex")

    @Test fun gateOrder() {
        val done = setOf("u1")
        assertEquals(Gate.Loading, computeGate(AuthState.Loading, ProfileLoad.Loading, emptySet(), false))
        assertEquals(Gate.SignedOut, computeGate(AuthState.SignedOut, ProfileLoad.Loading, done, true))
        assertEquals(Gate.Loading, computeGate(signedIn, ProfileLoad.Loading, done, true))
        // e.g. permission denied reading the profile: Retry, never the username picker.
        assertEquals(Gate.ProfileError, computeGate(signedIn, ProfileLoad.Failed, done, true))
        // Email-link sign-in: nothing is derived from the address.
        assertEquals(Gate.NeedsUsername(null), computeGate(signedIn, ProfileLoad.Loaded(null), done, true))
        assertEquals(Gate.NeedsActivitySetup("u1"), computeGate(signedIn, ProfileLoad.Loaded(profile), emptySet(), true))
        // Setup done by a different account on this phone doesn't count.
        assertEquals(Gate.NeedsActivitySetup("u1"), computeGate(signedIn, ProfileLoad.Loaded(profile), setOf("u2"), true))
        assertEquals(Gate.NeedsPermissions, computeGate(signedIn, ProfileLoad.Loaded(profile), done, false))
        assertEquals(Gate.Ready, computeGate(signedIn, ProfileLoad.Loaded(profile), setOf("u2", "u1"), true))
    }

    @Test fun onlyGoogleNamesSeedTheUsernamePicker() {
        val google = AuthState.SignedIn("u1", "alex@gmail.com", " Alex Sharma ", null, SignInProvider.GOOGLE)
        assertEquals(Gate.NeedsUsername("Alex Sharma"), computeGate(google, ProfileLoad.Loaded(null), emptySet(), false))
        val emailWithName = AuthState.SignedIn("u1", "alex@x.com", "Alex", null, SignInProvider.EMAIL_LINK)
        assertEquals(Gate.NeedsUsername(null), computeGate(emailWithName, ProfileLoad.Loaded(null), emptySet(), false))
    }

    @Test fun staleJoinRequests() {
        val now = 100 * 60_000L
        fun req(from: String, sentMinAgo: Long, status: JoinStatus = JoinStatus.PENDING) =
            JoinRequest("me", from, from, from, ActivityApp.NETFLIX, null, status, null, now - sentMinAgo * 60_000)
        val fresh = SocialEventsViewModel.freshPending(
            listOf(req("old", 31), req("new", 2), req("edge", 30), req("answered", 1, JoinStatus.ACCEPTED), req("mid", 10)),
            now,
        )
        // Oldest first, pending and younger than 30 min only.
        assertEquals(listOf("mid", "new"), fresh.map { it.fromUid })
        assertTrue(SocialEventsViewModel.isStale(now - 30 * 60_000, now))
        assertFalse(SocialEventsViewModel.isStale(now - 29 * 60_000, now))
    }

    @Test fun emailValidation() {
        assertTrue(AuthViewModel.isValidEmail("a@b.co"))
        assertTrue(AuthViewModel.isValidEmail(" riya.sen+tv@gmail.com "))
        assertFalse(AuthViewModel.isValidEmail("riya"))
        assertFalse(AuthViewModel.isValidEmail("riya@gmail"))
        assertFalse(AuthViewModel.isValidEmail("ri ya@gmail.com"))
    }

    private fun friend(uid: String, name: String, presence: Presence?) = Friend(UserProfile(uid, uid, name), 0L, presence)

    @Test fun rowsSortActiveThenOnlineThenRecentlySeen() {
        val now = 10_000_000L
        val watching = Activity(ActivityApp.NETFLIX, "Dune", null, null, true, null, 0L, false)
        val rows = HomeViewModel.buildRows(
            friends = listOf(
                friend("old", "Zed", Presence(false, now - 3_600_000, null)),
                friend("online", "Bea", Presence(true, now, null)),
                friend("recent", "Yan", Presence(false, now - 60_000, null)),
                friend("active", "Cal", Presence(true, now, watching)),
                friend("never", "Abe", null),
            ),
            unread = mapOf("online" to 2),
            myRequests = listOf(
                JoinRequest("active", "me", "me", "Me", ActivityApp.NETFLIX, "Dune", JoinStatus.PENDING, null, now),
                JoinRequest("online", "me", "me", "Me", ActivityApp.NETFLIX, null, JoinStatus.DECLINED, null, now),
            ),
            nowMs = now,
        )
        assertEquals(listOf("active", "online", "recent", "old", "never"), rows.map { it.uid })
        assertEquals("Watching Dune on Netflix", rows[0].status)
        assertTrue(rows[0].joinRequested)
        assertFalse(rows[1].joinRequested)
        assertEquals(2, rows[1].unread)
        assertEquals("Last seen 1m ago", rows[2].status)
        assertEquals("Asked to join", rows[0].askLabel)
        assertFalse(rows[0].isJam)
        // Nothing playing: nothing to join.
        assertNull(rows[1].askLabel)
    }

    @Test fun musicAndYouTubeFriendsGetAskToJam() {
        val now = 10_000_000L
        val song = Activity(ActivityApp.SPOTIFY, "Blinding Lights", "The Weeknd", null, true, null, 0L, false)
        val video = Activity(ActivityApp.YOUTUBE, "Lo-fi beats", null, null, false, null, 0L, true)
        val rows = HomeViewModel.buildRows(
            friends = listOf(
                friend("riya", "Riya", Presence(true, now, song)),
                friend("sam", "Sam", Presence(true, now, video)),
            ),
            unread = emptyMap(),
            myRequests = listOf(
                JoinRequest("sam", "me", "me", "Me", ActivityApp.YOUTUBE, "Lo-fi beats", JoinStatus.PENDING, null, now),
            ),
            nowMs = now,
        ).associateBy { it.uid }
        assertTrue(rows.getValue("riya").isJam)
        assertEquals("Ask to jam", rows.getValue("riya").askLabel)
        assertEquals("Listening to Blinding Lights on Spotify", rows.getValue("riya").status)
        assertTrue(rows.getValue("sam").isJam)
        assertEquals("Asked to jam", rows.getValue("sam").askLabel)
    }
}
