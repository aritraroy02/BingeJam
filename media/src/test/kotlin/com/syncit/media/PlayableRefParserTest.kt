package com.syncit.media

import com.syncit.core.config.StreamingConfig.AMAZON_MUSIC_PACKAGE
import com.syncit.core.config.StreamingConfig.JIOHOTSTAR_PACKAGE
import com.syncit.core.config.StreamingConfig.NETFLIX_PACKAGE
import com.syncit.core.config.StreamingConfig.PRIME_VIDEO_PACKAGE
import com.syncit.core.config.StreamingConfig.SPOTIFY_PACKAGE
import com.syncit.core.config.StreamingConfig.YOUTUBE_MUSIC_PACKAGE
import com.syncit.core.config.StreamingConfig.YOUTUBE_PACKAGE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayableRefParserTest {

    private val trackId = "4uLU6hMCjMI75M1A2tKUQC" // 22 base62 chars
    private val videoId = "dQw4w9WgXcQ" // 11 chars

    // ---- Spotify ----

    @Test fun spotifyTrackFromMediaId() =
        assertEquals("spotify:track:$trackId", PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:track:$trackId", null))

    @Test fun spotifyEpisodeFromMediaId() =
        assertEquals("spotify:episode:$trackId", PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:episode:$trackId", null))

    @Test fun spotifyFromMediaUriWhenMediaIdIsSomethingElse() =
        assertEquals("spotify:track:$trackId", PlayableRefParser.parse(SPOTIFY_PACKAGE, "internal-123", "spotify:track:$trackId"))

    @Test fun spotifyRejectsWrongLength() {
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:track:${trackId.drop(1)}", null))
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:track:${trackId}X", null))
    }

    @Test fun spotifyRejectsOtherKindsAndSuffixes() {
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:playlist:$trackId", null))
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:user:abc:collection", null))
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:track:$trackId:extra", null))
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, "spotify:track:${trackId.dropLast(1)}_", null))
    }

    @Test fun spotifyIgnoresYouTubeStyleIds() = assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, videoId, null))

    @Test fun surroundingWhitespaceIsTrimmed() =
        assertEquals("spotify:track:$trackId", PlayableRefParser.parse(SPOTIFY_PACKAGE, "  spotify:track:$trackId ", null))

    // ---- YouTube / YouTube Music ----

    @Test fun youtubeBareVideoId() =
        assertEquals("https://www.youtube.com/watch?v=$videoId", PlayableRefParser.parse(YOUTUBE_PACKAGE, videoId, null))

    @Test fun youtubeMusicBareVideoId() =
        assertEquals("https://music.youtube.com/watch?v=$videoId", PlayableRefParser.parse(YOUTUBE_MUSIC_PACKAGE, videoId, null))

    @Test fun youtubeWatchUrlsAreNormalised() {
        val expected = "https://www.youtube.com/watch?v=$videoId"
        for (url in listOf(
            "https://www.youtube.com/watch?v=$videoId",
            "https://m.youtube.com/watch?v=$videoId&t=42s",
            "http://youtube.com/watch?feature=share&v=$videoId",
            "https://music.youtube.com/watch?v=$videoId&list=RDAMVM",
            "https://youtu.be/$videoId?si=abc",
        )) {
            assertEquals(url, expected, PlayableRefParser.parse(YOUTUBE_PACKAGE, null, url))
        }
    }

    @Test fun youtubeMusicUrlNormalisedToMusicHost() =
        assertEquals(
            "https://music.youtube.com/watch?v=$videoId",
            PlayableRefParser.parse(YOUTUBE_MUSIC_PACKAGE, null, "https://www.youtube.com/watch?v=$videoId"),
        )

    @Test fun youtubeRejectsNonWatchAndPrivateUrls() {
        for (bad in listOf(
            "https://www.youtube.com/watch?v=${videoId}X", // 12-char id
            "https://www.youtube.com/watch?vv=$videoId",
            "https://www.youtube.com/playlist?list=PL123",
            "https://evil.com/watch?v=$videoId",
            "https://www.youtube.com.evil.com/watch?v=$videoId",
            "https://rr3---sn-abc.googlevideo.com/videoplayback?id=$videoId",
            "content://com.google.android.youtube/$videoId",
            "dQw4w9WgXc", // 10 chars
            "dQw4w9WgXcQQ", // 12 chars
            "dQw4w9 gXcQ",
        )) {
            assertNull(bad, PlayableRefParser.parse(YOUTUBE_PACKAGE, bad, bad))
        }
    }

    @Test fun mediaIdWinsOverMediaUri() =
        assertEquals(
            "https://www.youtube.com/watch?v=$videoId",
            PlayableRefParser.parse(YOUTUBE_PACKAGE, videoId, "https://youtu.be/AAAAAAAAAAA"),
        )

    // ---- Everything else ----

    @Test fun amazonMusicHasNoPublicFormat() {
        assertNull(PlayableRefParser.parse(AMAZON_MUSIC_PACKAGE, "spotify:track:$trackId", null))
        assertNull(PlayableRefParser.parse(AMAZON_MUSIC_PACKAGE, videoId, "https://www.youtube.com/watch?v=$videoId"))
    }

    @Test fun videoAppsNeverGetARef() {
        for (pkg in listOf(NETFLIX_PACKAGE, JIOHOTSTAR_PACKAGE, PRIME_VIDEO_PACKAGE)) {
            assertNull(PlayableRefParser.parse(pkg, videoId, "https://www.youtube.com/watch?v=$videoId"))
            assertNull(PlayableRefParser.parse(pkg, "spotify:track:$trackId", null))
        }
    }

    @Test fun mediaUriOnlyReadForJamApps() {
        assertTrue(PlayableRefParser.readsMediaUri(SPOTIFY_PACKAGE))
        assertTrue(PlayableRefParser.readsMediaUri(YOUTUBE_PACKAGE))
        assertTrue(PlayableRefParser.readsMediaUri(YOUTUBE_MUSIC_PACKAGE))
        assertTrue(PlayableRefParser.readsMediaUri(JIOHOTSTAR_PACKAGE))
        assertFalse(PlayableRefParser.readsMediaUri(NETFLIX_PACKAGE))
        assertFalse(PlayableRefParser.readsMediaUri(AMAZON_MUSIC_PACKAGE))
    }

    // ---- JioHotstar (MEDIA_URI measured on the OPPO with "October", 2026-11-02) ----

    private val hotstarStream =
        "https://hses9.hotstar.com/gsvod1/movies-prod-origin1/videos/hotstarint/v3/1271713346/1791213049860/c811af9e895e40605b055"
    private val hotstarWatch = "https://www.hotstar.com/in/movies/x/1271713346/watch"

    @Test fun jioHotstarContentIdFromStreamUrl() =
        assertEquals(hotstarWatch, PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, null, hotstarStream))

    @Test fun jioHotstarNeverKeepsTheStreamUrl() {
        val ref = PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, "", hotstarStream)!!
        assertFalse(ref.contains("hses9"))
        assertFalse(ref.contains("c811af9e"))
    }

    @Test fun jioHotstarRejectsOtherHostsAndShapes() {
        assertNull(PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, null, hotstarStream.replace("hotstar.com", "hotstar.com.evil.io")))
        assertNull(PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, null, "http://hses9.hotstar.com/videos/hotstarint/v3/1271713346/x"))
        assertNull(PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, null, "https://hses9.hotstar.com/videos/hotstarint/1271713346/x"))
        assertNull(PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, "1271713346", null))
        assertNull(PlayableRefParser.parse(JIOHOTSTAR_PACKAGE, null, "https://www.youtube.com/watch?v=$videoId"))
    }

    @Test fun jioHotstarWatchLinkIsValidOnlyForJioHotstar() {
        assertEquals(hotstarWatch, PlayableRefParser.validFor(JIOHOTSTAR_PACKAGE, hotstarWatch))
        assertNull(PlayableRefParser.validFor(NETFLIX_PACKAGE, hotstarWatch))
        assertNull(PlayableRefParser.validFor(JIOHOTSTAR_PACKAGE, hotstarStream))
        assertNull(PlayableRefParser.validFor(JIOHOTSTAR_PACKAGE, "$hotstarWatch?x=1"))
    }

    @Test fun nullAndBlankInputs() {
        assertNull(PlayableRefParser.parse(SPOTIFY_PACKAGE, null, null))
        assertNull(PlayableRefParser.parse(YOUTUBE_PACKAGE, "", "   "))
    }

    // ---- Validating a host's ref for the member's app ----

    @Test fun validForSameAppOnly() {
        val yt = "https://www.youtube.com/watch?v=$videoId"
        val ytm = "https://music.youtube.com/watch?v=$videoId"
        assertEquals(yt, PlayableRefParser.validFor(YOUTUBE_PACKAGE, yt))
        assertEquals(ytm, PlayableRefParser.validFor(YOUTUBE_MUSIC_PACKAGE, ytm))
        assertNull(PlayableRefParser.validFor(YOUTUBE_MUSIC_PACKAGE, yt))
        assertNull(PlayableRefParser.validFor(SPOTIFY_PACKAGE, yt))
        assertEquals("spotify:track:$trackId", PlayableRefParser.validFor(SPOTIFY_PACKAGE, "spotify:track:$trackId"))
        // Only the normalised form is accepted from the network.
        assertNull(PlayableRefParser.validFor(YOUTUBE_PACKAGE, "https://youtu.be/$videoId"))
        assertNull(PlayableRefParser.validFor(YOUTUBE_PACKAGE, null))
    }
}
