package com.syncit.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomLinksTest {
    @Test fun httpsLink() = assertEquals("LG8K4V", RoomLinks.codeFromLink("https://syncit-watchparty.web.app/join/LG8K4V"))

    @Test fun lowercaseCodeIsUppercased() = assertEquals("LG8K4V", RoomLinks.codeFromLink("https://syncit-watchparty.web.app/join/lg8k4v"))

    @Test fun customScheme() = assertEquals("ABC234", RoomLinks.codeFromLink("syncit://join/ABC234"))

    @Test fun linkInsideShareText() = assertEquals("ABC234", RoomLinks.codeFromLink(RoomLinks.shareText("ABC234")))

    @Test fun trailingSlashOrQuery() {
        assertEquals("ABC234", RoomLinks.codeFromLink("https://syncit-watchparty.web.app/join/ABC234/"))
        assertEquals("ABC234", RoomLinks.codeFromLink("https://syncit-watchparty.web.app/join/ABC234?x=1"))
    }

    @Test fun rejectsAmbiguousCharacters() = assertNull(RoomLinks.codeFromLink("syncit://join/ABC0O1"))

    @Test fun rejectsTooLong() = assertNull(RoomLinks.codeFromLink("syncit://join/ABC2345"))

    @Test fun rejectsOtherHosts() = assertNull(RoomLinks.codeFromLink("https://evil.example/join/ABC234"))

    @Test fun rejectsNullAndPlainText() {
        assertNull(RoomLinks.codeFromLink(null))
        assertNull(RoomLinks.codeFromLink("ABC234"))
    }
}
