package com.syncit.firebase.social

import com.syncit.core.model.StreamingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchCardsTest {
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    private fun card(lastAt: Long?, durationMs: Long = 20 * min, items: Int = 1, sentAt: Long = now - 30 * min) =
        mapOf("kind" to "watch", "by" to "u1", "title" to "Leo", "service" to "NETFLIX", "durationMs" to durationMs, "items" to items.toLong(), "sentAt" to sentAt, "lastAt" to lastAt)

    @Test
    fun `empty slot is created, fresh card joined, old card skipped`() {
        assertEquals(WatchCards.Decision.Create, WatchCards.decide(null, now))
        assertEquals(WatchCards.Decision.Join(20 * min, 3), WatchCards.decide(card(now - 4 * min, items = 3), now))
        assertEquals(WatchCards.Decision.Next, WatchCards.decide(card(now - WatchCards.STALE_MS - 1), now))
        // Exactly at the limit still counts as the same session.
        assertEquals(WatchCards.Decision.Join(20 * min, 1), WatchCards.decide(card(now - WatchCards.STALE_MS), now))
    }

    @Test
    fun `missing lastAt falls back to sentAt, and a message in the slot is skipped`() {
        assertEquals(WatchCards.Decision.Join(20 * min, 1), WatchCards.decide(card(null, sentAt = now - min), now))
        assertEquals(WatchCards.Decision.Next, WatchCards.decide(card(null, sentAt = now - 3 * 60 * min), now))
        assertEquals(WatchCards.Decision.Next, WatchCards.decide(mapOf("from" to "u1", "text" to "hi", "sentAt" to now), now))
    }

    @Test
    fun `slot keys walk from the base key and stay within the rules pattern`() {
        val base = "w-ABC234-1a2b3c4d"
        assertTrue(WatchCards.isValidBaseKey(base))
        assertEquals(base, WatchCards.slotKey(base, 0))
        assertEquals("$base-2", WatchCards.slotKey(base, 1))
        assertEquals("$base-20", WatchCards.slotKey(base, WatchCards.MAX_SLOTS - 1))
        // database.rules.json: base, or base + "-" + [1-9][0-9]{0,2}.
        val suffixed = Regex("^w-[A-HJ-NP-Z2-9]{6}-[0-9a-f]{8}-[1-9][0-9]{0,2}$")
        for (slot in 1 until WatchCards.MAX_SLOTS) assertTrue(suffixed.matches(WatchCards.slotKey(base, slot)))
    }

    @Test
    fun `base keys outside the format are refused`() {
        assertFalse(WatchCards.isValidBaseKey("w-abc234-1a2b3c4d"))
        assertFalse(WatchCards.isValidBaseKey("w-ABC1O4-1a2b3c4d"))
        assertFalse(WatchCards.isValidBaseKey("w-ABC234-1A2B3C4D"))
        assertFalse(WatchCards.isValidBaseKey("w-ABC234-1a2b3c4d-2"))
        assertFalse(WatchCards.isValidBaseKey("-NxYz"))
    }

    @Test
    fun `durations only grow and stay within the rules range`() {
        assertEquals(30 * min, WatchCards.mergedDuration(30 * min, 20 * min))
        assertEquals(40 * min, WatchCards.mergedDuration(30 * min, 40 * min))
        assertEquals(WatchCards.MIN_DURATION_MS, WatchCards.mergedDuration(0L, min))
        assertEquals(WatchCards.MAX_DURATION_MS, WatchCards.mergedDuration(0L, 48 * 60 * min))
        assertEquals(5, WatchCards.mergedItems(5, 2))
        assertEquals(1, WatchCards.mergedItems(0, 0))
    }

    // ---- Parsing dms/{dmId}/{id} ----

    @Test
    fun `plain and forwarded messages`() {
        val m = SocialParser.message("-Nabc", mapOf("from" to "u1", "text" to "hi", "sentAt" to 5L))!!
        assertEquals("hi", m.text)
        assertFalse(m.forwarded)
        assertFalse(m.deleted)
        assertNull(m.watched)
        assertTrue(SocialParser.message("-Nabc", mapOf("from" to "u1", "text" to "hi", "sentAt" to 5L, "forwarded" to true))!!.forwarded)
    }

    @Test
    fun `deleted message keeps its sender and time but no text`() {
        val m = SocialParser.message("-Nabc", mapOf("from" to "u1", "sentAt" to 5L, "deleted" to true))!!
        assertTrue(m.deleted)
        assertEquals("", m.text)
        assertEquals("u1", m.fromUid)
        assertEquals(5L, m.sentAt)
        // A message without text that isn't marked deleted is malformed.
        assertNull(SocialParser.message("-Nabc", mapOf("from" to "u1", "sentAt" to 5L)))
    }

    @Test
    fun `watched-together card`() {
        val m = SocialParser.message("w-ABC234-1a2b3c4d", card(now))!!
        assertNotNull(m.watched)
        val w = m.watched!!
        assertEquals("Leo", w.title)
        assertEquals(StreamingService.NETFLIX, w.service)
        assertEquals(20 * min, w.durationMs)
        assertEquals(1, w.items)
        assertEquals("u1", m.fromUid)
        assertEquals(now - 30 * min, m.sentAt)
        assertFalse(m.deleted)
    }

    @Test
    fun `unknown kinds and broken cards are skipped`() {
        assertNull(SocialParser.message("x", mapOf("kind" to "poll", "by" to "u1", "sentAt" to 5L)))
        assertNull(SocialParser.message("x", card(now) - "title"))
        assertNull(SocialParser.message("x", card(now) - "durationMs"))
        assertNull(SocialParser.message("x", card(now) + ("service" to "VHS"))!!.watched!!.service)
    }
}
