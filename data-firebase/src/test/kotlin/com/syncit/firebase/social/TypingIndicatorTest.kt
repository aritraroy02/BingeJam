package com.syncit.firebase.social

import com.syncit.firebase.social.TypingIndicator.MAX_FUTURE_SKEW_MS
import com.syncit.firebase.social.TypingIndicator.REFRESH_MS
import com.syncit.firebase.social.TypingIndicator.STALE_MS
import com.syncit.firebase.social.TypingIndicator.TICK_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TypingIndicatorTest {

    private val stamp = 1_000_000L

    @Test
    fun `fresh until STALE_MS old, in server time`() {
        assertTrue(TypingIndicator.isFresh(stamp, stamp))
        assertTrue(TypingIndicator.isFresh(stamp, stamp + STALE_MS - 1))
        assertFalse(TypingIndicator.isFresh(stamp, stamp + STALE_MS))
        assertFalse(TypingIndicator.isFresh(stamp, stamp + 60_000))
    }

    @Test
    fun `refresh interval keeps a typing friend fresh with room for latency`() {
        assertTrue(STALE_MS >= 2 * REFRESH_MS)
    }

    @Test
    fun `small future skew tolerated, a far-future stamp is not`() {
        assertTrue(TypingIndicator.isFresh(stamp, stamp - MAX_FUTURE_SKEW_MS))
        assertFalse(TypingIndicator.isFresh(stamp, stamp - MAX_FUTURE_SKEW_MS - 1))
    }

    @Test
    fun `next check lands just after the entry goes stale, at most one tick away`() {
        assertEquals(TICK_MS, TypingIndicator.nextCheckDelayMs(stamp, stamp))
        assertEquals(501L, TypingIndicator.nextCheckDelayMs(stamp, stamp + STALE_MS - 500))
        assertFalse(TypingIndicator.isFresh(stamp, stamp + STALE_MS - 500 + 501))
        assertEquals(1L, TypingIndicator.nextCheckDelayMs(stamp, stamp + STALE_MS + 10_000))
    }

    @Test
    fun `throttle writes at most once per REFRESH_MS per chat`() {
        var now = 50_000L
        val throttle = TypingThrottle { now }
        assertTrue(throttle.shouldWrite("a_b"))
        now += REFRESH_MS - 1
        assertFalse(throttle.shouldWrite("a_b"))
        assertTrue("other chats aren't throttled by this one", throttle.shouldWrite("a_c"))
        now += 1
        assertTrue(throttle.shouldWrite("a_b"))
    }

    @Test
    fun `clear reports a written entry once and lets the next keystroke write at once`() {
        var now = 0L
        val throttle = TypingThrottle { now }
        assertFalse("nothing written yet", throttle.clear("a_b"))
        assertTrue(throttle.shouldWrite("a_b"))
        now += 100
        assertTrue(throttle.clear("a_b"))
        assertFalse(throttle.clear("a_b"))
        assertTrue(throttle.shouldWrite("a_b"))
    }
}
