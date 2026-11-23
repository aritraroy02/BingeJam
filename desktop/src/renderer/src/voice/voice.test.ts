import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decodeAudio, encodeAudio, parseControl, stateMessage } from './wire.ts'
import { cityOf, pingQuality, smoothPing } from './regions.ts'
import { VoiceActivity, rms } from './activity.ts'
import { MAX_DELAY_S, TARGET_DELAY_S, schedule } from './playout.ts'

// ---------- Wire ----------

/** What the relay does to our message before passing it on: insert our slot after the type byte. */
function relayed(sent: Uint8Array, slot: number): Uint8Array {
  const out = new Uint8Array(sent.length + 1)
  out[0] = sent[0]
  out[1] = slot
  out.set(sent.subarray(1), 2)
  return out
}

test('audio we send comes back from the relay with the same packets', () => {
  const a = Uint8Array.from([1, 2, 3])
  const b = Uint8Array.from([9, 8])
  const sent = encodeAudio(65537, true, [a, b])
  assert.ok(sent)
  // [1][seq u16 = 1][flags = HD][count = 2]
  assert.deepEqual([...sent.subarray(0, 5)], [1, 0, 1, 1, 2])
  const got = decodeAudio(relayed(sent, 3))
  assert.ok(got)
  assert.equal(got.slot, 3)
  assert.equal(got.seq, 1)
  assert.equal(got.hd, true)
  assert.deepEqual(got.packets.map((p) => [...p]), [[1, 2, 3], [9, 8]])
})

test('messages the relay would drop are not built', () => {
  assert.equal(encodeAudio(0, true, []), null)
  assert.equal(encodeAudio(0, true, [new Uint8Array(0)]), null)
  assert.equal(encodeAudio(0, true, [new Uint8Array(1200), new Uint8Array(1200)]), null) // over 2048 bytes
})

test('malformed audio from the relay is ignored', () => {
  assert.equal(decodeAudio(Uint8Array.from([1, 0, 0, 0, 0])), null) // too short
  assert.equal(decodeAudio(Uint8Array.from([2, 0, 0, 0, 0, 1, 0, 1, 7])), null) // not audio
  assert.equal(decodeAudio(Uint8Array.from([1, 0, 0, 0, 0, 1, 0, 5, 7])), null) // length past the end
  assert.equal(decodeAudio(Uint8Array.from([1, 0, 0, 0, 0, 0])), null) // no packets
})

test('hello carries the slot, the people already there and where the server is', () => {
  const m = parseControl(
    JSON.stringify({ t: 'hello', slot: 2, server: 'BOM', edge: 'BOM', peers: [{ slot: 0, uid: 'a', name: 'Riya', muted: true }, { nope: 1 }] })
  )
  assert.deepEqual(m, { t: 'hello', slot: 2, server: 'BOM', edge: 'BOM', peers: [{ slot: 0, uid: 'a', name: 'Riya', muted: true, deafened: false, live: false }] })
})

test('an older relay without server info still says hello', () => {
  const m = parseControl(JSON.stringify({ t: 'hello', slot: 0, peers: [] }))
  assert.deepEqual(m, { t: 'hello', slot: 0, peers: [], server: null, edge: null })
  assert.deepEqual(parseControl(JSON.stringify({ t: 'hello', slot: 0, peers: [], server: 'bom<script>' })), {
    t: 'hello',
    slot: 0,
    peers: [],
    server: null,
    edge: null
  })
})

test('join, leave, state and pong are understood; anything else is not', () => {
  assert.deepEqual(parseControl('{"t":"join","peer":{"slot":1,"uid":"b","name":""}}'), {
    t: 'join',
    peer: { slot: 1, uid: 'b', name: 'Friend', muted: false, deafened: false, live: false }
  })
  assert.deepEqual(parseControl('{"t":"leave","slot":1}'), { t: 'leave', slot: 1 })
  assert.deepEqual(parseControl('{"t":"state","slot":1,"muted":true,"deafened":true}'), { t: 'state', slot: 1, muted: true, deafened: true, live: false })
  assert.deepEqual(parseControl('pong'), { t: 'pong' })
  assert.equal(parseControl('{"t":"what"}'), null)
  assert.equal(parseControl('not json'), null)
})

test('our state message matches the phone', () => {
  assert.deepEqual(JSON.parse(stateMessage(true, false)), { t: 'state', muted: true, deafened: false, quality: 'hd', live: false })
  assert.equal(JSON.parse(stateMessage(false, false, true)).live, true)
})

// ---------- Regions and ping ----------

test('data centres read as cities', () => {
  assert.equal(cityOf('BOM'), 'Mumbai')
  assert.equal(cityOf('SIN'), 'Singapore')
  assert.equal(cityOf('XYZ'), 'XYZ')
  assert.equal(cityOf(null), null)
})

test('ping is the median of the last five round trips', () => {
  assert.equal(smoothPing([]), null)
  assert.equal(smoothPing([30]), 30)
  assert.equal(smoothPing([900, 30, 32, 31, 400, 29]), 31) // last five sorted: 29 30 31 32 400
  assert.equal(pingQuality(40), 'good')
  assert.equal(pingQuality(120), 'ok')
  assert.equal(pingQuality(250), 'poor')
  assert.equal(pingQuality(null), null)
})

// ---------- Voice activity ----------

test('silence is not sent, speech is, and word endings are held for the hangover', () => {
  const vad = new VoiceActivity(400)
  assert.equal(vad.isVoiced(0.001, 0), false)
  assert.equal(vad.isVoiced(0.2, 10), true)
  assert.equal(vad.isVoiced(0.001, 300), true) // within 400 ms
  assert.equal(vad.isVoiced(0.001, 500), false)
})

test("friends' voices playing loudly raise the bar (no echo sent back)", () => {
  const vad = new VoiceActivity(0)
  assert.equal(vad.isVoiced(0.05, 0, 0.2), false)
  assert.equal(vad.isVoiced(0.05, 1000, 0), true)
})

test('rms of a square wave is its amplitude', () => {
  assert.equal(rms(Float32Array.from([0.5, -0.5, 0.5, -0.5])), 0.5)
  assert.equal(rms(new Float32Array(0)), 0)
})

// ---------- Playout ----------

test('a talker starts with an 80 ms cushion and then plays back to back', () => {
  const slot = { playhead: 0 }
  assert.deepEqual(schedule(slot, 10, 0.02), { at: 10 + TARGET_DELAY_S, reset: false })
  const second = schedule(slot, 10.001, 0.02)
  assert.equal(second.reset, false)
  assert.ok(Math.abs(second.at - (10 + TARGET_DELAY_S + 0.02)) < 1e-9)
})

test('after a pause in talking the cushion starts again', () => {
  const slot = { playhead: 10.1 }
  assert.equal(schedule(slot, 20, 0.02).at, 20 + TARGET_DELAY_S)
})

test('a burst that queues more than 400 ms resets to the cushion', () => {
  const slot = { playhead: 10 + MAX_DELAY_S + 0.05 }
  assert.deepEqual(schedule(slot, 10, 0.02), { at: 10 + TARGET_DELAY_S, reset: true })
})

// ---------- Screen share ----------
import { MAX_VIEWERS, QUALITY, parseSignal, preferredCodecs } from './streamModel.ts'
import { isDrmWindow } from '../../../shared/screen.ts'

test('set-up messages from other apps are checked before use', () => {
  assert.deepEqual(parseSignal({ kind: 'watch' }), { kind: 'watch' })
  assert.deepEqual(parseSignal({ kind: 'offer', sdp: 'v=0' }), { kind: 'offer', sdp: 'v=0' })
  assert.equal(parseSignal({ kind: 'offer', sdp: '' }), null)
  assert.equal(parseSignal({ kind: 'answer', sdp: 'x'.repeat(40_000) }), null)
  assert.deepEqual(parseSignal({ kind: 'ice', candidate: { candidate: 'candidate:1 1 udp 1 1.2.3.4 5 typ host', sdpMid: '0', sdpMLineIndex: 0, extra: 'x' } }), {
    kind: 'ice',
    candidate: { candidate: 'candidate:1 1 udp 1 1.2.3.4 5 typ host', sdpMid: '0', sdpMLineIndex: 0 }
  })
  assert.equal(parseSignal({ kind: 'ice', candidate: 'nope' }), null)
  assert.equal(parseSignal({ kind: 'exec' }), null)
  assert.equal(parseSignal(null), null)
})

test('H.264 is offered first (hardware encoders), extras keep their place after the codecs', () => {
  const order = preferredCodecs([{ mimeType: 'video/VP8' }, { mimeType: 'video/rtx' }, { mimeType: 'video/AV1' }, { mimeType: 'video/H264' }, { mimeType: 'video/VP9' }])
  assert.deepEqual(order.map((c) => c.mimeType), ['video/H264', 'video/VP9', 'video/AV1', 'video/VP8', 'video/rtx'])
})

test('quality presets match the plan (1080p60 default, up to 4K60 at ~25 Mbps) and viewers are capped', () => {
  assert.equal(QUALITY['1080p60'].fps, 60)
  assert.equal(QUALITY['4k60'].bitrate, 25_000_000)
  assert.ok(MAX_VIEWERS >= 3 && MAX_VIEWERS <= 8)
})

test('Netflix, Prime Video and JioHotstar windows are blocked; games and other apps are not', () => {
  assert.equal(isDrmWindow('Netflix - Microsoft Edge'), true)
  assert.equal(isDrmWindow('Watch Panchayat | Prime Video - Google Chrome'), true)
  assert.equal(isDrmWindow('JioHotstar - Watch TV Shows'), true)
  assert.equal(isDrmWindow('VALORANT'), false)
  assert.equal(isDrmWindow('Visual Studio Code'), false)
})
