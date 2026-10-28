package com.syncit.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypingSignalTest {
    @Test fun eachRealChangeToTextSendsTrue() {
        val s = TypingSignal()
        assertEquals(true, s.onDraftChanged("h"))
        assertEquals(true, s.onDraftChanged("hi"))
        // Same text again (e.g. the field re-reporting it) sends nothing.
        assertNull(s.onDraftChanged("hi"))
    }

    @Test fun clearingSendsFalseOnce() {
        val s = TypingSignal()
        s.onDraftChanged("hi")
        assertEquals(false, s.onDraftChanged(""))
        assertNull(s.onDraftChanged(""))
    }

    @Test fun whitespaceIsNotTyping() {
        val s = TypingSignal()
        assertNull(s.onDraftChanged("  "))
        assertEquals(true, s.onDraftChanged("  a"))
        assertEquals(false, s.onDraftChanged("  "))
    }

    @Test fun sendingStopsTypingAndTheClearedBoxSendsNothingMore() {
        val s = TypingSignal()
        s.onDraftChanged("hello")
        assertEquals(false, s.onSent())
        assertNull(s.onDraftChanged(""))
        // The next message starts typing again.
        assertEquals(true, s.onDraftChanged("a"))
    }

    @Test fun stoppingOnlyWritesIfWeWereTyping() {
        val s = TypingSignal()
        assertNull(s.onStopped())
        s.onDraftChanged("draft")
        assertEquals(false, s.onStopped())
        assertNull(s.onStopped())
        // Back in the chat with the draft still there: typing resumes on the next keystroke only.
        assertEquals(true, s.onDraftChanged("draft!"))
    }

    @Test fun idleChatNeverWrites() {
        val s = TypingSignal()
        assertNull(s.onSent())
        assertNull(s.onStopped())
        assertNull(s.onDraftChanged(""))
    }
}
