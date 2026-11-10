@echo off
chcp 65001 >nul
title ZhiWang - Deploy All

echo ========================================
echo   ZhiWang Full Deploy
echo ========================================
echo.

set "SD=%~dp0"
set "ROOT=%SD%.."

:: 1. Build Server
echo [1/4] Building server JAR...
cd /d "%ROOT%\server"
call gradlew shadowJar >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Server build failed
  pause
  exit /b 1
)
echo     OK

:: 2. Build APK（正式签名版；调试签名包会被杀软判为风险包，禁止发布）
echo [2/4] Building Android APK...
set ANDROID_HOME=D:\Android
if not exist "%ROOT%\release\keystore.properties" (
  echo [ERROR] Missing release\keystore.properties - release build must be signed
  pause
  exit /b 1
)
cd /d "%ROOT%\client\android"
call gradlew assembleRelease >nul 2>&1
if errorlevel 1 (
  echo [ERROR] APK build failed
  pause
  exit /b 1
)
copy /Y "app\build\outputs\apk\release\app-release.apk" "%ROOT%\release\密语.apk" >nul
echo     OK: release\密语.apk

:: 3. Kill old
echo [3/4] Stopping old processes...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTEN"') do taskkill /F /PID %%a >nul 2>&1
taskkill /F /IM cloudflared.exe >nul 2>&1
timeout /t 2 /nobreak >nul
echo     OK

:: 4. Start Server
echo [4/4] Starting server + tunnel...
cd /d "%ROOT%"
:: log is appended to server\server.log (same file start-server.bat writes, never overwritten)
call "%SD%rotate-server-log.bat"
start /MIN "ZhiWang Server" cmd /c "java -Dfile.encoding=UTF-8 -jar server\build\libs\familychat-server.jar >> server\server.log 2>&1"
:: the restart only counts as success if the port is really LISTENING: poll up to 10x (1s each),
:: otherwise report the error and exit non-zero instead of printing a fake OK
set /a TRIES=0
:waitlisten
netstat -ano | findstr ":5000 " | findstr "LISTEN" >nul
if not errorlevel 1 goto listen_ok
set /a TRIES+=1
if %TRIES% GEQ 10 goto listen_fail
:: ping as the 1s sleep: "timeout" returns instantly when stdin is redirected (pipe / scheduled task)
ping -n 2 127.0.0.1 >nul 2>&1
goto waitlisten

:listen_fail
echo.
echo   [ERROR] server is not LISTENING on port 5000 after 10 polls
echo           log: %ROOT%\server\server.log
echo           port may still be held: run stop.bat, then retry
pause
exit /b 1

:listen_ok
echo     Server OK (LISTENING on port 5000)

:: 5. Start Tunnel
start /MIN "ZhiWang Tunnel" cmd /c "cloudflared tunnel --url http://localhost:5000"
timeout /t 8 /nobreak >nul
echo     Tunnel OK

echo.
echo ========================================
echo   Done!
echo   LAN:  http://<局域网IP>:5000
echo   APK:  release\密语.apk
echo   Stop: run stop.bat
echo ========================================
pause
