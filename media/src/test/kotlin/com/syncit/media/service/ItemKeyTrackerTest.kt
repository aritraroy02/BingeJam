package com.syncit.media.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemKeyTrackerTest {
    private val tracker = ItemKeyTracker()

    @Test fun firstKeyIsNotAChange() = assertFalse(tracker.changed("a"))

    @Test fun differentKeyIsAChangeOnce() {
        tracker.changed("a")
        assertTrue(tracker.changed("b"))
        assertFalse(tracker.changed("b"))
    }

    @Test fun nullIsIgnoredAndKeepsTheLastKey() {
        tracker.changed("a")
        assertFalse(tracker.changed(null))
        assertFalse("same track after a metadata gap", tracker.changed("a"))
        assertFalse(tracker.changed(null))
        assertTrue(tracker.changed("b"))
    }

    @Test fun resetMakesTheNextKeyAFirstKey() {
        tracker.changed("a")
        tracker.reset()
        assertFalse(tracker.changed("b"))
    }
}
