@echo off
setlocal enabledelayedexpansion
title Miyu - Public Tunnel

:: ============================================================
::  Miyu - expose the local server to the internet via cloudflared
::
::  WHY THIS FILE IS PURE ASCII -- DO NOT "LOCALIZE" IT:
::    cmd reads a .bat using the console's code page. On a console that
::    starts in a legacy code page (GBK/936), UTF-8 multi-byte characters
::    in the file make cmd's line offsets drift and lines get chopped in
::    half and executed as commands. A UTF-8 BOM does not fix it; it
::    breaks line 1 instead. Reproduced 2026-09-12. Keep this ASCII-only.
:: ============================================================

echo ============================================
echo   Miyu Server - Public Tunnel
echo ============================================
echo.

:: ---- 1. locate cloudflared ----
set "CF="
:: No machine-specific path: rely on PATH ("where cloudflared" below).
if not defined CF (
    where cloudflared >nul 2>&1
    if not errorlevel 1 set "CF=cloudflared"
)
if not defined CF (
    echo [ERROR] cloudflared.exe not found.
    echo         Put cloudflared on PATH (or set CF explicitly).
    echo         Download: https://github.com/cloudflare/cloudflared/releases/latest
    echo.
    pause
    exit /b 1
)

:: ---- 2. make sure the local server is actually running ----
echo [1/3] Checking local server on 127.0.0.1:5000 ...
curl -s -o NUL -w "%%{http_code}" --max-time 5 http://127.0.0.1:5000/api/wallet > "%TEMP%\miyu_probe.txt" 2>nul
set /p LOCALCODE=<"%TEMP%\miyu_probe.txt"
del "%TEMP%\miyu_probe.txt" >nul 2>&1
if "%LOCALCODE%"=="000" (
    echo.
    echo [ERROR] Local server is not responding.
    echo         Run scripts\start-server.bat first, then run this again.
    echo.
    pause
    exit /b 1
)
echo       local server OK ^(HTTP %LOCALCODE%^)

:: ---- 3. named tunnel if a real domain is configured, else quick tunnel ----
set "CFG=%~dp0..\deploy\cloudflare\familychat.yml"
set "USENAMED="
if exist "%CFG%" (
    findstr /C:"your-domain.com" "%CFG%" >nul 2>&1
    if errorlevel 1 set "USENAMED=1"
)

echo [2/3] Starting tunnel ...
echo.
if defined USENAMED (
    echo       mode: NAMED tunnel ^(stable domain - recommended^)
    echo.
    "%CF%" tunnel --config "%CFG%" run familychat
) else (
    echo       mode: QUICK tunnel ^(temporary domain^)
    echo.
    echo   ************************************************************
    echo   * WARNING: a quick tunnel gets a NEW random domain on every  *
    echo   *          restart. The server address already saved on your *
    echo   *          family phones will stop working and must be       *
    echo   *          re-entered. For a stable domain, fill in          *
    echo   *          deploy\cloudflare\familychat.yml                  *
    echo   ************************************************************
    echo.
    "%CF%" tunnel --url http://localhost:5000
)

echo.
echo [3/3] Tunnel exited.
pause
