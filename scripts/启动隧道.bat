@echo off
chcp 65001 >nul
setlocal
title Miyu Admin Tunnel

set "CLOUDFLARED=D:\Program Files\cloudflared\cloudflared.exe"
set "CONFIG=%USERPROFILE%\.cloudflared\config.yml"

echo ===================================================
echo 正在启动 Cloudflare 固定隧道 (admin.example.com —— 占位符，请改成你的真实域名)
echo 请保持此窗口开启，按 Ctrl+C 可停止隧道。
echo ===================================================
echo.

if not exist "%CLOUDFLARED%" (
    echo [错误] 找不到 cloudflared：
    echo %CLOUDFLARED%
    echo 请检查安装路径。
    pause
    exit /b 1
)

if not exist "%CONFIG%" (
    echo [错误] 找不到配置文件：
    echo %CONFIG%
    echo 请检查 config.yml 是否存在。
    pause
    exit /b 1
)

"%CLOUDFLARED%" tunnel --config "%CONFIG%" run miyu-admin
set "EXITCODE=%ERRORLEVEL%"

echo.
echo 隧道已停止，退出码：%EXITCODE%
if not "%EXITCODE%"=="0" (
    echo.
    echo 启动失败。请检查：
    echo 1. config.yml 中 tunnel / credentials-file 是否正确
    echo 2. 凭证 json 文件是否存在
    echo 3. 隧道名称是否为 miyu-admin
    echo 4. 网络是否能访问 Cloudflare
)

pause
endlocal
exit /b %EXITCODE%
