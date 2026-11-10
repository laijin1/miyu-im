@echo off
chcp 65001 >nul
title ZhiWang - Stop All

echo Stopping all processes...
echo.

echo [1/2] Killing Java server...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTEN"') do taskkill /F /PID %%a >nul 2>&1
echo     OK

echo [2/2] Killing Cloudflare Tunnel...
taskkill /F /IM cloudflared.exe >nul 2>&1
echo     OK

echo.
echo All stopped.
pause
