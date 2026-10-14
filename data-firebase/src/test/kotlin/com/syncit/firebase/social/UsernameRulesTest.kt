package com.syncit.firebase.social

import com.syncit.core.model.SignInProvider
import com.syncit.firebase.FirebaseAuthService
import com.syncit.firebase.isRoomCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class UsernameRulesTest {

    @Test
    fun `normalize trims, lowercases and drops a leading at sign`() {
        assertEquals("aritra.roy", UsernameRules.normalize("  @Aritra.Roy "))
        assertEquals("abc", UsernameRules.normalize("ABC"))
    }

    @Test
    fun `valid usernames`() {
        listOf("abc", "aritra", "a_b", "a.b", "_x_", "user_123", "a".repeat(20), "x.y.z", "9lives").forEach {
            assertNull(it, UsernameRules.invalidReason(it))
        }
    }

    @Test
    fun `invalid usernames`() {
        listOf(
            "ab", // too short
            "a".repeat(21), // too long
            ".abc", "abc.", // dot at the ends
            "a..b", // double dot
            "ab-c", "ab c", "Abc", "émile", "ab@c", // characters
            "admin", "vyro", "syncit", "support", "help", "root", "system", "null", "undefined", // reserved
        ).forEach { assertNotNull(it, UsernameRules.invalidReason(it)) }
    }

    @Test
    fun `database keys store dots as commas and keep username order`() {
        assertEquals("aritra,roy", UsernameRules.key("aritra.roy"))
        assertEquals("aritra.roy", UsernameRules.fromKey("aritra,roy"))
        assertEquals("plain_name", UsernameRules.key("plain_name"))
        val names = listOf("a.b", "a_b", "a0b", "ab", "a.", "a9", "aa", "a")
        assertEquals(names.sorted(), names.map(UsernameRules::key).sorted().map(UsernameRules::fromKey))
    }

    @Test
    fun `searchable prefixes`() {
        assertTrue(UsernameRules.isSearchablePrefix("ar"))
        assertTrue(UsernameRules.isSearchablePrefix("ar."))
        assertFalse(UsernameRules.isSearchablePrefix("a"))
        assertFalse(UsernameRules.isSearchablePrefix(".a"))
        assertFalse(UsernameRules.isSearchablePrefix("a b"))
    }

    @Test
    fun `seed words handle names, accents and e-mail addresses`() {
        assertEquals(listOf("aritra", "roy"), UsernameRules.seedWords("Aritra Roy"))
        assertEquals(listOf("jose", "luis", "garcia"), UsernameRules.seedWords("José-Luis García"))
        assertEquals(listOf("a", "roy"), UsernameRules.seedWords("a.roy@example.com"))
        assertEquals(emptyList<String>(), UsernameRules.seedWords("   "))
        assertEquals(emptyList<String>(), UsernameRules.seedWords(null))
    }

    @Test
    fun `suggestions are valid, unique and start with name-based handles`() {
        val s = UsernameRules.suggestionCandidates("Aritra Roy", Random(1), max = 18)
        assertEquals(18, s.size)
        assertEquals(s.size, s.toSet().size)
        s.forEach { assertNull(it, UsernameRules.invalidReason(it)) }
        assertEquals(listOf("aritra", "aritra.roy"), s.take(2))
        assertTrue(s.contains("aritra_roy"))
        assertTrue(s.contains("aritra.r") || s.contains("aritra_r"))
        // Mixed in: at least one fun handle with the name, and one numbered one.
        assertTrue(s.any { it.contains("aritra") && (it.contains("owl") || it.contains("popcorn") || it.contains("cinema") || it.contains("fox") || it.contains("_") && it.any(Char::isDigit)) })
        assertTrue(s.any { it.startsWith("aritra") && it.any(Char::isDigit) })
    }

    @Test
    fun `suggestions without a usable seed are still valid`() {
        listOf(null, "", "李小龙", "!!").forEach { seed ->
            val s = UsernameRules.suggestionCandidates(seed, Random(7), max = 10)
            assertEquals(10, s.size)
            s.forEach { assertNull("$seed → $it", UsernameRules.invalidReason(it)) }
        }
    }

    @Test
    fun `long names are shortened to fit`() {
        val s = UsernameRules.suggestionCandidates("Bartholomewsandersonville Montgomeryfitzgerald", Random(3), max = 20)
        assertTrue(s.isNotEmpty())
        s.forEach { assertTrue(it, it.length <= UsernameRules.MAX_LENGTH) }
    }

    @Test
    fun `a single short name works`() {
        val s = UsernameRules.suggestionCandidates("Al", Random(5), max = 6)
        assertEquals(6, s.size)
        s.forEach { assertNull(it, UsernameRules.invalidReason(it)) }
        assertFalse(s.contains("al")) // too short on its own
    }

    @Test
    fun `dm id is the sorted uids joined with underscore`() {
        assertEquals("abc_xyz", DmIds.dmId("xyz", "abc"))
        assertEquals("abc_xyz", DmIds.dmId("abc", "xyz"))
        assertEquals("Abc_abc", DmIds.dmId("abc", "Abc")) // code-point order, same as the rules' string compare
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dm id with yourself is rejected`() {
        DmIds.dmId("abc", "abc")
    }

    @Test
    fun `provider ids map to sign-in providers`() {
        assertEquals(SignInProvider.GOOGLE, FirebaseAuthService.providerOf(listOf("firebase", "google.com")))
        assertEquals(SignInProvider.EMAIL_LINK, FirebaseAuthService.providerOf(listOf("firebase", "password")))
        assertEquals(SignInProvider.GOOGLE, FirebaseAuthService.providerOf(listOf("password", "google.com")))
        assertEquals(SignInProvider.OTHER, FirebaseAuthService.providerOf(listOf("firebase")))
    }

    @Test
    fun `room codes`() {
        assertTrue(isRoomCode("ABC234"))
        assertFalse(isRoomCode("ABC0O1"))
        assertFalse(isRoomCode("abc234"))
        assertFalse(isRoomCode("ABC23"))
    }
}
