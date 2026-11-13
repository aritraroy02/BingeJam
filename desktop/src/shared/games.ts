// Which game is running, from the process list (name + executable path). Pure, unit-tested.
// Known games by executable name first (exact names, case-insensitive), then anything started from
// a game library folder (Steam, Epic, Riot, Xbox, EA, Ubisoft, GOG), named after its folder.

export interface RunningProcess {
  name: string
  path: string | null
}

/** Executable → shown name. Launchers and anti-cheat services are deliberately absent. */
export const KNOWN_GAMES: Record<string, string> = {
  'valorant-win64-shipping.exe': 'VALORANT',
  'league of legends.exe': 'League of Legends',
  'cs2.exe': 'Counter-Strike 2',
  'csgo.exe': 'Counter-Strike: Global Offensive',
  'dota2.exe': 'Dota 2',
  'fortniteclient-win64-shipping.exe': 'Fortnite',
  'r5apex.exe': 'Apex Legends',
  'r5apex_dx12.exe': 'Apex Legends',
  'gta5.exe': 'Grand Theft Auto V',
  'gta5_enhanced.exe': 'Grand Theft Auto V',
  'rdr2.exe': 'Red Dead Redemption 2',
  'minecraft.windows.exe': 'Minecraft',
  'javaw.exe#minecraft': 'Minecraft',
  'robloxplayerbeta.exe': 'Roblox',
  'valheim.exe': 'Valheim',
  'overwatch.exe': 'Overwatch 2',
  'rocketleague.exe': 'Rocket League',
  'tslgame.exe': 'PUBG: BATTLEGROUNDS',
  'genshinimpact.exe': 'Genshin Impact',
  'starrail.exe': 'Honkai: Star Rail',
  'zenlesszonezero.exe': 'Zenless Zone Zero',
  'shootergame.exe': 'ARK: Survival Evolved',
  'arkascended.exe': 'ARK: Survival Ascended',
  'fc25.exe': 'EA SPORTS FC 25',
  'fc26.exe': 'EA SPORTS FC 26',
  'cod.exe': 'Call of Duty',
  'cod24-cod.exe': 'Call of Duty',
  'eldenring.exe': 'ELDEN RING',
  'cyberpunk2077.exe': 'Cyberpunk 2077',
  'rainbowsix.exe': "Tom Clancy's Rainbow Six Siege",
  'rainbowsix_vulkan.exe': "Tom Clancy's Rainbow Six Siege",
  'palworld-win64-shipping.exe': 'Palworld',
  'fallguys_client_game.exe': 'Fall Guys',
  'marvel-win64-shipping.exe': 'Marvel Rivals',
  'deadlock.exe': 'Deadlock',
  'destiny2.exe': 'Destiny 2',
  'eurotrucks2.exe': 'Euro Truck Simulator 2',
  'forzahorizon5.exe': 'Forza Horizon 5',
  'rustclient.exe': 'Rust',
  'terraria.exe': 'Terraria',
  'stardew valley.exe': 'Stardew Valley',
  'lethal company.exe': 'Lethal Company',
  'helldivers2.exe': 'HELLDIVERS 2',
  'b1-win64-shipping.exe': 'Black Myth: Wukong',
  'discovery.exe': 'THE FINALS',
  'among us.exe': 'Among Us',
  'warframe.x64.exe': 'Warframe',
  'sdhdship.exe': 'Sleeping Dogs',
  'businesstour.exe': 'Business Tour',
  'witcher3.exe': 'The Witcher 3',
  'hogwartslegacy.exe': 'Hogwarts Legacy',
  'bg3.exe': "Baldur's Gate 3",
  'bg3_dx11.exe': "Baldur's Gate 3",
  'pathofexile.exe': 'Path of Exile',
  'pathofexile_x64.exe': 'Path of Exile',
  'wow.exe': 'World of Warcraft',
  'diablo iv.exe': 'Diablo IV',
  'hollow_knight.exe': 'Hollow Knight',
  'sekiro.exe': 'Sekiro',
  'darksoulsiii.exe': 'DARK SOULS III',
  'monsterhunterwilds.exe': 'Monster Hunter Wilds',
  'nba2k25.exe': 'NBA 2K25',
  'f1_24.exe': 'F1 24',
  'cricket24.exe': 'Cricket 24'
}

/** Folders whose sub-folder is a game: …\steamapps\common\<Game>\… and the like. */
const LIBRARY_FOLDERS = [
  /\\steamapps\\common\\([^\\]+)\\/i,
  /\\Epic Games\\([^\\]+)\\/i,
  /\\Riot Games\\([^\\]+)\\/i,
  /\\XboxGames\\([^\\]+)\\/i,
  /\\EA Games\\([^\\]+)\\/i,
  /\\Ubisoft Game Launcher\\games\\([^\\]+)\\/i,
  /\\GOG Galaxy\\Games\\([^\\]+)\\/i
]

/** Things in those folders that aren't games (launchers, runtimes, tools). */
const NOT_GAMES = /^(steamworks shared|steam controller configs|steamvr|wallpaper_engine|wallpaper engine|launcher|riot client|directx|redist|_commonredist|epic online services|easyanticheat|battleye|ea app|vc_?redist)/i
const NOT_GAME_EXES = /^(unitycrashhandler(64)?|crashreportclient|easyanticheat.*|beservice.*|vc_redist.*|dxsetup|launcher|riotclient.*|epicgameslauncher|steam(webhelper)?|uninstall.*|unins\d+)\.exe$/i

export function detectGame(processes: readonly RunningProcess[]): string | null {
  for (const p of processes) {
    const exe = p.name.toLowerCase()
    const known = KNOWN_GAMES[exe]
    if (known) return known
    // Java Minecraft runs as javaw.exe from a folder that says so.
    if (exe === 'javaw.exe' && p.path && /minecraft/i.test(p.path)) return KNOWN_GAMES['javaw.exe#minecraft']
  }
  for (const p of processes) {
    if (!p.path || NOT_GAME_EXES.test(p.name)) continue
    for (const re of LIBRARY_FOLDERS) {
      const folder = re.exec(p.path)?.[1]?.trim()
      if (folder && !NOT_GAMES.test(folder)) return folder.slice(0, 60)
    }
  }
  return null
}

/** "for 12 min", "for 2 h" (display only). */
export function playingFor(sinceMs: number, nowMs: number): string {
  const min = Math.max(0, Math.floor((nowMs - sinceMs) / 60_000))
  if (min < 1) return 'just started'
  if (min < 60) return `for ${min} min`
  return `for ${Math.floor(min / 60)} h`
}
