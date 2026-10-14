package com.syncit.app.social

/**
 * Client-side copy of the username format in core's UserProfile doc: lowercase a–z, 0–9, '_' and
 * '.', 3–20 characters, not starting or ending with '.'. Checked locally first so obviously bad
 * input never costs a network round trip; ProfileRepository.checkUsername stays the authority.
 */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    private val allowed = Regex("[a-z0-9_.]+")

    /** Trims, drops a leading '@' and lowercases. */
    fun normalize(raw: String): String = raw.trim().removePrefix("@").trim().lowercase()

    /** Null if [normalized] is a valid username, else a short reason to show under the field. */
    fun problem(normalized: String): String? = when {
        normalized.isEmpty() -> "Pick a username"
        !allowed.matches(normalized) -> "Use only letters, numbers, _ and ."
        normalized.length < MIN_LENGTH -> "At least $MIN_LENGTH characters"
        normalized.length > MAX_LENGTH -> "At most $MAX_LENGTH characters"
        normalized.startsWith('.') || normalized.endsWith('.') -> "Can't start or end with ."
        else -> null
    }

    /** Characters the field accepts while typing (anything else is dropped as you type). */
    fun filterInput(raw: String): String =
        raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '.' }.take(MAX_LENGTH)
}
