package com.syncit.core.lookup

import com.syncit.core.model.StreamingService

/** A popular movie on one platform (TMDB data, see push-relay/src/trending.js). */
data class TrendingTitle(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    /** https://image.tmdb.org/… poster, or null. */
    val posterUrl: String?,
    /** TMDB's 0–10 score, or null. */
    val rating: Double?,
)

/** "Trending on Netflix": [titles] most popular first. */
data class TrendingRow(val service: StreamingService, val titles: List<TrendingTitle>)

/** Home's trending rows and the link that opens a title in its app. Never throws. */
interface TrendingCatalog {
    /** Netflix, JioHotstar, Prime Video rows; empty when unavailable. */
    suspend fun trending(): List<TrendingRow>

    /**
     * The link that opens [title] in [service]'s app: netflix.com/watch/… and JioHotstar's watch link
     * start it playing, Prime Video's opens its page. Null when it can't be found.
     */
    suspend fun watchLink(title: TrendingTitle, service: StreamingService): String?

    /** Off (fakes, tests, no relay configured). */
    object None : TrendingCatalog {
        override suspend fun trending(): List<TrendingRow> = emptyList()
        override suspend fun watchLink(title: TrendingTitle, service: StreamingService): String? = null
    }

    companion object {
        /** Links we'll open, per service (the relay already filters; this is the app's own check). */
        fun isSafeLink(service: StreamingService, url: String?): Boolean = url != null && url.length <= 300 && when (service) {
            StreamingService.NETFLIX -> NETFLIX.matches(url)
            StreamingService.JIOHOTSTAR -> HOTSTAR.matches(url)
            StreamingService.PRIME_VIDEO -> PRIME.matches(url)
            else -> false
        }

        private val NETFLIX = Regex("""https://www\.netflix\.com/watch/\d{1,12}""")
        private val HOTSTAR = Regex("""https://www\.hotstar\.com/in/movies/x/\d{6,12}/watch""")
        private val PRIME = Regex("""https://(?:www\.|app\.)?primevideo\.com/(?:[a-z-]+/)?(?:detail|dp)/[A-Za-z0-9._-]{1,80}/?(?:[?#].*)?""")
    }
}
