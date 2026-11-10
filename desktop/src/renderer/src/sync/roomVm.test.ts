import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildRoomVM, livePositionMs, offsetLabel, type RoomVmInput } from './roomVm.ts'
import type { RoomSyncState } from './roomSession.ts'

function state(over: Partial<RoomSyncState> = {}): RoomSyncState {
  return {
    code: 'ABC234', status: 'synced', mode: 'full', isHost: false, playing: false, started: true,
    positionMs: 60_000, positionAt: 1_000, durationMs: 120_000, service: 'NETFLIX', title: 'Midnight Signal', subtitle: 'S2:E4',
    tabId: 7, driftMs: 0, nudging: false, members: [], allInSync: true, syncLabel: 'All in sync · ±0.2 s', countdown: null, lastError: null,
    ...over
  } as RoomSyncState
}

function input(over: Partial<RoomVmInput> = {}): RoomVmInput {
  return {
    state: state(), code: 'ABC234', chatId: 'g1', chatName: 'Movie Night', chatKind: 'group', chatPeople: [],
    photos: new Map(), browser: 'Edge', waitForBuffering: true, localNow: 3_000, ...over
  }
}

test('position moves on only while playing, and stops at the end', () => {
  assert.equal(livePositionMs(state(), 3_000), 60_000)
  assert.equal(livePositionMs(state({ playing: true }), 3_000), 62_000)
  assert.equal(livePositionMs(state({ playing: true, positionMs: 119_500 }), 3_000), 120_000)
  assert.equal(livePositionMs(state({ positionMs: null }), 3_000), 0)
})

test('offsets read as ahead / behind', () => {
  assert.equal(offsetLabel(400), '+0.4 s')
  assert.equal(offsetLabel(-1_250), '−1.3 s')
})

test('title, service and player line', () => {
  const vm = buildRoomVM(input())
  assert.equal(vm.title, 'Midnight Signal · S2:E4')
  assert.equal(vm.service, 'Netflix')
  assert.equal(vm.context, 'Movie Night · group room')
  assert.equal(vm.playerLabel, 'Playing in your Netflix tab · Edge')
  assert.equal(vm.playerAction, undefined)
})

test('no tab: open the service; no extension: add it', () => {
  assert.equal(buildRoomVM(input({ state: state({ tabId: null }) })).playerAction, 'Open Netflix')
  const none = buildRoomVM(input({ state: state({ tabId: null, service: null, title: null }), browser: null, chatKind: 'dm', chatName: 'Riya' }))
  assert.equal(none.playerAction, 'Add to browser')
  assert.equal(none.title, 'Nothing playing yet')
  assert.equal(none.context, 'Room with Riya')
})

test('members map to sync labels; people already in are not "not here"', () => {
  const vm = buildRoomVM(
    input({
      state: state({
        members: [
          { uid: 'me', name: 'Aritra', online: true, isMe: true, isHost: true, offsetMs: 0, health: 'inSync' },
          { uid: 'k', name: 'Kabir', online: true, isMe: false, isHost: false, offsetMs: -2_100, health: 'catchingUp' }
        ] as RoomSyncState['members']
      }),
      chatPeople: [
        { id: 'k', name: 'Kabir', detail: '', inRoom: true, isMe: false, ping: null },
        { id: 'm', name: 'Meera', detail: 'Join any time', inRoom: false, isMe: false, ping: 'can' }
      ]
    })
  )
  assert.deepEqual(vm.members.map((m) => [m.name, m.syncLabel, m.device]), [
    ['You', 'In sync', 'This PC · Edge'],
    ['Kabir', '−2.1 s', 'In the room']
  ])
  assert.deepEqual(vm.notHere.map((p) => p.id), ['m'])
})
