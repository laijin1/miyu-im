@echo off
setlocal enabledelayedexpansion
title Miyu Server

:: ---- console encoding: keep Chinese javac warnings readable ----
:: javac prints its diagnostics in the JVM default charset. The Gradle
:: *client* JVM defaults to GBK on zh-CN Windows while the daemon is pinned
:: to UTF-8 by server/gradle.properties, so whichever side converts
:: leaves mojibake in the other one's console. Pin the client to UTF-8 too
:: and switch this window to UTF-8: both sides then agree.
:: Keep this block ASCII-only, like the rest of the file.
chcp 65001 >nul
set "GRADLE_OPTS=-Dfile.encoding=UTF-8 %GRADLE_OPTS%"

:: ============================================================
::  Miyu server launcher   (miyu-app / FamilyChat)
::
::  Usage:
::    start-server.bat                     stop old -> build -> start
::    start-server.bat nobuild             skip build, start directly
::    start-server.bat port:5010           use another port
::    start-server.bat port:5010 data:D:\tmp\test-data
::                                         isolated data dir (safe to run
::                                         alongside the production instance)
::
::  IMPORTANT: the argument separator is a COLON, not an equals sign.
::             cmd splits arguments on = ; , too, so "port=5010" would
::             arrive as two separate arguments.
::
::  IMPORTANT: changing the port does NOT change the database. Two
::             instances on different ports still share one SQLite file
::             unless you also pass data:.
::
::  WHY THIS FILE IS PURE ASCII -- DO NOT "LOCALIZE" IT:
::    cmd reads a .bat using the console's code page. When the console
::    starts in a legacy code page (GBK/936) and the file contains
::    UTF-8 multi-byte characters, cmd's line offsets drift and lines
::    get chopped in half, for example:
::        D:\>@echo off
::        '@echo' is not recognized as an internal or external command
::        D:\>.bat   ...fragment of a comment line, executed
::    A UTF-8 BOM does NOT fix the drift above -- only ASCII does that.
::    It is not needed either: current cmd skips the BOM. Re-tested
::    2026-10-01 with BOM + "@echo off" under CP 936 and CP 65001: clean.
::    Reproduced 2026-09-12 on a freshly opened console. Keep it ASCII.
::
::  WHY BUILD BY DEFAULT:
::    On Windows "java -jar" locks the JAR, so you cannot rebuild while
::    the server runs (gradlew shadowJar fails with "Unable to delete
::    file"). The order must be: stop -> build -> start.
::    Gradle skips work when nothing changed (UP-TO-DATE, a few seconds).
::
::  WHY A FAILED BUILD ABORTS INSTEAD OF STARTING ANYWAY:
::    Otherwise you hit the worst case: you changed code, restarted, and
::    are still running the old JAR -- then spend half an hour hunting a
::    bug that does not exist. Fail loudly instead.
:: ============================================================

set "ROOT=%~dp0..\"
set "JAR=%ROOT%server\build\libs\familychat-server.jar"
:: Heap cap: keep it on a small VPS (2-4 GB). On a big machine use -Xmx2g.
set "JAVA_OPTS=-Xmx1g -Dfile.encoding=UTF-8"
set "PORT=5000"
set "DATADIR="
set "DO_BUILD=1"
:: Recharge allowlist (comma-separated user ids), read by WalletHandler.
:: LEFT EMPTY ON PURPOSE (open-source default): an empty allowlist means
:: NOBODY can self-recharge, which is the secure default.
:: To enable it for a deployment, export ids before starting, e.g.:
::     set ADMIN_USER_IDS=1,2
:: Never hardcode real user ids in this file.
set "ADMIN_USER_IDS="

:: ---- parse arguments (separator is a colon, see above) ----
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="nobuild" set "DO_BUILD=0"
for /f "tokens=1,* delims=:" %%k in ("%~1") do (
    if /i "%%k"=="port" set "PORT=%%l"
    if /i "%%k"=="data" set "DATADIR=%%l"
)
shift
goto parse
:parsed

:: ---- safety: port must be numeric ----
:: Without this check an empty PORT turns the findstr below into a bare
:: ":" pattern that matches every netstat line and kills unrelated
:: processes on other ports. That actually happened on 2026-09-12.
echo %PORT%| findstr /r "^[0-9][0-9]*$" >nul
if errorlevel 1 (
    echo.
    echo [ERROR] Invalid port: "%PORT%"
    echo         Example:  start-server.bat port:5000
    echo         The separator is a colon, not an equals sign.
    echo.
    pause
    exit /b 1
)

set "DATAARG="
if defined DATADIR set "DATAARG=-Dfamilychat.data.dir=!DATADIR!"

echo ============================================
echo   Miyu Server     port %PORT%
echo ============================================
if defined DATADIR echo   data dir: !DATADIR!  (not the production one)
echo.

:: ---- [1/4] Java ----
set JAVA_CMD=java
if not "%JAVA_HOME%"=="" (
    if exist "%JAVA_HOME%\bin\java.exe" set JAVA_CMD="%JAVA_HOME%\bin\java.exe"
)
%JAVA_CMD% -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Install JDK 17+ from https://adoptium.net/
    echo         JAVA_HOME is used first when it is set.
    echo.
    pause
    exit /b 1
)
:: Only print which java is used; do not parse its version.
:: A quoted path inside for /f makes cmd report "filename syntax incorrect",
:: and the version is informational anyway (an old JDK fails at startup with
:: UnsupportedClassVersionError, which is clear enough).
echo [1/4] Java: %JAVA_CMD%

:: ---- [2/4] stop whatever listens on the port ----
set "LASTPID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTEN"') do (
    taskkill /F /PID %%a >nul 2>&1
    set "LASTPID=%%a"
)
if defined LASTPID (
    echo [2/4] Stopped old instance on port %PORT% ^(PID !LASTPID!^)
) else (
    echo [2/4] Port %PORT% is free
)

:: wait for the port to be released (an admin-owned process cannot be killed)
set /a WAIT=0
:waitport
netstat -ano | findstr ":%PORT% " | findstr "LISTEN" >nul
if errorlevel 1 goto portfree
set /a WAIT+=1
if !WAIT! GEQ 10 goto portbusy
:: ping as the 1s sleep: "timeout" returns instantly when stdin is redirected
ping -n 2 127.0.0.1 >nul 2>&1
goto waitport

:portbusy
echo.
echo [ERROR] Port %PORT% is still in use, cannot start.
echo         Usually the old instance runs with Administrator rights.
echo         Two options:
echo           1^) close the old window titled "Miyu Server";
echo           2^) in an admin PowerShell run:  taskkill /F /PID !LASTPID!
echo.
pause
exit /b 1

:portfree

:: ---- [3/4] build ----
if "!DO_BUILD!"=="0" (
    echo [3/4] Build skipped ^(nobuild^) -- code changes will NOT take effect
    goto runserver
)

echo [3/4] Building server JAR ...
pushd "%ROOT%server"
call gradlew.bat shadowJar --console=plain
set "BUILD_RC=!errorlevel!"
popd

if not "!BUILD_RC!"=="0" (
    echo.
    echo [ERROR] Build failed, aborted -- will NOT start from the old JAR.
    echo         Common cause: another instance still holds the JAR
    echo         ^(did you run an instance on another port? stop it first^).
    echo         To just get the server running:  start-server.bat nobuild
    echo.
    pause
    exit /b 1
)

if not exist "%JAR%" (
    echo.
    echo [ERROR] JAR still missing after build: %JAR%
    echo.
    pause
    exit /b 1
)
echo       build OK

:: ---- [4/4] run ----
:runserver
if not exist "%JAR%" (
    echo.
    echo [ERROR] JAR not found: %JAR%
    echo         Run this script once without nobuild to build it.
    echo.
    pause
    exit /b 1
)

:: ---- LAN IP for the phone URL ----
:: The server's own banner goes to server.log, so this window prints no
:: address unless we detect one here. Same trick as
:: FamilyChatServer.getLocalIp: ask which source address a UDP socket
:: routed off-box would use. A naive "first IPv4 in ipconfig" picks the
:: wrong adapter on this machine (Radmin VPN, cfw-tap, Hyper-V vEthernet).
:: Detect failure / loopback keeps the placeholder, so the printed URL is never a
:: dead "http://:5000" or "http://127.0.0.1:5000": a placeholder tells the user to
:: read the real address off the "APK:" line in server.log. Startup never depends
:: on this detection succeeding.
set "LAN_IP=THE-PC-IP"
for /f "delims=" %%i in ('powershell -NoProfile -Command "$u=New-Object Net.Sockets.UdpClient; $u.Connect('8.8.8.8',9); $u.Client.LocalEndPoint.Address.ToString()" 2^>nul') do set "LAN_IP=%%i"
:: Same guards as FamilyChatServer.getLocalIp: a loopback address is useless for a
:: phone, and an empty value would print a dead "http://:5000". Keep the placeholder
:: in both cases so the user falls back to the "APK:" line in server.log.
if "%LAN_IP%"=="" set "LAN_IP=THE-PC-IP"
if "%LAN_IP:~0,4%"=="127." set "LAN_IP=THE-PC-IP"

echo [4/4] Starting server ...
echo.
echo   this PC :  http://localhost:%PORT%
echo   phone   :  http://%LAN_IP%:%PORT%
echo   admin   :  http://localhost:%PORT%/admin/login
echo.
echo ------------------------------------------------------------
echo   log file:   %ROOT%server\server.log
echo ------------------------------------------------------------
call "%ROOT%scripts\rotate-server-log.bat"
%JAVA_CMD% %JAVA_OPTS% -Dserver.port=%PORT% !DATAARG! -jar "%JAR%" >> "%ROOT%server\server.log" 2>&1
set "SERVER_RC=!ERRORLEVEL!"
echo ------------------------------------------------------------
echo.
echo Server exited. exit code !SERVER_RC!
echo If it never became reachable, check: %ROOT%server\server.log
pause
