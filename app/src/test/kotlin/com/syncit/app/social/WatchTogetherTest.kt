package com.syncit.app.social

import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.RoomState
import com.syncit.core.model.StreamingService
import com.syncit.core.model.WatchedTogether
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTogetherTest {
    private val min = 60_000L

    // ---- Session key ----

    @Test fun keyIsDeterministicAndMatchesTheRulesFormat() {
        val a = WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "Leo")
        val b = WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "Leo")
        assertEquals(a, b)
        // Same pattern as database.rules.json (dms/$dmId/$messageId) and WatchCards.
        assertTrue(a, Regex("^w-[A-HJ-NP-Z2-9]{6}-[0-9a-f]{8}$").matches(a))
    }

    @Test fun keyIgnoresCaseSpacingAndPunctuationButNotTheTitleOrApp() {
        val base = WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "Spider-Man: Homecoming")
        assertEquals(base, WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "  spider man   homecoming "))
        assertNotEquals(base, WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "Spider-Man: Far From Home"))
        assertNotEquals(base, WatchTogether.sessionKey("ABC234", StreamingService.PRIME_VIDEO, "Spider-Man: Homecoming"))
        assertNotEquals(base, WatchTogether.sessionKey("XYZ789", StreamingService.NETFLIX, "Spider-Man: Homecoming"))
    }

    @Test fun jamKeyIsTheWholeJamNotEachTrack() {
        assertEquals(
            WatchTogether.sessionKey("ABC234", StreamingService.SPOTIFY, "Blinding Lights"),
            WatchTogether.sessionKey("ABC234", StreamingService.SPOTIFY, "Save Your Tears"),
        )
    }

    @Test fun fnvMatchesTheReferenceValues() {
        // Published FNV-1a 32-bit test vectors: the recipe must never change between versions.
        assertEquals(0x811c9dc5.toInt(), WatchTogether.fnv1a32(""))
        assertEquals(0xe40c292c.toInt(), WatchTogether.fnv1a32("a"))
        assertEquals(0xbf9cf968.toInt(), WatchTogether.fnv1a32("foobar"))
    }

    // ---- Who is together ----

    private fun member(uid: String, online: Boolean = true, reportedAt: Long? = null) =
        Member(uid, uid, online, positionMs = null, reportedAt = reportedAt)

    @Test fun togetherMeansFriendOnlineAndStillReporting() {
        val now = 1_000_000L
        val members = listOf(
            member("me"),
            member("harsh", reportedAt = now - 5_000),
            member("stale", reportedAt = now - 2 * min),
            member("offline", online = false),
            member("stranger"),
            member("fallback", reportedAt = null),
        )
        val friends = setOf("harsh", "stale", "offline", "fallback")
        assertEquals(setOf("harsh", "fallback"), WatchTogether.togetherFriends(members, "me", friends, now))
    }

    private fun room(status: PlaybackStatus?, vararg members: Member) = Room(
        code = "ABC234",
        hostUid = "me",
        createdAt = 0L,
        title = null,
        state = status?.let { RoomState(it, 0L, 0L, "me", 1L) },
        members = members.toList(),
        countdown = null,
    )

    private fun preview(title: String?, service: StreamingService? = StreamingService.NETFLIX, subtitle: String? = null) =
        RoomPreview("ABC234", "Me", service, title, subtitle, null, 0L)

    @Test fun sampleNeedsTitlePlayingAndAFriend() {
        val r = room(PlaybackStatus.PLAYING, member("me"), member("harsh"))
        val friends = setOf("harsh")
        val now = WatchTogether.sample(true, r, preview("Leo"), null, "me", friends, 0L)
        assertNotNull(now)
        assertEquals(setOf("harsh"), now!!.friends)
        assertEquals("Leo", now.title)
        assertEquals(StreamingService.NETFLIX, now.service)
        assertEquals(WatchTogether.sessionKey("ABC234", StreamingService.NETFLIX, "Leo"), now.sessionKey)

        assertNull("not on the title", WatchTogether.sample(false, r, preview("Leo"), null, "me", friends, 0L))
        assertNull("paused", WatchTogether.sample(true, room(PlaybackStatus.PAUSED, member("me"), member("harsh")), preview("Leo"), null, "me", friends, 0L))
        assertNull("no title", WatchTogether.sample(true, r, preview(null), null, "me", friends, 0L))
        assertNull("no service", WatchTogether.sample(true, r, preview("Leo", service = null), null, "me", friends, 0L))
        assertNull("not friends", WatchTogether.sample(true, r, preview("Leo"), null, "me", emptySet(), 0L))
        assertNull("signed out", WatchTogether.sample(true, r, preview("Leo"), null, null, friends, 0L))
        assertNotNull("service from the session", WatchTogether.sample(true, r, preview("Leo", service = null), StreamingService.NETFLIX, "me", friends, 0L))
    }

    @Test fun onlyJamsCountItems() {
        val r = room(PlaybackStatus.PLAYING, member("me"), member("harsh"))
        val friends = setOf("harsh")
        val episode = WatchTogether.sample(true, r, preview("Wednesday", subtitle = "S1:E2"), null, "me", friends, 0L)!!
        assertEquals("", episode.itemKey)
        val track = WatchTogether.sample(true, r, preview("Blinding Lights", StreamingService.SPOTIFY, "The Weeknd"), null, "me", friends, 0L)!!
        assertEquals("blinding lights the weeknd", track.itemKey)
        assertEquals(WatchTogether.sessionKey("ABC234", StreamingService.SPOTIFY, ""), track.sessionKey)
    }

    // ---- Tracker ----

    private fun now(key: String = "w-ABC234-00000001", friends: Set<String> = setOf("harsh"), item: String = "leo") =
        WatchTogetherTracker.Now(key, "Leo", StreamingService.NETFLIX, item, friends)

    /** Samples every 30 s from [from] to [to] (inclusive), returning all flushes. */
    private fun WatchTogetherTracker.run(from: Long, to: Long, sample: WatchTogetherTracker.Now?): List<WatchTogetherTracker.Flush> {
        val out = ArrayList<WatchTogetherTracker.Flush>()
        var t = from
        while (t <= to) {
            out += step(t, sample)
            t += 30_000L
        }
        return out
    }

    @Test fun nothingBeforeFiveMinutesThenOneCard() {
        val tracker = WatchTogetherTracker()
        assertTrue(tracker.run(0L, 4 * min + 30_000L, now()).isEmpty())
        val first = tracker.step(5 * min, now())
        assertEquals(1, first.size)
        assertEquals("harsh", first[0].friendUid)
        assertEquals(5 * min, first[0].report.togetherMs)
        assertEquals("w-ABC234-00000001", first[0].report.sessionKey)
        assertEquals(1, first[0].report.items)
    }

    @Test fun tooShortTogetherWritesNothingEvenWhenItEnds() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 3 * min, now())
        assertTrue(tracker.step(3 * min + 30_000L, null).isEmpty())
    }

    @Test fun updatesEveryFiveMinutesAndOnceMoreWhenItStops() {
        val tracker = WatchTogetherTracker()
        val flushes = tracker.run(0L, 12 * min, now())
        assertEquals(listOf(5 * min, 10 * min), flushes.map { it.report.togetherMs })
        // Paused, noticed at 13 min: the minute since the 12-minute sample still counts.
        val last = tracker.step(13 * min, null)
        assertEquals(listOf(13 * min), last.map { it.report.togetherMs })
        assertTrue(tracker.step(14 * min, null).isEmpty())
    }

    @Test fun pausesDontCountAndTheSameCardContinues() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 6 * min, now())
        tracker.run(6 * min + 30_000L, 20 * min, null)
        val after = tracker.run(20 * min + 30_000L, 26 * min, now())
        // 6 min before the pause (+30 s that ended at the pause sample), then 5.5 min more.
        assertEquals("w-ABC234-00000001", after.last().report.sessionKey)
        assertTrue(after.last().report.togetherMs in 11 * min..12 * min + 30_000L)
    }

    @Test fun aFrozenProcessCountsAtMostTheMaxStep() {
        val tracker = WatchTogetherTracker()
        tracker.step(0L, now())
        // 10 minutes without a sample (process frozen): only MAX_STEP_MS counts, so no card yet.
        tracker.step(10 * min, now())
        assertTrue(tracker.step(10 * min + 1, null).isEmpty())
    }

    @Test fun newTitleIsANewSession() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 6 * min, now(key = "w-ABC234-00000001"))
        val switch = tracker.step(6 * min + 30_000L, now(key = "w-ABC234-00000002"))
        assertEquals(listOf("w-ABC234-00000001"), switch.map { it.report.sessionKey })
        val second = tracker.run(7 * min, 12 * min, now(key = "w-ABC234-00000002"))
        assertEquals("w-ABC234-00000002", second.single().report.sessionKey)
        assertEquals(5 * min, second.single().report.togetherMs)
    }

    @Test fun eachFriendGetsTheirOwnTime() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 2 * min, now(friends = setOf("harsh")))
        val both = tracker.run(2 * min + 30_000L, 7 * min, now(friends = setOf("harsh", "ayan")))
        assertEquals(listOf("harsh"), both.map { it.friendUid })
        val later = tracker.run(7 * min + 30_000L, 8 * min, now(friends = setOf("harsh", "ayan")))
        assertEquals(listOf("ayan"), later.map { it.friendUid })
    }

    @Test fun jamsCountDistinctTracks() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 2 * min, now(item = "track a"))
        tracker.run(2 * min + 30_000L, 4 * min, now(item = "track b"))
        val flush = tracker.run(4 * min + 30_000L, 5 * min, now(item = "track a"))
        assertEquals(2, flush.single().report.items)
    }

    @Test fun adoptsTheLongerCardDuration() {
        val tracker = WatchTogetherTracker()
        val first = tracker.run(0L, 5 * min, now()).single()
        // The card already said 60 min (we restarted mid-movie): carry on from there.
        tracker.adopt("harsh", first.report.sessionKey, 60 * min)
        assertTrue(tracker.run(5 * min + 30_000L, 9 * min, now()).isEmpty())
        val stop = tracker.step(9 * min + 30_000L, null).single()
        assertEquals(60 * min + 4 * min + 30_000L, stop.report.togetherMs)
    }

    @Test fun longBreakStartsANewSession() {
        val tracker = WatchTogetherTracker()
        tracker.run(0L, 6 * min, now())
        tracker.step(6 * min + 30_000L, null)
        val start = 6 * min + 30_000L + WatchTogether.SESSION_GAP_MS + 1
        val again = tracker.run(start, start + 5 * min, now())
        assertEquals(5 * min, again.single().report.togetherMs)
    }

    // ---- Wording ----

    @Test fun cardWording() {
        assertEquals(
            "Watched Leo together · Netflix · 1h 42m",
            WatchTogether.cardText(WatchedTogether("Leo", StreamingService.NETFLIX, 102 * min)),
        )
        assertEquals(
            "Listened to Blinding Lights + 3 more together · Spotify · 45m",
            WatchTogether.cardText(WatchedTogether("Blinding Lights", StreamingService.SPOTIFY, 45 * min, items = 4)),
        )
        assertEquals("Watched Lo-fi beats together", WatchTogether.headline(WatchedTogether("Lo-fi beats", StreamingService.YOUTUBE, 10 * min)))
        assertEquals("Watched Leo together · 2h", WatchTogether.cardText(WatchedTogether("Leo", null, 120 * min)))
    }

    @Test fun durationFormat() {
        assertEquals("1m", WatchTogether.formatDuration(10_000L))
        assertEquals("5m", WatchTogether.formatDuration(5 * min + 59_000L))
        assertEquals("1h", WatchTogether.formatDuration(60 * min))
        assertEquals("2h 3m", WatchTogether.formatDuration(123 * min))
    }
}
