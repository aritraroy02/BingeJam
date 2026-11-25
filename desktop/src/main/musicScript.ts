// The PowerShell helper src/main/music.ts keeps open: Windows' media sessions, Spotify's only, one
// JSON line whenever its song or play state changes ("none" when nothing). No Electron here, so it
// can be tried on its own.

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
    '$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])',
    "if (-not $mgr) { [Console]::Out.WriteLine('error'); exit 1 }",
    '$last = $null',
    'while ($true) {',
    // BingeJam closed (or crashed): stop too.
    '  try { $null = [System.Diagnostics.Process]::GetProcessById($parent) } catch { exit 0 }',
    "  $line = 'none'",
    '  try {',
    '    foreach ($s in $mgr.GetSessions()) {',
    "      if ($s.SourceAppUserModelId -notmatch 'spotify') { continue }",
    '      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])',
    '      if (-not $p) { continue }',
    '      $tl = $s.GetTimelineProperties()',
    '      $line = ConvertTo-Json -Compress -InputObject @{ app = $s.SourceAppUserModelId; status = [string]$s.GetPlaybackInfo().PlaybackStatus; title = $p.Title; artist = $p.Artist; durationMs = [int64]($tl.EndTime - $tl.StartTime).TotalMilliseconds }',
    '      break',
    '    }',
    "  } catch { $line = 'error' }",
    '  if ($line -ne $last) { [Console]::Out.WriteLine($line); [Console]::Out.Flush(); $last = $line }',
    `  Start-Sleep -Seconds ${Math.max(1, Math.trunc(pollSeconds))}`,
    '}'
  ].join('\n')
}
