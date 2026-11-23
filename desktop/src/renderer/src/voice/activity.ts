// Is the mic picking up someone talking? Only then is audio sent, like the phone
// (core/.../voice/VoiceActivity.kt): louder than a slowly tracked noise floor, held for a short
// hangover so word endings aren't clipped, and the bar rises while friends' voices play out loud so
// their echo isn't sent back. Levels are RMS of float samples (-1..1).

/** The phone's 300 on the 16-bit scale. */
const MIN_LEVEL = 300 / 32768

export class VoiceActivity {
  private noiseFloor = MIN_LEVEL
  private voicedUntil = -Infinity
  private readonly hangoverMs: number

  /** [hangoverMs]: how long to keep sending after the voice drops below the bar. */
  constructor(hangoverMs = 400) {
    this.hangoverMs = hangoverMs
  }

  /**
   * [nowMs]: a monotonic clock (performance.now()). [playbackLevel]: RMS of what we're playing
   * right now, 0 when quiet.
   */
  isVoiced(level: number, nowMs: number, playbackLevel = 0): boolean {
    // Follow the floor down quickly and up slowly, so speech doesn't become "noise".
    this.noiseFloor = level < this.noiseFloor ? this.noiseFloor * 0.9 + level * 0.1 : this.noiseFloor * 0.995 + level * 0.005
    this.noiseFloor = Math.max(this.noiseFloor, MIN_LEVEL / 3)
    const bar = Math.max(MIN_LEVEL, this.noiseFloor * 3, playbackLevel * 0.6)
    if (level > bar) this.voicedUntil = nowMs + this.hangoverMs
    return nowMs <= this.voicedUntil
  }

  reset(): void {
    this.noiseFloor = MIN_LEVEL
    this.voicedUntil = -Infinity
  }
}

export function rms(samples: Float32Array): number {
  if (samples.length === 0) return 0
  let sum = 0
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i]
  return Math.sqrt(sum / samples.length)
}
