package com.syncit.app.ui.chat

/**
 * Decides when to tell the friend we're typing (DirectMessages.setTyping). Pure state machine so
 * it's unit-tested (TypingSignalTest); not thread-safe, call it from the main thread.
 *
 * - Each change of the draft to non-blank text sends true. The repository throttles the writes,
 *   and repeating true keeps the short-lived typing entry fresh while the user keeps typing.
 * - The draft becoming blank, sending, and the chat stopping (ON_STOP / leaving) send false, but
 *   only if true was the last thing sent, so an idle chat writes nothing.
 */
class TypingSignal {
    private var draft = ""

    /** Last value handed to setTyping; false until the user types. */
    var lastSent: Boolean = false
        private set

    /** The draft changed; returns the value to send, or null for "send nothing". */
    fun onDraftChanged(text: String): Boolean? {
        if (text == draft) return null
        draft = text
        return if (text.isNotBlank()) mark(true) else stop()
    }

    /** A message went out: the box is about to be cleared. */
    fun onSent(): Boolean? {
        draft = ""
        return stop()
    }

    /** The chat left the screen (navigated away, app backgrounded). */
    fun onStopped(): Boolean? = stop()

    private fun stop(): Boolean? = if (lastSent) mark(false) else null

    private fun mark(value: Boolean): Boolean {
        lastSent = value
        return value
    }
}
