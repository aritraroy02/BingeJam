package com.syncit.app.ui.room

import com.syncit.core.model.RoomPreview
import com.syncit.core.model.StreamingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetflixLinksTest {
    private fun preview(
        title: String?,
        subtitle: String? = null,
        service: StreamingService? = StreamingService.NETFLIX,
        durationMs: Long? = 170 * 60_000L,
    ) = RoomPreview(
        code = "ABC123", hostName = "Host", service = service, title = title, subtitle = subtitle,
        artworkJpeg = null, updatedAt = 0, durationMs = durationMs,
    )

    @Test fun movieIsLookedUpByTitleAndLength() {
        assertEquals(NetflixLinks.Query("The Paradise", 170 * 60_000L), NetflixLinks.query(preview(" The Paradise ")))
    }

    @Test fun unknownLengthIsSentAsNull() {
        assertEquals(NetflixLinks.Query("Maharaja", null), NetflixLinks.query(preview("Maharaja", durationMs = 0)))
    }

    @Test fun episodesAreNotLookedUp() {
        assertNull(NetflixLinks.query(preview("Redanian Intelligence", "S2:E4 - Redanian Intelligence")))
        assertNull(NetflixLinks.query(preview("Aastha", "S1 E2")))
        assertNull(NetflixLinks.query(preview("s3e10 Finale")))
    }

    @Test fun titlesThatOnlyLookLikeEpisodesAreStillMovies() {
        assertEquals("Se7en", NetflixLinks.query(preview("Se7en"))?.title)
        assertEquals("Mission: Impossible", NetflixLinks.query(preview("Mission: Impossible"))?.title)
    }

    @Test fun otherAppsAndMissingTitlesAreNotLookedUp() {
        assertNull(NetflixLinks.query(preview("The Paradise", service = StreamingService.JIOHOTSTAR)))
        assertNull(NetflixLinks.query(preview("  ")))
        assertNull(NetflixLinks.query(preview(null)))
        assertNull(NetflixLinks.query(null))
    }

    @Test fun watchUrlOnlyForDigits() {
        assertEquals("https://www.netflix.com/watch/82134615", NetflixLinks.watchUrl("82134615"))
        assertNull(NetflixLinks.watchUrl(null))
        assertNull(NetflixLinks.watchUrl("82134615/../evil"))
        assertNull(NetflixLinks.watchUrl(""))
        assertNull(NetflixLinks.watchUrl("1234567890123"))
    }

    @Test fun searchUrlEncodesTheTitle() {
        assertEquals("https://www.netflix.com/search/The%20Paradise", NetflixLinks.searchUrl("The Paradise"))
        assertEquals("https://www.netflix.com/search/Tom%20%26%20Jerry%3F", NetflixLinks.searchUrl("Tom & Jerry?"))
    }
}
