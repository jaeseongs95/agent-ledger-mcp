@echo off
rem deploy\veridger-updater.cmd: least-privilege Windows host updater triggered by Scheduled Task VeridgerUpdater-<name>
rem when ledger-admin-web stages release JARs and writes C:\ProgramData\<name>\etc\update\request.properties.
rem Security model: never trusts SHA256SUMS from the service-writable directory, checks ReparsePoint on every segment,
rem copies staged JARs into a SYSTEM-private temp directory before fetching authoritative SHA256SUMS over HTTPS from
rem the pinned repository and verifying every digest, backs up existing JARs, restarts WinSW services, verifies both
rem /healthz endpoints, writes UTF-8 (no BOM) escaped status.properties, and rolls back automatically on failure.
setlocal EnableExtensions
set "VU_NAME=%~1"
set "VU_MCP_PORT=%~2"
set "VU_ADMIN_PORT=%~3"
set "VU_REPO=%~4"
if "%VU_NAME%"=="" set "VU_NAME=veridger"
if "%VU_MCP_PORT%"=="" set "VU_MCP_PORT=8765"
if "%VU_ADMIN_PORT%"=="" set "VU_ADMIN_PORT=8865"
if "%VU_REPO%"=="" set "VU_REPO=jaeseongs95/veridger"

set "WHOME=C:\ProgramData\%VU_NAME%"
set "REQ=%WHOME%\etc\update\request.properties"
if not exist "%REQ%" exit /b 0

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$name=$env:VU_NAME; $mcpPort=[int]$env:VU_MCP_PORT; $adminPort=[int]$env:VU_ADMIN_PORT; $repo=$env:VU_REPO;" ^
  "if ($name -notmatch '^[a-z][a-z0-9-]{0,22}$') { throw 'Invalid instance name' };" ^
  "if ($repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid pinned repository' };" ^
  "$homeDir = 'C:\ProgramData\' + $name; $etcDir = Join-Path $homeDir 'etc'; $lib = Join-Path $homeDir 'lib';" ^
  "$upd = Join-Path $etcDir 'update'; $staged = Join-Path $upd 'staged'; $reqFile = Join-Path $upd 'request.properties'; $statusFile = Join-Path $upd 'status.properties';" ^
  "if (-not (Test-Path $reqFile)) { exit 0 };" ^
  "function Test-IsReparse($p) { return (Test-Path $p) -and (((Get-Item $p -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) };" ^
  "foreach ($seg in @($homeDir, $etcDir, $upd, $staged, $reqFile)) { if (Test-IsReparse $seg) { Write-Error ('Refused reparse point: ' + $seg); exit 1 } };" ^
  "function Write-Status($st, $ver, $detail) {" ^
  "  foreach ($seg in @($homeDir, $etcDir, $upd, $statusFile)) { if (Test-IsReparse $seg) { return } };" ^
  "  $clean = ([string]$detail).Replace([string][char]13, ' ').Replace([string][char]10, ' ').Replace('\', '\\');" ^
  "  $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ');" ^
  "  $lines = @('state=' + $st, 'targetVersion=' + $ver, 'updatedAt=' + $now, 'detail=' + $clean);" ^
  "  [System.IO.File]::WriteAllLines($statusFile, $lines, [System.Text.UTF8Encoding]::new($false));" ^
  "};" ^
  "$work = Join-Path (Join-Path $env:SystemRoot 'Temp') ('veridger-upd-' + [guid]::NewGuid().ToString('N'));" ^
  "$targetVer = '';" ^
  "try {" ^
  "  New-Item -ItemType Directory -Force -Path $work | Out-Null;" ^
  "  $acl = New-Object System.Security.AccessControl.DirectorySecurity;" ^
  "  $acl.SetAccessRuleProtection($true, $false);" ^
  "  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-18'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')));" ^
  "  $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule((New-Object System.Security.Principal.SecurityIdentifier 'S-1-5-32-544'), 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')));" ^
  "  Set-Acl -Path $work -AclObject $acl;" ^
  "  $props = @{}; foreach ($l in Get-Content $reqFile) { if ($l -match '^([^#][^=]*)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() } };" ^
  "  $rawVer = [string]$props['targetVersion'];" ^
  "  if ($rawVer -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') { throw 'Invalid targetVersion' };" ^
  "  $targetVer = $rawVer;" ^
  "  $sumsFile = Join-Path $work 'SHA256SUMS';" ^
  "  Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 -Uri ('https://github.com/' + $repo + '/releases/download/v' + $targetVer + '/SHA256SUMS') -OutFile $sumsFile;" ^
  "  $sums = @{}; foreach ($l in Get-Content $sumsFile) { if ($l -match '^([0-9a-fA-F]{64})\s+\*?([A-Za-z0-9._-]+)$') { $k = $Matches[2]; if ($sums.ContainsKey($k)) { throw ('Duplicate SHA256SUMS entry: ' + $k) }; $sums[$k] = $Matches[1].ToLowerInvariant() } };" ^
  "  $jars = @('ledger-cli.jar','ledger-mcp-server.jar','ledger-admin-web.jar');" ^
  "  foreach ($j in $jars) {" ^
  "    $sf = Join-Path $staged $j; if ((Test-IsReparse $sf) -or -not (Test-Path $sf -PathType Leaf)) { throw ('Missing or symlinked staged jar: ' + $j) };" ^
  "    $wf = Join-Path $work $j; Copy-Item -Force $sf $wf;" ^
  "    if (-not $sums.ContainsKey($j)) { throw ('SHA256SUMS missing entry for ' + $j) };" ^
  "    $actual = (Get-FileHash $wf -Algorithm SHA256).Hash.ToLowerInvariant();" ^
  "    if ($actual -ne ([string]$props['sha256.' + $j]).ToLowerInvariant() -or $actual -ne $sums[$j]) { throw ('SHA-256 mismatch for ' + $j) }" ^
  "  };" ^
  "  $backup = Join-Path $lib ('backup-' + (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ'));" ^
  "  New-Item -ItemType Directory -Force -Path $backup | Out-Null;" ^
  "  $svcExe = Join-Path $lib ($name + '.exe'); $adminExe = Join-Path $lib ($name + '-admin.exe');" ^
  "  try { & $svcExe stop /stopwait | Out-Null } catch {}; try { & $adminExe stop /stopwait | Out-Null } catch {};" ^
  "  foreach ($j in $jars) { $cur = Join-Path $lib $j; if (Test-Path $cur) { Copy-Item -Force $cur (Join-Path $backup $j) } };" ^
  "  try {" ^
  "    foreach ($j in $jars) { Copy-Item -Force (Join-Path $work $j) (Join-Path $lib $j) };" ^
  "    & $svcExe start | Out-Null; & $adminExe start | Out-Null;" ^
  "    foreach ($port in @($mcpPort, $adminPort)) {" ^
  "      $ok = $false; for ($i = 0; $i -lt 30; $i++) { try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 -Uri ('http://127.0.0.1:' + $port + '/healthz'); if ($r.Content -like '*\"status\":\"UP\"*') { $ok = $true; break } } catch {}; Start-Sleep -Seconds 1 };" ^
  "      if (-not $ok) { throw ('/healthz on port ' + $port + ' did not return UP within 30s') }" ^
  "    };" ^
  "    Write-Status 'SUCCEEDED' $targetVer 'Updated and verified both /healthz endpoints';" ^
  "    if (-not (Test-IsReparse $upd) -and -not (Test-IsReparse $staged)) { foreach ($f in ($jars + @('SHA256SUMS'))) { Remove-Item -Force (Join-Path $staged $f) -ErrorAction SilentlyContinue } };" ^
  "    if (-not (Test-IsReparse $reqFile)) { Remove-Item -Force $reqFile -ErrorAction SilentlyContinue };" ^
  "  } catch {" ^
  "    $err = $_.Exception.Message;" ^
  "    try { & $svcExe stop /stopwait | Out-Null } catch {}; try { & $adminExe stop /stopwait | Out-Null } catch {};" ^
  "    foreach ($j in $jars) { $bk = Join-Path $backup $j; if (Test-Path $bk) { Copy-Item -Force $bk (Join-Path $lib $j) } };" ^
  "    try { & $svcExe start | Out-Null } catch {}; try { & $adminExe start | Out-Null } catch {};" ^
  "    Write-Status 'ROLLED_BACK' $targetVer $err;" ^
  "    if (-not (Test-IsReparse $reqFile)) { Remove-Item -Force $reqFile -ErrorAction SilentlyContinue };" ^
  "    exit 1;" ^
  "  }" ^
  "} catch {" ^
  "  Write-Status 'FAILED' $targetVer $_.Exception.Message;" ^
  "  if (-not (Test-IsReparse $reqFile)) { Remove-Item -Force $reqFile -ErrorAction SilentlyContinue };" ^
  "  exit 1;" ^
  "} finally { if (Test-Path $work) { Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue } }"
exit /b %ERRORLEVEL%
