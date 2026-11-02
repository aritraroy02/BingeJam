package com.syncit.firebase.social

/**
 * "Watched together" cards in a DM thread: dms/{dmId}/{key} = {kind: "watch", by, title, service,
 * durationMs, items, sentAt, lastAt}. Pure, so the slot choice is unit-tested (WatchCardsTest).
 *
 * Both friends' phones record the same session, so the key is deterministic: the app derives a
 * base key from the room code, app and title (`w-ABC234-1a2b3c4d`, see WatchTogether in :app) and
 * both phones walk the same slots: the base key, then `-2`, `-3`, … A slot holding a card that
 * was updated within [STALE_MS] (server time) is this session's card: join it. An older card is
 * an earlier session in the same room with the same title: try the next slot. An empty slot:
 * create the card there. Both phones see the same data, so they land on the same slot; if both
 * create at once, the rules reject the second write (sentAt and by must not change) and that
 * phone reads the slot again and joins.
 *
 * Rules (database.rules.json, dms/$dmId/$messageId) match these limits: either participant may
 * create or extend a card, durationMs and items only grow, title/service/by/sentAt never change.
 */
internal object WatchCards {
    const val KIND = "watch"

    /** A card not updated for this long belongs to an earlier session. */
    const val STALE_MS = 2 * 60 * 60_000L

    /** Never walk further than this many slots (the same title in the same room 20 times). */
    const val MAX_SLOTS = 20

    /** Rules: durationMs between these. */
    const val MIN_DURATION_MS = 5 * 60_000L
    const val MAX_DURATION_MS = 24 * 60 * 60_000L

    const val MAX_ITEMS = 1000
    const val MAX_TITLE_LENGTH = 200

    /** Base keys the app derives (rules accept these plus a `-2`…`-999` slot suffix). */
    private val BASE_KEY = Regex("^w-[A-HJ-NP-Z2-9]{6}-[0-9a-f]{8}$")

    fun isValidBaseKey(key: String): Boolean = BASE_KEY.matches(key)

    /** Slot 0 is the base key itself; later sessions get "-2", "-3", … */
    fun slotKey(baseKey: String, slot: Int): String = if (slot == 0) baseKey else "$baseKey-${slot + 1}"

    sealed interface Decision {
        data object Create : Decision

        data class Join(val durationMs: Long, val items: Int) : Decision

        data object Next : Decision
    }

    /** What to do with a slot whose current value is [existing] (null = empty). */
    fun decide(existing: Map<*, *>?, nowServerMs: Long): Decision {
        if (existing == null) return Decision.Create
        if (existing["kind"] != KIND) return Decision.Next
        val lastAt = (existing["lastAt"] as? Number)?.toLong() ?: (existing["sentAt"] as? Number)?.toLong() ?: 0L
        if (nowServerMs - lastAt > STALE_MS) return Decision.Next
        val duration = (existing["durationMs"] as? Number)?.toLong() ?: 0L
        val items = (existing["items"] as? Number)?.toInt() ?: 1
        return Decision.Join(duration, items)
    }

    /** Duration to write: never less than the card's (rules: it only grows), within the rules' range. */
    fun mergedDuration(existingMs: Long, ourMs: Long): Long =
        maxOf(existingMs, ourMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))

    fun mergedItems(existing: Int, ours: Int): Int = maxOf(existing, ours.coerceIn(1, MAX_ITEMS))
}
