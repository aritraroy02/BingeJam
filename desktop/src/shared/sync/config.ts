// All sync thresholds in one place (port of sync-engine/.../SyncConfig.kt). The defaults are the
// Android app's, so a phone and a PC in the same room make the same decisions; tests may override.
// All times are milliseconds.

export interface SyncConfig {
  /** How long an applied command's expected result suppresses matching local callbacks. */
  readonly echoWindowMs: number
  /** While the player is buffering after our command, keep expectations alive, up to this long. */
  readonly echoMaxHoldMs: number
  /** A local position jump bigger than this (vs. extrapolation) is a user scrub. */
  readonly seekThresholdMs: number
  /**
   * While the player is buffering/skipping (a transient state), drift checks wait for it to
   * settle and a scrub seen on entering that state is broadcast once it settles, but we only wait
   * this long. After that the scrub is broadcast anyway and drift checks resume.
   */
  readonly transientHoldMs: number
  /**
   * A BUFFERING reading behind the extrapolated position counts as a stall (the video froze while
   * the player still said PLAYING) only if it's at most this far behind. Without a cap the stall
   * range would reach back to the last raw report, which can be many minutes old (Android apps
   * only re-publish on state changes), and a real backward scrub would be swallowed.
   */
  readonly stallWindowMaxMs: number
  /** Never correct drift at or below this by seeking: small seeks cause visible stutter. */
  readonly driftThresholdMs: number
  /** How often drift is checked. */
  readonly driftCheckIntervalMs: number
  /** Minimum time between two seek corrections (rate limit). */
  readonly correctionCooldownMs: number
  /** After this many seek corrections in a row that didn't fix the drift, stop seeking (player ignores us). */
  readonly maxConsecutiveCorrections: number
  /** How often we write our own position for the drift overlay. */
  readonly positionReportIntervalMs: number
  /** Member reports older than this are ignored for catch-up decisions and sync health. */
  readonly memberReportMaxAgeMs: number
  /** Longest we'll pause a device that's ahead in catch-up mode. */
  readonly maxCatchUpPauseMs: number
  /** After (re)attaching to the player, its own start-up changes aren't treated as user actions. */
  readonly attachSettleMs: number
  /** Within the attach-settle window, at most this many realigns; one more when it ends. */
  readonly maxSettleRealigns: number
  /** After we broadcast, skip drift checks this long so our write can land first. */
  readonly broadcastGraceMs: number

  // ---- Item change (onItemChanged: next episode, another title or service) ----
  /** Leader: don't broadcast the new item's state sooner than this after the change. */
  readonly itemSettleMinMs: number
  /** Take the player's settled reading after this long even if it isn't known to be the new item's. */
  readonly itemSettleMaxMs: number
  /** A reading sampled up to this long BEFORE the change counts as the new item's. */
  readonly itemFreshToleranceMs: number
  /** Give up waiting (no settled reading / no room write for the new item) and re-align like a late join. */
  readonly itemRestartTimeoutMs: number
  /** Leader: after broadcasting the new item, others' writes aren't applied until ours lands, at most this long. */
  readonly itemOwnWriteWaitMs: number

  // ---- Capability checks ----
  /** A pause after at least this much play must show a moved position. */
  readonly positionCheckMinPlayMs: number
  /** ...moved by more than this. */
  readonly positionCheckToleranceMs: number

  // ---- Speed nudges (desktop only; Android never changes the app's speed) ----
  /**
   * In full mode, drift at or below driftThresholdMs (which Android leaves alone) is closed by
   * playing slightly slower or faster for a while instead of seeking, when the player can change
   * playbackRate (HTML5 video through the extension). Bigger drift is still fixed by a seek, with
   * the same threshold and rate limit as Android. The room state written is unchanged.
   */
  readonly rateNudges: boolean
  /** Drift at or below this isn't worth nudging (measurement noise: server clock, write latency). */
  readonly rateNudgeMinDriftMs: number
  /** How far from 1x a nudge goes: 0.02 = 0.98x when ahead, 1.02x when behind. */
  readonly rateNudgeDelta: number
  /** A running nudge stops early once the measured drift is within this (or has changed sign). */
  readonly rateNudgeDoneMs: number
  /** No reading shows the nudge speed this long after the command: the player ignores playbackRate. */
  readonly rateNudgeApplyTimeoutMs: number
}

export const DEFAULT_SYNC_CONFIG: SyncConfig = Object.freeze({
  echoWindowMs: 1_500,
  echoMaxHoldMs: 10_000,
  seekThresholdMs: 3_000,
  transientHoldMs: 10_000,
  stallWindowMaxMs: 15_000,
  driftThresholdMs: 1_500,
  driftCheckIntervalMs: 5_000,
  correctionCooldownMs: 10_000,
  maxConsecutiveCorrections: 3,
  positionReportIntervalMs: 5_000,
  memberReportMaxAgeMs: 15_000,
  maxCatchUpPauseMs: 30_000,
  attachSettleMs: 4_000,
  maxSettleRealigns: 2,
  broadcastGraceMs: 3_000,
  itemSettleMinMs: 800,
  itemSettleMaxMs: 3_000,
  itemFreshToleranceMs: 1_000,
  itemRestartTimeoutMs: 8_000,
  itemOwnWriteWaitMs: 3_000,
  positionCheckMinPlayMs: 5_000,
  positionCheckToleranceMs: 500,
  rateNudges: true,
  rateNudgeMinDriftMs: 300,
  rateNudgeDelta: 0.02,
  rateNudgeDoneMs: 100,
  rateNudgeApplyTimeoutMs: 3_000
})

/** The defaults with some values changed (tests, experiments). */
export function syncConfig(overrides: Partial<SyncConfig> = {}): SyncConfig {
  return Object.freeze({ ...DEFAULT_SYNC_CONFIG, ...overrides })
}
