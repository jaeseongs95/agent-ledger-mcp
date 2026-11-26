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
rem   deploy\install.cmd --download [latest|v4.2.0]
rem
rem The secret folder (common, admin, mcp, tool) and secrets belong to the installing user with read for LocalService
rem (nothing is handed over). Run it again as the same user. Passwords are never given on a command line.
rem An installation of an earlier major version is moved with the separate tool ledger-migrate-1x (see the README).
setlocal EnableExtensions
set "STDIN="
set "DL_DIR="
set "SCRIPT_DIR=%~dp0"
if /i "%~1"=="--download" goto :download_mode
if "%~1"=="" goto :download_mode
if /i "%~1"=="--stdin" set "STDIN=--stdin" & shift
if "%~7"=="" echo install.cmd: arguments are missing: see the header of this file& exit /b 1
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
set "DL_DIR=%TEMP%\veridger-release-%RANDOM%-%RANDOM%"
set "VI_DIR=%DL_DIR%"
echo install.cmd: downloading Veridger release (%VI_VER%) and verifying SHA256SUMS ...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12;" ^
  "$ver=$env:VI_VER; $dir=$env:VI_DIR; New-Item -ItemType Directory -Force -Path $dir | Out-Null;" ^
  "$base = if ($ver -eq 'latest') { 'https://github.com/jaeseongs95/veridger/releases/latest/download' } elseif ($ver -match '^v[0-9]+\.[0-9]+\.[0-9]+$') { 'https://github.com/jaeseongs95/veridger/releases/download/' + $ver } else { throw 'Invalid version tag' };" ^
  "foreach ($f in @('SHA256SUMS','ledger-cli.jar','ledger-mcp-server.jar','ledger-admin-web.jar')) { Invoke-WebRequest -UseBasicParsing -Uri ($base + '/' + $f) -OutFile (Join-Path $dir $f) };" ^
  "$hasUpd = $false; try { Invoke-WebRequest -UseBasicParsing -Uri ($base + '/veridger-updater.cmd') -OutFile (Join-Path $dir 'veridger-updater.cmd'); $hasUpd = $true } catch { Remove-Item -Force (Join-Path $dir 'veridger-updater.cmd') -ErrorAction SilentlyContinue };" ^
  "$sums = @{}; foreach ($l in Get-Content (Join-Path $dir 'SHA256SUMS')) { if ($l -match '^([0-9a-fA-F]{64})\s+\*?([A-Za-z0-9._-]+)$') { $k = $Matches[2]; if ($sums.ContainsKey($k)) { throw ('Duplicate SHA256SUMS entry: ' + $k) }; $sums[$k] = $Matches[1].ToLowerInvariant() } };" ^
  "$toCheck = @('ledger-cli.jar','ledger-mcp-server.jar','ledger-admin-web.jar'); if ($hasUpd) { $toCheck += 'veridger-updater.cmd' };" ^
  "foreach ($f in $toCheck) { if (-not $sums.ContainsKey($f)) { throw ('SHA256SUMS missing entry for ' + $f) }; $actual = (Get-FileHash (Join-Path $dir $f) -Algorithm SHA256).Hash.ToLowerInvariant(); if ($actual -ne $sums[$f]) { throw ('SHA-256 mismatch for ' + $f) } };" ^
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
if not defined JAVA echo install.cmd: java is not installed& exit /b 1
set "JAVA=%JAVA:\=/%"
set "CLIJAR=%JARS_ARG%\ledger-cli.jar" & set "JVMOPTS=-Duser.timezone=UTC"
if "%NAME%"=="" call :ask instance-name NAME abcdefghijklmnopqrstuvwxyz0123456789- || exit /b 1
call :ask mcp-port MCPPORT 0123456789 || exit /b 1
call :ask admin-port ADMINPORT 0123456789 || exit /b 1
set "SVC=%NAME%" & set "ADMIN=%NAME%-admin"
set "ACCOUNT=NT AUTHORITY\LocalService"
set "WHOME=C:\ProgramData\%NAME%" & set "HOME=C:/ProgramData/%NAME%"
set RENDER=--platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%JDBC_ARG%" "--app-account=%APP_ARG%" %MORE_ARG%
set JARCMD="%JAVA%" %JVMOPTS% -jar "%WHOME%\lib\ledger-cli.jar"
set CLI=%JARCMD% --ledger.secrets.home=%HOME%/etc
for %%P in (%MORE_ARG%) do if defined TAKE (set "MCPPORT=%%~P" & set "TAKE=") else if /i "%%~P"=="--mcp-port" set "TAKE=1"
for %%P in (%MORE_ARG%) do if defined TAKEA (set "ADMINPORT=%%~P" & set "TAKEA=") else if /i "%%~P"=="--admin-port" set "TAKEA=1"
"%JAVA%" %JVMOPTS% -jar "%JARS_ARG%\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent render-deploy --check %RENDER% || exit /b 1

for %%S in (%SVC% %ADMIN%) do for /f "skip=2 tokens=3" %%A in (' sc.exe query %%S 2^>nul') do if "%%A"=="4" set "UP=1" & set "UP_%%S=1"
for %%S in (%SVC% %ADMIN%) do if exist "%WHOME%\lib\%%S.exe" "%WHOME%\lib\%%S.exe" stop /stopwait >nul 2>&1
call :steps || goto :fail
if defined DL_DIR if exist "%DL_DIR%" rmdir /s /q "%DL_DIR%"
echo install.cmd: done. Check the MCP server with: %CLI% mcp-check --url=http://127.0.0.1:%MCPPORT%/mcp
exit /b 0

:steps
"%JAVA%" %JVMOPTS% -jar "%JARS_ARG%\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent prepare-host "--jars=%JARS_ARG%" "--winsw=%WINSW_ARG%" "--driver-jar=%DRV_JAR_ARG%" %RENDER% || exit /b 1
%CLI% secret-set ledger.jdbc.password --if-absent %STDIN% || exit /b 1
%CLI% token-generate ledger.admin.token --print || exit /b 1
%CLI% token-generate ledger.mcp.token --print "--copy-to=ledger.admin.agent-token" || exit /b 1
for %%D in ("%DRV_JAR_ARG%") do set "DRV_NAME=%%~nxD"
%CLI% driver-register "--jar=%HOME%/etc/drivers/%DRV_NAME%" "--class=%DRV_CLS_ARG%" || exit /b 1
%CLI% setup "--owner-account=%OWNER_ARG%" "--app-account=%APP_ARG%" %STDIN% || exit /b 1
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" uninstall >nul 2>&1 & "%WHOME%\lib\%%S.exe" install && sc.exe config %%S obj= "%ACCOUNT%" >nul || exit /b 1
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" start || exit /b 1
set "UPD_SRC="
if exist "%JARS_ARG%\veridger-updater.cmd" set "UPD_SRC=%JARS_ARG%\veridger-updater.cmd"
if not defined UPD_SRC if exist "%SCRIPT_DIR%veridger-updater.cmd" set "UPD_SRC=%SCRIPT_DIR%veridger-updater.cmd"
if defined UPD_SRC (
  if not exist "%WHOME%\etc\update" mkdir "%WHOME%\etc\update"
  copy /y "%UPD_SRC%" "%WHOME%\lib\veridger-updater.cmd" >nul
  schtasks /create /f /tn "VeridgerUpdater-%NAME%" /ru SYSTEM /sc MINUTE /mo 1 /tr "\"%WHOME%\lib\veridger-updater.cmd\" %NAME% %MCPPORT% %ADMINPORT% jaeseongs95/veridger" >nul 2>&1
)
exit /b 0

:fail
(if defined DL_DIR if exist "%DL_DIR%" rmdir /s /q "%DL_DIR%") & for %%S in (%SVC% %ADMIN%) do if defined UP_%%S ("%WHOME%\lib\%%S.exe" install >nul 2>&1 & sc.exe config %%S obj= "%ACCOUNT%" >nul 2>&1 & "%WHOME%\lib\%%S.exe" start)
exit /b 1

:ask
set "%2="
for /f "usebackq delims=" %%V in (`""%JAVA%" %JVMOPTS% -jar "%CLIJAR%" --ledger.secrets.home=C:/nonexistent install-default %1 || echo !"`) do set "%2=%%V"
if not defined %2 echo install.cmd: install-default %1 gave no value& exit /b 1
call set "VALUE=%%%2%%"
for /f "delims=%~3" %%X in ("%VALUE%") do echo install.cmd: install-default %1 is not of the form its value has& exit /b 1
exit /b 0
