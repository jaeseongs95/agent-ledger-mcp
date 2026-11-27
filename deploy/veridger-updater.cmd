@echo off
rem deploy\veridger-updater.cmd: least-privilege Windows host updater triggered by Scheduled Task VeridgerUpdater-<name>
rem when ledger-admin-web stages release JARs and writes C:\ProgramData\<name>\etc\update\request.properties.
rem Security model: never trusts SHA256SUMS from the service-writable directory, checks ReparsePoint on every segment
rem (including dangling symlinks/junctions), enforces a monotonic version floor against SYSTEM-owned .installed-version,
rem copies request.properties and staged JARs into a SYSTEM-private temp directory before fetching authoritative
rem SHA256SUMS over HTTPS-only allowlisted redirects from the pinned repository and verifying every digest, backs up
rem existing JARs, restarts WinSW services, verifies both /healthz endpoints, writes UTF-8 (no BOM) escaped
rem status.properties atomically, and rolls back automatically on failure.
setlocal EnableExtensions DisableDelayedExpansion
set "VU_NAME=%~1"
set "VU_MCP_PORT=%~2"
set "VU_ADMIN_PORT=%~3"
set "VU_REPO=%~4"
if "%VU_NAME%"=="" set "VU_NAME=veridger"
if "%VU_MCP_PORT%"=="" set "VU_MCP_PORT=8765"
if "%VU_ADMIN_PORT%"=="" set "VU_ADMIN_PORT=8865"
if "%VU_REPO%"=="" set "VU_REPO=jaeseongs95/veridger"
set "VU_SELF=%~f0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$s=[System.IO.File]::ReadAllText($env:VU_SELF,[System.Text.Encoding]::UTF8); $m='#'+'__VERIDGER_UPDATER_PS__'; $i=$s.IndexOf($m); & ([scriptblock]::Create($s.Substring($i+$m.Length)))"
exit /b %ERRORLEVEL%
#__VERIDGER_UPDATER_PS__
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

$name = $env:VU_NAME
$mcpPort = [int]$env:VU_MCP_PORT
$adminPort = [int]$env:VU_ADMIN_PORT
$repo = $env:VU_REPO
if ($name -notmatch '^[a-z][a-z0-9-]{0,22}$' -or $name.EndsWith('-')) { throw 'Invalid instance name' }
if ($mcpPort -lt 1 -or $mcpPort -gt 65535 -or $adminPort -lt 1 -or $adminPort -gt 65535) { throw 'Invalid port' }
if ($repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid pinned repository' }

$homeDir = 'C:\ProgramData\' + $name
$etcDir = Join-Path $homeDir 'etc'
$lib = Join-Path $homeDir 'lib'
$upd = Join-Path $etcDir 'update'
$staged = Join-Path $upd 'staged'
$reqFile = Join-Path $upd 'request.properties'
$statusFile = Join-Path $upd 'status.properties'

function Test-IsReparse($p) {
  $it = Get-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue
  return ($null -ne $it) -and (($it.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)
}

foreach ($seg in @($homeDir, $etcDir, $upd, $staged, $reqFile)) {
  if (Test-IsReparse $seg) {
    Write-Error ('Refused reparse point: ' + $seg)
    exit 1
  }
}
if (-not (Test-Path -LiteralPath $reqFile -PathType Leaf)) { exit 0 }

function Invoke-HttpsDownload($url, $dest, $maxBytes) {
  $allowed = @('github.com', 'objects.githubusercontent.com', 'release-assets.githubusercontent.com')
  $cur = [Uri]$url
  for ($hop = 0; $hop -le 3; $hop++) {
    if ($cur.Scheme -ne 'https' -or $cur.UserInfo -or ($cur.Port -ne 443 -and -not $cur.IsDefaultPort) -or ($allowed -notcontains $cur.Host.ToLowerInvariant())) {
      throw ('Disallowed download URI: ' + $cur.Host)
    }
    $req = [System.Net.HttpWebRequest]::Create($cur)
    $req.AllowAutoRedirect = $false
    $req.Timeout = 20000
    $req.ReadWriteTimeout = 20000
    $req.UserAgent = 'Veridger-Host-Updater'
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
      try {
        $buf = New-Object byte[] 16384
        $tot = 0L
        while (($n = $inStream.Read($buf, 0, $buf.Length)) -gt 0) {
          $tot += $n
          if ($tot -gt $maxBytes) { throw 'Download exceeds size limit' }
          $outStream.Write($buf, 0, $n)
        }
      } finally {
        $outStream.Dispose()
        $inStream.Dispose()
      }
      return
    } finally {
      if ($resp) { $resp.Dispose() }
    }
  }
  throw 'Too many redirects'
}

function Get-InstalledVersion($libDir) {
  $vf = Join-Path $libDir '.installed-version'
  if ((Test-Path -LiteralPath $vf -PathType Leaf) -and -not (Test-IsReparse $vf)) {
    $v = (Get-Content -LiteralPath $vf -Raw).Trim()
    if ($v -match '^[0-9]+\.[0-9]+\.[0-9]+$') { return $v }
  }
  foreach ($j in @('ledger-admin-web.jar', 'ledger-cli.jar')) {
    $jp = Join-Path $libDir $j
    if ((Test-Path -LiteralPath $jp -PathType Leaf) -and -not (Test-IsReparse $jp)) {
      try {
        $z = [System.IO.Compression.ZipFile]::OpenRead($jp)
        try {
          $e = $z.GetEntry('META-INF/MANIFEST.MF')
          if ($e) {
            $sr = New-Object System.IO.StreamReader($e.Open())
            try {
              while ($null -ne ($ln = $sr.ReadLine())) {
                if ($ln -match '^Implementation-Version:\s*([0-9]+\.[0-9]+\.[0-9]+)') { return $Matches[1] }
              }
            } finally { $sr.Dispose() }
          }
        } finally { $z.Dispose() }
      } catch {}
    }
  }
  return ''
}

function Write-Status($st, $ver, $detail) {
  foreach ($seg in @($homeDir, $etcDir, $upd, $statusFile)) { if (Test-IsReparse $seg) { return } }
  $clean = ([string]$detail).Replace([string][char]13, ' ').Replace([string][char]10, ' ').Replace('\', '\\')
  $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
  $lines = @('state=' + $st, 'targetVersion=' + $ver, 'updatedAt=' + $now, 'detail=' + $clean)
  $tmpSt = Join-Path $upd ('.status-' + [guid]::NewGuid().ToString('N') + '.tmp')
  if (Test-IsReparse $tmpSt) { return }
  [System.IO.File]::WriteAllLines($tmpSt, $lines, [System.Text.UTF8Encoding]::new($false))
  if (Test-IsReparse $statusFile) { Remove-Item -LiteralPath $tmpSt -Force -ErrorAction SilentlyContinue; return }
  Move-Item -LiteralPath $tmpSt -Destination $statusFile -Force
}

function Remove-SafeFile($p) {
  if (-not (Test-IsReparse $p) -and (Test-Path -LiteralPath $p -PathType Leaf)) {
    Remove-Item -LiteralPath $p -Force -ErrorAction SilentlyContinue
  }
}

$work = Join-Path (Join-Path $env:SystemRoot 'Temp') ('veridger-upd-' + [guid]::NewGuid().ToString('N'))
$targetVer = ''
try {
  New-Item -ItemType Directory -Force -Path $work | Out-Null
  $acl = New-Object System.Security.AccessControl.DirectorySecurity
  $acl.SetAccessRuleProtection($true, $false)
  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')))
  Set-Acl -Path $work -AclObject $acl

  if ((Get-Item -LiteralPath $reqFile -Force).Length -gt 65536) { throw 'request.properties exceeds size cap' }
  $reqCopy = Join-Path $work 'request.properties'
  Copy-Item -LiteralPath $reqFile -Destination $reqCopy -Force
  $props = @{}
  foreach ($l in Get-Content -LiteralPath $reqCopy) {
    if ($l -match '^([^#][^=]*)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
  }
  $rawVer = [string]$props['targetVersion']
  if ($rawVer -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid targetVersion' }
  $targetVer = $rawVer

  $installedVer = Get-InstalledVersion $lib
  if ($installedVer -and ([version]$targetVer -le [version]$installedVer)) {
    throw ('targetVersion ' + $targetVer + ' must be newer than installed version ' + $installedVer)
  }

  $sumsFile = Join-Path $work 'SHA256SUMS'
  Invoke-HttpsDownload ('https://github.com/' + $repo + '/releases/download/v' + $targetVer + '/SHA256SUMS') $sumsFile 1048576
  $sums = @{}
  foreach ($l in Get-Content -LiteralPath $sumsFile) {
    if ($l -match '^([0-9a-fA-F]{64})\s+\*?([A-Za-z0-9._-]+)$') {
      $k = $Matches[2]
      if ($sums.ContainsKey($k)) { throw ('Duplicate SHA256SUMS entry: ' + $k) }
      $sums[$k] = $Matches[1].ToLowerInvariant()
    }
  }

  $jars = @('ledger-cli.jar', 'ledger-mcp-server.jar', 'ledger-admin-web.jar')
  foreach ($j in $jars) {
    foreach ($seg in @($homeDir, $etcDir, $upd, $staged)) {
      if (Test-IsReparse $seg) { throw ('Refused reparse point: ' + $seg) }
    }
    $sf = Join-Path $staged $j
    if ((Test-IsReparse $sf) -or -not (Test-Path -LiteralPath $sf -PathType Leaf)) { throw ('Missing or symlinked staged jar: ' + $j) }
    if ((Get-Item -LiteralPath $sf -Force).Length -gt 268435456) { throw ('Oversized staged jar: ' + $j) }
    $wf = Join-Path $work $j
    Copy-Item -LiteralPath $sf -Destination $wf -Force
    if (-not $sums.ContainsKey($j)) { throw ('SHA256SUMS missing entry for ' + $j) }
    $actual = (Get-FileHash -LiteralPath $wf -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne ([string]$props['sha256.' + $j]).ToLowerInvariant() -or $actual -ne $sums[$j]) { throw ('SHA-256 mismatch for ' + $j) }
  }

  $backup = Join-Path $lib ('backup-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'))
  New-Item -ItemType Directory -Force -Path $backup | Out-Null
  $svcExe = Join-Path $lib ($name + '.exe')
  $adminExe = Join-Path $lib ($name + '-admin.exe')
  try { & $svcExe stop /stopwait | Out-Null } catch {}
  try { & $adminExe stop /stopwait | Out-Null } catch {}
  foreach ($j in $jars) {
    $cur = Join-Path $lib $j
    if (Test-Path -LiteralPath $cur) { Copy-Item -LiteralPath $cur -Destination (Join-Path $backup $j) -Force }
  }
  try {
    foreach ($j in $jars) { Copy-Item -LiteralPath (Join-Path $work $j) -Destination (Join-Path $lib $j) -Force }
    & $svcExe start | Out-Null
    & $adminExe start | Out-Null
    foreach ($port in @($mcpPort, $adminPort)) {
      $ok = $false
      for ($i = 0; $i -lt 30; $i++) {
        try {
          $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri ('http://127.0.0.1:' + $port + '/healthz')
          if ($r.Content -like '*"status":"UP"*') { $ok = $true; break }
        } catch {}
        Start-Sleep -Seconds 1
      }
      if (-not $ok) { throw ('/healthz on port ' + $port + ' did not return UP within 30s') }
    }
    [System.IO.File]::WriteAllText((Join-Path $lib '.installed-version'), ($targetVer + [Environment]::NewLine), [System.Text.UTF8Encoding]::new($false))
    Write-Status 'SUCCEEDED' $targetVer 'Updated and verified both /healthz endpoints'
    if (-not (Test-IsReparse $upd) -and -not (Test-IsReparse $staged)) {
      foreach ($f in ($jars + @('SHA256SUMS'))) { Remove-SafeFile (Join-Path $staged $f) }
    }
    Remove-SafeFile $reqFile
  } catch {
    $err = $_.Exception.Message
    try { & $svcExe stop /stopwait | Out-Null } catch {}
    try { & $adminExe stop /stopwait | Out-Null } catch {}
    foreach ($j in $jars) {
      $bk = Join-Path $backup $j
      if (Test-Path -LiteralPath $bk) { Copy-Item -LiteralPath $bk -Destination (Join-Path $lib $j) -Force }
    }
    try { & $svcExe start | Out-Null } catch {}
    try { & $adminExe start | Out-Null } catch {}
    Write-Status 'ROLLED_BACK' $targetVer $err
    Remove-SafeFile $reqFile
    exit 1
  }
} catch {
  Write-Status 'FAILED' $targetVer $_.Exception.Message
  Remove-SafeFile $reqFile
  exit 1
} finally {
  if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
