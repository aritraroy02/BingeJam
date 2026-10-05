package com.syncit.core.config

/**
 * Package names of the streaming apps we control through MediaSession.
 */
object StreamingConfig {
    const val NETFLIX_PACKAGE = "com.netflix.mediaclient"

    // TODO(JIOHOTSTAR_PACKAGE): fill this in after running the Phase 0 diagnostics screen
    //  (or `adb shell dumpsys media_session`) on a real phone with JioHotstar playing.
    //  Left blank on purpose: do NOT guess it. While blank, JioHotstar rooms show
    //  "JioHotstar isn't configured yet" and sync stays off for that service.
    //  See RUN_ON_MY_MACHINE.md, "Phase 0: find the JioHotstar package name".
    const val JIOHOTSTAR_PACKAGE = ""
}
