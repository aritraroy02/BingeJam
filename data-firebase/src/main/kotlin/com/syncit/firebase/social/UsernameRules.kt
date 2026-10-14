package com.syncit.firebase.social

import java.text.Normalizer
import kotlin.random.Random

/**
 * Username format, reserved names and suggestion generation. Pure Kotlin (no Android, no
 * Firebase) so it's unit-tested in src/test. The same format is enforced again by the database
 * rules (usernames/$name), so a modified client can't claim a malformed name.
 *
 * Format: lowercase a–z, 0–9, '_' and '.', 3–20 characters, not starting or ending with '.',
 * no "..".
 */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    val RESERVED: Set<String> = setOf(
        "admin", "vyro", "syncit", "support", "help", "root", "system", "null", "undefined",
    )

    private val ALLOWED = Regex("^[a-z0-9_.]+$")

    /** Lowercases, trims and drops a leading '@'. Doesn't fix anything else. */
    fun normalize(candidate: String): String = candidate.trim().removePrefix("@").trim().lowercase()

    /** Null if [normalized] is a valid, unreserved username, otherwise a reason to show the user. */
    fun invalidReason(normalized: String): String? = when {
        normalized.length < MIN_LENGTH -> "At least $MIN_LENGTH characters"
        normalized.length > MAX_LENGTH -> "At most $MAX_LENGTH characters"
        !ALLOWED.matches(normalized) -> "Only letters, numbers, '_' and '.'"
        normalized.startsWith(".") || normalized.endsWith(".") -> "Can't start or end with '.'"
        normalized.contains("..") -> "Can't have two dots in a row"
        normalized in RESERVED -> "That username is reserved"
        else -> null
    }

    fun isValid(normalized: String): Boolean = invalidReason(normalized) == null

    /**
     * Database key for usernames/{key}. Realtime Database keys can't contain '.', so it's stored
     * as ','. Nothing else in the allowed set sorts between ',' (0x2C) and '.' (0x2E), so key
     * order, and with it prefix search, is the same as username order. The rules apply the same
     * mapping (username.replace('.', ',')).
     */
    fun key(username: String): String = username.replace('.', ',')

    fun fromKey(key: String): String = key.replace(',', '.')

    /** True if [prefix] could be the start of a valid username (used to skip pointless searches). */
    fun isSearchablePrefix(prefix: String): Boolean =
        prefix.length in 2..MAX_LENGTH && ALLOWED.matches(prefix) && !prefix.startsWith(".")

    // ---- Suggestions ----

    private val ADJECTIVES = listOf(
        "night", "cozy", "lazy", "sleepy", "midnight", "retro", "pixel", "cosmic", "lucky", "chill",
    )
    private val NOUNS = listOf("owl", "fox", "panda", "pilot", "critic", "buff", "otter", "ninja")
    private val FUN_WORDS = listOf(
        "nightowl", "popcorn", "cinema", "bingewatch", "reelfan", "couchpotato", "plot_twist",
        "screen", "encore", "matinee",
    )

    /**
     * Lowercase ASCII words from a display name or e-mail address: "Aritra Roy" → [aritra, roy],
     * "José-Luis" → [jose, luis], "a.roy@x.com" → [a, roy]. Accents are dropped; non-Latin
     * scripts yield nothing (the fun suggestions still work).
     */
    fun seedWords(seed: String?): List<String> {
        if (seed.isNullOrBlank()) return emptyList()
        val local = seed.substringBefore('@')
        val ascii = Normalizer.normalize(local, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
    }

    /**
     * Candidate usernames for [seed], best first, all valid and unique. Name-based handles
     * ("aritra", "aritra.roy", "aritra_r", "aritra_42") are interleaved with fun ones
     * ("nightowl_aritra", "popcorn_pilot", "aritra.fox"), then more numbered variants, so the
     * first few available ones are a mix. Availability is checked by the caller.
     */
    fun suggestionCandidates(seed: String?, random: Random = Random.Default, max: Int = 30): List<String> {
        val words = seedWords(seed)
        // Keep names short enough that "_" + suffix still fits in 20 characters.
        val first = words.firstOrNull()?.take(12)?.takeIf { it.isNotEmpty() }
        val last = words.drop(1).lastOrNull()?.take(10)

        fun num2() = random.nextInt(10, 100).toString()
        fun num3() = random.nextInt(100, 1000).toString()
        fun adjNoun() = ADJECTIVES[random.nextInt(ADJECTIVES.size)] + NOUNS[random.nextInt(NOUNS.size)]
        fun funWord() = FUN_WORDS[random.nextInt(FUN_WORDS.size)]
        fun noun() = NOUNS[random.nextInt(NOUNS.size)]

        val nameBased = mutableListOf<String>()
        val fun_ = mutableListOf<String>()
        if (first != null) {
            nameBased += first
            if (last != null) {
                nameBased += "$first.$last"
                nameBased += "${first}_$last"
                nameBased += "$first.${last.first()}"
                nameBased += "${first}_${last.first()}"
                nameBased += "$first$last"
            }
            nameBased += "${first}_${num2()}"
            nameBased += "$first.${num3()}"
            nameBased += "$first${num2()}"
            nameBased += "${first}_${num3()}"

            fun_ += "${funWord()}_$first"
            fun_ += "$first.${noun()}"
            fun_ += "${adjNoun()}_$first"
            fun_ += "${first}_${funWord()}"
            fun_ += "${adjNoun()}.$first"
            fun_ += "${funWord()}_${first}${num2()}"
        }
        // Fun ones without a name, for empty or non-Latin seeds and as extra variety.
        fun_ += "popcorn_pilot"
        fun_ += "cinema_fox"
        fun_ += adjNoun()
        fun_ += "${funWord()}_${noun()}"
        fun_ += "${adjNoun()}_${num2()}"
        fun_ += "${funWord()}_${num3()}"
        fun_ += "${adjNoun()}${num3()}"

        val out = LinkedHashSet<String>()
        val a = nameBased.iterator()
        val b = fun_.iterator()
        // Two name-based, one fun, repeat: name-based handles are what most people pick.
        while (a.hasNext() || b.hasNext()) {
            repeat(2) { if (a.hasNext()) out += a.next() }
            if (b.hasNext()) out += b.next()
        }
        // Top up with numbered variants so there's always enough to check.
        var guard = 0
        val base = first ?: "watcher"
        while (out.size < max && guard++ < max * 4) {
            out += if (random.nextBoolean()) "${base}_${num3()}" else "${adjNoun()}_${num2()}"
        }
        return out.map(::normalize).filter(::isValid).distinct().take(max)
    }
}

/** dms/{dmId}: the two uids sorted and joined with "_" (Firebase uids never contain '_'). */
object DmIds {
    fun dmId(a: String, b: String): String {
        require(a.isNotEmpty() && b.isNotEmpty() && a != b) { "A DM needs two different users" }
        return if (a < b) "${a}_$b" else "${b}_$a"
    }
}
