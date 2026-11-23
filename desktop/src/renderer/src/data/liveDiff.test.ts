import { test } from 'node:test'
import assert from 'node:assert/strict'
import { listenerId, planListeners } from './liveDiff.ts'

function running(...targets: { key: string; path: string }[]): Map<string, string> {
  return new Map(targets.map((t) => [listenerId(t), t.key]))
}

test('opening another 1:1 chat swaps the message listener (the "same chat everywhere" bug)', () => {
  const now = running({ key: 'msgs', path: 'dms/a_harsh' }, { key: 'room', path: 'dmRooms/a_harsh' })
  const plan = planListeners(now, [
    { key: 'msgs', path: 'dms/a_somesh' },
    { key: 'room', path: 'dmRooms/a_somesh' }
  ])
  assert.deepEqual(plan.stop, [listenerId({ key: 'msgs', path: 'dms/a_harsh' }), listenerId({ key: 'room', path: 'dmRooms/a_harsh' })])
  assert.deepEqual(plan.staleKeys, ['msgs', 'room'])
  assert.deepEqual(plan.start.map((t) => t.path), ['dms/a_somesh', 'dmRooms/a_somesh'])
})

test('a chat to a group keeps nothing from the chat', () => {
  const now = running({ key: 'msgs', path: 'dms/a_harsh' }, { key: 'hidden', path: 'dmHidden/a/a_harsh' })
  const plan = planListeners(now, [
    { key: 'msgs', path: 'groupMessages/g1' },
    { key: 'group', path: 'groups/g1' }
  ])
  assert.deepEqual(plan.staleKeys.sort(), ['hidden', 'msgs'])
  assert.deepEqual(plan.start.map((t) => t.key).sort(), ['group', 'msgs'])
})

test('the same reads again change nothing (no re-subscribing on every render)', () => {
  const targets = [
    { key: 'msgs', path: 'dms/a_harsh' },
    { key: 'room', path: 'dmRooms/a_harsh' }
  ]
  const plan = planListeners(running(...targets), targets)
  assert.deepEqual(plan, { stop: [], staleKeys: [], start: [] })
})

test('closing the chat stops everything', () => {
  const plan = planListeners(running({ key: 'msgs', path: 'dms/a_harsh' }), [])
  assert.equal(plan.stop.length, 1)
  assert.deepEqual(plan.start, [])
})

test('keys that already include the path (friends, profiles) behave as before', () => {
  const now = running({ key: 'u1', path: 'users/u1' })
  const plan = planListeners(now, [
    { key: 'u1', path: 'users/u1' },
    { key: 'u2', path: 'users/u2' }
  ])
  assert.deepEqual(plan.stop, [])
  assert.deepEqual(plan.start.map((t) => t.key), ['u2'])
})
