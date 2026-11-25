package com.syncit.core.social

import com.syncit.core.model.DirectMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A group's channels, Discord style (PC 2026-11-22, phones 2026-11-25), same data as the PC app:
 * - groups/{gid}/channels/{cid} {name, kind text|voice, order, createdBy, createdAt}: admins add, rename, delete
 * - groupChannelMessages/{gid}/{cid}/{msgId} {from, text, sentAt}: text channels other than #general
 * - groupChannelReads/{uid}/{gid}/{cid}: read up to (server time)
 * - groupVoice/{gid}/{cid}/{uid} {name, since, muted, deafened, live}: who's in a voice channel (onDisconnect removes)
 * - groupVoiceStatus/{gid}/{cid} {text, by, at}: "Set a channel status"
 * Every group also has two channels nobody stores: #general ([GENERAL], the group chat in
 * groupMessages/{gid}) and the "General" voice channel ([LOUNGE]). Implemented in :data-firebase.
 */
interface GroupChannels {
    /** The stored channels (not the two built-in ones). */
    fun observeChannels(groupId: String): Flow<List<GroupChannel>>

    /** Channel id → who's in it, first to join first. */
    fun observeVoice(groupId: String): Flow<Map<String, List<VoiceOccupant>>>

    /** Channel id → its status text. */
    fun observeVoiceStatuses(groupId: String): Flow<Map<String, String>>

    /** Channel id → read up to (server time), ours. */
    fun observeReads(groupId: String): Flow<Map<String, Long>>

    /** The newest message of a text channel (not #general), for the unread dot. */
    fun observeNewest(groupId: String, channelId: String): Flow<DirectMessage?>

    /** Oldest first. Not for #general (that's [GroupChats.observeMessages]). */
    fun observeMessages(groupId: String, channelId: String, limit: Int = 100): Flow<List<DirectMessage>>

    suspend fun send(groupId: String, channelId: String, text: String)

    suspend fun markRead(groupId: String, channelId: String)

    /** Admins: a new channel, last of its kind. Returns its id. */
    suspend fun create(groupId: String, name: String, kind: ChannelKind): String

    suspend fun rename(groupId: String, channelId: String, name: String, kind: ChannelKind)

    /** Admins: the channel goes with its messages, status and anyone still listed in it. */
    suspend fun delete(groupId: String, channelId: String)

    /** We're in a voice channel: listed until we leave or drop off (onDisconnect). */
    suspend fun joinVoice(groupId: String, channelId: String, name: String, muted: Boolean, deafened: Boolean)

    suspend fun updateVoice(groupId: String, channelId: String, muted: Boolean, deafened: Boolean)

    /** The last one out also clears the channel's status. */
    suspend fun leaveVoice(groupId: String, channelId: String)

    /** Anyone in the voice channel; blank clears it. */
    suspend fun setStatus(groupId: String, channelId: String, text: String)

    companion object {
        const val GENERAL = "general"
        const val LOUNGE = "lounge"
        const val NAME_MAX = 32
        const val STATUS_MAX = 60
    }

    /** No channels (the in-memory fakes): only the two built-in ones show. */
    object None : GroupChannels {
        override fun observeChannels(groupId: String): Flow<List<GroupChannel>> = flowOf(emptyList())
        override fun observeVoice(groupId: String): Flow<Map<String, List<VoiceOccupant>>> = flowOf(emptyMap())
        override fun observeVoiceStatuses(groupId: String): Flow<Map<String, String>> = flowOf(emptyMap())
        override fun observeReads(groupId: String): Flow<Map<String, Long>> = flowOf(emptyMap())
        override fun observeNewest(groupId: String, channelId: String): Flow<DirectMessage?> = flowOf(null)
        override fun observeMessages(groupId: String, channelId: String, limit: Int): Flow<List<DirectMessage>> = flowOf(emptyList())
        override suspend fun send(groupId: String, channelId: String, text: String) = Unit
        override suspend fun markRead(groupId: String, channelId: String) = Unit
        override suspend fun create(groupId: String, name: String, kind: ChannelKind): String = error("Channels aren't available")
        override suspend fun rename(groupId: String, channelId: String, name: String, kind: ChannelKind) = Unit
        override suspend fun delete(groupId: String, channelId: String) = Unit
        override suspend fun joinVoice(groupId: String, channelId: String, name: String, muted: Boolean, deafened: Boolean) = Unit
        override suspend fun updateVoice(groupId: String, channelId: String, muted: Boolean, deafened: Boolean) = Unit
        override suspend fun leaveVoice(groupId: String, channelId: String) = Unit
        override suspend fun setStatus(groupId: String, channelId: String, text: String) = Unit
    }
}

enum class ChannelKind(val wire: String) {
    TEXT("text"),
    VOICE("voice");

    companion object {
        fun fromWire(value: String?): ChannelKind? = entries.firstOrNull { it.wire == value }
    }
}

/** [builtIn]: #general and the General voice channel, always there, never renamed or deleted. */
data class GroupChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    val order: Double,
    val createdAt: Long,
    val builtIn: Boolean = false,
)

/** Someone in a voice channel. [since] is server time; [live] = sharing their screen. */
data class VoiceOccupant(
    val uid: String,
    val name: String,
    val since: Long,
    val muted: Boolean,
    val deafened: Boolean,
    val live: Boolean,
)
