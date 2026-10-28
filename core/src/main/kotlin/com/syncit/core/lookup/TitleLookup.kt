package com.syncit.core.lookup

/**
 * Finds a streaming app's own ID for what the host is playing, so members can open the exact
 * title. Needed because Netflix's MediaSession publishes only title, length and poster.
 */
interface TitleLookup {
    /**
     * Netflix's numeric title ID for a movie called [title] that runs about [durationMs]
     * (null = unknown length), or null if it can't be found. Never throws.
     */
    suspend fun netflixId(title: String, durationMs: Long?): String?

    /** Lookups off (fakes, tests, no relay configured). */
    object None : TitleLookup {
        override suspend fun netflixId(title: String, durationMs: Long?): String? = null
    }
}
