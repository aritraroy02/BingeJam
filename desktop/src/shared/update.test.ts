import { test } from 'node:test'
import assert from 'node:assert/strict'
import { compareVersions, pickUpdate } from './update.ts'

const SHA = 'a'.repeat(64)

function pc(version: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    tag_name: `pc-v${version}`,
    draft: false,
    prerelease: false,
    body: `BingeJam for Windows PC ${version}\n\n- Fixed a thing\n\nInstall: download it\n\nSHA-256: ${SHA}`,
    assets: [
      {
        name: `BingeJam-Setup-${version}.exe`,
        size: 113_000_000,
        browser_download_url: `https://github.com/aritraroy02/sync-it-releases/releases/download/pc-v${version}/BingeJam-Setup-${version}.exe`
      }
    ],
    ...extra
  }
}

test('versions compare by number, not text', () => {
  assert.equal(compareVersions('0.1.10', '0.1.9'), 1)
  assert.equal(compareVersions('0.1.2', '0.1.2'), 0)
  assert.equal(compareVersions('0.1.2', '1.0.0'), -1)
  assert.equal(compareVersions('junk', '0.0.1'), -1)
})

test('the newest PC release newer than ours wins; phone releases are ignored', () => {
  const list = [{ tag_name: 'v0.6.6', assets: [] }, pc('0.1.2'), pc('0.1.4'), pc('0.1.3')]
  const u = pickUpdate(list, '0.1.2')
  assert.equal(u?.version, '0.1.4')
  assert.equal(u?.sha256, SHA)
  assert.equal(u?.notes, 'BingeJam for Windows PC 0.1.4\n- Fixed a thing')
  assert.equal(pickUpdate(list, '0.1.4'), null)
})

test('drafts, pre-releases, missing installers and foreign download links are skipped', () => {
  assert.equal(pickUpdate([pc('0.2.0', { draft: true })], '0.1.0'), null)
  assert.equal(pickUpdate([pc('0.2.0', { prerelease: true })], '0.1.0'), null)
  assert.equal(pickUpdate([pc('0.2.0', { assets: [] })], '0.1.0'), null)
  const evil = pc('0.2.0')
  ;(evil.assets as Record<string, unknown>[])[0].browser_download_url = 'https://example.com/BingeJam-Setup-0.2.0.exe'
  assert.equal(pickUpdate([evil], '0.1.0'), null)
  assert.equal(pickUpdate('nope', '0.1.0'), null)
})

test("GitHub's own asset digest is preferred over the notes", () => {
  const r = pc('0.2.0')
  ;(r.assets as Record<string, unknown>[])[0].digest = `sha256:${'B'.repeat(64)}`
  assert.equal(pickUpdate([r], '0.1.0')?.sha256, 'b'.repeat(64))
  assert.equal(pickUpdate([pc('0.2.0', { body: 'no hash here' })], '0.1.0')?.sha256, null)
})
