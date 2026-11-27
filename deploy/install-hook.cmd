@echo off
rem deploy\install-hook.cmd: one-stop Veridger client hook installer for Codex, Claude Code, and Gemini/Antigravity on
rem Windows. Downloads ledger-client-hook.jar and ledger-hook-resident.jar from GitHub Releases (or uses --jars=<dir>),
rem verifies every artifact explicitly against SHA256SUMS, stops any running resident hook before copying into a
rem versioned directory under %LOCALAPPDATA%\Programs\veridger-hook (preserving prior versions as rollback backup),
rem safely merges PreToolUse entries using PowerShell 5.1-compatible JSON (UTF-8 without BOM), and runs a live
rem smoke test on mcp__veridger__ledger_ledger_whoami.
rem
rem Usage:
rem   deploy\install-hook.cmd [--version=latest|v4.3.2] [--jars=<local-dir>] [--hosts=codex,claude,antigravity] [--java=<path>]
setlocal EnableExtensions DisableDelayedExpansion
set "VH_VER=latest"
set "VH_REPO=jaeseongs95/veridger"
set "VH_JARS="
set "VH_HOSTS=codex,claude,antigravity"
set "VH_JAVA="
set "VH_SELF=%~f0"

:parse
if "%~1"=="" goto :parsed
set "A=%~1"
if /i "%A%"=="--version" goto :opt_version
if /i "%A%"=="--repo" goto :opt_repo
if /i "%A%"=="--jars" goto :opt_jars
if /i "%A%"=="--java" goto :opt_java
if /i "%A%"=="--hosts" goto :opt_hosts
if /i "%A:~0,10%"=="--version=" goto :eq_version
if /i "%A:~0,7%"=="--repo=" goto :eq_repo
if /i "%A:~0,7%"=="--jars=" goto :eq_jars
if /i "%A:~0,8%"=="--hosts=" goto :eq_hosts
if /i "%A:~0,7%"=="--java=" goto :eq_java
echo install-hook.cmd: unknown option
exit /b 1

:opt_version
if "%~2"=="" goto :missing
set "VH_VER=%~2"
shift
shift
goto :parse

:opt_repo
if "%~2"=="" goto :missing
set "VH_REPO=%~2"
shift
shift
goto :parse

:opt_jars
if "%~2"=="" goto :missing
set "VH_JARS=%~2"
shift
shift
goto :parse

:opt_java
if "%~2"=="" goto :missing
set "VH_JAVA=%~2"
shift
shift
goto :parse

:opt_hosts
shift
set "VH_HOSTS="
:parse_hosts
if "%~1"=="" goto :hosts_done
set "H=%~1"
if "%H:~0,2%"=="--" goto :hosts_done
if defined VH_HOSTS set "VH_HOSTS=%VH_HOSTS%,%H%"
if not defined VH_HOSTS set "VH_HOSTS=%H%"
shift
goto :parse_hosts
:hosts_done
if not defined VH_HOSTS goto :missing
goto :parse

:eq_version
set "VH_VER=%A:~10%"
shift
goto :parse

:eq_repo
set "VH_REPO=%A:~7%"
shift
goto :parse

:eq_jars
set "VH_JARS=%A:~7%"
shift
goto :parse

:eq_hosts
set "VH_HOSTS=%A:~8%"
shift
goto :parse

:eq_java
set "VH_JAVA=%A:~7%"
shift
goto :parse

:missing
echo install-hook.cmd: missing value for option
exit /b 1

:parsed
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[System.IO.File]::ReadAllText($env:VH_SELF,[System.Text.Encoding]::UTF8); $m='#'+'__VERIDGER_HOOK_PS__'; $i=$s.IndexOf($m); & ([scriptblock]::Create($s.Substring($i+$m.Length)))"
exit /b %ERRORLEVEL%
#__VERIDGER_HOOK_PS__
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$ver = $env:VH_VER
$repo = $env:VH_REPO
$jars = $env:VH_JARS
$hostsCsv = $env:VH_HOSTS
$java = $env:VH_JAVA

if ($ver -ne 'latest' -and $ver -notmatch '^v[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid --version tag (expected latest or vX.Y.Z)' }
if (-not $java) {
  $cmd = Get-Command java.exe -ErrorAction SilentlyContinue
  if (-not $cmd) { throw 'Java 25 is required (pass --java=<path>)' }
  $java = $cmd.Source
}
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$tmp = $null

function Invoke-HttpsDownload($url, $dest) {
  $allowed = @('github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com')
  $cur = [Uri]$url
  for ($hop = 0; $hop -le 3; $hop++) {
    if ($cur.Scheme -ne 'https' -or $cur.UserInfo -or ($cur.Port -ne 443 -and -not $cur.IsDefaultPort) -or ($allowed -notcontains $cur.Host.ToLowerInvariant())) {
      throw ('Disallowed download URI: ' + $cur.Host)
    }
    $req = [System.Net.HttpWebRequest]::Create($cur)
    $req.AllowAutoRedirect = $false
    $req.Timeout = 30000
    $req.ReadWriteTimeout = 60000
    $req.UserAgent = 'Veridger-Hook-Installer'
    $resp = $null
    try {
      $resp = [System.Net.HttpWebResponse]$req.GetResponse()
    } catch [System.Net.WebException] {
      if ($_.Exception.Response) { $resp = [System.Net.HttpWebResponse]$_.Exception.Response } else { throw }
    }
    try {
      $code = [int]$resp.StatusCode
      if ($code -ge 300 -and $code -lt 400) {
        $loc = $resp.Headers['Location']
        if (-not $loc) { throw 'Redirect missing Location' }
        $cur = [Uri]::new($cur, $loc)
        continue
      }
      if ($code -lt 200 -or $code -ge 300) { throw ('HTTP ' + $code + ' downloading ' + $cur.Host) }
      $inStream = $resp.GetResponseStream()
      $outStream = [System.IO.File]::Create($dest)
      try { $inStream.CopyTo($outStream) } finally { $outStream.Dispose(); $inStream.Dispose() }
      return
    } finally {
      if ($resp) { $resp.Dispose() }
    }
  }
  throw 'Too many redirects'
}

try {
  if (-not $jars) {
    if ($repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid --repo' }
    $base = if ($ver -eq 'latest') { 'https://github.com/' + $repo + '/releases/latest/download' } else { 'https://github.com/' + $repo + '/releases/download/' + $ver }
    $tmp = Join-Path $env:TEMP ('veridger-hook-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    Write-Host ('Downloading Veridger hook JARs from ' + $base + ' ...')
    foreach ($f in @('SHA256SUMS', 'ledger-client-hook.jar', 'ledger-hook-resident.jar')) {
      Invoke-HttpsDownload ($base + '/' + $f) (Join-Path $tmp $f)
    }
    $sums = @{}
    foreach ($l in Get-Content -LiteralPath (Join-Path $tmp 'SHA256SUMS')) {
      if ($l -match '^([0-9a-fA-F]{64})\s+\*?([A-Za-z0-9._-]+)$') {
        $k = $Matches[2]
        if ($sums.ContainsKey($k)) { throw ('Duplicate SHA256SUMS entry: ' + $k) }
        $sums[$k] = $Matches[1].ToLowerInvariant()
      }
    }
    foreach ($f in @('ledger-client-hook.jar', 'ledger-hook-resident.jar')) {
      if (-not $sums.ContainsKey($f)) { throw ('SHA256SUMS missing entry for ' + $f) }
      $actual = (Get-FileHash -LiteralPath (Join-Path $tmp $f) -Algorithm SHA256).Hash.ToLowerInvariant()
      if ($actual -ne $sums[$f]) { throw ('SHA-256 mismatch for ' + $f) }
    }
    $jars = $tmp
  }
  $tag = if ($ver -eq 'latest') { if ($tmp) { 'latest' } else { 'local' } } else { $ver.TrimStart('v') }
  $dest = Join-Path (Join-Path $env:LOCALAPPDATA 'Programs\veridger-hook') $tag
  New-Item -ItemType Directory -Force -Path $dest | Out-Null
  $jarPath = Join-Path $dest 'ledger-client-hook.jar'
  if (Test-Path -LiteralPath $jarPath) { try { & $java -jar $jarPath stop | Out-Null } catch {} }
  $srcClient = Join-Path $jars 'ledger-client-hook.jar'
  $srcResident = Join-Path $jars 'ledger-hook-resident.jar'
  try { & $java -jar $srcClient stop | Out-Null } catch {}
  Copy-Item -Force -LiteralPath $srcClient -Destination $jarPath
  Copy-Item -Force -LiteralPath $srcResident -Destination (Join-Path $dest 'ledger-hook-resident.jar')

  $jvm = '-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable'
  $flatMatcher = '^mcp__[A-Za-z0-9_-]+__(?:[A-Za-z0-9_-]+_)?(?:ledger_(issue_save|spec_save|task_save|criterion_save|transition|record_deliverable|record_case|link|correct|batch|review|review_correct|whoami)|artifact_(begin|chunk|finish|status|read))$'
  $agyMatcher = '^(call_mcp_tool|mcp:[A-Za-z0-9._-]+[:/_](?:[A-Za-z0-9._-]+[:/_])?(?:ledger_(issue_save|spec_save|task_save|criterion_save|transition|record_deliverable|record_case|link|correct|batch|review|review_correct|whoami)|artifact_(begin|chunk|finish|status|read)))$'
  $hosts = $hostsCsv.Split(',') | ForEach-Object { $_.Trim().ToLowerInvariant() } | Where-Object { $_ }
  $javaFwd = $java.Replace('\', '/')
  $jarFwd = $jarPath.Replace('\', '/')

  function Set-JsonProp($obj, $key, $val) {
    if ($obj.PSObject.Properties[$key]) { $obj.$key = $val } else { $obj | Add-Member -NotePropertyName $key -NotePropertyValue $val }
  }
  function Load-JsonObj($file) {
    if (-not (Test-Path -LiteralPath $file)) { return [pscustomobject]@{} }
    $raw = [System.IO.File]::ReadAllText($file, [System.Text.Encoding]::UTF8)
    if ($raw.Length -gt 0 -and $raw[0] -eq [char]0xFEFF) { $raw = $raw.Substring(1) }
    if ([string]::IsNullOrWhiteSpace($raw)) { return [pscustomobject]@{} }
    $parsed = $raw | ConvertFrom-Json
    if ($parsed -is [pscustomobject]) { return $parsed }
    return [pscustomobject]@{}
  }
  function Update-PreToolUse($file, $client, $cmdText) {
    $dir = Split-Path $file -Parent
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    if (Test-Path -LiteralPath $file) { Copy-Item -Force -LiteralPath $file -Destination ($file + '.bak') }
    $doc = Load-JsonObj $file
    $hooksObj = if ($doc.PSObject.Properties['hooks'] -and ($doc.hooks -is [pscustomobject])) { $doc.hooks } else { [pscustomobject]@{} }
    $existing = if ($hooksObj.PSObject.Properties['PreToolUse'] -and $hooksObj.PreToolUse) { @($hooksObj.PreToolUse) } else { @() }
    $kept = @($existing | Where-Object {
      $keep = $true
      if ($_ -and $_.PSObject.Properties['hooks'] -and $_.hooks) {
        foreach ($h in @($_.hooks)) {
          if ($h -and $h.PSObject.Properties['command'] -and ([string]$h.command) -like '*ledger-client-hook.jar*') { $keep = $false }
        }
      }
      $keep
    })
    $kept += [pscustomobject]@{ matcher = $flatMatcher; hooks = @([pscustomobject]@{ type = 'command'; command = $cmdText; timeout = 10 }) }
    Set-JsonProp $hooksObj 'PreToolUse' $kept
    Set-JsonProp $doc 'hooks' $hooksObj
    [System.IO.File]::WriteAllText($file, (($doc | ConvertTo-Json -Depth 12) + [Environment]::NewLine), $utf8NoBom)
    Write-Host ('Updated ' + $client + ' hook config: ' + $file)
  }

  if ($hosts -contains 'codex') { Update-PreToolUse (Join-Path $env:USERPROFILE '.codex\hooks.json') 'codex' ('"' + $javaFwd + '" ' + $jvm + ' -jar "' + $jarFwd + '" codex') }
  if ($hosts -contains 'claude') { Update-PreToolUse (Join-Path $env:USERPROFILE '.claude\settings.json') 'claude' ('"' + $javaFwd + '" ' + $jvm + ' -jar "' + $jarFwd + '" claude') }
  if (($hosts -contains 'antigravity') -or ($hosts -contains 'gemini')) {
    $agyFile = Join-Path $env:USERPROFILE '.gemini\config\hooks.json'
    $dir = Split-Path $agyFile -Parent
    if (-not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    if (Test-Path -LiteralPath $agyFile) { Copy-Item -Force -LiteralPath $agyFile -Destination ($agyFile + '.bak') }
    $doc = Load-JsonObj $agyFile
    foreach ($old in @('pg-ledger-host-attestation', 'agent-ledger-host-attestation')) {
      if ($doc.PSObject.Properties[$old]) { $doc.PSObject.Properties.Remove($old) }
    }
    Set-JsonProp $doc 'veridger-host-attestation' ([pscustomobject]@{
      enabled = $true
      PreToolUse = @([pscustomobject]@{
        matcher = $agyMatcher
        hooks = @([pscustomobject]@{ type = 'command'; command = ('"' + $javaFwd + '" ' + $jvm + ' -jar "' + $jarFwd + '" antigravity'); timeout = 10 })
      })
    })
    [System.IO.File]::WriteAllText($agyFile, (($doc | ConvertTo-Json -Depth 12) + [Environment]::NewLine), $utf8NoBom)
    Write-Host ('Updated antigravity hook config: ' + $agyFile)
  }

  $smoke = '{"hook_event_name":"PreToolUse","session_id":"install-smoke","tool_name":"mcp__veridger__ledger_ledger_whoami","tool_input":{}}' | & $java -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:-UsePerfData -Xlog:disable -jar $jarPath codex
  if ($smoke -notlike '*"_hostAttestation":"codex:install-smoke"*') { throw ('Smoke test failed: ' + $smoke) }
  Write-Host ('install-hook.cmd: verified (' + $jarPath + ').')
  Write-Host 'install-hook.cmd: if you use Codex, open /hooks in Codex to review and trust the updated hook.'
} finally {
  if ($tmp -and (Test-Path -LiteralPath $tmp)) { Remove-Item -Recurse -Force -LiteralPath $tmp }
}
