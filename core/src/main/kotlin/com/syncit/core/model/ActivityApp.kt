package com.syncit.core.model

import com.syncit.core.config.StreamingConfig

/** What an app plays: MOVIE_TV rooms are watch parties, MUSIC/VIDEO rooms are jams. */
enum class MediaKind { MOVIE_TV, MUSIC, VIDEO }

/**
 * Every app whose "now playing" friends can see, if the user chose to share that app.
 * [syncService] is the room type a friend can join: a watch party for video apps, a jam for music
 * and YouTube (jams added 2026-10-15 at the user's request). [wire] is the value stored in Firebase.
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
    SPOTIFY("spotify", "Spotify", StreamingConfig.SPOTIFY_PACKAGE, MediaKind.MUSIC, StreamingService.SPOTIFY),
    YOUTUBE_MUSIC("ytmusic", "YouTube Music", StreamingConfig.YOUTUBE_MUSIC_PACKAGE, MediaKind.MUSIC, StreamingService.YOUTUBE_MUSIC),
    AMAZON_MUSIC("amazonmusic", "Amazon Music", StreamingConfig.AMAZON_MUSIC_PACKAGE, MediaKind.MUSIC, StreamingService.AMAZON_MUSIC),
    YOUTUBE("youtube", "YouTube", StreamingConfig.YOUTUBE_PACKAGE, MediaKind.VIDEO, StreamingService.YOUTUBE);

    val joinable: Boolean get() = syncService != null

    companion object {
        fun fromWire(value: String?): ActivityApp? = entries.firstOrNull { it.wire == value }

        fun forPackage(packageName: String): ActivityApp? = entries.firstOrNull { it.packageName == packageName }
    }
}
