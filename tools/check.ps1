<#
.SYNOPSIS
  Local CI-less gate: debug build, unit tests, HAND-OWNED guard, DAL check. No signing, no secrets.
  Prints PASS/FAIL per step; exit 1 if any step FAILs (all steps run so one run shows everything).

.PARAMETER NoBuild  Skip gradle steps (e.g. as a pre-check from build-signed-apk.bat).
.PARAMETER Offline  Pass -Offline to dal-check (skip published file + DAL API).
.PARAMETER Apk      Pass -Apk to dal-check (user-run only: compares the APK's public cert).
#>
[CmdletBinding()]
param([switch]$NoBuild, [switch]$Offline, [string]$Apk)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Shell = (Get-Process -Id $PID).Path
$results = New-Object System.Collections.Generic.List[object]
function Add-Result([string]$step, [string]$status, [string]$note) {
  $results.Add([pscustomobject]@{ Step = $step; Status = $status; Note = $note })
  $c = 'Green'; if ($status -eq 'FAIL') { $c = 'Red' } elseif ($status -eq 'SKIP') { $c = 'Yellow' }
  Write-Host "[$status] $step $note" -ForegroundColor $c
}

# 1. Environment (process scope only; paths only, nothing secret).
$src = 'env'
if (-not $env:ANDROID_HOME) {
  $lp = Join-Path $Root 'local.properties'
  $sdk = $null
  if (Test-Path -LiteralPath $lp) {
    $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
    if ($line) { $sdk = (($line -split '=', 2)[1]).Trim() -replace '\\:', ':' -replace '\\\\', '\'; $src = 'local.properties' }
  }
  if (-not $sdk) { $sdk = 'C:\Users\user\AppData\Local\Android\Sdk'; $src = 'default' }
  $env:ANDROID_HOME = $sdk
}
if (Test-Path -LiteralPath $env:ANDROID_HOME) { Add-Result 'env' 'PASS' "ANDROID_HOME=$($env:ANDROID_HOME) ($src)" }
else { Add-Result 'env' 'FAIL' "ANDROID_HOME=$($env:ANDROID_HOME) ($src) does not exist" }

# 2-3. Gradle.
$gradlew = Join-Path $Root 'gradlew.bat'
foreach ($task in @('assembleDebug', 'testDebugUnitTest')) {
  if ($NoBuild) { Add-Result $task 'SKIP' '(-NoBuild)'; continue }
  Push-Location $Root
  try {
    $log = Join-Path ([IO.Path]::GetTempPath()) "questa-check-$task.log"
    & $gradlew $task --console=plain *> $log
    $code = $LASTEXITCODE
  } finally { Pop-Location }
  $note = "(log: $log)"
  if ($task -eq 'testDebugUnitTest') {
    $tests = 0; $failed = 0
    $dir = Join-Path $Root 'app\build\test-results\testDebugUnitTest'
    if (Test-Path -LiteralPath $dir) {
      foreach ($x in Get-ChildItem -LiteralPath $dir -Filter *.xml) {
        [xml]$d = Get-Content -LiteralPath $x.FullName -Raw
        $tests += [int]$d.testsuite.tests; $failed += [int]$d.testsuite.failures + [int]$d.testsuite.errors
      }
    }
    $note = "tests=$tests failed=$failed $note"
  }
  if ($code -eq 0) { Add-Result $task 'PASS' $note } else { Add-Result $task 'FAIL' "exit $code $note" }
}

# 4. HAND-OWNED guard.
& $Shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'hand-owned-guard.ps1') -Root $Root
if ($LASTEXITCODE -eq 0) { Add-Result 'hand-owned-guard' 'PASS' '' } else { Add-Result 'hand-owned-guard' 'FAIL' '' }

# 5. DAL check.
$dalArgs = @('-Root', $Root)
if ($Offline) { $dalArgs += '-Offline' }
if ($Apk) { $dalArgs += @('-Apk', $Apk) }
& $Shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'dal-check.ps1') @dalArgs
if ($LASTEXITCODE -eq 0) { Add-Result 'dal-check' 'PASS' '' } else { Add-Result 'dal-check' 'FAIL' '' }

Write-Host ''
Write-Host '== check.ps1 summary'
foreach ($r in $results) {
  $c = 'Green'; if ($r.Status -eq 'FAIL') { $c = 'Red' } elseif ($r.Status -eq 'SKIP') { $c = 'Yellow' }
  Write-Host ("{0,-5} {1,-18} {2}" -f $r.Status, $r.Step, $r.Note) -ForegroundColor $c
}
$nf = @($results | Where-Object { $_.Status -eq 'FAIL' }).Count
if ($nf -gt 0) { Write-Host "check.ps1: FAIL ($nf step(s))" -ForegroundColor Red; exit 1 }
Write-Host 'check.ps1: PASS' -ForegroundColor Green
exit 0
