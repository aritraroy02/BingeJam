package com.syncit.app.ui.chat

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * WhatsApp-style message times and day separators for the DM and room chats. Pure, so it's
 * unit-tested (ChatTimesTest).
 *
 * Timing: [sentAt] and `now` are both server time in epoch ms (messages are stamped with
 * ServerValue.TIMESTAMP; callers pass ServerClock.serverNowMs()). Turning them into a wall-clock
 * label with the phone's time zone is display-only, which CLAUDE.md's timing rule allows (that
 * rule is about sync decisions). A just-sent message can briefly have sentAt 0 before the server
 * value arrives; it's shown as sent "now".
 */
object ChatTimes {
    /** Consecutive messages from one sender closer than this sit tighter together. */
    const val GROUP_WINDOW_MS = 5 * 60_000L

    /** Separator labels use weekday names for dates up to this many days back. */
    private const val WEEKDAY_DAYS = 6L

    /** The time to show for [sentAt]; a missing/pending timestamp counts as [now]. */
    fun effectiveSentAt(sentAt: Long, now: Long): Long = if (sentAt > 0) sentAt else now

    /** "10:42 pm" or "22:42" (the phone's 12/24-hour setting), in [zone]. */
    fun timeLabel(sentAt: Long, now: Long, zone: ZoneId, is24h: Boolean, locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(effectiveSentAt(sentAt, now)).atZone(zone)
        return if (is24h) {
            time.format(DateTimeFormatter.ofPattern("HH:mm", locale))
        } else {
            // WhatsApp writes "pm" in lower case.
            time.format(DateTimeFormatter.ofPattern("h:mm a", locale)).lowercase(locale)
        }
    }

    /** The local calendar day a message belongs to (separators split on this). */
    fun dayOf(sentAt: Long, now: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(effectiveSentAt(sentAt, now)).atZone(zone).toLocalDate()

    /**
     * Separator text: "Today", "Yesterday", the weekday within the last week, otherwise
     * "12 October" (this year) or "12 October 2025".
     */
    fun dayLabel(sentAt: Long, now: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String {
        val day = dayOf(sentAt, now, zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(day, today)
        return when {
            // <= 0: a server stamp slightly ahead of our clock across midnight still reads "Today".
            daysAgo <= 0 -> "Today"
            daysAgo == 1L -> "Yesterday"
            daysAgo <= WEEKDAY_DAYS -> day.format(DateTimeFormatter.ofPattern("EEEE", locale))
            day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMMM", locale))
            else -> day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
        }
    }

    /**
     * Whether a message continues the previous one's group: same sender, same day, and sent
     * within [GROUP_WINDOW_MS]. Grouped messages get tighter spacing and (in rooms) no repeat name.
     */
    fun isGrouped(
        previousSender: String?,
        previousSentAt: Long,
        sender: String,
        sentAt: Long,
        now: Long,
        zone: ZoneId,
    ): Boolean {
        if (previousSender == null || previousSender != sender) return false
        val prev = effectiveSentAt(previousSentAt, now)
        val cur = effectiveSentAt(sentAt, now)
        if (dayOf(prev, now, zone) != dayOf(cur, now, zone)) return false
        return cur - prev in 0..GROUP_WINDOW_MS
    }

    /** A row of the chat list: a day separator or a message (with its time and grouping). */
    sealed interface Row<out T> {
        val key: String

        data class Day(val label: String, override val key: String) : Row<Nothing>

        data class Message<T>(val item: T, val time: String, val grouped: Boolean, override val key: String) : Row<T>
    }

    /** Messages (oldest first) → rows with a separator before the first message of each day. */
    fun <T> rows(
        items: List<T>,
        id: (T) -> String,
        sender: (T) -> String,
        sentAt: (T) -> Long,
        now: Long,
        zone: ZoneId,
        is24h: Boolean,
        locale: Locale = Locale.getDefault(),
    ): List<Row<T>> {
        val out = ArrayList<Row<T>>(items.size + 4)
        var previous: T? = null
        var previousDay: LocalDate? = null
        val seenDays = HashSet<LocalDate>()
        for ((index, item) in items.withIndex()) {
            val at = sentAt(item)
            val day = dayOf(at, now, zone)
            val newDay = day != previousDay
            if (newDay) {
                // Lazy list keys must be unique, even if out-of-order stamps revisit a day.
                val key = if (seenDays.add(day)) "day-${day.toEpochDay()}" else "day-${day.toEpochDay()}-$index"
                out += Row.Day(dayLabel(at, now, zone, locale), key)
            }
            val grouped = !newDay && previous != null &&
                isGrouped(sender(previous), sentAt(previous), sender(item), at, now, zone)
            out += Row.Message(item, timeLabel(at, now, zone, is24h, locale), grouped, id(item))
            previous = item
            previousDay = day
        }
        return out
    }
}
