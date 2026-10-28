package com.syncit.app.social

import com.syncit.core.config.Brand
import com.syncit.core.config.RoomLinks
import com.syncit.core.model.ActivityApp
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.RoomInvite
import com.syncit.core.model.StreamingService

/**
 * Wording that differs between a **watch party** (Netflix, JioHotstar, Prime Video) and a **jam**
 * (Spotify, YouTube Music, Amazon Music, YouTube; StreamingService.isJam). One place so every
 * screen says the same thing. Pure Kotlin: unit-tested in RoomWordingTest.
 */
object RoomWording {

    fun isJam(app: ActivityApp?): Boolean = app?.syncService?.isJam == true

    fun isJam(service: StreamingService?): Boolean = service?.isJam == true

    /** "jam" / "watch party". */
    fun roomNoun(jam: Boolean): String = if (jam) "jam" else "watch party"

    /** The friend-row action: "Ask to jam" / "Ask to join", or the "already asked" state. */
    fun askLabel(jam: Boolean, requested: Boolean): String = when {
        jam && requested -> "Asked to jam"
        jam -> "Ask to jam"
        requested -> "Asked to join"
        else -> "Ask to join"
    }

    /** Under a friend who's syncing a room right now. */
    fun inRoomLabel(jam: Boolean): String = if (jam) "In a jam" else "In a watch party"

    /** Host side: "@riya wants to jam with you to Blinding Lights". */
    fun joinRequestText(request: JoinRequest): String {
        val title = request.title?.trim()?.takeIf { it.isNotEmpty() }
        return if (isJam(request.app)) {
            "@${request.fromUsername} wants to jam with you" + (title?.let { " to $it" } ?: " on ${request.app.displayName}")
        } else {
            "@${request.fromUsername} wants to join you" + (title?.let { " watching $it" } ?: " on ${request.app.displayName}")
        }
    }

    /** Invitee side: "@riya invited you to jam to Blinding Lights". */
    fun inviteText(invite: RoomInvite): String {
        val title = invite.title?.trim()?.takeIf { it.isNotEmpty() }
        return if (isJam(invite.app)) {
            "@${invite.fromUsername} invited you to jam" +
                (title?.let { " to $it" } ?: invite.app?.let { " on ${it.displayName}" }.orEmpty())
        } else {
            "@${invite.fromUsername} invited you to watch" +
                (title?.let { " $it" } ?: invite.app?.let { " on ${it.displayName}" } ?: " together")
        }
    }

    /** Host side, after accepting. */
    fun joiningNotice(request: JoinRequest): String =
        "@${request.fromUsername} is joining your ${roomNoun(isJam(request.app))}"

    /** Requester side, right after asking. */
    fun askedNotice(displayName: String, jam: Boolean): String =
        if (jam) "Asked $displayName to jam. You'll hop in on their track as soon as they accept."
        else "Asked $displayName. You'll join as soon as they accept."

    /** Share-sheet text for the room's invite link. */
    fun shareText(code: String, jam: Boolean): String =
        "Join my ${Brand.NAME} ${roomNoun(jam)}! Room code: $code\n${RoomLinks.shareLink(code)}"
}
