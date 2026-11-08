package com.syncit.app.ui.chat

import com.syncit.core.social.GroupChats

/** One item in a Chats row's long-press menu. */
enum class ChatRowAction(val label: String) {
    VIEW_PROFILE("View profile"),
    DELETE_CHAT("Delete chat"),
    GROUP_INFO("Group info"),

    /** Groups have no "delete chat for me" (no such data path), so the second item is Leave group. */
    LEAVE_GROUP("Leave group"),
}

/** Long-press menu items for a row in the Chats list (user's request, 2026-11-08). */
internal fun chatRowActions(entry: ChatEntry): List<ChatRowAction> = when (entry) {
    is ChatEntry.Dm -> listOf(ChatRowAction.VIEW_PROFILE, ChatRowAction.DELETE_CHAT)
    is ChatEntry.Grp -> listOf(ChatRowAction.GROUP_INFO, ChatRowAction.LEAVE_GROUP)
}

/** Groups can be created with nobody but us and filled later (user's request, 2026-11-08). */
internal object GroupWording {
    /** The New group button: "Create group", or "Create group · 3 people" (us included) once friends are picked. */
    fun createLabel(picked: Int): String = if (picked <= 0) "Create group" else "Create group · ${picked + 1} people"

    /** A name (1–50 characters after cleaning) is all that's needed; picking friends is optional. */
    fun canCreate(name: String, creating: Boolean): Boolean = !creating && GroupChats.cleanName(name) != null

    /**
     * Group chat header line under the name; [inRoom] = people in its room, null when there's no
     * room. A group of one says so (the header's Join/Open room button already shows a live room).
     */
    fun subtitle(members: Int, inRoom: Int?): String = when {
        members <= 1 -> "Just you · add people"
        inRoom != null -> "$members members · $inRoom in the room"
        else -> "$members members"
    }
}
