package com.syncit.app.social

import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.Presence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivityTextTest {
    private fun activity(
        app: ActivityApp,
        title: String?,
        subtitle: String? = null,
        playing: Boolean = true,
        inWatchParty: Boolean = false,
    ) = Activity(app, title, subtitle, null, playing, null, 0L, inWatchParty)

    @Test fun netflixEpisode() = assertEquals(
        "Watching S2:E4 · Redanian Intelligence on Netflix",
        ActivityText.activityLine(activity(ActivityApp.NETFLIX, "Redanian Intelligence", "S2:E4")),
    )

    @Test fun movieWithoutSubtitle() =
        assertEquals("Watching Dune on Prime Video", ActivityText.activityLine(activity(ActivityApp.PRIME_VIDEO, "Dune")))

    @Test fun movieWithoutAnyTitle() =
        assertEquals("Watching JioHotstar", ActivityText.activityLine(activity(ActivityApp.JIOHOTSTAR, null, " ")))

    @Test fun music() = assertEquals(
        "Listening to Blinding Lights on Spotify",
        ActivityText.activityLine(activity(ActivityApp.SPOTIFY, "Blinding Lights", "The Weeknd")),
    )

    @Test fun musicWithoutTitle() =
        assertEquals("Listening on YouTube Music", ActivityText.activityLine(activity(ActivityApp.YOUTUBE_MUSIC, null)))

    @Test fun video() =
        assertEquals("Watching Lo-fi beats on YouTube", ActivityText.activityLine(activity(ActivityApp.YOUTUBE, "Lo-fi beats")))

    @Test fun paused() = assertEquals(
        "Watching Dune on Netflix (paused)",
        ActivityText.activityLine(activity(ActivityApp.NETFLIX, "Dune", playing = false)),
    )

    @Test fun statusPrefersActivityWhileOnline() {
        val a = activity(ActivityApp.SPOTIFY, "Song")
        assertEquals("Listening to Song on Spotify", ActivityText.statusLine(Presence(true, 0, a), NOW))
        assertEquals("Online", ActivityText.statusLine(Presence(true, 0, null), NOW))
    }

    @Test fun offlineIgnoresStaleActivity() {
        val p = Presence(false, NOW - 5 * MIN, activity(ActivityApp.NETFLIX, "Dune"))
        assertEquals("Last seen 5m ago", ActivityText.statusLine(p, NOW))
        assertNull(ActivityText.visibleActivity(p))
    }

    @Test fun watchPartyLabel() {
        assertEquals("In a watch party", ActivityText.watchPartyLabel(activity(ActivityApp.NETFLIX, "Dune", inWatchParty = true)))
        assertEquals("In a jam", ActivityText.watchPartyLabel(activity(ActivityApp.SPOTIFY, "Song", inWatchParty = true)))
        assertEquals("In a jam", ActivityText.watchPartyLabel(activity(ActivityApp.YOUTUBE, "Video", inWatchParty = true)))
        assertNull(ActivityText.watchPartyLabel(activity(ActivityApp.NETFLIX, "Dune")))
        assertNull(ActivityText.watchPartyLabel(null))
    }

    @Test fun lastSeenBuckets() {
        assertEquals("Last seen just now", ActivityText.lastSeen(NOW - 30_000, NOW))
        // Server-clock estimate slightly behind the stamp: still "just now".
        assertEquals("Last seen just now", ActivityText.lastSeen(NOW + 5_000, NOW))
        assertEquals("Last seen 59m ago", ActivityText.lastSeen(NOW - 59 * MIN, NOW))
        assertEquals("Last seen 2h ago", ActivityText.lastSeen(NOW - 125 * MIN, NOW))
        assertEquals("Last seen 3d ago", ActivityText.lastSeen(NOW - 3 * 24 * 60 * MIN, NOW))
        assertEquals("Last seen over a week ago", ActivityText.lastSeen(NOW - 9 * 24 * 60 * MIN, NOW))
        assertEquals("Offline", ActivityText.lastSeen(null, NOW))
        assertEquals("Offline", ActivityText.statusLine(null, NOW))
    }

    @Test fun searchQuery() {
        assertEquals(
            "Blinding Lights The Weeknd",
            ActivityText.searchQuery(activity(ActivityApp.SPOTIFY, "Blinding Lights", "The Weeknd")),
        )
        assertEquals("Lo-fi", ActivityText.searchQuery(activity(ActivityApp.YOUTUBE, "Lo-fi", "Channel")))
        assertNull(ActivityText.searchQuery(activity(ActivityApp.SPOTIFY, null)))
    }

    @Test fun searchUris() {
        assertEquals("spotify:search:Blinding%20Lights", ActivityLinks.searchUri(ActivityApp.SPOTIFY, "Blinding Lights"))
        assertEquals(
            "https://www.youtube.com/results?search_query=lo-fi%20%26%20chill",
            ActivityLinks.searchUri(ActivityApp.YOUTUBE, "lo-fi & chill"),
        )
        assertEquals("https://music.youtube.com/search?q=Song%20A", ActivityLinks.searchUri(ActivityApp.YOUTUBE_MUSIC, "Song A"))
        assertNull(ActivityLinks.searchUri(ActivityApp.AMAZON_MUSIC, "Song"))
    }

    @Test fun watchPartyLabelNamesWhoYouAreWith() {
        val withSomesh = Activity(ActivityApp.NETFLIX, "Dune", null, null, true, null, 0L, inWatchParty = true, watchingWith = "Somesh")
        assertEquals("In a watch party with Somesh", ActivityText.watchPartyLabel(withSomesh))
        val notInRoom = Activity(ActivityApp.NETFLIX, "Dune", null, null, true, null, 0L, inWatchParty = false, watchingWith = "Somesh")
        assertEquals(null, ActivityText.watchPartyLabel(notInRoom))
    }

    @Test fun watchingWithWording() {
        assertEquals(null, ActivityText.watchingWith(emptyList()))
        assertEquals(null, ActivityText.watchingWith(listOf(" ", "")))
        assertEquals("Somesh", ActivityText.watchingWith(listOf("Somesh", "Somesh")))
        assertEquals("Somesh and Ayan", ActivityText.watchingWith(listOf("Somesh", "Ayan")))
        assertEquals("Somesh, Ayan and Riya", ActivityText.watchingWith(listOf("Somesh", "Ayan", "Riya")))
        assertEquals("Somesh, Ayan and 2 others", ActivityText.watchingWith(listOf("Somesh", "Ayan", "Riya", "Dev")))
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
        const val MIN = 60_000L
    }
}
