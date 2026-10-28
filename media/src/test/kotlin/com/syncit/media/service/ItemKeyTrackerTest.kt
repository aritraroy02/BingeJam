package com.syncit.media.service

import com.syncit.core.model.StreamingService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemKeyTrackerTest {
    private val tracker = ItemKeyTracker()

    private val refA = "spotify:track:AAAAAAAAAAAAAAAAAAAAAA"
    private val refB = "spotify:track:BBBBBBBBBBBBBBBBBBBBBB"

    @Test fun firstTrackIsNotAChange() = assertFalse(tracker.changed(refA, "Song A"))

    @Test fun differentRefIsAChangeOnce() {
        tracker.changed(refA, "Song")
        assertTrue(tracker.changed(refB, "Song"))
        assertFalse(tracker.changed(refB, "Song"))
    }

    @Test fun differentTitleWithoutRefsIsAChange() {
        tracker.changed(null, "Song A")
        assertTrue(tracker.changed(null, "song b"))
        assertFalse("case and spacing don't matter", tracker.changed(null, "  SONG   B "))
    }

    @Test fun refArrivingAfterTheTitleIsTheSameTrack() {
        tracker.changed(null, "Song A")
        assertFalse(tracker.changed(refA, "Song A"))
        // ...and the ref is remembered: a different ref is a new track even with the same title.
        assertTrue(tracker.changed(refB, "Song A"))
    }

    @Test fun refDisappearingWithSameTitleIsTheSameTrack() {
        tracker.changed(refA, "Song A")
        assertFalse(tracker.changed(null, "Song A"))
        assertTrue(tracker.changed(null, "Song B"))
    }

    @Test fun emptyReadingsAreIgnoredAndKeepTheLastTrack() {
        tracker.changed(refA, "Song A")
        assertFalse(tracker.changed(null, null))
        assertFalse(tracker.changed(null, "  "))
        assertFalse("same track after a metadata gap", tracker.changed(refA, "Song A"))
        assertTrue(tracker.changed(refB, "Song B"))
    }

    @Test fun anotherAppIsAlwaysANewItem() {
        tracker.changed(StreamingService.NETFLIX, null, "Movie")
        assertTrue("same title, other app", tracker.changed(StreamingService.PRIME_VIDEO, null, "Movie"))
        assertFalse(tracker.changed(StreamingService.PRIME_VIDEO, null, "Movie"))
        // Even before the new app publishes any metadata.
        assertTrue(tracker.changed(StreamingService.NETFLIX, null, null))
        assertFalse("its title arriving later is the same item", tracker.changed(StreamingService.NETFLIX, null, "Ep 1"))
        assertTrue(tracker.changed(StreamingService.NETFLIX, null, "Ep 2"))
    }

    @Test fun resetMakesTheNextTrackAFirstTrack() {
        tracker.changed(refA, "Song A")
        tracker.reset()
        assertFalse(tracker.changed(refB, "Song B"))
    }
}
