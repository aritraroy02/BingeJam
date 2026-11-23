// When to play each decoded 20 ms of a friend's voice (Web Audio time, seconds). Same targets as the
// phone's jitter buffer: start a talker 80 ms behind so late packets still make it, and never let
// them fall more than 400 ms behind (jump back to the cushion instead).

export const TARGET_DELAY_S = 0.08
export const MAX_DELAY_S = 0.4

export interface Slot {
  /** Audio-clock time the next chunk should start at; 0 = not playing. */
  playhead: number
}

/**
 * Where a chunk of [duration] seconds goes, given the audio clock [now]. [reset] = more than
 * MAX_DELAY_S was queued (a burst after a network hiccup): the caller stops what it queued for this
 * slot, and this chunk starts the cushion again. Advances the slot's playhead.
 */
export function schedule(slot: Slot, now: number, duration: number): { at: number; reset: boolean } {
  let reset = false
  if (slot.playhead - now > MAX_DELAY_S) {
    reset = true
    slot.playhead = now + TARGET_DELAY_S
  } else if (slot.playhead < now + 0.005) {
    // Ran dry (a pause in talking, or a late packet): restart with the cushion.
    slot.playhead = now + TARGET_DELAY_S
  }
  const at = slot.playhead
  slot.playhead += duration
  return { at, reset }
}
