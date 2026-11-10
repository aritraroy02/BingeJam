package com.syncit.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupPermissionsTest {
    private fun group(onlyAdminsSend: Boolean = false, onlyAdminsEdit: Boolean = false) = Group(
        id = "g",
        name = "Valorant",
        createdBy = "creator",
        createdAt = 1L,
        members = listOf(
            GroupMember("creator", admin = true, joinedAt = 1L),
            GroupMember("admin2", admin = true, joinedAt = 2L),
            GroupMember("member", admin = false, joinedAt = 3L),
        ),
        onlyAdminsSend = onlyAdminsSend,
        onlyAdminsEdit = onlyAdminsEdit,
    )

    @Test fun everyoneSendsByDefault() = assertTrue(group().canSend("member"))

    @Test fun onlyAdminsSendBlocksMembers() {
        val g = group(onlyAdminsSend = true)
        assertFalse(g.canSend("member"))
        assertTrue(g.canSend("admin2"))
    }

    @Test fun onlyAdminsEditBlocksMembers() {
        val g = group(onlyAdminsEdit = true)
        assertFalse(g.canEditInfo("member"))
        assertTrue(g.canEditInfo("creator"))
    }

    @Test fun outsidersCanDoNothing() {
        assertFalse(group().canSend("stranger"))
        assertFalse(group().canEditInfo("stranger"))
    }

    @Test fun adminManagesMembersAndOtherAdmins() {
        assertTrue(group().canManage("admin2", "member"))
        assertTrue(group().canManage("creator", "admin2"))
    }

    @Test fun nobodyManagesTheCreator() = assertFalse(group().canManage("admin2", "creator"))

    @Test fun membersManageNobody() = assertFalse(group().canManage("member", "admin2"))

    @Test fun noOneManagesThemselves() = assertFalse(group().canManage("admin2", "admin2"))
}
