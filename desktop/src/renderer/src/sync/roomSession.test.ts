// Room session end to end: two PCs (each a RoomSession + FakePlayer) on one fake room server,
// with a shared fake clock. Plus the session's pure helpers.
import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { FakePlayer, type FakeTab, type FakeTabOptions } from './fakePlayer.ts'
import { FakeRoomServer, ManualTimers } from './fakeRoom.ts'
import { ItemTracker, RoomSession, isOtherTitle, summarizeSync, type MemberSync } from './roomSession.ts'

const CODE = 'ABC234'
const SERVER_BASE = 1_700_000_000_000

class Sim {
  t = 10_000
  readonly now = (): number => this.t
  readonly timers = new ManualTimers(this.now)
  readonly server = new FakeRoomServer(() => SERVER_BASE + this.t)

  /** Real time passing, 100 ms at a time, firing ticks / heartbeats on the way. */
  advance(ms: number, step = 100): void {
    for (let done = 0; done < ms; done += step) {
      this.t += step
      this.timers.runDue()
    }
  }

  pc(uid: string, tab: FakeTabOptions): { player: FakePlayer; tab: FakeTab; session: RoomSession } {
    const player = new FakePlayer(this.now, { heartbeatMs: 1_000, timers: this.timers })
    const session = new RoomSession({ transport: this.server.transport(uid), player, localNow: this.now, timers: this.timers })
    session.start(CODE)
    return { player, tab: player.openTab(tab), session }
  }
}

const MOVIE: FakeTabOptions = { tabId: 1, service: 'NETFLIX', title: 'Midnight Signal', durationMs: 6_000_000 }

/** Riya hosts on one PC, Kabir is a member on another; both opened the movie, paused at 0. */
function twoPcs(): { sim: Sim; host: ReturnType<Sim['pc']>; member: ReturnType<Sim['pc']> } {
  const sim = new Sim()
  sim.server.createRoom(CODE, 'riya', 'Riya')
  sim.server.join(CODE, 'kabir', 'Kabir')
  const host = sim.pc('riya', MOVIE)
  const member = sim.pc('kabir', { ...MOVIE, tabId: 7 })
  sim.advance(5_000) // attach-settle; the empty room gets seeded
  return { sim, host, member }
}

const gap = (a: FakeTab, b: FakeTab): number => Math.abs(a.position() - b.position())

describe('RoomSession (two PCs)', () => {
  it('seeds the empty room once, then a host play is followed without echoes', () => {
    const { sim, host, member } = twoPcs()
    assert.equal(sim.server.stateWrites.length, 1)
    assert.equal(sim.server.room(CODE).state?.status, 'paused')

    host.tab.userPlay()
    sim.advance(3_000)
    assert.equal(member.tab.playing, true)
    assert.deepEqual(member.tab.received.map((c) => c.type), ['play'])
    assert.equal(sim.server.stateWrites.length, 2, 'the member must not write its echo back')
    assert.ok(gap(host.tab, member.tab) < 50, `gap ${gap(host.tab, member.tab)} ms`)
    assert.equal(member.session.state.status, 'syncing')
    assert.equal(member.session.state.playing, true)
  })

  it('a member scrub is followed by the host', () => {
    const { sim, host, member } = twoPcs()
    host.tab.userPlay()
    sim.advance(3_000)
    member.tab.userSeek(600_000)
    sim.advance(3_000)
    const last = sim.server.stateWrites.at(-1)
    assert.equal(last?.by, 'kabir')
    assert.equal(last?.reason, 'seek')
    assert.equal(sim.server.stateWrites.length, 3)
    assert.ok(host.tab.received.some((c) => c.type === 'seek'))
    assert.ok(gap(host.tab, member.tab) < 50)
    assert.ok(host.tab.position() > 600_000)
  })

  it('a late joiner seeks to the room and plays', () => {
    const sim = new Sim()
    sim.server.createRoom(CODE, 'riya', 'Riya')
    sim.server.join(CODE, 'kabir', 'Kabir')
    const host = sim.pc('riya', MOVIE)
    sim.advance(5_000)
    host.tab.userPlay()
    sim.advance(30_000)
    const member = sim.pc('kabir', { ...MOVIE, tabId: 7 })
    sim.advance(2_000)
    // seek, play; the tab's intermediate "paused at the target" reading inside the attach-settle
    // window makes the engine realign once more (a repeated, harmless play, as on Android).
    const types = member.tab.received.map((c) => c.type)
    assert.deepEqual(types.slice(0, 2), ['seek', 'play'])
    assert.ok(types.slice(2).every((t) => t === 'play'), JSON.stringify(types))
    assert.ok(gap(host.tab, member.tab) < 50, `gap ${gap(host.tab, member.tab)} ms`)
  })

  it('small drift is closed with a speed nudge, not a seek', () => {
    const { sim, host, member } = twoPcs()
    host.tab.userPlay()
    sim.advance(6_000)
    const before = member.tab.received.length
    member.tab.drift(800) // the member's video runs 0.8 s ahead, no event
    sim.advance(60_000)
    const after = member.tab.received.slice(before)
    assert.deepEqual(after, [{ type: 'rate', playbackRate: 0.98 }, { type: 'rate', playbackRate: 1 }])
    assert.ok(gap(host.tab, member.tab) < 150, `gap ${gap(host.tab, member.tab)} ms`)
    assert.equal(sim.server.stateWrites.length, 2, 'nudges never write the room state')
    assert.match(member.session.state.syncLabel, /^All in sync · ±0\.[0-9] s$/)
    assert.equal(member.session.state.allInSync, true)
  })

  it('reports member sync health to the room screen', () => {
    const { sim, host } = twoPcs()
    host.tab.userPlay()
    sim.advance(12_000) // a couple of position reports
    const state = host.session.state
    assert.equal(state.isHost, true)
    assert.deepEqual(
      state.members.map((m) => [m.name, m.isMe, m.isHost, m.health]),
      [['Riya', true, true, 'inSync'], ['Kabir', false, false, 'inSync']]
    )
    assert.ok(state.members.every((m) => m.offsetMs !== null && Math.abs(m.offsetMs) < 200))
    assert.equal(state.title, 'Midnight Signal')
    assert.equal(state.playing, true)
  })

  it('the host mirrors the title into the preview once per item', () => {
    const { sim } = twoPcs()
    sim.advance(10_000) // heartbeats don't re-publish
    assert.equal(sim.server.nowPlayingWrites.length, 1)
    assert.equal(sim.server.preview(CODE)?.title, 'Midnight Signal')
    assert.equal(sim.server.preview(CODE)?.service, 'NETFLIX')
  })

  it('a member on another title is not synced', () => {
    const sim = new Sim()
    sim.server.createRoom(CODE, 'riya', 'Riya')
    sim.server.join(CODE, 'kabir', 'Kabir')
    const host = sim.pc('riya', MOVIE)
    const member = sim.pc('kabir', { ...MOVIE, tabId: 7, title: 'Other Film', durationMs: 5_400_000 })
    sim.advance(5_000)
    assert.equal(member.session.state.status, 'otherTitle')
    const before = member.tab.received.length
    host.tab.userPlay()
    sim.advance(3_000)
    assert.equal(member.tab.received.length, before)
    assert.equal(member.tab.playing, false)
  })

  it('a closed tab shows "session lost"', () => {
    const { sim, member } = twoPcs()
    member.tab.close()
    sim.advance(1_000)
    assert.equal(member.session.state.status, 'sessionLost')
    assert.equal(member.session.state.tabId, null)
  })

  it('host next episode: one restart write for the new item, member follows', () => {
    const sim = new Sim()
    sim.server.createRoom(CODE, 'riya', 'Riya')
    sim.server.join(CODE, 'kabir', 'Kabir')
    const episode: FakeTabOptions = { ...MOVIE, title: 'Kaer Morhen', subtitle: 'S1:E1', durationMs: 3_000_000 }
    const host = sim.pc('riya', episode)
    const member = sim.pc('kabir', { ...episode, tabId: 7 })
    sim.advance(5_000)
    host.tab.userPlay()
    sim.advance(5_000)
    const writes = sim.server.stateWrites.length

    host.tab.changeItem('Kaer Morhen', 'S1:E2', 3_100_000, 0)
    member.tab.changeItem('Kaer Morhen', 'S1:E2', 3_100_000, 0) // the member's site autoplays it too
    sim.advance(5_000)
    const after = sim.server.stateWrites.slice(writes)
    assert.equal(after.length, 1, JSON.stringify(after))
    assert.equal(after[0].by, 'riya')
    assert.equal(after[0].reason, 'seek')
    assert.ok(after[0].positionMs < 2_000)
    assert.equal(sim.server.preview(CODE)?.subtitle, 'S1:E2')
    assert.ok(gap(host.tab, member.tab) < 1_600)
  })
})

describe('RoomSession helpers', () => {
  it('ItemTracker: first item and late subtitles are not changes; new episodes are', () => {
    const t = new ItemTracker()
    assert.equal(t.changed('NETFLIX', 'Show', null), false)
    assert.equal(t.changed('NETFLIX', 'show ', 'S1:E1'), false)
    assert.equal(t.changed('NETFLIX', 'Show', 'S1:E2'), true)
    assert.equal(t.changed('NETFLIX', null, null), false)
    assert.equal(t.changed('PRIME_VIDEO', 'Show', 'S1:E2'), true)
    assert.equal(t.changed('PRIME_VIDEO', 'Another', null), true)
  })

  it('isOtherTitle: another service, or a clearly different length', () => {
    const preview = { code: CODE, hostName: 'R', service: 'NETFLIX' as const, title: 'Midnight Signal', subtitle: null, durationMs: 6_000_000, playableRef: null, updatedAt: 0 }
    const tab = (service: 'NETFLIX' | 'PRIME_VIDEO', title: string, durationMs: number | null) => ({
      raw: { tabId: 1, service, title, subtitle: null, durationMs, positionMs: 0, playing: false, buffering: false, playbackRate: 1, sampledAt: 0, ageMs: 0 }
    })
    assert.equal(isOtherTitle(tab('PRIME_VIDEO', 'Midnight Signal', 6_000_000), preview), true)
    assert.equal(isOtherTitle(tab('NETFLIX', 'Midnight Signal', 6_005_000), preview), false)
    assert.equal(isOtherTitle(tab('NETFLIX', 'Midnight Signal: Director', 6_000_000), preview), false, 'wording differs across platforms')
    assert.equal(isOtherTitle(tab('NETFLIX', 'Something', 2_000_000), preview), true)
    assert.equal(isOtherTitle(tab('NETFLIX', 'Something', null), { ...preview, durationMs: null }), false)
  })

  it('summarizeSync labels', () => {
    const m = (name: string, offsetMs: number | null, health: MemberSync['health'], isMe = false): MemberSync => ({
      uid: name, name, online: true, isMe, isHost: false, offsetMs, health
    })
    assert.equal(summarizeSync([], false).label, 'Waiting for someone to press play')
    assert.deepEqual(summarizeSync([m('A', 40, 'inSync'), m('B', -180, 'inSync')], true), { allInSync: true, label: 'All in sync · ±0.2 s' })
    assert.equal(summarizeSync([m('A', 0, 'inSync'), m('Kabir', -2_100, 'catchingUp')], true).label, 'Kabir is 2.1 s behind')
    assert.equal(summarizeSync([m('Me', 1_600, 'catchingUp', true)], true).label, 'You are 1.6 s ahead')
    assert.equal(summarizeSync([m('A', null, 'buffering')], true).label, 'A is buffering')
    assert.equal(summarizeSync([m('A', 2_000, 'catchingUp'), m('B', -2_000, 'catchingUp')], true).label, '2 catching up')
  })
})
