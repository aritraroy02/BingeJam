package com.syncit.firebase.social

import android.util.Base64
import com.google.firebase.database.DataSnapshot
import com.syncit.core.model.Activity
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.DirectMessage
import com.syncit.core.model.FriendRequest
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.Presence
import com.syncit.core.model.RoomInvite
import com.syncit.core.model.UserProfile
import com.syncit.firebase.bool
import com.syncit.firebase.long
import com.syncit.firebase.string

/** DataSnapshot → :core social models. Tolerant of missing fields; never throws. */
internal object SocialParser {

    fun profile(uid: String, s: DataSnapshot?): UserProfile? {
        if (s == null || !s.exists()) return null
        return UserProfile(
            uid = uid,
            username = s.child("username").string() ?: return null,
            displayName = s.child("displayName").string() ?: return null,
            photoUrl = s.child("photoUrl").string(),
            bio = s.child("bio").string(),
            createdAt = s.child("createdAt").long() ?: 0L,
        )
    }

    fun presence(s: DataSnapshot?): Presence? {
        if (s == null || !s.exists()) return null
        return Presence(
            online = s.child("online").bool() ?: false,
            lastSeen = s.child("lastSeen").long(),
            activity = activity(s.child("activity")),
        )
    }

    fun activity(s: DataSnapshot): Activity? {
        if (!s.exists()) return null
        return Activity(
            app = ActivityApp.fromWire(s.child("app").string()) ?: return null,
            title = s.child("title").string(),
            subtitle = s.child("subtitle").string(),
            artworkJpeg = s.child("artwork").string()?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
            playing = s.child("playing").bool() ?: false,
            durationMs = s.child("durationMs").long(),
            startedAt = s.child("startedAt").long() ?: 0L,
            inWatchParty = s.child("inWatchParty").bool() ?: false,
            watchingWith = s.child("watchingWith").string()?.takeIf { it.isNotBlank() },
        )
    }

    /** friendRequests/{to}/{from}. */
    fun friendRequest(toUid: String, s: DataSnapshot?): FriendRequest? {
        if (s == null || !s.exists()) return null
        return FriendRequest(
            fromUid = s.key ?: return null,
            toUid = toUid,
            fromUsername = s.child("fromUsername").string().orEmpty(),
            fromDisplayName = s.child("fromDisplayName").string().orEmpty(),
            toUsername = s.child("toUsername").string().orEmpty(),
            toDisplayName = s.child("toDisplayName").string().orEmpty(),
            sentAt = s.child("sentAt").long() ?: 0L,
        )
    }

    fun message(s: DataSnapshot): DirectMessage? {
        return DirectMessage(
            id = s.key ?: return null,
            fromUid = s.child("from").string() ?: return null,
            text = s.child("text").string() ?: return null,
            sentAt = s.child("sentAt").long() ?: 0L,
        )
    }

    /** joinRequests/{host}/{from}. */
    fun joinRequest(hostUid: String, s: DataSnapshot?): JoinRequest? {
        if (s == null || !s.exists()) return null
        return JoinRequest(
            hostUid = hostUid,
            fromUid = s.key ?: return null,
            fromUsername = s.child("fromUsername").string().orEmpty(),
            fromDisplayName = s.child("fromDisplayName").string().orEmpty(),
            app = ActivityApp.fromWire(s.child("app").string()) ?: return null,
            title = s.child("title").string(),
            status = joinStatus(s.child("status").string()) ?: return null,
            roomCode = s.child("roomCode").string(),
            sentAt = s.child("sentAt").long() ?: 0L,
            answeredAt = s.child("answeredAt").long(),
        )
    }

    /** invites/{to}/{from}. */
    fun invite(s: DataSnapshot): RoomInvite? {
        return RoomInvite(
            fromUid = s.key ?: return null,
            fromUsername = s.child("fromUsername").string().orEmpty(),
            fromDisplayName = s.child("fromDisplayName").string().orEmpty(),
            roomCode = s.child("roomCode").string() ?: return null,
            app = ActivityApp.fromWire(s.child("app").string()),
            title = s.child("title").string(),
            sentAt = s.child("sentAt").long() ?: 0L,
            declined = s.child("declined").bool() ?: false,
        )
    }

    /** Statuses are stored as lowercase strings ("pending", "accepted", "declined"). */
    fun joinStatus(wire: String?): JoinStatus? = JoinStatus.entries.firstOrNull { it.wire() == wire }

    fun JoinStatus.wire(): String = name.lowercase()
}
