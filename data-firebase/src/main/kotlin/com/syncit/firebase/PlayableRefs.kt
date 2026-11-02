package com.syncit.firebase

/**
 * The public item references a host may publish in previews/{code}/playableRef. Mirrors the
 * playableRef rule in firebase/database.rules.json exactly: anything else would make the whole
 * preview update fail, so the transport drops a ref that doesn't match instead of sending it.
 */
internal object PlayableRefs {
    const val MAX_LENGTH = 200

    /** spotify:track:<22 base62> or spotify:episode:<22 base62>. */
    private val SPOTIFY = Regex("""spotify:(track|episode):[A-Za-z0-9]{22}""")

    /** https://www.youtube.com/watch?v=<11> or https://music.youtube.com/watch?v=<11>, nothing after the id. */
    private val YOUTUBE = Regex("""https://(www|music)\.youtube\.com/watch\?v=[A-Za-z0-9_-]{11}""")

    /** https://www.hotstar.com/in/movies/x/<6-12 digit content id>/watch (JioHotstar watch parties). */
    private val HOTSTAR = Regex("""https://www\.hotstar\.com/in/movies/x/[0-9]{6,12}/watch""")

    fun isValid(ref: String?): Boolean =
        ref != null && ref.length <= MAX_LENGTH && (SPOTIFY.matches(ref) || YOUTUBE.matches(ref) || HOTSTAR.matches(ref))
}
