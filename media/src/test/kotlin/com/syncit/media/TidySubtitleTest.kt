package com.syncit.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TidySubtitleTest {
    @Test fun netflixEpisode() =
        assertEquals("S2:E4", MetadataMapper.tidySubtitle("S2:E4 - Redanian Intelligence", "Redanian Intelligence"))

    @Test fun sameAsTitleIsDropped() = assertNull(MetadataMapper.tidySubtitle("Sacred Games", "sacred games"))

    @Test fun unrelatedSubtitleKept() = assertEquals("Anuv Jain", MetadataMapper.tidySubtitle("Anuv Jain", "Arz Kiya Hai"))

    @Test fun noTitleKeepsSubtitle() = assertEquals("S1:E1 - Pilot", MetadataMapper.tidySubtitle("S1:E1 - Pilot", null))

    @Test fun nullSubtitle() = assertNull(MetadataMapper.tidySubtitle(null, "Anything"))
}
