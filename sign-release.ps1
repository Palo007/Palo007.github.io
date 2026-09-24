# Sign the Questa release APK without typing the keystore password each time.
#
# The password is stored OUTSIDE this repo, encrypted with Windows DPAPI for your
# Windows account only:  %USERPROFILE%\.questa-secrets\android-keystore-password.dpapi
# This script holds no secret, so it is safe in git.
#
#   .\sign-release.ps1 -SavePassword   # once: type the password, it is saved encrypted
#   .\sign-release.ps1 -Build          # gradle assembleRelease, then align + sign + verify
#   .\sign-release.ps1                 # align + sign + verify the last release build
#
# DPAPI is tied to this Windows account. Keep a second copy of the password in a
# password manager: a new PC or a reset Windows profile cannot decrypt this file.

param([switch]$SavePassword, [switch]$Build)
$ErrorActionPreference = 'Stop'

$Repo       = $PSScriptRoot
$SecretDir  = Join-Path $env:USERPROFILE '.questa-secrets'
$SecretFile = Join-Path $SecretDir 'android-keystore-password.dpapi'
$Sdk        = Join-Path $env:USERPROFILE '.bubblewrap\android_sdk'
$BT         = Join-Path $Sdk 'build-tools\36.1.0'

if ($SavePassword) {
    New-Item -ItemType Directory -Force $SecretDir | Out-Null
    $s = Read-Host 'Keystore password (android.keystore)' -AsSecureString
    $s | ConvertFrom-SecureString | Set-Content -Path $SecretFile -Encoding ascii
    Write-Host "Saved, encrypted for your Windows account: $SecretFile"
    exit 0
}

if (-not (Test-Path $SecretFile)) {
    throw "No saved password. Run once: .\sign-release.ps1 -SavePassword"
}

if ($Build) {
    $env:ANDROID_HOME = $Sdk
    & (Join-Path $Repo 'gradlew.bat') -p $Repo assembleRelease -q
    if ($LASTEXITCODE) { throw 'gradle assembleRelease failed' }
}

$unsigned = Join-Path $Repo 'app\build\outputs\apk\release\app-release-unsigned.apk'
$aligned  = Join-Path $Repo 'app-release-unsigned-aligned.apk'
$signed   = Join-Path $Repo 'app-release-signed.apk'
if (-not (Test-Path $unsigned)) { throw "Missing $unsigned. Run with -Build." }

& (Join-Path $BT 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE) { throw 'zipalign failed' }

# apksigner reads the password from an env var that exists only for this process.
$sec = Get-Content $SecretFile | ConvertTo-SecureString
$env:QUESTA_KS_PASS = [System.Net.NetworkCredential]::new('', $sec).Password
try {
    & (Join-Path $BT 'apksigner.bat') sign `
        --ks (Join-Path $Repo 'android.keystore') --ks-key-alias android `
        --ks-pass env:QUESTA_KS_PASS --key-pass env:QUESTA_KS_PASS `
        --out $signed $aligned
    if ($LASTEXITCODE) { throw 'apksigner sign failed (wrong password? re-run -SavePassword)' }
} finally {
    Remove-Item Env:QUESTA_KS_PASS -ErrorAction SilentlyContinue
}

Write-Host "Signed: $signed"
Write-Host 'Compare this SHA-256 with README section 2 before you install:'
& (Join-Path $BT 'apksigner.bat') verify --print-certs $signed | Select-String 'SHA-256'
