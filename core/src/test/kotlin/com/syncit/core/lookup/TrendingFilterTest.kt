package com.syncit.core.lookup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrendingFilterTest {

    @Test fun switchingToSeriesDropsMovieOnlyGenres() {
        val romance = TrendingFilter(TitleType.MOVIE, genre = "romance", language = "hi")
        assertEquals(TrendingFilter(TitleType.SERIES, genre = null, language = "hi"), romance.withType(TitleType.SERIES))
        val action = TrendingFilter(TitleType.MOVIE, genre = "action")
        assertEquals("action", action.withType(TitleType.SERIES).genre)
    }

    @Test fun seriesGenresLeaveOutRomanceAndHorror() {
        val keys = TrendingFilters.genres(TitleType.SERIES).map { it.key }
        assertFalse("romance" in keys)
        assertFalse("horror" in keys)
    }

    @Test fun unknownSavedValuesFallBackToAll() {
        assertEquals(TrendingFilter(), TrendingFilter.of("x", "nope", "zz"))
        assertEquals(TrendingFilter(TitleType.SERIES, "comedy", "ta"), TrendingFilter.of("tv", "comedy", "ta"))
        assertNull(TrendingFilter.of("tv", "horror", null).genre)
    }
}
