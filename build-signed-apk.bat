@echo off
rem Build a fresh, signed Questa APK.
rem Output: builds\Questa-v<versionName>-<versionCode>-<yyyyMMdd-HHmm>.apk
rem Password: put it on the first line of keystore.pass next to this file (git-ignored).
rem Without that file, apksigner asks for it.
setlocal
cd /d "%~dp0"

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
set "ANDROID_HOME=C:\Users\user\.bubblewrap\android_sdk"
set "BT=%ANDROID_HOME%\build-tools\36.1.0"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "UNSIGNED=app\build\outputs\apk\release\app-release-unsigned.apk"

for /f "tokens=2" %%v in ('findstr /r /c:"^ *versionName " app\build.gradle') do set "VNAME=%%~v"
for /f "tokens=2" %%v in ('findstr /r /c:"^ *versionCode " app\build.gradle') do set "VCODE=%%~v"
for /f %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set "TS=%%t"
if not defined VNAME goto :noversion
if not defined VCODE goto :noversion
set "OUTNAME=Questa-v%VNAME%-%VCODE%-%TS%.apk"
echo.
echo === Building %OUTNAME% ===
echo.

rem 1. Remove old outputs, so an old APK can never be signed by mistake.
if exist "%UNSIGNED%" del /q "%UNSIGNED%"
if exist app-release-unsigned-aligned.apk del /q app-release-unsigned-aligned.apk
if exist app-release-signed.apk del /q app-release-signed.apk
if exist app-release-signed.apk.idsig del /q app-release-signed.apk.idsig

rem 2. Stop old Gradle processes (they lock files in app\build), then clean build + unit tests.
call "%~dp0gradlew.bat" --stop >nul 2>&1
call "%~dp0gradlew.bat" clean testReleaseUnitTest assembleRelease --no-daemon
if errorlevel 1 goto :fail
if not exist "%UNSIGNED%" (
  echo ERROR: Gradle finished but made no APK at %UNSIGNED%
  goto :fail
)

rem 3. Align.
"%BT%\zipalign.exe" -f 4 "%UNSIGNED%" app-release-unsigned-aligned.apk
if errorlevel 1 goto :fail

rem 4. Sign. If keystore.pass exists (first line = password, git-ignored), it is used
rem    for the keystore; apksigner uses the same one for the key. Otherwise apksigner asks for the password.
set "PASSARGS="
if exist "%~dp0keystore.pass" set PASSARGS=--ks-pass "file:%~dp0keystore.pass"
if not defined PASSARGS (
  echo.
  echo === No keystore.pass file: type the keystore password when asked ===
)
call "%BT%\apksigner.bat" sign --ks android.keystore --ks-key-alias android %PASSARGS% --out app-release-signed.apk app-release-unsigned-aligned.apk
if errorlevel 1 goto :fail

rem 5. Verify the signature.
call "%BT%\apksigner.bat" verify app-release-signed.apk
if errorlevel 1 goto :fail

rem 6. Copy with version + time in the name.
if not exist builds mkdir builds
copy /y app-release-signed.apk "builds\%OUTNAME%" >nul
if errorlevel 1 goto :fail

echo.
echo === OK: %~dp0builds\%OUTNAME% ===
echo.
pause
exit /b 0

:noversion
echo ERROR: could not read versionName / versionCode from app\build.gradle
:fail
echo.
echo === BUILD FAILED - read the messages above ===
echo.
pause
exit /b 1
