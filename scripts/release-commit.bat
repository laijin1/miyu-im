@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title 知网 - 一键发布并自动提交

set "SD=%~dp0"
set "ROOT=%SD%.."
set "VF=%ROOT%\release\version.properties"

echo ========================================
echo   知网 一键发布 + 自动提交
echo ========================================
echo.
echo   [1/6] 编译服务端 JAR
echo   [2/6] 版本号自动 +1（构建前，保证 APK 版本与 version.properties 一致）
echo   [3/6] 编译 Android APK（自动读取 version.properties 版本号）
echo   [4/6] 复制 APK 到 release\
echo   [5/6] 重启服务端 (端口5000)
echo   [6/6] 自动提交 git
echo.

REM ===== 1. 构建服务端 =====
echo [1/6] 编译服务端 JAR...
cd /d "%ROOT%\server"
call gradlew.bat shadowJar >nul 2>&1
if errorlevel 1 ( echo     ❌ 服务端编译失败 & pause & exit /b 1 )
echo     OK
cd /d "%ROOT%"

REM ===== 2. 版本号自动 +1（可跳过）=====
REM 必须在构建 APK 之前执行：APK 的 versionCode/versionName 由 build.gradle 读取 version.properties
set "NEW_CODE="
set "NEW_NAME="
echo [2/6] 版本号自动 +1...
set /p BUMP=     是否自动把版本号加1? (Y=是 / N=否, 回车默认Y):
if /i not "%BUMP%"=="N" (
    call :bump_version
    echo     已加: versionName=%NEW_NAME%  versionCode=%NEW_CODE%
) else (
    echo     已跳过（版本号不变）
)

REM ===== 3. 构建 APK（正式签名版；调试版会被杀软判为风险包，绝不再用） =====
echo [3/6] 编译 Android 正式版 APK (assembleRelease)...
if not exist "%ROOT%\release\keystore.properties" (
    echo     ❌ 缺少 %ROOT%\release\keystore.properties
    echo        正式包必须签名，请参照 keystore.properties.example 配置后重试。
    pause & exit /b 1
)
set "ANDROID_HOME=D:\Android"
cd /d "%ROOT%\client\android"
call gradlew.bat assembleRelease >nul 2>&1
if errorlevel 1 ( echo     ❌ APK 编译失败 & pause & exit /b 1 )

REM 出货前硬校验：绝不能把调试签名包或 debuggable 包发出去
set "REL_APK=%ROOT%\client\android\app\build\outputs\apk\release\app-release.apk"
set "BT=%ANDROID_HOME%\build-tools\34.0.0"
if not exist "%REL_APK%" ( echo     ❌ 未找到 %REL_APK% & pause & exit /b 1 )
"%BT%\apksigner.bat" verify --print-certs "%REL_APK%" 2>nul | findstr /C:"Android Debug" >nul
if not errorlevel 1 ( echo     ❌ 产出包用的是 Android 调试证书，禁止发布！ & pause & exit /b 1 )
"%BT%\aapt.exe" dump xmltree "%REL_APK%" AndroidManifest.xml 2>nul | findstr /C:"debuggable" >nul
if not errorlevel 1 ( echo     ❌ 产出包含 android:debuggable，禁止发布！ & pause & exit /b 1 )

REM Gson 字段名体检：混淆把字段名改掉会导致接口返回空体 400、升级后读不出旧存档，
REM 构建期发现不了、装到手机上才炸，因此必须在复制到 release 之前拦住。
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\publish-check-dex.ps1" "%REL_APK%"
if errorlevel 1 (
    echo     ❌ Gson 字段名被混淆破坏了接口契约，禁止发布！
    echo        请检查 app/proguard-rules.pro 的 @SerializedName 保留规则。
    pause & exit /b 1
)
echo     OK（签名 / debuggable / Gson 字段名 校验通过）
cd /d "%ROOT%"

REM ===== 4. 复制 APK 到 release 目录（只此一份：密语.apk，服务器伺服的就是它） =====
echo [4/6] 复制 APK 到 release\...
set "APK_SRC=%ROOT%\client\android\app\build\outputs\apk\release\app-release.apk"
copy /Y "%APK_SRC%" "%ROOT%\release\密语.apk" >nul
if errorlevel 1 ( echo     ❌ 复制失败 & pause & exit /b 1 )
echo     OK

REM ===== 5. 重启服务端 =====
echo [5/6] 重启服务端 (端口5000)...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5000" ^| findstr "LISTEN"') do taskkill /F /PID %%a >nul 2>&1
timeout /t 2 /nobreak >nul
cd /d "%ROOT%"
REM 日志一律追加到 server\server.log（与 start-server.bat 同一份，绝不覆盖）
REM 启动前先轮转：> 5 MB 时改名成 server.log.1（只保留 1 份历史）
call "%SD%rotate-server-log.bat"
start /MIN "ZhiWang Server" cmd /c "java -Dfile.encoding=UTF-8 -jar server\build\libs\familychat-server.jar >> server\server.log 2>&1"
REM 重启后必须真的 LISTENING 才算成功：轮询最多 10 次（每次 1s），失败报错并以非 0 退出
set /a TRIES=0
:waitlisten
netstat -ano | findstr ":5000 " | findstr "LISTEN" >nul
if not errorlevel 1 goto listen_ok
set /a TRIES+=1
if %TRIES% GEQ 10 goto listen_fail
REM 用 ping 当 1 秒睡眠：timeout 在 stdin 被重定向（管道/计划任务）时会立即返回，导致轮询被压缩成 0.4 秒
ping -n 2 127.0.0.1 >nul 2>&1
goto waitlisten

:listen_fail
echo.
echo     ERROR 服务端在 10 次轮询后仍未 LISTENING 端口 5000
echo           日志: %ROOT%\server\server.log
echo           端口可能仍被占用: 先运行 stop.bat 再重试
pause
exit /b 1

:listen_ok
echo     OK (LISTENING 端口 5000)

REM ===== 6. git 提交 =====
echo [6/6] 提交到 git...
cd /d "%ROOT%"
set "MSG=发布更新"
if not "%NEW_NAME%"=="" set "MSG=发布 v%NEW_NAME%"
set /p CUST=     提交说明 (回车用默认"%MSG%"):
if not "%CUST%"=="" set "MSG=%CUST%"

git add -A
echo.
echo     --- 即将提交的内容 ---
git status --short
echo.
set /p CONFIRM=     确认提交? (Y=提交 / N=取消, 回车默认Y):
if /i "%CONFIRM%"=="N" ( echo     已取消提交，改动仍在工作区 & goto :done )
git commit -m "%MSG%" >nul
if errorlevel 1 ( echo     ❌ 提交失败（可能没有改动） & goto :done )
echo     OK: %MSG%

:done
echo.
echo ========================================
echo   完成！提交信息: %MSG%
echo ========================================
echo.
pause
exit /b 0

REM ===== 版本号+1：读取 version.properties，code/补丁各+1；保留 changelog 行 =====
:bump_version
set "OLD_CODE=0"
set "OLD_NAME=1.0.0"
set "OLD_LOG="
for /f "usebackq tokens=1,2 delims==" %%a in ("%VF%") do (
    set "K=%%a"
    set "V=%%b"
    if "!K!"=="versionCode" set "OLD_CODE=!V!"
    if "!K!"=="versionName" set "OLD_NAME=!V!"
    if "!K!"=="changelog" set "OLD_LOG=!V!"
)
set /a NEW_CODE=%OLD_CODE%+1
for /f "tokens=1-3 delims=." %%a in ("%OLD_NAME%") do (
    if "%%c"=="" ( set "NEW_NAME=%%a.%%b.1" ) else ( set /a PATCH=%%c+1 & set "NEW_NAME=%%a.%%b.!PATCH!" )
)
> "%VF%" echo versionCode=%NEW_CODE%
>> "%VF%" echo versionName=%NEW_NAME%
if not "!OLD_LOG!"=="" >> "%VF%" echo changelog=!OLD_LOG!
exit /b 0
