import { test } from 'node:test'
import assert from 'node:assert/strict'
import { detectGame } from './games.ts'

test('known games by executable', () => {
  assert.equal(detectGame([{ name: 'explorer.exe', path: 'C:\\Windows\\explorer.exe' }, { name: 'VALORANT-Win64-Shipping.exe', path: null }]), 'VALORANT')
  assert.equal(detectGame([{ name: 'cs2.exe', path: null }]), 'Counter-Strike 2')
})

test('library folders name the game; launchers and helpers do not count', () => {
  assert.equal(detectGame([{ name: 'Game.exe', path: 'D:\\SteamLibrary\\steamapps\\common\\Hades II\\Game.exe' }]), 'Hades II')
  assert.equal(detectGame([{ name: 'steamwebhelper.exe', path: 'C:\\Program Files (x86)\\Steam\\steamapps\\common\\Foo\\steamwebhelper.exe' }]), null)
  assert.equal(detectGame([{ name: 'x.exe', path: 'C:\\Program Files (x86)\\Steam\\steamapps\\common\\Steamworks Shared\\x.exe' }]), null)
  assert.equal(detectGame([{ name: 'RiotClientServices.exe', path: 'C:\\Riot Games\\Riot Client\\RiotClientServices.exe' }]), null)
})

test('Java Minecraft only when its path says so', () => {
  assert.equal(detectGame([{ name: 'javaw.exe', path: 'C:\\Users\\a\\AppData\\Roaming\\.minecraft\\runtime\\javaw.exe' }]), 'Minecraft')
  assert.equal(detectGame([{ name: 'javaw.exe', path: 'C:\\Program Files\\Java\\bin\\javaw.exe' }]), null)
  assert.equal(detectGame([]), null)
})
