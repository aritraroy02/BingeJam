package com.syncit.app.ui.channels

import com.syncit.core.social.ChannelKind
import com.syncit.core.social.GroupChannel
import com.syncit.core.social.GroupChannels
import com.syncit.core.social.VoiceOccupant

/**
 * A group's channel list on the phone, the same as the PC's (desktop data/channelModel.ts): pure,
 * unit-tested in ChannelModelTest.
 */
object ChannelModel {
    val BUILT_IN: List<GroupChannel> = listOf(
        GroupChannel(GroupChannels.GENERAL, "general", ChannelKind.TEXT, -1.0, 0L, builtIn = true),
        GroupChannel(GroupChannels.LOUNGE, "General", ChannelKind.VOICE, -1.0, 0L, builtIn = true),
    )

    /** Built-in first, then by order, then oldest first; split into text and voice. */
    fun list(stored: List<GroupChannel>): Pair<List<GroupChannel>, List<GroupChannel>> {
        val all = (BUILT_IN + stored.filter { it.id != GroupChannels.GENERAL && it.id != GroupChannels.LOUNGE })
            .sortedWith(compareBy<GroupChannel>({ it.order }, { it.createdAt }, { it.id }))
        return all.filter { it.kind == ChannelKind.TEXT } to all.filter { it.kind == ChannelKind.VOICE }
    }

    /** Something new from someone else since we last read the channel. */
    fun unread(newestFrom: String?, newestAt: Long, readAt: Long, me: String?): Boolean =
        newestFrom != null && newestFrom != me && newestAt > readAt

    /** "4:07", "1:15:05" from the first one still in (server times); null when nobody's in. */
    fun callElapsed(people: List<VoiceOccupant>, nowMs: Long): String? {
        if (people.isEmpty()) return null
        val s = ((nowMs - people.minOf { it.since }) / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "#movie-picks" for text channels; voice channels as named. */
    fun label(c: GroupChannel): String = if (c.kind == ChannelKind.TEXT) "#${c.name}" else c.name

    private val CITIES = mapOf(
        "BOM" to "Mumbai", "DEL" to "New Delhi", "MAA" to "Chennai", "BLR" to "Bengaluru", "HYD" to "Hyderabad",
        "CCU" to "Kolkata", "AMD" to "Ahmedabad", "COK" to "Kochi", "NAG" to "Nagpur", "PAT" to "Patna",
        "IXC" to "Chandigarh", "BBI" to "Bhubaneswar", "CMB" to "Colombo", "DAC" to "Dhaka", "KTM" to "Kathmandu",
        "KHI" to "Karachi", "DXB" to "Dubai", "MCT" to "Muscat", "DOH" to "Doha", "SIN" to "Singapore",
        "KUL" to "Kuala Lumpur", "BKK" to "Bangkok", "CGK" to "Jakarta", "MNL" to "Manila", "HKG" to "Hong Kong",
        "TPE" to "Taipei", "NRT" to "Tokyo", "KIX" to "Osaka", "ICN" to "Seoul", "SYD" to "Sydney", "MEL" to "Melbourne",
        "LHR" to "London", "AMS" to "Amsterdam", "FRA" to "Frankfurt", "CDG" to "Paris", "MAD" to "Madrid",
        "ARN" to "Stockholm", "IAD" to "Ashburn", "EWR" to "Newark", "ORD" to "Chicago", "DFW" to "Dallas",
        "LAX" to "Los Angeles", "SJC" to "San Jose", "SEA" to "Seattle", "YYZ" to "Toronto", "GRU" to "São Paulo",
    )

    /** "Singapore" for SIN; the code itself when unknown. */
    fun cityOf(colo: String?): String? = colo?.let { CITIES[it] ?: it }

    /** "Singapore · 42 ms", like the PC's voice panel. */
    fun serverLine(server: String?, pingMs: Int?): String = when {
        server != null && pingMs != null -> "${cityOf(server)} · $pingMs ms"
        server != null -> cityOf(server)!!
        pingMs != null -> "$pingMs ms"
        else -> "Finding a voice server"
    }
}
