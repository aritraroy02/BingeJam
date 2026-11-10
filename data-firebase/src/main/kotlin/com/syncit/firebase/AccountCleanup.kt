package com.syncit.firebase

import com.syncit.firebase.social.DmIds

/**
 * Which database paths account deletion clears, as one multi-path update of nulls (atomic: all
 * or nothing). Pure Kotlin so it's unit-tested; [FirebaseAccountEraser] does the reads and the
 * write. Every path here is allowed for the deleting user by database.rules.json, and the rules
 * emulator test replays exactly this shape ("account deletion" section).
 *
 * - [OWN_ROOTS]: `{root}/{uid}` deleted as a whole node. The rules allow the owner to set these
 *   to null (and only to null) at the parent level.
 * - `usernames/{key}`: only when the caller checked it still points at us (rules: data === uid).
 * - For every related uid: their entries that name us ([MIRROR_ROOTS], `{root}/{other}/{uid}`),
 *   the DM thread `dms/{dmId}` and its typing indicators `typing/{dmId}` ([THREAD_ROOTS]; rules:
 *   participant only, and only while our own profile is gone in the same update, i.e. only as
 *   part of account deletion).
 *
 * Deleting a path that doesn't exist is fine, so a retry after a fresh sign-in completes cleanly.
 */
internal object AccountCleanup {

    /** The user's own subtrees, keyed by their uid. */
    val OWN_ROOTS = listOf(
        "users", "users_private", "presence", "pushTokens",
        "friends", "friendRequests", "sentFriendRequests", "blocked", "dmReads", "dmHidden", "joinRequests", "invites",
        "dmCleared", "userGroups", "groupReads", "groupInvites", "blockedGroups",
    )

    /** Owner-readable lists whose keys are uids we're connected to (friends, requests, blocks, …). */
    val RELATED_SOURCES = listOf(
        "friends", "friendRequests", "sentFriendRequests", "blocked", "dmReads", "joinRequests", "invites",
    )

    /**
     * Other users' subtrees that may hold an entry keyed by our uid: their friend entry, a request
     * we sent them and its index on their side, a join request we sent them, an invite we sent them.
     */
    val MIRROR_ROOTS = listOf("friends", "friendRequests", "sentFriendRequests", "joinRequests", "invites")

    /** Per-DM-thread nodes keyed by dmId, deleted whole: the messages and both typing entries. */
    val THREAD_ROOTS = listOf("dms", "typing", "dmRooms")

    /** Group ids: push ids (letters, digits, '-' and '_'). Anything else is skipped. */
    private val GROUP_ID = Regex("^[A-Za-z0-9_-]{1,40}$")

    /** Firebase uids (same check as the push relay). Anything else isn't a user and is skipped. */
    private val UID = Regex("^[A-Za-z0-9]{1,128}$")

    fun isUid(value: String): Boolean = UID.matches(value)

    /**
     * @param usernameKey usernames/ key to release, already checked to point at [uid]; null if none.
     * @param related uids from [RELATED_SOURCES]; [uid] itself and non-uids are ignored.
     */
    fun deletionPaths(
        uid: String,
        usernameKey: String?,
        related: Collection<String>,
        groups: Collection<String> = emptyList(),
    ): Map<String, Any?> {
        val paths = LinkedHashMap<String, Any?>()
        for (root in OWN_ROOTS) paths["$root/$uid"] = null
        if (usernameKey != null) paths["usernames/$usernameKey"] = null
        // Leave every group (the group and its messages stay for the others) and its room list.
        for (gid in groups.toSortedSet()) {
            if (!GROUP_ID.matches(gid)) continue
            paths["groups/$gid/members/$uid"] = null
            paths["groupRooms/$gid/in/$uid"] = null
        }
        for (other in related.toSortedSet()) {
            if (other == uid || !isUid(other)) continue
            for (root in MIRROR_ROOTS) paths["$root/$other/$uid"] = null
            // The rules find the other participant with dmId.replace(auth.uid, ''), which only
            // works if neither uid contains the other. Real Firebase uids (28 random characters)
            // never do; skipping keeps one odd id from failing the whole atomic update.
            if (uid !in other && other !in uid) {
                val dmId = DmIds.dmId(uid, other)
                for (root in THREAD_ROOTS) paths["$root/$dmId"] = null
            }
        }
        return paths
    }
}
