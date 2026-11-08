package com.syncit.app.ui.chat

import com.syncit.core.model.DirectMessage
import com.syncit.core.model.Friend
import com.syncit.core.model.Group
import com.syncit.core.model.GroupMember
import com.syncit.core.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRowMenuTest {

    private val dm = ChatEntry.Dm(
        ChatRow(Friend(UserProfile("u1", "harsh_k", "Harsh"), 0L, null), DirectMessage("m1", "u1", "hi", 10L), unread = 0),
    )

    private fun group(members: Int) = ChatEntry.Grp(
        GroupRow(
            Group("g1", "Movie Night", "me", 1L, (0 until members).map { GroupMember("u$it", admin = it == 0, joinedAt = it.toLong()) }),
            last = null,
            unread = 0,
            inRoom = 0,
        ),
    )

    @Test fun oneToOneChatsGetViewProfileThenDeleteChat() =
        assertEquals(listOf(ChatRowAction.VIEW_PROFILE, ChatRowAction.DELETE_CHAT), chatRowActions(dm))

    @Test fun groupsGetGroupInfoThenLeaveGroup() {
        assertEquals(listOf(ChatRowAction.GROUP_INFO, ChatRowAction.LEAVE_GROUP), chatRowActions(group(3)))
        assertEquals(listOf(ChatRowAction.GROUP_INFO, ChatRowAction.LEAVE_GROUP), chatRowActions(group(1)))
    }

    @Test fun menuLabels() {
        assertEquals("View profile", ChatRowAction.VIEW_PROFILE.label)
        assertEquals("Delete chat", ChatRowAction.DELETE_CHAT.label)
        assertEquals("Group info", ChatRowAction.GROUP_INFO.label)
        assertEquals("Leave group", ChatRowAction.LEAVE_GROUP.label)
    }

    @Test fun createLabelCountsUsOnlyOncePeopleArePicked() {
        assertEquals("Create group", GroupWording.createLabel(0))
        assertEquals("Create group · 2 people", GroupWording.createLabel(1))
        assertEquals("Create group · 4 people", GroupWording.createLabel(3))
    }

    @Test fun aNameIsAllAGroupNeeds() {
        assertTrue(GroupWording.canCreate("Movie Night", creating = false))
        assertTrue(GroupWording.canCreate("x".repeat(50), creating = false))
        assertFalse(GroupWording.canCreate("   ", creating = false))
        assertFalse(GroupWording.canCreate("", creating = false))
        assertFalse(GroupWording.canCreate("Movie Night", creating = true))
    }

    @Test fun headerSaysJustYouInAGroupOfOne() {
        assertEquals("Just you · add people", GroupWording.subtitle(1, null))
        assertEquals("Just you · add people", GroupWording.subtitle(1, 1))
        assertEquals("3 members", GroupWording.subtitle(3, null))
        assertEquals("3 members · 2 in the room", GroupWording.subtitle(3, 2))
    }
}
