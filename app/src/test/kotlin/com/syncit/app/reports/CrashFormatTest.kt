package com.syncit.app.reports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashFormatTest {

    @Test fun scrubRemovesEmailsLinksAndTokens() {
        val out = LogScrubber.scrub("user riya.k@gmail.com opened https://example.com/x?a=1 with Bearer abc.def token ${"A".repeat(40)}")
        assertFalse(out.contains("riya.k@gmail.com"))
        assertFalse(out.contains("example.com"))
        assertFalse(out.contains("abc.def"))
        assertFalse(out.contains("A".repeat(40)))
        assertTrue(out.contains("[email]") && out.contains("[link]") && out.contains("[token]"))
    }

    @Test fun cleanKeepsOnlyLogLinesInShortForm() {
        val raw = """
            --------- beginning of main
            11-10 12:04:31.207  1234  1250 W SyncItDiag: drift 420 ms
            11-10 12:04:32.001  1234  1234 E AndroidRuntime: FATAL EXCEPTION: main
            garbage line
        """.trimIndent()
        assertEquals(
            "12:04:31.207 W SyncItDiag: drift 420 ms\n12:04:32.001 E AndroidRuntime: FATAL EXCEPTION: main",
            LogScrubber.clean(raw),
        )
    }

    @Test fun cleanKeepsTheNewestLinesWhenTooLong() {
        val raw = (0 until 2000).joinToString("\n") { i -> "11-10 12:00:00.000  1  1 I Tag: line $i ${"ab ".repeat(14)}" }
        val out = LogScrubber.clean(raw)
        assertTrue(out.length <= 24_000)
        assertTrue(out.endsWith("line 1999 ${"ab ".repeat(14).trimEnd()}"))
    }

    @Test fun stackKeepsAppFramesAndFoldsTheRest() {
        val error = IllegalStateException("boom").apply {
            stackTrace = arrayOf(
                StackTraceElement("java.util.ArrayList", "get", "ArrayList.java", 10),
                StackTraceElement("android.os.Handler", "dispatch", "Handler.java", 20),
                StackTraceElement("com.syncit.app.ui.room.RoomViewModel", "leave", "RoomViewModel.kt", 281),
                StackTraceElement("android.os.Looper", "loop", "Looper.java", 30),
                StackTraceElement("com.android.internal.os.ZygoteInit", "main", "ZygoteInit.java", 40),
            )
        }
        val text = CrashCatcher.cleanStack(error)
        assertTrue(text.startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(text.contains("at java.util.ArrayList.get (ArrayList.java:10)"))
        assertTrue(text.contains("at app.ui.room.RoomViewModel.leave (RoomViewModel.kt:281)"))
        assertTrue(text.contains("… 1 more"))
        assertTrue(text.contains("… 2 more"))
        assertFalse(text.contains("Looper"))
    }

    @Test fun stackShowsCauses() {
        val error = RuntimeException("outer", IllegalArgumentException("inner"))
        assertTrue(CrashCatcher.cleanStack(error).contains("Caused by: java.lang.IllegalArgumentException: inner"))
    }
}
