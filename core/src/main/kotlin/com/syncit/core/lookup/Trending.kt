package com.syncit.core.lookup

import com.syncit.core.model.StreamingService

/** Movies or web series (TMDB "movie" / "tv"). [wire] is what the relay takes. */
enum class TitleType(val wire: String, val label: String) {
    MOVIE("movie", "Movies"),
    SERIES("tv", "Web series"),
    ;

    companion object {
        fun fromWire(value: String?): TitleType = entries.firstOrNull { it.wire == value } ?: MOVIE
    }
}

/** A genre filter; [series] = also offered for web series (TMDB has no TV romance or horror). */
data class TrendingGenre(val key: String, val label: String, val series: Boolean = true)

/** A language filter: ISO 639-1 [code] (original language on TMDB). */
data class TrendingLanguage(val code: String, val label: String)

/** What Home's "Trending now" shows. Null [genre] / [language] = all. */
data class TrendingFilter(
    val type: TitleType = TitleType.MOVIE,
    val genre: String? = null,
    val language: String? = null,
) {
    /** Drops a genre the new type doesn't have (romance, horror for series). */
    fun withType(newType: TitleType): TrendingFilter {
        val keep = genre?.takeIf { key -> TrendingFilters.genres(newType).any { it.key == key } }
        return copy(type = newType, genre = keep)
    }

    companion object {
        /** Unknown keys (an older or newer app's saved choice) fall back to "all". */
        fun of(type: String?, genre: String?, language: String?): TrendingFilter {
            val t = TitleType.fromWire(type)
            return TrendingFilter(
                type = t,
                genre = genre?.takeIf { key -> TrendingFilters.genres(t).any { it.key == key } },
                language = language?.takeIf { code -> TrendingFilters.LANGUAGES.any { it.code == code } },
            )
        }
    }
}

/** The choices on Home. Must match GENRES / LANGUAGES in push-relay/src/trending.js. */
object TrendingFilters {
    val GENRES = listOf(
        TrendingGenre("action", "Action"),
        TrendingGenre("comedy", "Comedy"),
        TrendingGenre("drama", "Drama"),
        TrendingGenre("thriller", "Thriller"),
        TrendingGenre("romance", "Romance", series = false),
        TrendingGenre("horror", "Horror", series = false),
        TrendingGenre("crime", "Crime"),
        TrendingGenre("scifi", "Sci-fi"),
        TrendingGenre("family", "Family"),
        TrendingGenre("animation", "Animation"),
        TrendingGenre("documentary", "Documentary"),
    )

    val LANGUAGES = listOf(
        TrendingLanguage("hi", "Hindi"),
        TrendingLanguage("en", "English"),
        TrendingLanguage("ta", "Tamil"),
        TrendingLanguage("te", "Telugu"),
        TrendingLanguage("ml", "Malayalam"),
        TrendingLanguage("kn", "Kannada"),
        TrendingLanguage("bn", "Bengali"),
        TrendingLanguage("mr", "Marathi"),
        TrendingLanguage("pa", "Punjabi"),
        TrendingLanguage("gu", "Gujarati"),
        TrendingLanguage("ko", "Korean"),
        TrendingLanguage("ja", "Japanese"),
        TrendingLanguage("es", "Spanish"),
    )

    fun genres(type: TitleType): List<TrendingGenre> = if (type == TitleType.SERIES) GENRES.filter { it.series } else GENRES
}

/** A popular movie or series on one platform (TMDB data, see push-relay/src/trending.js). */
data class TrendingTitle(
    val tmdbId: Long,
    val title: String,
    val year: Int?,
    /** https://image.tmdb.org/… poster, or null. */
    val posterUrl: String?,
    /** TMDB's 0–10 score, or null. */
    val rating: Double?,
    val type: TitleType = TitleType.MOVIE,
)

/** "Netflix": [titles] most popular first. */
data class TrendingRow(val service: StreamingService, val titles: List<TrendingTitle>)

/** Home's trending rows and the link that opens a title in its app. Never throws. */
interface TrendingCatalog {
    /** Netflix, JioHotstar, Prime Video rows for [filter]; empty when unavailable. */
    suspend fun trending(filter: TrendingFilter = TrendingFilter()): List<TrendingRow>

    /**
     * The link that opens [title] in [service]'s app: netflix.com/watch/… and JioHotstar's watch link
     * start it playing, Prime Video's opens its page. Null when it can't be found.
     */
    suspend fun watchLink(title: TrendingTitle, service: StreamingService): String?

    /** Off (fakes, tests, no relay configured). */
    object None : TrendingCatalog {
        override suspend fun trending(filter: TrendingFilter): List<TrendingRow> = emptyList()
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
