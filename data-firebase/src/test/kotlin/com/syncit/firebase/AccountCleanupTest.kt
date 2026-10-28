package com.syncit.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountCleanupTest {

    private val me = "Me000000000000000000000000mm"
    private val bob = "Bob00000000000000000000000bb"
    private val zed = "Zzz00000000000000000000000zz"

    @Test
    fun `own subtrees are deleted as whole nodes, all to null`() {
        val paths = AccountCleanup.deletionPaths(me, null, emptyList())
        assertEquals(AccountCleanup.OWN_ROOTS.map { "$it/$me" }.toSet(), paths.keys)
        assertTrue(paths.values.all { it == null })
        assertTrue("users/$me" in paths && "users_private/$me" in paths && "presence/$me" in paths)
        assertTrue("pushTokens/$me" in paths && "dmReads/$me" in paths && "blocked/$me" in paths)
    }

    @Test
    fun `username key only when given`() {
        assertFalse(AccountCleanup.deletionPaths(me, null, emptyList()).keys.any { it.startsWith("usernames/") })
        val paths = AccountCleanup.deletionPaths(me, "movie,buff", emptyList())
        assertTrue(paths.containsKey("usernames/movie,buff"))
        assertEquals(null, paths["usernames/movie,buff"])
    }

    @Test
    fun `every related uid gets its mirror entries, the DM thread and its typing node`() {
        val paths = AccountCleanup.deletionPaths(me, null, listOf(bob, zed))
        for (other in listOf(bob, zed)) {
            for (root in AccountCleanup.MIRROR_ROOTS) assertTrue("$root/$other/$me", paths.containsKey("$root/$other/$me"))
        }
        // dmId = the two uids sorted, joined with '_' (same as DmIds and the push relay).
        assertTrue(paths.containsKey("dms/${bob}_$me"))
        assertTrue(paths.containsKey("dms/${me}_$zed"))
        assertTrue(paths.containsKey("typing/${bob}_$me"))
        assertTrue(paths.containsKey("typing/${me}_$zed"))
        assertEquals(
            AccountCleanup.OWN_ROOTS.size + 2 * (AccountCleanup.MIRROR_ROOTS.size + AccountCleanup.THREAD_ROOTS.size),
            paths.size,
        )
    }

    @Test
    fun `self, duplicates and non-uids are skipped`() {
        val paths = AccountCleanup.deletionPaths(me, null, listOf(me, bob, bob, "", "a/b", "x_y", "has.dot", "c#d"))
        val mirrorTargets = paths.keys.filter { it.startsWith("friends/") && it != "friends/$me" }
        assertEquals(listOf("friends/$bob/$me"), mirrorTargets)
        assertFalse(paths.keys.any { it.startsWith("friends/$me/") })
    }

    @Test
    fun `DM thread skipped when one uid contains the other, mirrors kept`() {
        val longer = "x${me}y"
        val paths = AccountCleanup.deletionPaths(me, null, listOf(longer))
        assertTrue(paths.containsKey("friends/$longer/$me"))
        assertFalse(paths.keys.any { it.startsWith("dms/") || it.startsWith("typing/") })
    }

    @Test
    fun `no path is an ancestor of another (updateChildren would reject the update)`() {
        val paths = AccountCleanup.deletionPaths(me, "me", listOf(bob, zed)).keys.toList()
        for (a in paths) for (b in paths) {
            if (a != b) assertFalse("$a is an ancestor of $b", b.startsWith("$a/"))
        }
    }
}
