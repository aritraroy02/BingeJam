package com.syncit.firebase.social

/**
 * A user's online state across all their devices. presence/{uid} holds:
 *
 *   online, lastSeen                      legacy fields, written by phones (0.6.x and later)
 *   devices/{deviceId} { kind, online, lastSeen, label? }
 *                                         one entry per device (the desktop app; maybe phones later),
 *                                         each with its own onDisconnect
 *
 * Someone is online if the legacy flag or ANY device says so, so the phone dropping doesn't hide
 * a PC that's still connected (and the other way round). Last seen is the latest of all of them;
 * every value is server time (ServerValue.TIMESTAMP), so they compare directly.
 *
 * Pure Kotlin over raw snapshot values (Boolean, Number, Map) so it's unit-tested; anything of
 * the wrong type is ignored, never thrown on.
 */
internal object PresenceStatus {

    data class Status(val online: Boolean, val lastSeen: Long?)

    /**
     * @param online raw value of presence/{uid}/online
     * @param lastSeen raw value of presence/{uid}/lastSeen
     * @param devices raw values of the presence/{uid}/devices/{deviceId} entries (Maps)
     */
    fun of(online: Any?, lastSeen: Any?, devices: Collection<Any?>): Status {
        var anyOnline = online == true
        var latest = (lastSeen as? Number)?.toLong()
        for (entry in devices) {
            val device = entry as? Map<*, *> ?: continue
            if (device["online"] == true) anyOnline = true
            val seen = (device["lastSeen"] as? Number)?.toLong() ?: continue
            if (latest == null || seen > latest) latest = seen
        }
        return Status(anyOnline, latest)
    }
}
