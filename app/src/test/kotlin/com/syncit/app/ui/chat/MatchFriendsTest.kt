package com.syncit.app.ui.chat

import com.syncit.core.model.Friend
import com.syncit.core.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class MatchFriendsTest {

    private fun friend(name: String, username: String) = Friend(UserProfile(uid = username, username = username, displayName = name), since = 0L, presence = null)

    private val harsh = friend("Harsh", "harsh_k")
    private val somesh = friend("Somesh Barik", "someshb")
    private val soumya = friend("Soumyaditya Roy", "soumya")
    private val all = listOf(somesh, harsh, soumya)

    @Test fun blankQueryMatchesNothing() = assertEquals(emptyList<Friend>(), matchFriends(all, "  "))

    @Test fun matchesNameOrUsernameIgnoringCase() {
        assertEquals(listOf(harsh), matchFriends(all, "HAR"))
        assertEquals(listOf(somesh), matchFriends(all, "barik"))
        assertEquals(listOf(somesh), matchFriends(all, "@someshb"))
    }

    @Test fun startsWithComesFirstThenByName() =
        assertEquals(listOf(somesh, soumya, harsh), matchFriends(all, "s"))
}
