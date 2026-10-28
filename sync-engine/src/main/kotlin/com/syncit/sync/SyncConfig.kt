package com.syncit.sync

/** All sync thresholds in one place. Defaults follow the spec; tests may override. */
data class SyncConfig(
    /** How long an applied command's expected result suppresses matching local callbacks. */
    val echoWindowMs: Long = 1_500,
    /** While the app is buffering after our command, keep expectations alive, up to this long. */
    val echoMaxHoldMs: Long = 10_000,
    /** A local position jump bigger than this (vs. extrapolation) is a user scrub. */
    val seekThresholdMs: Long = 3_000,
    /**
     * While the app is buffering/skipping (a transient state), drift checks wait for it to settle
     * and a scrub seen on entering that state is broadcast once it settles, but we only wait this
     * long. After that the scrub is broadcast anyway and drift checks resume.
     */
    val transientHoldMs: Long = 10_000,
    /**
     * A BUFFERING reading behind the extrapolated position counts as a stall (the video froze
     * while the app still said PLAYING) only if it's at most this far behind. Without a cap the
     * stall range would reach back to the last raw report, which can be many minutes old (apps
     * only re-publish on state changes), and a real backward scrub would be swallowed.
     */
    val stallWindowMaxMs: Long = 15_000,
    /** Never correct drift at or below this: small seeks cause visible stutter. */
    val driftThresholdMs: Long = 1_500,
    /** How often drift is checked. */
    val driftCheckIntervalMs: Long = 5_000,
    /** Minimum time between two corrections (rate limit). */
    val correctionCooldownMs: Long = 10_000,
    /** After this many corrections in a row that didn't fix the drift, stop seeking (app ignores us). */
    val maxConsecutiveCorrections: Int = 3,
    /** How often we write our own position for the drift overlay. */
    val positionReportIntervalMs: Long = 5_000,
    /** Member reports older than this are ignored for catch-up decisions. */
    val memberReportMaxAgeMs: Long = 15_000,
    /** Longest we'll pause a device that's ahead in catch-up mode. */
    val maxCatchUpPauseMs: Long = 30_000,
    /** After (re)attaching to the app, its own start-up changes aren't treated as user actions. */
    val attachSettleMs: Long = 4_000,
    /** After we broadcast, skip drift checks this long so our write can land first. */
    val broadcastGraceMs: Long = 3_000,
    // ---- Item change (onItemChanged: a new track in a jam) ----
    /** Leader: don't broadcast the new item's state sooner than this after the change. */
    val itemSettleMinMs: Long = 800,
    /** Take the app's settled reading after this long even if it isn't known to be the new item's. */
    val itemSettleMaxMs: Long = 3_000,
    /** A reading sampled up to this long BEFORE the change counts as the new item's. */
    val itemFreshToleranceMs: Long = 1_000,
    /** Give up waiting (no settled reading / no room write for the new item) and re-align like a late join. */
    val itemRestartTimeoutMs: Long = 8_000,
    /** Leader: after broadcasting the new item, others' writes aren't applied until ours lands, at most this long. */
    val itemOwnWriteWaitMs: Long = 3_000,
    /** Capability check: a pause after at least this much play must show a moved position. */
    val positionCheckMinPlayMs: Long = 5_000,
    /** ...moved by more than this. */
    val positionCheckToleranceMs: Long = 500,
)
