package com.syncit.app.di

/**
 * Debug-only access to the in-memory fakes. The release source set has a twin of this object
 * with the switch fixed to false and no fakes, so release builds never contain [FakeSocial].
 */
object DebugSocial {
    /**
     * ======================== SWITCH: social backend (debug builds) ========================
     * true  = in-memory fakes (FakeSocial: sample friends, nothing leaves the phone). Rooms don't
     *         work in this mode: the v2 transport needs a real signed-in account.
     * false = real :data-firebase + :media's AndroidActivityTracker (same as release).
     * =======================================================================================
     */
    const val USE_FAKE_SOCIAL = false

    /** [now] = server time (the transport's clock). */
    fun createFake(now: () -> Long): SocialServices = FakeSocial.create(now)
}
