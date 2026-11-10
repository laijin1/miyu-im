@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion
title Miyu - publish-check
cd /d "%~dp0.."

echo ============================================================
echo   Publish pre-check (Miyu)
echo   Purpose: catch "only shows up after release" problems HERE
echo ============================================================
echo.

REM IMPORTANT: keep this .bat file PURE ASCII.
REM cmd.exe parses .bat using the ANSI codepage before chcp takes effect;
REM UTF-8 Chinese here can be read as a GBK lead/continuation byte (0x85 is a
REM line-continuation char) and silently corrupt the whole script.
REM That is why the APK is located by wildcard instead of its Chinese filename.

REM Only ONE APK ships: the release-dir APK named in FileHandler.APK_PATH. Its name is
REM non-ASCII and this script must stay pure ASCII, so every check below works
REM on release\*.apk - which is exactly one file - and stages it under an ASCII
REM name first (aapt.exe cannot open non-ASCII paths: it exits 1 with no output).
REM History: until 146 the release dir held three identical copies (Miyu/ZhiWang/miyu)
REM because this script could not name the published one; the copies are gone and
REM every check now simply iterates whatever .apk is present.

set "STAGE=%TEMP%\miyu-publish-check"
if exist "%STAGE%" rd /s /q "%STAGE%" >nul 2>&1
mkdir "%STAGE%" >nul 2>&1
set /a APKN=0
for %%F in (release\*.apk) do (
  set /a APKN+=1
  copy /Y "%%F" "%STAGE%\apk!APKN!.apk" >nul 2>&1
)
set "BT=C:\android-sdk\build-tools\34.0.0"
set /a PASS=0
set /a FAIL=0

if not exist "%BT%\aapt.exe" set "BT=%ANDROID_HOME%\build-tools\34.0.0"
if not exist "%BT%\aapt.exe" (
  echo [SKIP] build-tools not found
  pause & exit /b 1
)
if "%APKN%"=="0" (
  echo [FAIL] no APK found under release\ - build first
  pause & exit /b 1
)

echo APK count under release\: %APKN%
echo.

REM ---------------------------------------------------------------------------
REM Checks [1/4]-[4/4] run on EVERY staged APK. With one copy that is one file,
REM but the loop is kept so a stale extra .apk can never be silently skipped
REM (the old script only ever inspected the FIRST one).
REM ---------------------------------------------------------------------------
set /a SIGNFAIL=0
set /a DBGFAIL=0
set /a PERMFAIL=0
set /a DEXFAIL=0
set /a N=0

for %%F in (release\*.apk) do (
  set /a N+=1
  set "ONE=%STAGE%\apk!N!.apk"

  echo [1/4] APK !N!/%APKN% (%%~nxF^) signing cert - must be release key, never Android Debug ...
  "%BT%\apksigner.bat" verify --print-certs "!ONE!" 2>nul | findstr /C:"Android Debug" >nul
  if not errorlevel 1 (
    echo        [FAIL] signed with Android DEBUG cert - AV flags this as bundled adware
    set /a SIGNFAIL+=1
  ) else (
    echo        [OK] not a debug cert
  )

  echo [2/4] APK !N!/%APKN% (%%~nxF^) android:debuggable - must be absent ...
  "%BT%\aapt.exe" dump xmltree "!ONE!" AndroidManifest.xml 2>nul | findstr /C:"debuggable" >nul
  if not errorlevel 1 (
    echo        [FAIL] APK still has android:debuggable
    set /a DBGFAIL+=1
  ) else (
    echo        [OK] no debuggable
  )

  echo [3/4] APK !N!/%APKN% (%%~nxF^) sensitive permissions ...
  "%BT%\aapt.exe" dump permissions "!ONE!" 2>nul | findstr /C:"IGNORE_BATTERY_OPTIMIZATIONS" >nul
  if not errorlevel 1 (
    echo        [FAIL] still requests IGNORE_BATTERY_OPTIMIZATIONS
    set /a PERMFAIL+=1
  ) else (
    echo        [OK] sensitive permissions removed
  )

  echo [4/4] APK !N!/%APKN% (%%~nxF^) Gson field names - obfuscating them breaks APIs with an empty-body 400 ...
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0publish-check-dex.ps1" "!ONE!"
  if errorlevel 1 ( set /a DEXFAIL+=1 )
)

if "%SIGNFAIL%"=="0" (
  echo        [OK] signature check passed on all %APKN% APK^(s^)
  set /a PASS+=1
) else (
  set /a FAIL+=1
)
if "%DBGFAIL%"=="0" (
  echo        [OK] debuggable check passed on all %APKN% APK^(s^)
  set /a PASS+=1
) else (
  set /a FAIL+=1
)
if "%PERMFAIL%"=="0" (
  echo        [OK] permission check passed on all %APKN% APK^(s^)
  set /a PASS+=1
) else (
  set /a FAIL+=1
)
if "%DEXFAIL%"=="0" (
  echo        [OK] Gson field names passed on all %APKN% APK^(s^)
  set /a PASS+=1
) else (
  set /a FAIL+=1
)

echo [5/7] Version consistency and publish window ...
set "VC="
for /f "usebackq tokens=1,2 delims==" %%a in ("release\version.properties") do (
  if "%%a"=="versionCode" set "VC=%%b"
)
if "!VC!"=="" (
  echo        [FAIL] cannot read versionCode from release\version.properties
  set /a FAIL+=1
) else (
  REM The server serves the release-dir APK named in FileHandler.APK_PATH
  REM (one file). This script must stay PURE ASCII so it
  REM cannot name that file directly; it therefore validates EVERY .apk under
  REM release\ and requires each one to carry the version in version.properties
  REM and to be newer than it. A stale leftover .apk can then never pass in
  REM place of the published pack.
  set /a APKN2=0
  set /a APKFAIL=0
  for %%F in (release\*.apk) do (
    set /a APKN2+=1
    copy /Y "%%F" "%STAGE%\one.apk" >nul 2>&1
    "%BT%\aapt.exe" dump badging "%STAGE%\one.apk" 2>nul | findstr /C:"versionCode='!VC!'" >nul
    if errorlevel 1 (
      echo        [FAIL] %%~nxF versionCode does not match version.properties ^(expected !VC!^)
      set /a APKFAIL+=1
    ) else (
      set "APKFILE=%%F"
      powershell -NoProfile -Command "if((Get-Item $env:APKFILE).LastWriteTime -lt (Get-Item 'release\version.properties').LastWriteTime){exit 1}else{exit 0}"
      if errorlevel 1 (
        echo        [FAIL] %%~nxF older than version.properties - server will refuse to publish
        set /a APKFAIL+=1
      )
    )
  )
  if "!APKN2!"=="0" (
    echo        [FAIL] no APK found under release\ - build first
    set /a FAIL+=1
  ) else (
    if "!APKFAIL!"=="0" (
      echo        [OK] all !APKN2! APK^(s^) carry versionCode !VC!, none older than the version file
      set /a PASS+=1
    ) else (
      set /a FAIL+=1
    )
  )
)

echo [6/7] Client unit tests - crypto round-trip, JSON contract, protocol logic ...
where pwsh >nul 2>&1
if errorlevel 1 (
  echo        [FAIL] pwsh ^(PowerShell 7^) not found - unit tests cannot run reliably
  echo               Windows PowerShell 5.1 silently reports "0 tests passed".
  echo               Install PowerShell 7: winget install Microsoft.PowerShell
  set /a FAIL+=1
) else (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-unit-tests.ps1" -SkipBuild
  if errorlevel 1 (
    echo        [FAIL] unit tests failed - a regression would ship with this build
    set /a FAIL+=1
  ) else (
    echo        [OK] unit tests passed
    set /a PASS+=1
  )
)

echo [7/7] Pipeline script encoding guards (bat/ps1/ps-version/test-presence) ...
REM Guards are ASCII-only messages and live in a .ps1 with UTF-8 BOM.
where pwsh >nul 2>&1
if errorlevel 1 (
  echo        [FAIL] pwsh not found - cannot run encoding guards
  set /a FAIL+=1
) else (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0test-quality-guards.ps1"
  if errorlevel 1 (
    echo        [FAIL] encoding/quality guards failed - see above
    set /a FAIL+=1
  ) else (
    echo        [OK] encoding/quality guards passed
    set /a PASS+=1
  )
)

echo.
echo ============================================================
echo   Result: %PASS% passed, %FAIL% failed
if %FAIL% GTR 0 (
  echo   [X] DO NOT PUBLISH. Fix the failures above and re-run.
) else (
  echo   [V] All checks passed - safe to publish
)
echo ============================================================
echo.
if exist "%STAGE%" rd /s /q "%STAGE%" >nul 2>&1
pause
