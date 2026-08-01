@echo off
chcp 65001 >nul
set ANDROID_HOME=D:\ANSSDK
set JAVA_HOME=D:\ANS\jbr

cd /d "%~dp0"

echo === Check Env ===
if not exist "%ANDROID_HOME%" echo WARNING: ANDROID_HOME=%ANDROID_HOME% not found
if not exist "%JAVA_HOME%" echo WARNING: JAVA_HOME=%JAVA_HOME% not found
where /q adb && echo ADB: OK || echo ADB: not found (non-blocking)
echo.

if "%1"=="dev" goto :build_dev
if "%1"=="--dev" goto :build_dev
if "%1"=="debug" goto :build_debug
if "%1"=="--debug" goto :build_debug

:build_release
if exist android\ goto :release_prebuilt

echo === Prebuild Android ===
call npx expo prebuild --platform android
if errorlevel 1 ( echo Prebuild FAILED & pause & exit /b 1 )
call :set_arm64_only

rem Fix autolinking JSON packageName if stale
if exist "android\build\generated\autolinking\autolinking.json" (
  powershell -Command "$f='android\build\generated\autolinking\autolinking.json'; $c=[IO.File]::ReadAllText($f); if ($c -match 'com\.example\.musicplayer') { $c=$c -replace '\"packageName\":\"com\.example\.musicplayer\"', '\"packageName\":\"com.annieplayer.diangoudev\"'; $enc=New-Object Text.UTF8Encoding $false; [IO.File]::WriteAllText($f,$c,$enc); echo Fixed autolinking.json packageName }"
)

:release_prebuilt
call :set_arm64_only
call :sync_version
echo === Build Release APK ===
cd android
call gradlew assembleRelease -PreactNativeArchitectures=arm64-v8a
set BUILD_RESULT=%errorlevel%
cd ..
if %BUILD_RESULT% neq 0 ( echo Build FAILED & pause & exit /b %BUILD_RESULT% )

echo.
echo APK: android\app\build\outputs\apk\release\app-release.apk
echo === Install on device ===
adb install -r android\app\build\outputs\apk\release\app-release.apk
if %errorlevel% neq 0 ( echo Install FAILED & pause & exit /b %errorlevel% )
echo === Done! ===
pause
exit /b 0

:build_dev
if not exist android\ goto :dev_prebuild

:dev_prebuilt
call :set_arm64_only
call :sync_version
echo === Install Dev APK via npx expo run:android ===
set RCT_ARCH=arm64-v8a
call npx expo run:android --variant debug
echo === Done! ===
pause
exit /b 0

:dev_prebuild
echo === Prebuild Android ===
call npx expo prebuild --platform android
if errorlevel 1 ( echo Prebuild FAILED & pause & exit /b 1 )
call :set_arm64_only
goto :dev_prebuilt

:build_debug
if exist android\ goto :debug_prebuilt

echo === Prebuild Android ===
call npx expo prebuild --platform android
if errorlevel 1 ( echo Prebuild FAILED & pause & exit /b 1 )
call :set_arm64_only

:debug_prebuilt
call :set_arm64_only
call :sync_version
echo === Build Debug APK ===
cd android
call gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a
set BUILD_RESULT=%errorlevel%
cd ..
if %BUILD_RESULT% neq 0 ( echo Build FAILED & pause & exit /b %BUILD_RESULT% )

echo.
echo APK: android\app\build\outputs\apk\debug\app-debug.apk
echo Install: adb install -r android\app\build\outputs\apk\debug\app-debug.apk
echo === Done! ===
pause
exit /b 0

:sync_version
rem Sync version from app.json to build.gradle and strings.xml
powershell -ExecutionPolicy Bypass -File sync-version.ps1
exit /b 0
exit /b 0

:set_arm64_only
rem Set gradle.properties to arm64-v8a only
powershell -Command "$f='android\gradle.properties'; $c=[IO.File]::ReadAllText($f); $c=$c -replace 'reactNativeArchitectures=.*', 'reactNativeArchitectures=arm64-v8a'; $enc=New-Object Text.UTF8Encoding $false; [IO.File]::WriteAllText($f, $c, $enc)"
rem Add usesCleartextTraffic to manifest
powershell -Command "$f='android\app\src\main\AndroidManifest.xml'; $c=[IO.File]::ReadAllText($f); if ($c -notmatch 'usesCleartextTraffic') { $c = $c -replace 'android:supportsRtl=\"true\"', 'android:supportsRtl=\"true\" android:usesCleartextTraffic=\"true\"'; $enc=New-Object Text.UTF8Encoding $false; [IO.File]::WriteAllText($f, $c, $enc) }"
exit /b 0
