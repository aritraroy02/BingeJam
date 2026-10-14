package com.syncit.app.di

/** Release twin of the debug DebugSocial: always the real backend; the fakes aren't compiled in. */
object DebugSocial {
    const val USE_FAKE_SOCIAL = false

    @Suppress("UNUSED_PARAMETER")
    fun createFake(now: () -> Long): SocialServices = error("Fake social services exist only in debug builds")
}
