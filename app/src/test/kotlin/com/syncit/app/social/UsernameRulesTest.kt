package com.syncit.app.social

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UsernameRulesTest {
    @Test fun normalizeTrimsAtAndCase() = assertEquals("riya.sen", UsernameRules.normalize("  @Riya.Sen "))

    @Test fun validNames() {
        for (name in listOf("abc", "riya_42", "a.b.c", "x".repeat(20), "1user")) {
            assertNull(name, UsernameRules.problem(name))
        }
    }

    @Test fun invalidNames() {
        for (name in listOf("", "ab", "x".repeat(21), ".riya", "riya.", "riya sen", "riya-sen", "Riya", "rïya")) {
            assertNotNull(name, UsernameRules.problem(name))
        }
    }

    @Test fun inputFilterDropsBadCharactersAndCaps() =
        assertEquals("riya.sen_1", UsernameRules.filterInput("Riya .Sen-_1!"))

    @Test fun inputFilterCapsLength() = assertEquals(20, UsernameRules.filterInput("a".repeat(30)).length)
}
