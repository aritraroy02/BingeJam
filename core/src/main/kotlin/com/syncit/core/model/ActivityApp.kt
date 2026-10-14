package com.syncit.core.model

import com.syncit.core.config.StreamingConfig

/** What kind of thing an app plays. Only [MOVIE_TV] apps can be joined as a synced watch party. */
enum class MediaKind { MOVIE_TV, MUSIC, VIDEO }

/**
 * Every app whose "now playing" friends can see, if the user chose to share that app.
 * [syncService] is set for apps a friend can join in a synced room. Music and YouTube are
 * "see + open" only (the user's decision, 2026-10-15). [wire] is the value stored in Firebase.
 */
enum class ActivityApp(
    val wire: String,
    val displayName: String,
    val packageName: String,
    val kind: MediaKind,
    val syncService: StreamingService?,
) {
    NETFLIX("netflix", "Netflix", StreamingConfig.NETFLIX_PACKAGE, MediaKind.MOVIE_TV, StreamingService.NETFLIX),
    JIOHOTSTAR("jiohotstar", "JioHotstar", StreamingConfig.JIOHOTSTAR_PACKAGE, MediaKind.MOVIE_TV, StreamingService.JIOHOTSTAR),
    PRIME_VIDEO("primevideo", "Prime Video", StreamingConfig.PRIME_VIDEO_PACKAGE, MediaKind.MOVIE_TV, StreamingService.PRIME_VIDEO),
    SPOTIFY("spotify", "Spotify", StreamingConfig.SPOTIFY_PACKAGE, MediaKind.MUSIC, null),
    YOUTUBE_MUSIC("ytmusic", "YouTube Music", StreamingConfig.YOUTUBE_MUSIC_PACKAGE, MediaKind.MUSIC, null),
    AMAZON_MUSIC("amazonmusic", "Amazon Music", StreamingConfig.AMAZON_MUSIC_PACKAGE, MediaKind.MUSIC, null),
    YOUTUBE("youtube", "YouTube", StreamingConfig.YOUTUBE_PACKAGE, MediaKind.VIDEO, null);

    val joinable: Boolean get() = syncService != null

    companion object {
        fun fromWire(value: String?): ActivityApp? = entries.firstOrNull { it.wire == value }

        fun forPackage(packageName: String): ActivityApp? = entries.firstOrNull { it.packageName == packageName }
    }
}
