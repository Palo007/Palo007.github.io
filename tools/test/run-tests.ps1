<#
.SYNOPSIS
  L1 tests for tools/hand-owned-guard.ps1 and tools/dal-check.ps1.
  Each case runs the script in a child process and asserts BOTH its exit code (good = 0, bad = 1) AND that a
  specific expected output line appears (the FAIL reason for bad cases, the PASS line for good ones), so a
  crash cannot satisfy an "expect FAIL" case.
  Offline only; uses fixtures under tools/test/fixtures and a temp copy for derived cases.
.PARAMETER Shell  PowerShell executable for child runs (default: the current host; pass powershell.exe for 5.1).
#>
[CmdletBinding()]
param([string]$Shell)

$ErrorActionPreference = 'Stop'
if (-not $Shell) { $Shell = (Get-Process -Id $PID).Path }
$Tools = Split-Path -Parent $PSScriptRoot
$Fx    = Join-Path $PSScriptRoot 'fixtures'
$Guard = Join-Path $Tools 'hand-owned-guard.ps1'
$Dal   = Join-Path $Tools 'dal-check.ps1'
$Al    = Join-Path $Fx 'assetlinks'
$Bl    = Join-Path $Fx 'guard-baseline.json'
$Good  = Join-Path $Al 'good.json'

$script:bad = 0
$script:n = 0
# $expectText: regex that must match at least one output line.
function Invoke-Case([string]$name, [int]$expect, [string]$expectText, [string]$script, [string[]]$scriptArgs) {
  $script:n++
  $oldEap = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
  try { $out = @(& $Shell -NoProfile -ExecutionPolicy Bypass -File $script @scriptArgs 2>&1 | ForEach-Object { [string]$_ }); $code = $LASTEXITCODE }
  finally { $ErrorActionPreference = $oldEap }
  $verdict = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
  $hit = @($out | Where-Object { $_ -match $expectText } | Select-Object -First 1)
  $color = if ($code -eq 0) { 'Green' } else { 'Red' }
  $ok = ($code -eq $expect) -and ($hit.Count -gt 0)
  if (-not $ok) { $script:bad++ }
  $tag = if ($ok) { '[ok]  ' } else { '[XX]  ' }
  $exp = if ($expect -eq 0) { 'expect PASS' } else { 'expect FAIL' }
  Write-Host ($tag + $name.PadRight(38) + " $exp -> ") -NoNewline
  Write-Host $verdict -ForegroundColor $color -NoNewline
  if ($hit.Count -gt 0) { Write-Host "  ($($hit[0]))" }
  else {
    Write-Host "  (expected text /$expectText/ NOT found; first lines:)" -ForegroundColor Red
    $out | Select-Object -First 3 | ForEach-Object { Write-Host "        $_" }
  }
}

$NC = 'fingerprint not canonical'
Write-Host "== dal-check lint ($Shell)"
Invoke-Case 'assetlinks good'               0 'lint ok \(1 fingerprint'              $Dal @('-File', $Good, '-LintOnly')
Invoke-Case 'assetlinks lowercase'          1 "^FAIL .*$NC.*got 32 group"            $Dal @('-File', (Join-Path $Al 'bad-lowercase.json'), '-LintOnly')
Invoke-Case 'assetlinks 31 pairs'           1 "^FAIL .*$NC.*got 31 group"            $Dal @('-File', (Join-Path $Al 'bad-31pairs.json'), '-LintOnly')
Invoke-Case 'assetlinks no colons (TWA-31)' 1 "^FAIL .*$NC.*got 1 group"             $Dal @('-File', (Join-Path $Al 'bad-nocolons.json'), '-LintOnly')
Invoke-Case 'assetlinks wrong package'      1 "^FAIL .*package_name 'io\.github\.palo007\.twa\.debug'" $Dal @('-File', (Join-Path $Al 'bad-package.json'), '-LintOnly')

# Published-compare cases use a temp root that has .nojekyll, so only the compare decides.
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('p3-tests-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  $r = Join-Path $tmp 'site'; New-Item -ItemType Directory -Path $r | Out-Null
  New-Item -ItemType File -Path (Join-Path $r '.nojekyll') | Out-Null
  Write-Host '== dal-check local vs published (published = good.json, offline)'
  Invoke-Case 'published = local (good)'    0 '^DAL check: PASS'                     $Dal @('-File', $Good, '-Root', $r, '-PublishedFile', $Good)
  Invoke-Case 'one hex char flipped'        1 '^FAIL published \(file\) differs'     $Dal @('-File', (Join-Path $Al 'bad-flipped.json'), '-Root', $r, '-PublishedFile', $Good)

  # -Apk with a junk (non-APK, unsigned) file: apksigner exits non-zero -> must FAIL (m4).
  if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = 'C:\Users\user\AppData\Local\Android\Sdk' }
  $junk = Join-Path $tmp 'junk.apk'; Set-Content -LiteralPath $junk -Value 'not an apk'
  Invoke-Case 'apk: apksigner non-zero'     1 '^FAIL APK: apksigner verify exited [1-9]' $Dal @('-File', $Good, '-Root', $r, '-PublishedFile', $Good, '-Apk', $junk)

  Write-Host '== hand-owned-guard'
  Invoke-Case 'guard good'                  0 '^HAND-OWNED guard: PASS - 3 lines / 2 files' $Guard @('-Root', (Join-Path $Fx 'guard-good'), '-Baseline', $Bl)
  Invoke-Case 'guard one marker removed'    1 '^FAIL app/build\.gradle : 1 < baseline 2'   $Guard @('-Root', (Join-Path $Fx 'guard-bad'), '-Baseline', $Bl)

  # Derived cases on temp copies of guard-good.
  function New-Copy([string]$name) {
    $d = Join-Path $tmp $name
    Copy-Item -LiteralPath (Join-Path $Fx 'guard-good') -Destination $d -Recurse
    Copy-Item -LiteralPath $Bl -Destination (Join-Path $tmp "$name-baseline.json")
    return $d
  }
  $d = New-Copy 'nojekyll-missing'
  Invoke-Case 'dal .nojekyll missing'       1 '^FAIL \.nojekyll missing'             $Dal @('-File', $Good, '-Root', $d, '-PublishedFile', $Good)

  $d = New-Copy 'missing'; Remove-Item -LiteralPath (Join-Path $d 'app\src\QuickLog.kt')
  Invoke-Case 'guard file missing'          1 '^FAIL missing file: app/src/QuickLog\.kt' $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'missing-baseline.json'))

  $d = New-Copy 'shortcuts'
  Set-Content -LiteralPath (Join-Path $d 'twa-manifest.json') -Value '{ "shortcuts": [ { "name": "x" } ], "minSdkVersion": 21 }'
  Invoke-Case 'guard shortcuts not empty'   1 '^FAIL twa-manifest\.json: shortcuts has 1' $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'shortcuts-baseline.json'))

  $d = New-Copy 'minsdk'
  Set-Content -LiteralPath (Join-Path $d 'twa-manifest.json') -Value '{ "shortcuts": [], "minSdkVersion": 19 }'
  Invoke-Case 'guard minSdkVersion 19'      1 "^FAIL twa-manifest\.json: minSdkVersion = '19'" $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'minsdk-baseline.json'))

  # -UpdateBaseline must refuse to lower.
  $d = Join-Path $tmp 'lower'; Copy-Item -LiteralPath (Join-Path $Fx 'guard-bad') -Destination $d -Recurse
  $lb = Join-Path $tmp 'lower-baseline.json'; Copy-Item -LiteralPath $Bl -Destination $lb
  Invoke-Case 'UpdateBaseline refuses to lower' 1 '^REFUSED -UpdateBaseline'        $Guard @('-Root', $d, '-Baseline', $lb, '-UpdateBaseline')
  $script:n++
  $after = (Get-Content -LiteralPath $lb -Raw | ConvertFrom-Json).files.'app/build.gradle'
  if ($after -eq 2) { Write-Host '[ok]  baseline unchanged after refused update  (app/build.gradle = 2)' }
  else { Write-Host "[XX]  baseline changed to $after" -ForegroundColor Red; $script:bad++ }

  # -UpdateBaseline raises.
  $d = New-Copy 'raise'
  Add-Content -LiteralPath (Join-Path $d 'app\build.gradle') -Value '// HAND-OWNED (quick-log extra)'
  $rb = Join-Path $tmp 'raise-baseline.json'
  Invoke-Case 'UpdateBaseline raises'       0 '^BASELINE updated: 4 lines / 2 files'  $Guard @('-Root', $d, '-Baseline', $rb, '-UpdateBaseline')
  $script:n++
  $after = (Get-Content -LiteralPath $rb -Raw | ConvertFrom-Json)
  if ($after.files.'app/build.gradle' -eq 3 -and $after.totalLines -eq 4) { Write-Host '[ok]  baseline raised to 3 (total 4)' }
  else { Write-Host "[XX]  baseline not raised: $($after.files.'app/build.gradle')" -ForegroundColor Red; $script:bad++ }
} finally {
  Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

if ($script:bad -gt 0) { Write-Host "run-tests: $($script:bad) of $($script:n) expectations NOT met" -ForegroundColor Red; exit 1 }
Write-Host "run-tests: all $($script:n) expectations met" -ForegroundColor Green
exit 0
