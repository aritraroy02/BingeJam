package com.syncit.core.model

import com.syncit.core.config.StreamingConfig

/**
 * An app we can sync in a room: a watch party for video apps, a **jam** for music and YouTube
 * (user's request, 2026-10-15: "in YouTube and Spotify I need jam"). [kind] decides wording and
 * whether members auto-follow the host's track changes (MUSIC/VIDEO do). [linkHosts] are only used
 * to warn about a pasted link that looks wrong; any http(s) link is still accepted.
 */
enum class StreamingService(
    val displayName: String,
    val packageName: String,
    val linkHosts: List<String>,
    val kind: MediaKind,
) {
    NETFLIX("Netflix", StreamingConfig.NETFLIX_PACKAGE, listOf("netflix.com"), MediaKind.MOVIE_TV),

    // linkHosts are a soft hint; confirm against a real JioHotstar share link.
    JIOHOTSTAR("JioHotstar", StreamingConfig.JIOHOTSTAR_PACKAGE, listOf("hotstar.com", "jiohotstar.com"), MediaKind.MOVIE_TV),

    // Added 2026-10-15. Whether Prime Video reports position and accepts seekTo is unverified;
    // the engine's fallback modes (SyncMode) decide at runtime.
    PRIME_VIDEO("Prime Video", StreamingConfig.PRIME_VIDEO_PACKAGE, listOf("primevideo.com", "amazon.com", "amazon.in"), MediaKind.MOVIE_TV),

    // Jams (2026-10-15). Spotify advertises PLAY_FROM_URI/PLAY_FROM_SEARCH and SEEK_TO (seen on
    // the OPPO). The others are unverified; fallback modes decide at runtime.
    SPOTIFY("Spotify", StreamingConfig.SPOTIFY_PACKAGE, listOf("open.spotify.com", "spotify.link"), MediaKind.MUSIC),
    YOUTUBE_MUSIC("YouTube Music", StreamingConfig.YOUTUBE_MUSIC_PACKAGE, listOf("music.youtube.com"), MediaKind.MUSIC),
    AMAZON_MUSIC("Amazon Music", StreamingConfig.AMAZON_MUSIC_PACKAGE, listOf("music.amazon.in", "music.amazon.com"), MediaKind.MUSIC),
    YOUTUBE("YouTube", StreamingConfig.YOUTUBE_PACKAGE, listOf("youtube.com", "youtu.be"), MediaKind.VIDEO);

    /** False until the package name has been filled in (see StreamingConfig). */
    val isConfigured: Boolean get() = packageName.isNotBlank()

    /** Music and YouTube rooms are jams: members follow the host from track to track. */
    val isJam: Boolean get() = kind != MediaKind.MOVIE_TV

    companion object {
        fun fromId(id: String?): StreamingService? = entries.firstOrNull { it.name == id }

        fun forPackage(packageName: String): StreamingService? =
            entries.firstOrNull { it.isConfigured && it.packageName == packageName }
    }
}
