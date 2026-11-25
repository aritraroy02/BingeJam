// The PowerShell helper src/main/music.ts keeps open: Windows' media sessions, Spotify's only, one
// JSON line whenever its song or play state changes, or the position jumps (a seek); "none" when
// nothing plays. The album art (base64) comes once per song, for your own profile card only. No
// Electron here, so it can be tried on its own.

/** No double quotes anywhere: Windows' command line would mangle them on the way to PowerShell. */
export function musicHelperScript(parentPid: number, pollSeconds: number): string {
  return [
    '$ErrorActionPreference = [System.Management.Automation.ActionPreference]::Stop',
    '[Console]::OutputEncoding = [System.Text.Encoding]::UTF8',
    `$parent = ${Math.trunc(parentPid)}`,
    'Add-Type -AssemblyName System.Runtime.WindowsRuntime',
    "$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
    'function Await($op, [Type]$type) { $t = $asTask.MakeGenericMethod($type).Invoke($null, @($op)); if ($t.Wait(5000)) { $t.Result } else { $null } }',
    '$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]',
    '$null = [Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime]',
    '$null = [Windows.Storage.Streams.IInputStream, Windows.Storage.Streams, ContentType = WindowsRuntime]',
    // PowerShell can't pass the art stream to .NET itself; reflection lets the runtime convert it.
    "$asStream = [System.IO.WindowsRuntimeStreamExtensions].GetMethod('AsStreamForRead', [Type[]]@([Windows.Storage.Streams.IInputStream]))",
    'function ArtOf($p) { try { if (-not $p.Thumbnail) { return $null }; $rs = Await ($p.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType]); if (-not $rs) { return $null }; $st = $asStream.Invoke($null, @($rs)); $ms = New-Object System.IO.MemoryStream; $st.CopyTo($ms); $st.Dispose(); if ($ms.Length -gt 1500000) { return $null }; return [Convert]::ToBase64String($ms.ToArray()) } catch { return $null } }',
    '$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])',
    "if (-not $mgr) { [Console]::Out.WriteLine('error'); exit 1 }",
    '$last = $null',
    '$lastSong = $null',
    '$lastPos = 0',
    '$lastAt = 0',
    '$lastPlaying = $false',
    '$clock = [System.Diagnostics.Stopwatch]::StartNew()',
    'while ($true) {',
    // BingeJam closed (or crashed): stop too.
    '  try { $null = [System.Diagnostics.Process]::GetProcessById($parent) } catch { exit 0 }',
    "  $key = 'none'",
    '  $obj = $null',
    '  try {',
    '    foreach ($s in $mgr.GetSessions()) {',
    "      if ($s.SourceAppUserModelId -notmatch 'spotify') { continue }",
    '      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])',
    '      if (-not $p) { continue }',
    '      $tl = $s.GetTimelineProperties()',
    '      $status = [string]$s.GetPlaybackInfo().PlaybackStatus',
    // Windows gives the position as of its last update; while playing, add the time since.
    '      $pos = $tl.Position.TotalMilliseconds',
    "      if ($status -eq 'Playing') { $pos = $pos + ([DateTimeOffset]::Now - $tl.LastUpdatedTime).TotalMilliseconds }",
    '      $dur = [int64]($tl.EndTime - $tl.StartTime).TotalMilliseconds',
    "      $key = $s.SourceAppUserModelId + '|' + $status + '|' + $p.Title + '|' + $p.Artist + '|' + $dur",
    '      $obj = @{ app = $s.SourceAppUserModelId; status = $status; title = $p.Title; artist = $p.Artist; durationMs = $dur; positionMs = [int64]$pos }',
    "      $song = $p.Title + '|' + $p.Artist",
    '      if ($song -ne $lastSong) { $art = ArtOf $p; if ($art) { $obj.art = $art }; $lastSong = $song }',
    '      break',
    '    }',
    "  } catch { $key = 'error'; $obj = $null }",
    "  if ($key -eq 'none') { $lastSong = $null }",
    '  $now = $clock.ElapsedMilliseconds',
    '  $expected = $lastPos',
    '  if ($lastPlaying) { $expected = $lastPos + ($now - $lastAt) }',
    '  $jumped = $obj -and [Math]::Abs($obj.positionMs - $expected) -gt 3000',
    '  if ($key -ne $last -or $jumped) {',
    "    if ($obj) { [Console]::Out.WriteLine((ConvertTo-Json -Compress -InputObject $obj)); $lastPos = $obj.positionMs; $lastAt = $now; $lastPlaying = ($obj.status -eq 'Playing') }",
    '    else { [Console]::Out.WriteLine($key) }',
    '    [Console]::Out.Flush()',
    '    $last = $key',
    '  }',
    `  Start-Sleep -Seconds ${Math.max(1, Math.trunc(pollSeconds))}`,
    '}'
  ].join('\n')
}
