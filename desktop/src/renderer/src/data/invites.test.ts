import { test } from 'node:test'
import assert from 'node:assert/strict'
import { inviteLink, parseInviteLink } from './inviteLinks.ts'

const TOKEN = 'Abcdefgh2345'

test('invite link round trip, same format as the phone', () => {
  assert.deepEqual(parseInviteLink(inviteLink('riya.k', TOKEN)), { username: 'riya.k', token: TOKEN })
})

test('found inside pasted text', () => {
  assert.deepEqual(parseInviteLink(`Add me on BingeJam: https://syncit-watchparty.web.app/add/kabir/${TOKEN} see you`), { username: 'kabir', token: TOKEN })
})

test('rejects room links, short or odd tokens, other hosts and bad usernames', () => {
  assert.equal(parseInviteLink('https://syncit-watchparty.web.app/join/LG8K4V'), null)
  assert.equal(parseInviteLink('https://syncit-watchparty.web.app/add/kabir/Abc123'), null)
  assert.equal(parseInviteLink('https://syncit-watchparty.web.app/add/kabir/Abcdefgh2340'), null)
  assert.equal(parseInviteLink(`https://evil.example/add/kabir/${TOKEN}`), null)
  assert.equal(parseInviteLink(`https://syncit-watchparty.web.app/add/a..b/${TOKEN}`), null)
})
