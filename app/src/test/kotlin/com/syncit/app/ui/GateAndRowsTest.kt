package com.syncit.app.ui

import com.syncit.app.ui.account.AuthViewModel
import com.syncit.app.ui.home.HomeViewModel
import com.syncit.app.ui.nav.Gate
import com.syncit.app.ui.nav.ProfileLoad
import com.syncit.app.ui.nav.computeGate
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
import org.junit.Assert.assertTrue
import org.junit.Test

class GateAndRowsTest {
    private val signedIn = AuthState.SignedIn("u1", "alex@example.com", null, null, SignInProvider.EMAIL_LINK)
    private val profile = UserProfile("u1", "alex", "Alex")

    @Test fun gateOrder() {
        assertEquals(Gate.Loading, computeGate(AuthState.Loading, ProfileLoad.Loading, null, false))
        assertEquals(Gate.SignedOut, computeGate(AuthState.SignedOut, ProfileLoad.Loading, "u1", true))
        assertEquals(Gate.Loading, computeGate(signedIn, ProfileLoad.Loading, "u1", true))
        assertEquals(Gate.ProfileError, computeGate(signedIn, ProfileLoad.Failed, "u1", true))
        // Email-link sign-in has no display name: suggestions seed from the address.
        assertEquals(Gate.NeedsUsername("alex"), computeGate(signedIn, ProfileLoad.Loaded(null), "u1", true))
        assertEquals(Gate.NeedsActivitySetup("u1"), computeGate(signedIn, ProfileLoad.Loaded(profile), null, true))
        // Setup done by a different account on this phone doesn't count.
        assertEquals(Gate.NeedsActivitySetup("u1"), computeGate(signedIn, ProfileLoad.Loaded(profile), "u2", true))
        assertEquals(Gate.NeedsPermissions, computeGate(signedIn, ProfileLoad.Loaded(profile), "u1", false))
        assertEquals(Gate.Ready, computeGate(signedIn, ProfileLoad.Loaded(profile), "u1", true))
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
        val watching = Activity(ActivityApp.NETFLIX, "Dune", null, null, true, null, 0L, null)
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
    }
}
