import { test } from 'node:test'
import assert from 'node:assert/strict'
import { agoShort, dayLabel, durationLabel, elapsedLabel, lastSeenText, listTimeLabel, timeLabel } from './format.ts'

const IST = { timeZone: 'Asia/Kolkata' }
// 2026-11-08 (a Sunday) 21:02 IST = 15:32 UTC
const NOW = Date.UTC(2026, 10, 8, 15, 32)
const HOUR = 3_600_000
const DAY = 24 * HOUR

test('time of day in 12 h and 24 h', () => {
  assert.equal(timeLabel(NOW, NOW, { ...IST, hour12: true }), '9:02 pm')
  assert.equal(timeLabel(NOW, NOW, { ...IST, hour12: false }), '21:02')
  assert.equal(timeLabel(0, NOW, { ...IST, hour12: false }), '21:02') // pending stamp = now
})

test('day pills: Today, Yesterday, weekday, date', () => {
  assert.equal(dayLabel(NOW - HOUR, NOW, IST), 'Today')
  assert.equal(dayLabel(NOW - DAY, NOW, IST), 'Yesterday')
  assert.equal(dayLabel(NOW - 3 * DAY, NOW, IST), 'Thursday')
  assert.equal(dayLabel(NOW - 30 * DAY, NOW, IST), '9 October')
  assert.equal(dayLabel(NOW - 400 * DAY, NOW, IST), '4 October 2025')
  // 23:30 and 00:30 IST: different days though an hour apart
  const midnight = Date.UTC(2026, 10, 8, 18, 30)
  assert.equal(dayLabel(midnight - 30 * 60_000, midnight + 30 * 60_000, IST), 'Yesterday')
  assert.equal(dayLabel(NOW + 60_000, NOW, IST), 'Today') // slightly ahead of our clock
})

test('chats list labels', () => {
  assert.equal(listTimeLabel(NOW - HOUR, NOW, { ...IST, hour12: true }), '8:02 pm')
  assert.equal(listTimeLabel(NOW - DAY, NOW, IST), 'Yesterday')
  assert.equal(listTimeLabel(NOW - 6 * DAY, NOW, IST), 'Mon')
  assert.equal(listTimeLabel(NOW - 30 * DAY, NOW, IST), '9 Oct')
})

test('ages and durations', () => {
  assert.equal(agoShort(30_000), 'now')
  assert.equal(agoShort(5 * 60_000), '5 min')
  assert.equal(agoShort(2 * HOUR + 5), '2 h')
  assert.equal(agoShort(3 * DAY), '3 d')
  assert.equal(lastSeenText(null, NOW), 'Offline')
  assert.equal(lastSeenText(NOW + 5000, NOW), 'Last seen just now')
  assert.equal(lastSeenText(NOW - 5 * 60_000, NOW), 'Last seen 5 min ago')
  assert.equal(lastSeenText(NOW - 2 * HOUR, NOW), 'Last seen 2 h ago')
  assert.equal(lastSeenText(NOW - 8 * DAY, NOW), 'Last seen over a week ago')
  assert.equal(durationLabel(102 * 60_000), '1 h 42 m')
  assert.equal(durationLabel(2 * HOUR), '2 h')
  assert.equal(durationLabel(10_000), '1 m')
  assert.equal(elapsedLabel(NOW - 18 * 60_000, NOW), '18 min')
  assert.equal(elapsedLabel(NOW - 65 * 60_000, NOW), '1 h 5 min')
  assert.equal(elapsedLabel(NOW + 1000, NOW), 'just now')
})
