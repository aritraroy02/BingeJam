package com.syncit.firebase

import com.syncit.core.model.ActivityApp
import com.syncit.core.model.StreamingService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomParserPreviewTest {

    private val noArtwork: (String) -> ByteArray? = { null }

    private fun parse(values: Map<String, Any?>, decode: (String) -> ByteArray? = noArtwork) =
        RoomParser.preview("ABC234", values, decode)

    @Test
    fun `jam preview parses service and playableRef`() {
        val p = parse(
            mapOf(
                "hostName" to "Aritra",
                "service" to "SPOTIFY",
                "title" to "Song",
                "subtitle" to "Artist",
                "durationMs" to 215000L,
                "updatedAt" to 1_700_000_000_000L,
                "playableRef" to "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            ),
        )
        assertNotNull(p)
        assertEquals(StreamingService.SPOTIFY, p!!.service)
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", p.playableRef)
        assertEquals(215000L, p.durationMs)
        assertEquals(1_700_000_000_000L, p.updatedAt)
    }

    @Test
    fun `every streaming service round-trips by name`() {
        for (service in StreamingService.entries) {
            assertEquals(service, parse(mapOf("hostName" to "H", "service" to service.name))!!.service)
        }
    }

    @Test
    fun `every activity app maps to a service, so all are joinable`() {
        assertTrue(ActivityApp.entries.all { it.joinable })
    }

    @Test
    fun `unknown service parses to null without dropping the preview`() {
        val p = parse(mapOf("hostName" to "H", "service" to "TIDAL", "title" to "x"))
        assertNotNull(p)
        assertNull(p!!.service)
        assertEquals("x", p.title)
    }

    @Test
    fun `non-string service or playableRef is ignored`() {
        val p = parse(mapOf("hostName" to "H", "service" to 3L, "playableRef" to 42L))!!
        assertNull(p.service)
        assertNull(p.playableRef)
    }

    @Test
    fun `invalid playableRef is dropped`() {
        val p = parse(mapOf("hostName" to "H", "service" to "YOUTUBE", "playableRef" to "https://evil.example/watch?v=dQw4w9WgXcQ"))!!
        assertEquals(StreamingService.YOUTUBE, p.service)
        assertNull(p.playableRef)
    }

    @Test
    fun `missing hostName means no preview`() {
        assertNull(parse(mapOf("service" to "NETFLIX")))
    }

    @Test
    fun `old movie preview without playableRef still parses`() {
        val p = parse(mapOf("hostName" to "H", "service" to "NETFLIX", "title" to "Stranger Things", "updatedAt" to 5L))!!
        assertEquals(StreamingService.NETFLIX, p.service)
        assertNull(p.playableRef)
        assertNull(p.durationMs)
    }

    @Test
    fun `artwork goes through the decoder`() {
        val bytes = byteArrayOf(1, 2, 3)
        val p = parse(mapOf("hostName" to "H", "artwork" to "AQID")) { if (it == "AQID") bytes else null }!!
        assertArrayEquals(bytes, p.artworkJpeg)
    }

    @Test
    fun `numbers stored as doubles still parse`() {
        val p = parse(mapOf("hostName" to "H", "durationMs" to 1000.0, "updatedAt" to 7.0))!!
        assertEquals(1000L, p.durationMs)
        assertEquals(7L, p.updatedAt)
    }

    // ---- PlayableRefs: must match the playableRef rule in database.rules.json ----

    @Test
    fun `valid playable refs`() {
        listOf(
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "spotify:episode:0Q86acNRm6V9GYx55SXKwf",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=a-b_c-d_e-f",
        ).forEach { assertTrue(it, PlayableRefs.isValid(it)) }
    }

    @Test
    fun `invalid playable refs`() {
        listOf(
            null,
            "",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQ", // 21 chars
            "spotify:track:4uLU6hMCjMI75M1A2tKUQCX", // 23 chars
            "spotify:track:4uLU6hMCjMI75M1A2tKU-C", // not base62
            "spotify:album:4uLU6hMCjMI75M1A2tKUQC",
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M",
            "SPOTIFY:track:4uLU6hMCjMI75M1A2tKUQC",
            " spotify:track:4uLU6hMCjMI75M1A2tKUQC",
            "spotify:track:4uLU6hMCjMI75M1A2tKUQC\n",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXc", // 10 chars
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL1",
            "https://wwwXyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watchXv=dQw4w9WgXcQ",
            "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "content://media/external/audio/1",
            "https://cdn.example/stream.m3u8",
        ).forEach { assertFalse(it.toString(), PlayableRefs.isValid(it)) }
    }
}
