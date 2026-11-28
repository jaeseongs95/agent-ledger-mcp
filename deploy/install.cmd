@echo off
rem deploy\install.cmd: the installer of veridger for Windows (a personal PC or a Windows server). Thin on purpose,
rem like deploy/install.sh: it starts only what Java cannot do (optional verified release download, WinSW/sc.exe service
rem management, and registering the least-privilege host updater task) and delegates validation, ACLs, jars, settings,
rem secrets, tokens, driver registration, and schema setup to `java -jar ledger-cli.jar`.
rem Documentation it follows: Spring Boot, "Installing Spring Boot Applications", section "Microsoft Windows Services"
rem (WinSW): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; the LocalService account:
rem https://learn.microsoft.com/en-us/windows/win32/services/localservice-account ; sc config (obj=):
rem https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/sc-config
rem
rem Non-interactive usage (fully backward-compatible):
rem   deploy\install.cmd [--stdin] <jars-dir> <winsw.exe> <driver-jar> <driver-class> <owner-account> <app-account> "<jdbc-url>" ["<name>" ["<more render-deploy options>"]]
rem
rem One-stop interactive/release usage (downloads & SHA-256 verifies release JARs from GitHub Releases):
rem   deploy\install.cmd --download [latest|v7.0.0]
rem
rem The secret folder (common, admin, mcp, tool) and secrets belong to the installing user with read for LocalService
rem (nothing is handed over). Run it again as the same user. Passwords are never given on a command line.
rem An installation of an earlier major version is moved with the separate tool ledger-migrate-1x (see the README).
rem Set VERIDGER_PG_DUMP to the full path of pg_dump.exe to enable automatic PostgreSQL schema updates.
setlocal EnableExtensions DisableDelayedExpansion
set "STDIN="
set "DL_DIR="
set "SCRIPT_DIR=%~dp0"
if /i "%~1"=="--download" goto :download_mode
if "%~1"=="" goto :download_mode
if /i "%~1"=="--stdin" (
  set "STDIN=--stdin"
  shift
)
if "%~7"=="" (
  echo install.cmd: arguments are missing: see the header of this file
  exit /b 1
)
set "JARS_ARG=%~1"
set "WINSW_ARG=%~2"
set "DRV_JAR_ARG=%~3"
set "DRV_CLS_ARG=%~4"
set "OWNER_ARG=%~5"
set "APP_ARG=%~6"
set "JDBC_ARG=%~7"
set "NAME=%~8"
set "MORE_ARG=%~9"
goto :run_install

:download_mode
set "VI_VER=%~2"
if "%VI_VER%"=="" set "VI_VER=latest"
set "DL_GUID="
for /f "usebackq delims=" %%G in (`powershell -NoProfile -Command "[guid]::NewGuid().ToString('N')"`) do set "DL_GUID=%%G"
if not defined DL_GUID (
  echo install.cmd: failed to generate temporary directory id
  exit /b 1
)
set "DL_DIR=%TEMP%\veridger-release-%DL_GUID%"
set "VI_DIR=%DL_DIR%"
echo install.cmd: downloading Veridger release (%VI_VER%) and verifying SHA256SUMS ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ver=$env:VI_VER; $dir=$env:VI_DIR; New-Item -ItemType Directory -Force -Path $dir | Out-Null;" ^
  "$base = if ($ver -eq 'latest') { 'https://github.com/jaeseongs95/veridger/releases/latest/download' } elseif ($ver -match '^v[0-9]+\.[0-9]+\.[0-9]+$') { 'https://github.com/jaeseongs95/veridger/releases/download/' + $ver } else { throw 'Invalid version tag' };" ^
  "function Invoke-HttpsDownload($url, $dest) {" ^
  "  $allowed = @('github.com','objects.githubusercontent.com','release-assets.githubusercontent.com');" ^
  "  $cur = [Uri]$url;" ^
  "  for ($hop = 0; $hop -le 3; $hop++) {" ^
  "    if ($cur.Scheme -ne 'https' -or $cur.UserInfo -or ($cur.Port -ne 443 -and -not $cur.IsDefaultPort) -or ($allowed -notcontains $cur.Host.ToLowerInvariant())) { throw ('Disallowed download URI: ' + $cur.Host) };" ^
  "    $req = [System.Net.HttpWebRequest]::Create($cur); $req.AllowAutoRedirect = $false; $req.Timeout = 30000; $req.ReadWriteTimeout = 60000; $req.UserAgent = 'Veridger-Installer';" ^
  "    $resp = $null;" ^
  "    try { $resp = [System.Net.HttpWebResponse]$req.GetResponse() } catch [System.Net.WebException] { if ($_.Exception.Response) { $resp = [System.Net.HttpWebResponse]$_.Exception.Response } else { throw } };" ^
  "    try {" ^
  "      $code = [int]$resp.StatusCode;" ^
  "      if ($code -ge 300 -and $code -lt 400) { $loc = $resp.Headers['Location']; if (-not $loc) { throw 'Redirect missing Location' }; $cur = [Uri]::new($cur, $loc); continue };" ^
  "      if ($code -lt 200 -or $code -ge 300) { throw ('HTTP ' + $code + ' downloading ' + $cur.Host) };" ^
  "      $inStream = $resp.GetResponseStream(); $outStream = [System.IO.File]::Create($dest);" ^
  "      try { $inStream.CopyTo($outStream) } finally { $outStream.Dispose(); $inStream.Dispose() };" ^
  "      return;" ^
  "    } finally { if ($resp) { $resp.Dispose() } };" ^
  "  }; throw 'Too many redirects';" ^
  "};" ^
  "foreach ($f in @('SHA256SUMS','ledger-cli.jar','ledger-mcp-server.jar','ledger-admin-web.jar')) { Invoke-HttpsDownload ($base + '/' + $f) (Join-Path $dir $f) };" ^
  "$hasUpd = $false; try { Invoke-HttpsDownload ($base + '/veridger-updater.cmd') (Join-Path $dir 'veridger-updater.cmd'); $hasUpd = $true } catch { Remove-Item -LiteralPath (Join-Path $dir 'veridger-updater.cmd') -Force -ErrorAction SilentlyContinue };" ^
  "$sums = @{}; foreach ($l in Get-Content -LiteralPath (Join-Path $dir 'SHA256SUMS')) { if ($l -match '^([0-9a-fA-F]{64})\s+\*?([A-Za-z0-9._-]+)$') { $k = $Matches[2]; if ($sums.ContainsKey($k)) { throw ('Duplicate SHA256SUMS entry: ' + $k) }; $sums[$k] = $Matches[1].ToLowerInvariant() } };" ^
  "$toCheck = @('ledger-cli.jar','ledger-mcp-server.jar','ledger-admin-web.jar'); if ($hasUpd) { $toCheck += 'veridger-updater.cmd' };" ^
  "foreach ($f in $toCheck) { if (-not $sums.ContainsKey($f)) { throw ('SHA256SUMS missing entry for ' + $f) }; $actual = (Get-FileHash -LiteralPath (Join-Path $dir $f) -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $sums[$f]) { throw ('SHA-256 mismatch for ' + $f) } };" ^
  "Write-Host 'SHA-256 verification passed.'" || (if exist "%DL_DIR%" rmdir /s /q "%DL_DIR%" & exit /b 1)
set "JARS_ARG=%DL_DIR%"
set /p "WINSW_ARG=Path to winsw.exe: "
set /p "DRV_JAR_ARG=Path to JDBC driver JAR: "
set /p "DRV_CLS_ARG=JDBC driver class [org.postgresql.Driver]: "
if "%DRV_CLS_ARG%"=="" set "DRV_CLS_ARG=org.postgresql.Driver"
set /p "OWNER_ARG=Database DDL owner account [ledger_owner]: "
if "%OWNER_ARG%"=="" set "OWNER_ARG=ledger_owner"
set /p "APP_ARG=Database application account [ledger_app]: "
if "%APP_ARG%"=="" set "APP_ARG=ledger_app"
set /p "JDBC_ARG=JDBC URL (e.g. jdbc:postgresql://127.0.0.1:5432/veridger): "
set /p "NAME=Instance name (empty for default): "
set "MORE_ARG="

:run_install
for %%I in (java.exe) do set "JAVA=%%~$PATH:I"
if not defined JAVA (
  echo install.cmd: java is not installed
  exit /b 1
)
set "JAVA=%JAVA:\=/%"
set "CLIJAR=%JARS_ARG%\ledger-cli.jar" & set "JVMOPTS=-Duser.timezone=UTC"
if "%NAME%"=="" call :ask instance-name NAME abcdefghijklmnopqrstuvwxyz0123456789- || exit /b 1
call :ask mcp-port MCPPORT 0123456789 || exit /b 1
call :ask admin-port ADMINPORT 0123456789 || exit /b 1
set "VI_NAME=%NAME%"
set "VI_MORE=%MORE_ARG%"
set "VI_MCP=%MCPPORT%"
set "VI_ADMIN=%ADMINPORT%"
powershell -NoProfile -Command ^
  "$n = $env:VI_NAME; if ($n -notmatch '^[a-z][a-z0-9-]{0,22}$' -or $n.EndsWith('-')) { exit 1 }" || (echo install.cmd: invalid instance name& exit /b 1)
for /f "usebackq tokens=1,2 delims=:" %%A in (`powershell -NoProfile -Command "$m=$env:VI_MCP; $a=$env:VI_ADMIN; $raw=$env:VI_MORE; if ($raw -match '--mcp-port(?:=|\s+)([0-9]+)') { $m=$Matches[1] }; if ($raw -match '--admin-port(?:=|\s+)([0-9]+)') { $a=$Matches[1] }; if ($m -notmatch '^[0-9]+$' -or [int]$m -lt 1 -or [int]$m -gt 65535) { exit 1 }; if ($a -notmatch '^[0-9]+$' -or [int]$a -lt 1 -or [int]$a -gt 65535) { exit 1 }; Write-Output ($m + ':' + $a)"`) do (set "MCPPORT=%%A" & set "ADMINPORT=%%B")
if errorlevel 1 (
  echo install.cmd: invalid port value
  exit /b 1
)
set "SVC=%NAME%" & set "ADMIN=%NAME%-admin"
set "ACCOUNT=NT AUTHORITY\LocalService"
set "WHOME=C:\ProgramData\%NAME%" & set "HOME=C:/ProgramData/%NAME%"
set RENDER=--platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%JDBC_ARG%" "--app-account=%APP_ARG%" %MORE_ARG%
set JARCMD="%JAVA%" %JVMOPTS% -jar "%WHOME%\lib\ledger-cli.jar"
set CLI=%JARCMD% --ledger.secrets.home=%HOME%/etc
"%JAVA%" %JVMOPTS% -jar "%JARS_ARG%\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent render-deploy --check %RENDER% || exit /b 1

for %%S in (%SVC% %ADMIN%) do for /f "skip=2 tokens=3" %%A in (' sc.exe query %%S 2^>nul') do if "%%A"=="4" set "UP=1" & set "UP_%%S=1"
for %%S in (%SVC% %ADMIN%) do if exist "%WHOME%\lib\%%S.exe" "%WHOME%\lib\%%S.exe" stop /stopwait >nul 2>&1
call :steps || goto :fail
if defined DL_DIR if exist "%DL_DIR%" rmdir /s /q "%DL_DIR%"
echo install.cmd: done. Check the MCP server with: %CLI% mcp-check --url=http://127.0.0.1:%MCPPORT%/mcp
exit /b 0

:steps
"%JAVA%" %JVMOPTS% -jar "%JARS_ARG%\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent prepare-host "--jars=%JARS_ARG%" "--winsw=%WINSW_ARG%" "--driver-jar=%DRV_JAR_ARG%" %RENDER% || exit /b 1
set "VI_LIB=%WHOME%\lib"
set "VI_JAVA=%JAVA%"
powershell -NoProfile -Command ^
  "Add-Type -AssemblyName System.IO.Compression.FileSystem;" ^
  "[System.IO.File]::WriteAllText((Join-Path $env:VI_LIB '.updater-java'), $env:VI_JAVA);" ^
  "$jp = Join-Path $env:VI_LIB 'ledger-cli.jar';" ^
  "if (Test-Path -LiteralPath $jp -PathType Leaf) { $z = [System.IO.Compression.ZipFile]::OpenRead($jp); try { $e = $z.GetEntry('META-INF/MANIFEST.MF'); if ($e) { $sr = New-Object System.IO.StreamReader($e.Open()); try { while ($null -ne ($ln = $sr.ReadLine())) { if ($ln -match '^Implementation-Version:\s*([0-9]+\.[0-9]+\.[0-9]+)') { [System.IO.File]::WriteAllText((Join-Path $env:VI_LIB '.installed-version'), ($Matches[1] + [Environment]::NewLine), [System.Text.UTF8Encoding]::new($false)); break } } } finally { $sr.Dispose() } } } finally { $z.Dispose() } }" >nul 2>&1
%CLI% secret-set ledger.jdbc.password --if-absent %STDIN% || exit /b 1
%CLI% token-generate ledger.admin.token --print || exit /b 1
%CLI% token-generate ledger.mcp.token --print "--copy-to=ledger.admin.agent-token" || exit /b 1
for %%D in ("%DRV_JAR_ARG%") do set "DRV_NAME=%%~nxD"
%CLI% driver-register "--jar=%HOME%/etc/drivers/%DRV_NAME%" "--class=%DRV_CLS_ARG%" || exit /b 1
if defined VERIDGER_PG_DUMP (
  if not "%DRV_CLS_ARG%"=="org.postgresql.Driver" (
    echo install.cmd: automatic schema updates require org.postgresql.Driver
    exit /b 1
  )
  %JARCMD% host-update provision "%WHOME%\lib" "%WHOME%\etc" "%DRV_JAR_ARG%" "%VERIDGER_PG_DUMP%" "%OWNER_ARG%" "%JDBC_ARG%" "%APP_ARG%" %STDIN% || exit /b 1
  %CLI% setup "--owner-account=%OWNER_ARG%" "--app-account=%APP_ARG%" --stdin < "%WHOME%\lib\schema-update\owner-password" || exit /b 1
) else (
  %CLI% setup "--owner-account=%OWNER_ARG%" "--app-account=%APP_ARG%" %STDIN% || exit /b 1
)
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" uninstall >nul 2>&1 & "%WHOME%\lib\%%S.exe" install && sc.exe config %%S obj= "%ACCOUNT%" >nul || exit /b 1
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" start || exit /b 1
set "UPD_SRC="
if exist "%JARS_ARG%\veridger-updater.cmd" set "UPD_SRC=%JARS_ARG%\veridger-updater.cmd"
if not defined UPD_SRC if exist "%SCRIPT_DIR%veridger-updater.cmd" set "UPD_SRC=%SCRIPT_DIR%veridger-updater.cmd"
if defined UPD_SRC (
  copy /y "%UPD_SRC%" "%WHOME%\lib\veridger-updater.cmd" >nul || exit /b 1
  schtasks /create /f /tn "VeridgerUpdater-%NAME%" /ru SYSTEM /sc MINUTE /mo 1 /tr "\"%WHOME%\lib\veridger-updater.cmd\" %NAME% %MCPPORT% %ADMINPORT% jaeseongs95/veridger" >nul 2>&1 || exit /b 1
  if defined VERIDGER_PG_DUMP type nul > "%WHOME%\lib\.schema-updates"
) else if defined VERIDGER_PG_DUMP (
  echo install.cmd: host updater script is required for automatic schema updates
  exit /b 1
)
exit /b 0

:fail
(if defined DL_DIR if exist "%DL_DIR%" rmdir /s /q "%DL_DIR%") & for %%S in (%SVC% %ADMIN%) do if defined UP_%%S ("%WHOME%\lib\%%S.exe" install >nul 2>&1 & sc.exe config %%S obj= "%ACCOUNT%" >nul 2>&1 & "%WHOME%\lib\%%S.exe" start)
exit /b 1

:ask
set "%2="
for /f "usebackq delims=" %%V in (`""%JAVA%" %JVMOPTS% -jar "%CLIJAR%" --ledger.secrets.home=C:/nonexistent install-default %1 || echo !"`) do set "%2=%%V"
if not defined %2 (
  echo install.cmd: install-default %1 gave no value
  exit /b 1
)
call set "VALUE=%%%2%%"
for /f "delims=%~3" %%X in ("%VALUE%") do echo install.cmd: install-default %1 is not of the form its value has& exit /b 1
exit /b 0
