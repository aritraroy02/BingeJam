package com.syncit.core.config

/**
 * Package names of the streaming apps we control through MediaSession.
 */
object StreamingConfig {
    const val NETFLIX_PACKAGE = "com.netflix.mediaclient"

    // JioHotstar's Play Store application ID (Hotstar kept it after the JioHotstar rebrand).
    //  Not yet confirmed on a device: check it once with the Phase 0 diagnostics screen or
    //  `adb shell dumpsys media_session | grep package=` while JioHotstar is playing.
    const val JIOHOTSTAR_PACKAGE = "in.startv.hotstar"
}
