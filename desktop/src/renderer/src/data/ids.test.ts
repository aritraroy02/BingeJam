import { test } from 'node:test'
import assert from 'node:assert/strict'
import { dmFriend, dmId, isGroupId, isRoomCode, newRoomCode, ROOM_CODE_ALPHABET } from './ids.ts'
import { cleanDisplayName, cleanGroupName, normalizeUsername, usernameFromKey, usernameKey, usernameProblem } from './usernames.ts'

test('dmId sorts the two uids like Android', () => {
  assert.equal(dmId('bob', 'Alice'), 'Alice_bob')
  assert.equal(dmId('Alice', 'bob'), 'Alice_bob')
  assert.throws(() => dmId('a', 'a'))
})

test('dmFriend recognises our 1:1 chats and nothing else', () => {
  assert.equal(dmFriend('Alice_bob', 'Alice'), 'bob')
  assert.equal(dmFriend('Alice_bob', 'bob'), 'Alice')
  assert.equal(dmFriend('Alice_bob', 'carol'), null)
  assert.equal(dmFriend('bob_Alice', 'bob'), null) // not canonical order
  assert.equal(dmFriend('-NxAb_cd12', 'cd12'), null) // a group push id that happens to hold '_'
  assert.ok(isGroupId('-NxAbcDEF_12-x'))
})

test('room codes use the 32-letter alphabet', () => {
  const code = newRoomCode((n) => Uint8Array.from({ length: n }, (_, i) => i * 37))
  assert.equal(code.length, 6)
  assert.ok(isRoomCode(code))
  assert.ok([...newRoomCode()].every((c) => ROOM_CODE_ALPHABET.includes(c)))
  assert.ok(!isRoomCode('ABCDE1')) // 1 isn't in the alphabet
  assert.ok(!isRoomCode('abcdef'))
})

test('usernames: format, reserved, every dot becomes a comma in the key', () => {
  assert.equal(normalizeUsername('  @Riya.Sen '), 'riya.sen')
  assert.equal(usernameProblem('ab'), 'At least 3 characters')
  assert.equal(usernameProblem('.riya'), "Can't start or end with '.'")
  assert.equal(usernameProblem('ri..ya'), "Can't have two dots in a row")
  assert.equal(usernameProblem('admin'), 'That username is reserved')
  assert.equal(usernameProblem('Riya'), "Only letters, numbers, '_' and '.'")
  assert.equal(usernameProblem('riya.sen_2'), null)
  assert.equal(usernameKey('a.b.c'), 'a,b,c')
  assert.equal(usernameFromKey('a,b,c'), 'a.b.c')
})

test('names are trimmed, collapsed and capped', () => {
  assert.equal(cleanDisplayName('  Riya   Sen '), 'Riya Sen')
  assert.equal(cleanDisplayName('   '), null)
  assert.equal(cleanDisplayName('x'.repeat(50))?.length, 40)
  assert.equal(cleanGroupName('y'.repeat(60))?.length, 50)
})
