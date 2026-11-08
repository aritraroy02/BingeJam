package com.syncit.firebase.social

import com.syncit.firebase.social.PresenceStatus.Status
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceStatusTest {

    private fun device(online: Any?, lastSeen: Any?, kind: String = "desktop") =
        mapOf("kind" to kind, "online" to online, "lastSeen" to lastSeen)

    @Test
    fun `phone only (0_6_x shape) is unchanged`() {
        assertEquals(Status(true, 100L), PresenceStatus.of(true, 100L, emptyList()))
        assertEquals(Status(false, 100L), PresenceStatus.of(false, 100L, emptyList()))
        assertEquals(Status(false, null), PresenceStatus.of(null, null, emptyList()))
    }

    @Test
    fun `desktop online while the phone is offline shows online`() {
        assertEquals(Status(true, 500L), PresenceStatus.of(false, 100L, listOf(device(true, 500L))))
    }

    @Test
    fun `phone online while the desktop is offline shows online`() {
        assertEquals(Status(true, 900L), PresenceStatus.of(true, 900L, listOf(device(false, 500L))))
    }

    @Test
    fun `desktop only, no legacy fields`() {
        assertEquals(Status(true, 500L), PresenceStatus.of(null, null, listOf(device(true, 500L))))
        assertEquals(Status(false, 500L), PresenceStatus.of(null, null, listOf(device(false, 500L))))
    }

    @Test
    fun `everything offline - last seen is the latest of all`() {
        val devices = listOf(device(false, 300L), device(false, 700L, kind = "phone"), device(false, 200L))
        assertEquals(Status(false, 700L), PresenceStatus.of(false, 400L, devices))
        assertEquals(Status(false, 900L), PresenceStatus.of(false, 900L, devices))
    }

    @Test
    fun `one online device among several is enough`() {
        val devices = listOf(device(false, 300L), device(true, 250L))
        assertEquals(Status(true, 400L), PresenceStatus.of(false, 400L, devices))
    }

    @Test
    fun `numbers of any width are accepted (Firebase gives Long, sometimes Int or Double)`() {
        assertEquals(Status(false, 800L), PresenceStatus.of(false, 100, listOf(device(false, 800.0))))
    }

    @Test
    fun `malformed values are ignored, never thrown on`() {
        val junk = listOf(
            "desktop", null, 42L, // not a map
            mapOf("online" to "true", "lastSeen" to "999"), // wrong types
            mapOf("kind" to "desktop"), // missing fields
        )
        assertEquals(Status(false, 100L), PresenceStatus.of("yes", 100L, junk))
        assertEquals(Status(false, null), PresenceStatus.of(1, "100", junk))
    }
}
