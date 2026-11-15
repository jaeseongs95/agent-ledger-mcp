@echo off
rem deploy\install.cmd: the installer of pg-ledger-mcp 2.x for Windows (a personal PC or a Windows server). Thin on purpose,
rem like deploy/install.sh: it starts only what Java cannot do (the service manager: the WinSW executables and sc.exe) and
rem everything else is `java -jar ledger-cli.jar <command>`: render-deploy --check (every option, validated first),
rem prepare-host (folders, ACLs, the jars, WinSW and the driver copied, the WinSW configurations and the settings), secret-set,
rem token-generate, driver-register and setup (schema, ANSI GRANTs and the seed codes). The access of the service account is set by
rem prepare-host when it makes the folders. No PowerShell, no download, no package, no icacls, mkdir or copy.
rem Documentation it follows: Spring Boot, "Installing Spring Boot Applications", section "Microsoft Windows Services"
rem (WinSW): https://docs.spring.io/spring-boot/how-to/deployment/installing.html ; the LocalService account:
rem https://learn.microsoft.com/en-us/windows/win32/services/localservice-account ; sc config (obj=):
rem https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/sc-config
rem One service account runs both services: the predefined account LocalService (no person's account, no password). The
rem secret folder and the secrets are the installing user's (everything) and are readable by LocalService (read only), because the
rem services read them and nothing is handed over (the installing user stays the owner, no privilege is used); no other account has access, so the accounts the agents run as cannot read the ledger's secrets. Run it again as the same user. Two database
rem accounts: the application account (both servers and the owner commands) and the DDL account (setup and the grants; its
rem password is typed and not stored). WinSW.exe (2.x) is obtained by the operator and passed here, like the JDBC driver.
rem Run in an elevated prompt (Java refuses to change the folders of the installation otherwise; there is no probe here):
rem   deploy\install.cmd [--stdin] <jars-dir> <winsw.exe> <driver-jar> <driver-class> <owner-account> <app-account> "<jdbc-url>" ["<name>" ["<more render-deploy options>"]]
rem --stdin (first argument, as the --stdin of install.sh) gives the passwords on standard input and not at the terminal: the application
rem account's (read only while it is not stored), then the DDL account's. It is marked and shifted off, so the other positions stay.
rem The name of the instance (default pg-ledger-mcp) gives the services <name> and <name>-admin and the home C:\ProgramData\<name>,
rem so two instances can live on one host with their own ports; the Java validation of the name comes before any change.
rem A re-run is an upgrade: jars, driver jar and files are replaced and the services are recreated; secrets, tokens and
rem data are never replaced. A step that fails after the services were stopped starts again the ones that ran (:fail). The Java 25 check is the JVM's: the jars are compiled for it and no other runtime loads them.
rem Both services listen on 127.0.0.1 only. Reaching them from outside is the operator's (a reverse proxy and a certificate, with a tool the
rem operator chooses): --mcp-host=<name> and --admin-host=<name> in the render-deploy options name the one host name each service accepts besides 127.0.0.1 and localhost.
rem An installation of an earlier major version is moved with the separate tool ledger-migrate-1x (see the README, Upgrading); this script does not know it.
setlocal EnableExtensions
set "STDIN="
if /i "%~1"=="--stdin" set "STDIN=--stdin" & shift
if "%~7"=="" echo install.cmd: arguments are missing: see the header of this file& exit /b 1
for %%I in (java.exe) do set "JAVA=%%~$PATH:I"
if not defined JAVA echo install.cmd: java is not installed& exit /b 1
set "JAVA=%JAVA:\=/%"
rem The default name and the default MCP port are install defaults of the seed bundle, the one place the defaults are written: Java tells them.
set "NAME=%~8"
rem The options of the JVM, written once (the preset of ledger-cli, JvmPreset, writes the same ones into the WinSW configurations; a test compares them).
set "CLIJAR=%~1\ledger-cli.jar" & set "JVMOPTS=-Duser.timezone=UTC"
if "%NAME%"=="" call :ask instance-name NAME abcdefghijklmnopqrstuvwxyz0123456789- || exit /b 1
call :ask mcp-port MCPPORT 0123456789 || exit /b 1
set "SVC=%NAME%" & set "ADMIN=%NAME%-admin"
set "ACCOUNT=NT AUTHORITY\LocalService"
set "WHOME=C:\ProgramData\%NAME%" & set "HOME=C:/ProgramData/%NAME%"
set RENDER=--platform=windows "--home=%HOME%" "--name=%NAME%" "--java=%JAVA%" "--jdbc-url=%~7" "--app-account=%~6" %~9
set JARCMD="%JAVA%" %JVMOPTS% -jar "%WHOME%\lib\ledger-cli.jar"
set CLI=%JARCMD% --ledger.secrets.home=%HOME%/etc
rem The hint at the end names the port of the MCP server: the value after --mcp-port in the render options (cmd splits it at the
rem equals sign), the install default (above) when there is none. Java validates the option itself before anything is changed.
for %%P in (%~9) do if defined TAKE (set "MCPPORT=%%~P" & set "TAKE=") else if /i "%%~P"=="--mcp-port" set "TAKE=1"
rem Every option, the name included, is validated by Java before anything is created (the name is only ever used in quotes until then).
"%JAVA%" %JVMOPTS% -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent render-deploy --check %RENDER% || exit /b 1

rem WinSW: note which services run (the STATE of sc.exe is 4 when one does, in any language) and stop them (the service manager is not
rem Java's to talk to; the jars of a running service are locked). The steps after this point run in :steps (given the first six arguments, which a routine reads as its own); a failure goes to :fail.
for %%S in (%SVC% %ADMIN%) do for /f "tokens=1,3" %%A in (' sc.exe query %%S 2^>nul') do if "%%A"=="STATE" if "%%B"=="4" set "UP=1" & set "UP_%%S=1"
for %%S in (%SVC% %ADMIN%) do if exist "%WHOME%\lib\%%S.exe" "%WHOME%\lib\%%S.exe" stop /stopwait >nul 2>&1
call :steps %1 %2 %3 %4 %5 %6 || goto :fail
echo install.cmd: done. Check the MCP server with: %CLI% mcp-check --url=http://127.0.0.1:%MCPPORT%/mcp
exit /b 0

:steps
rem Folders and their ACLs (the service account LocalService gets its access here; the secret folders are the installing user's with read for
rem LocalService), the jars, WinSW and the driver copied, the WinSW configurations and the settings rendered: Java (no secret is read: the home of this call has none).
"%JAVA%" %JVMOPTS% -jar "%~1\ledger-cli.jar" --ledger.secrets.home=C:/nonexistent prepare-host "--jars=%~1" "--winsw=%~2" "--driver-jar=%~3" %RENDER% || exit /b 1
rem Secrets: typed with hidden input or generated, as the administrator; an existing one is kept. The tokens are shown once.
%CLI% secret-set ledger.jdbc.password --if-absent %STDIN% || exit /b 1
%CLI% token-generate ledger.admin.token --print || exit /b 1
rem The admin web must know the agents' token to refuse it: the same token under a second name.
%CLI% token-generate ledger.mcp.token --print "--copy-to=ledger.admin.agent-token" || exit /b 1
rem The driver is registered after the secrets (a registered driver makes the commands connect).
%CLI% driver-register "--jar=%HOME%/etc/drivers/%~nx3" "--class=%~4" || exit /b 1
rem The first setup through the DDL account (schema, grants, the seed codes); its password is typed and not stored.
%CLI% setup "--owner-account=%~5" "--app-account=%~6" %STDIN% || exit /b 1
rem WinSW registers the services from the configurations, sc.exe sets the one account (the service manager again).
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" uninstall >nul 2>&1 & "%WHOME%\lib\%%S.exe" install && sc.exe config %%S obj= "%ACCOUNT%" >nul || exit /b 1
rem The service account has its access since prepare-host made the folders (Java): the jars to read, the settings and the data to change, the secrets to read.
for %%S in (%SVC% %ADMIN%) do "%WHOME%\lib\%%S.exe" start || exit /b 1
exit /b 0

:fail
rem A step failed after the services were stopped: the services that ran are started again (a prepare-host that failed has put the access of the folders back).
for %%S in (%SVC% %ADMIN%) do if defined UP_%%S "%WHOME%\lib\%%S.exe" start
exit /b 1

:ask
rem An install default from Java: %1 its name, %2 the variable that gets it, %3 the characters its form has. A command that fails prints a
rem "!" after its own output (no default has one), and cmd has no pattern match, so delims= the characters of the form leaves nothing of a good value.
set "%2="
for /f "usebackq delims=" %%V in (`""%JAVA%" %JVMOPTS% -jar "%CLIJAR%" --ledger.secrets.home=C:/nonexistent install-default %1 || echo !"`) do set "%2=%%V"
if not defined %2 echo install.cmd: install-default %1 gave no value& exit /b 1
call set "VALUE=%%%2%%"
for /f "delims=%~3" %%X in ("%VALUE%") do echo install.cmd: install-default %1 is not of the form its value has& exit /b 1
exit /b 0
