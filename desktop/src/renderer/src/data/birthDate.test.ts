import { test } from 'node:test'
import assert from 'node:assert/strict'
import { ageOn, birthDateProblem, parseBirthDate, wireBirthDate } from './birthDate.ts'

const today = { y: 2026, m: 11, d: 10 }

test('parses and writes YYYY-MM-DD, refusing impossible days', () => {
  assert.deepEqual(parseBirthDate('2001-04-09'), { y: 2001, m: 4, d: 9 })
  assert.equal(wireBirthDate({ y: 2001, m: 4, d: 9 }), '2001-04-09')
  assert.equal(parseBirthDate('2001-02-30'), null)
  assert.equal(parseBirthDate('2000-02-29')?.d, 29)
  assert.equal(parseBirthDate('1899-01-01'), null)
  assert.equal(parseBirthDate('2001-4-9'), null)
  assert.equal(parseBirthDate(null), null)
})

test('age counts whole years, birthday included', () => {
  assert.equal(ageOn({ y: 2008, m: 11, d: 10 }, today), 18)
  assert.equal(ageOn({ y: 2008, m: 11, d: 11 }, today), 17)
  assert.equal(ageOn({ y: 2008, m: 12, d: 1 }, today), 17)
})

test('18+ only, same wording as the phone', () => {
  assert.equal(birthDateProblem({ y: 2008, m: 11, d: 10 }, today), null)
  assert.equal(birthDateProblem({ y: 2008, m: 11, d: 11 }, today), 'You need to be 18 or older to use BingeJam')
  assert.equal(birthDateProblem({ y: 2027, m: 1, d: 1 }, today), "That doesn't look like a real date of birth")
  assert.equal(birthDateProblem({ y: 1919, m: 1, d: 1 }, today), "That doesn't look like a real date of birth")
  assert.equal(birthDateProblem(null, today), "That doesn't look like a real date of birth")
})
