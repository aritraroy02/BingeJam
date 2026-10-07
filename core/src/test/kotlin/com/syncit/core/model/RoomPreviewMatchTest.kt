package com.syncit.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomPreviewMatchTest {
    private fun preview(title: String?, durationMs: Long? = null) =
        RoomPreview("ABC234", "Host", StreamingService.NETFLIX, title, "S2:E4", null, 0L, durationMs)

    private fun local(title: String?, durationMs: Long? = null) = NowPlayingMetadata(title, null, null, durationMs = durationMs)

    @Test fun sameEpisode() =
        assertEquals(true, preview("Redanian Intelligence", 3_353_000).matches(local("Redanian Intelligence", 3_353_000)))

    @Test fun titleIgnoresCaseAndSpacing() =
        assertEquals(true, preview("Redanian  Intelligence").matches(local(" redanian intelligence ")))

    @Test fun differentEpisode() =
        assertEquals(false, preview("Redanian Intelligence").matches(local("Kaer Morhen")))

    @Test fun sameTitleDifferentLength() =
        assertEquals(false, preview("Pilot", 3_000_000).matches(local("Pilot", 2_400_000)))

    @Test fun lengthWithinTolerance() =
        assertEquals(true, preview("Pilot", 3_000_000).matches(local("Pilot", 3_008_000)))

    @Test fun unknownLengthOnOneSideStillMatches() =
        assertEquals(true, preview("Pilot", 3_000_000).matches(local("Pilot", null)))

    @Test fun cantTellWithoutTitles() {
        assertNull(preview(null).matches(local("Pilot")))
        assertNull(preview("Pilot").matches(local(null)))
        assertNull(preview("Pilot").matches(null))
    }
}
