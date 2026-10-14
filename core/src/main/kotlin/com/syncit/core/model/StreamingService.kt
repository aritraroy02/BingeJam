package com.syncit.core.model

import com.syncit.core.config.StreamingConfig

/**
 * A video streaming app we can sync in a room (watch party). [linkHosts] are only used to warn
 * about a pasted link that looks wrong; any http(s) link is still accepted. Music and YouTube are
 * shown as friends' activity but aren't synced (see [ActivityApp]).
 */
enum class StreamingService(
    val displayName: String,
    val packageName: String,
    val linkHosts: List<String>,
) {
    NETFLIX("Netflix", StreamingConfig.NETFLIX_PACKAGE, listOf("netflix.com")),

    // linkHosts are a soft hint; confirm against a real JioHotstar share link.
    JIOHOTSTAR("JioHotstar", StreamingConfig.JIOHOTSTAR_PACKAGE, listOf("hotstar.com", "jiohotstar.com")),

    // Added 2026-10-15. Whether Prime Video reports position and accepts seekTo is unverified;
    // the engine's fallback modes (SyncMode) decide at runtime.
    PRIME_VIDEO("Prime Video", StreamingConfig.PRIME_VIDEO_PACKAGE, listOf("primevideo.com", "amazon.com", "amazon.in"));

    /** False until the package name has been filled in (see StreamingConfig). */
    val isConfigured: Boolean get() = packageName.isNotBlank()

    companion object {
        fun fromId(id: String?): StreamingService? = entries.firstOrNull { it.name == id }

        fun forPackage(packageName: String): StreamingService? =
            entries.firstOrNull { it.isConfigured && it.packageName == packageName }
    }
}
