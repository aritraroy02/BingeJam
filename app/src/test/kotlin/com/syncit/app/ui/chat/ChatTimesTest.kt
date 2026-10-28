package com.syncit.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class ChatTimesTest {
    private val india = ZoneId.of("Asia/Kolkata")
    private val en = Locale.ENGLISH

    /** Epoch ms for a wall-clock time in [zone]. */
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = india) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // Thursday 29 October 2026, 10:00 in India.
    private val now = at(2026, 10, 29, 10, 0)

    private fun day(sentAt: Long, nowMs: Long = now) = ChatTimes.dayLabel(sentAt, nowMs, india, en)

    @Test fun timeLabelFollows12And24HourSetting() {
        val t = at(2026, 10, 29, 22, 42)
        assertEquals("10:42 pm", ChatTimes.timeLabel(t, now, india, is24h = false, locale = en))
        assertEquals("22:42", ChatTimes.timeLabel(t, now, india, is24h = true, locale = en))
        val morning = at(2026, 10, 29, 0, 5)
        assertEquals("12:05 am", ChatTimes.timeLabel(morning, now, india, is24h = false, locale = en))
        assertEquals("00:05", ChatTimes.timeLabel(morning, now, india, is24h = true, locale = en))
    }

    @Test fun timeLabelUsesTheGivenZone() {
        val t = at(2026, 10, 29, 22, 42) // 17:12 UTC
        assertEquals("17:12", ChatTimes.timeLabel(t, now, ZoneId.of("UTC"), is24h = true, locale = en))
    }

    @Test fun pendingMessageShowsAsNow() {
        assertEquals("10:00 am", ChatTimes.timeLabel(0L, now, india, is24h = false, locale = en))
        assertEquals("Today", day(0L))
    }

    @Test fun midnightBoundaries() {
        val justAfterMidnight = at(2026, 10, 29, 0, 0, 1)
        assertEquals("Today", ChatTimes.dayLabel(at(2026, 10, 29, 0, 0, 0), justAfterMidnight, india, en))
        assertEquals("Yesterday", ChatTimes.dayLabel(at(2026, 10, 28, 23, 59, 59), justAfterMidnight, india, en))
        // One second before midnight, a message from the same evening is still today.
        val lateEvening = at(2026, 10, 29, 23, 59, 59)
        assertEquals("Today", ChatTimes.dayLabel(at(2026, 10, 29, 0, 0), lateEvening, india, en))
    }

    @Test fun dayDependsOnTheZone() {
        // 29 Oct 02:00 in India is still 28 Oct (20:30) in UTC.
        val utc = ZoneId.of("UTC")
        val nowUtc = at(2026, 10, 29, 10, 0, zone = utc)
        assertEquals("Yesterday", ChatTimes.dayLabel(at(2026, 10, 29, 2, 0), nowUtc, utc, en))
        assertEquals("Today", ChatTimes.dayLabel(at(2026, 10, 29, 2, 0), at(2026, 10, 29, 10, 0), india, en))
    }

    @Test fun yesterdayAndLastWeek() {
        assertEquals("Today", day(at(2026, 10, 29, 9, 59)))
        assertEquals("Yesterday", day(at(2026, 10, 28, 8, 0)))
        assertEquals("Tuesday", day(at(2026, 10, 27, 8, 0)))
        // Six days back is still a weekday name; seven days back becomes a date.
        assertEquals("Friday", day(at(2026, 10, 23, 23, 0)))
        assertEquals("22 October", day(at(2026, 10, 22, 12, 0)))
    }

    @Test fun olderDatesShowTheYearOnlyWhenItDiffers() {
        assertEquals("12 October", day(at(2026, 10, 12, 12, 0)))
        assertEquals("1 January", day(at(2026, 1, 1, 0, 0)))
        assertEquals("31 December 2025", day(at(2025, 12, 31, 23, 0)))
        // On 1 January, 31 December is "Yesterday", not a date.
        assertEquals("Yesterday", day(at(2025, 12, 31, 23, 0), at(2026, 1, 1, 0, 30)))
    }

    @Test fun aStampSlightlyAheadOfOurClockIsToday() {
        assertEquals("Today", day(at(2026, 10, 30, 0, 0, 1), at(2026, 10, 29, 23, 59, 59)))
    }

    @Test fun grouping() {
        val t = at(2026, 10, 29, 9, 0)
        assertTrue(ChatTimes.isGrouped("a", t, "a", t + 60_000, now, india))
        assertTrue(ChatTimes.isGrouped("a", t, "a", t + ChatTimes.GROUP_WINDOW_MS, now, india))
        assertFalse(ChatTimes.isGrouped("a", t, "a", t + ChatTimes.GROUP_WINDOW_MS + 1, now, india))
        assertFalse(ChatTimes.isGrouped("a", t, "b", t + 1_000, now, india))
        assertFalse(ChatTimes.isGrouped(null, 0L, "a", t, now, india))
        // Never across midnight, even a minute apart.
        assertFalse(ChatTimes.isGrouped("a", at(2026, 10, 28, 23, 59, 30), "a", at(2026, 10, 29, 0, 0, 10), now, india))
    }

    private data class Msg(val id: String, val from: String, val at: Long)

    private fun rows(vararg msgs: Msg) =
        ChatTimes.rows(msgs.toList(), { it.id }, { it.from }, { it.at }, now, india, is24h = false, locale = en)

    @Test fun rowsPutSeparatorsBetweenDaysAndGroupMessages() {
        val rows = rows(
            Msg("1", "a", at(2026, 10, 27, 21, 0)),
            Msg("2", "a", at(2026, 10, 28, 23, 58)),
            Msg("3", "a", at(2026, 10, 28, 23, 59)),
            Msg("4", "b", at(2026, 10, 29, 0, 1)),
            Msg("5", "b", at(2026, 10, 29, 0, 2)),
            Msg("6", "b", 0L), // just sent, server stamp pending
        )
        val shape = rows.map {
            when (it) {
                is ChatTimes.Row.Day -> "[${it.label}]"
                is ChatTimes.Row.Message -> it.item.id + (if (it.grouped) "+" else "") + " " + it.time
            }
        }
        assertEquals(
            listOf(
                "[Tuesday]", "1 9:00 pm",
                "[Yesterday]", "2 11:58 pm", "3+ 11:59 pm",
                "[Today]", "4 12:01 am", "5+ 12:02 am",
                // Pending counts as sent now (10:00), too long after 12:02 to group.
                "6 10:00 am",
            ),
            shape,
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test fun rowKeysStayUniqueWhenStampsGoBackwards() {
        val rows = rows(
            Msg("1", "a", at(2026, 10, 28, 9, 0)),
            Msg("2", "a", at(2026, 10, 29, 9, 0)),
            Msg("3", "a", at(2026, 10, 28, 9, 5)),
        )
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertEquals(6, rows.size)
    }

    @Test fun emptyChatHasNoRows() {
        assertTrue(rows().isEmpty())
    }
}
