@echo off
REM ============================================================
REM  rotate-server-log.bat - stop server.log from growing forever.
REM
REM  Called by start-server.bat / deploy.bat / release-commit.bat
REM  BEFORE java is launched. One generation only, never truncated:
REM      server\server.log  -->  server\server.log.1
REM  An existing server.log.1 is replaced.
REM
REM  Threshold 5 MB: the 2026-08-14 log was 25 KB for one short run,
REM  but the per-minute cache/order reports plus one [HTTP] line per
REM  request add up - 5 MB is roughly two to four weeks of running.
REM
REM  Rotation must NEVER block startup: on any failure it prints a
REM  warning and still returns 0.
REM
REM  The path is derived from %~dp0 because the callers run from
REM  different working directories (repo root vs scripts\).
REM ============================================================
setlocal

set "LOG=%~dp0..\server\server.log"
set "OLD=%~dp0..\server\server.log.1"
set "LIMIT=5242880"

if not exist "%LOG%" goto :done

set "SIZE="
for %%A in ("%LOG%") do set "SIZE=%%~zA"
if not defined SIZE goto :done
if %SIZE% LSS %LIMIT% goto :done

if exist "%OLD%" del /f /q "%OLD%" >nul 2>&1
move /y "%LOG%" "%OLD%" >nul 2>&1
if errorlevel 1 (
    echo [ROTATE] WARN: could not rename server.log ^(still in use?^) - keeping it
    goto :done
)
echo [ROTATE] server.log was %SIZE% bytes ^(limit 5 MB^) -^> server.log.1

:done
endlocal
exit /b 0
