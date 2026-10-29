package com.syncit.firebase

import android.util.Base64
import com.google.firebase.database.DataSnapshot
import com.syncit.core.model.ChatMessage
import com.syncit.core.model.Countdown
import com.syncit.core.model.Member
import com.syncit.core.model.PlaybackStatus
import com.syncit.core.model.Reaction
import com.syncit.core.model.Room
import com.syncit.core.model.RoomPreview
import com.syncit.core.model.RoomState
import com.syncit.core.model.RoomTitle
import com.syncit.core.model.StreamingService

/** DataSnapshot → :core models. Tolerant of missing fields; never throws. */
internal object RoomParser {

    fun room(code: String, s: DataSnapshot): Room? {
        if (!s.exists()) return null
        val hostUid = s.child("hostUid").string() ?: return null
        return Room(
            code = code,
            hostUid = hostUid,
            createdAt = s.child("createdAt").long() ?: 0L,
            title = title(s.child("title")),
            state = state(s.child("state")),
            members = s.child("members").children.mapNotNull(::member),
            countdown = countdown(s.child("countdown")),
        )
    }

    private fun state(s: DataSnapshot): RoomState? {
        if (!s.exists()) return null
        val status = PlaybackStatus.fromWire(s.child("status").string()) ?: return null
        return RoomState(
            status = status,
            positionMs = s.child("positionMs").long() ?: 0L,
            updatedAt = s.child("updatedAt").long() ?: 0L,
            updatedBy = s.child("updatedBy").string().orEmpty(),
            seq = s.child("seq").long() ?: 0L,
        )
    }

    private fun member(s: DataSnapshot): Member? {
        val uid = s.key ?: return null
        return Member(
            uid = uid,
            name = s.child("name").string() ?: return null,
            online = s.child("online").value as? Boolean ?: false,
            positionMs = s.child("positionMs").long(),
            reportedAt = s.child("reportedAt").long(),
            lastSeen = s.child("lastSeen").long(),
            inVoice = s.child("voice").value as? Boolean ?: false,
        )
    }

    private fun title(s: DataSnapshot): RoomTitle? {
        if (!s.exists()) return null
        return RoomTitle(
            service = StreamingService.fromId(s.child("service").string()) ?: return null,
            deepLink = s.child("deepLink").string() ?: return null,
            setBy = s.child("setBy").string().orEmpty(),
        )
    }

    private fun countdown(s: DataSnapshot): Countdown? {
        if (!s.exists()) return null
        return Countdown(
            requestedAt = s.child("requestedAt").long() ?: return null,
            leadMs = s.child("leadMs").long() ?: return null,
            startedBy = s.child("startedBy").string().orEmpty(),
        )
    }

    fun chat(s: DataSnapshot): ChatMessage? {
        return ChatMessage(
            id = s.key ?: return null,
            uid = s.child("uid").string() ?: return null,
            name = s.child("name").string().orEmpty(),
            text = s.child("text").string() ?: return null,
            sentAt = s.child("sentAt").long() ?: 0L,
        )
    }

    fun reaction(s: DataSnapshot): Reaction? {
        return Reaction(
            id = s.key ?: return null,
            uid = s.child("uid").string() ?: return null,
            name = s.child("name").string().orEmpty(),
            emoji = s.child("emoji").string() ?: return null,
            sentAt = s.child("sentAt").long() ?: 0L,
        )
    }

    fun preview(code: String, s: DataSnapshot): RoomPreview? {
        if (!s.exists()) return null
        val values = s.value as? Map<*, *> ?: return null
        return preview(code, values) { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() }
    }

    /**
     * previews/{code} from its raw values (what DataSnapshot.value returns), so it can be unit
     * tested without Android. A service this build doesn't know (an older client reading a jam, or
     * a future app) parses to null rather than failing; a playableRef in an unknown format is dropped.
     */
    internal fun preview(code: String, values: Map<*, *>, decodeArtwork: (String) -> ByteArray?): RoomPreview? {
        return RoomPreview(
            code = code,
            hostName = values["hostName"] as? String ?: return null,
            service = StreamingService.fromId(values["service"] as? String),
            title = values["title"] as? String,
            subtitle = values["subtitle"] as? String,
            artworkJpeg = (values["artwork"] as? String)?.let(decodeArtwork),
            updatedAt = (values["updatedAt"] as? Number)?.toLong() ?: 0L,
            durationMs = (values["durationMs"] as? Number)?.toLong(),
            playableRef = (values["playableRef"] as? String)?.takeIf(PlayableRefs::isValid),
        )
    }

    private fun DataSnapshot.string(): String? = value as? String
    private fun DataSnapshot.long(): Long? = (value as? Number)?.toLong()
}
