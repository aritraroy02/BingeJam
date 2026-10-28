package com.syncit.app.ui.room

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetflixRetryTest {
    @Test fun bouncedBackSoonAfterTap_retriesOnce() {
        val retry = NetflixRetry()
        retry.opened(1_000)
        assertTrue(retry.shouldRetry(4_000, netflixPlayingHere = false))
        assertFalse("only once per tap", retry.shouldRetry(5_000, netflixPlayingHere = false))
    }

    @Test fun netflixPlaying_noRetry() {
        val retry = NetflixRetry()
        retry.opened(1_000)
        assertFalse(retry.shouldRetry(4_000, netflixPlayingHere = true))
        assertFalse("decided; a later return doesn't reopen", retry.shouldRetry(6_000, netflixPlayingHere = false))
    }

    @Test fun backTooLate_orNeverTapped_noRetry() {
        val retry = NetflixRetry()
        assertFalse(retry.shouldRetry(1_000, netflixPlayingHere = false))
        retry.opened(1_000)
        assertFalse(retry.shouldRetry(1_000 + NetflixRetry.WINDOW_MS + 1, netflixPlayingHere = false))
    }
}
