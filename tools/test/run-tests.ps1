<#
.SYNOPSIS
  L1 tests for tools/hand-owned-guard.ps1 and tools/dal-check.ps1.
  Each case runs the script in a child process and asserts its exit code: good = 0, bad = 1.
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

$script:bad = 0
$script:n = 0
function Invoke-Case([string]$name, [int]$expect, [string]$script, [string[]]$scriptArgs) {
  $script:n++
  $out = & $Shell -NoProfile -ExecutionPolicy Bypass -File $script @scriptArgs 2>&1
  $code = $LASTEXITCODE
  $verdict = if ($code -eq 0) { 'PASS' } else { 'FAIL' }
  $why = @($out | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^FAIL|^REFUSED' } | Select-Object -First 1)
  $color = if ($code -eq 0) { 'Green' } else { 'Red' }
  $ok = ($code -eq $expect)
  if (-not $ok) { $script:bad++ }
  $tag = if ($ok) { '[ok]  ' } else { '[XX]  ' }
  $exp = if ($expect -eq 0) { 'expect PASS' } else { 'expect FAIL' }
  Write-Host ($tag + $name.PadRight(38) + " $exp -> ") -NoNewline
  Write-Host $verdict -ForegroundColor $color -NoNewline
  if ($why.Count -gt 0) { Write-Host "  ($($why[0]))" } else { Write-Host '' }
}

Write-Host "== dal-check lint ($Shell)"
Invoke-Case 'assetlinks good'              0 $Dal @('-File', (Join-Path $Al 'good.json'), '-LintOnly')
Invoke-Case 'assetlinks lowercase'         1 $Dal @('-File', (Join-Path $Al 'bad-lowercase.json'), '-LintOnly')
Invoke-Case 'assetlinks 31 pairs'          1 $Dal @('-File', (Join-Path $Al 'bad-31pairs.json'), '-LintOnly')
Invoke-Case 'assetlinks no colons (TWA-31)' 1 $Dal @('-File', (Join-Path $Al 'bad-nocolons.json'), '-LintOnly')
Invoke-Case 'assetlinks wrong package'     1 $Dal @('-File', (Join-Path $Al 'bad-package.json'), '-LintOnly')
# Published-compare cases use a temp root that has .nojekyll, so only the compare decides.
$tmp = Join-Path ([IO.Path]::GetTempPath()) ('p3-tests-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
  $r = Join-Path $tmp 'site'; New-Item -ItemType Directory -Path $r | Out-Null
  New-Item -ItemType File -Path (Join-Path $r '.nojekyll') | Out-Null
  Write-Host '== dal-check local vs published (published = good.json, offline)'
  Invoke-Case 'published = local (good)'     0 $Dal @('-File', (Join-Path $Al 'good.json'), '-Root', $r, '-PublishedFile', (Join-Path $Al 'good.json'))
  Invoke-Case 'one hex char flipped'         1 $Dal @('-File', (Join-Path $Al 'bad-flipped.json'), '-Root', $r, '-PublishedFile', (Join-Path $Al 'good.json'))

  Write-Host '== hand-owned-guard'
  Invoke-Case 'guard good'                   0 $Guard @('-Root', (Join-Path $Fx 'guard-good'), '-Baseline', $Bl)
  Invoke-Case 'guard one marker removed'     1 $Guard @('-Root', (Join-Path $Fx 'guard-bad'), '-Baseline', $Bl)

  # Derived cases on temp copies of guard-good.
  function New-Copy([string]$name) {
    $d = Join-Path $tmp $name
    Copy-Item -LiteralPath (Join-Path $Fx 'guard-good') -Destination $d -Recurse
    Copy-Item -LiteralPath $Bl -Destination (Join-Path $tmp "$name-baseline.json")
    return $d
  }
  $d = New-Copy 'nojekyll-missing'
  Invoke-Case 'dal .nojekyll missing'        1 $Dal @('-File', (Join-Path $Al 'good.json'), '-Root', $d, '-PublishedFile', (Join-Path $Al 'good.json'))

  $d = New-Copy 'missing'; Remove-Item -LiteralPath (Join-Path $d 'app\src\QuickLog.kt')
  Invoke-Case 'guard file missing'           1 $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'missing-baseline.json'))

  $d = New-Copy 'shortcuts'
  Set-Content -LiteralPath (Join-Path $d 'twa-manifest.json') -Value '{ "shortcuts": [ { "name": "x" } ], "minSdkVersion": 21 }'
  Invoke-Case 'guard shortcuts not empty'    1 $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'shortcuts-baseline.json'))

  $d = New-Copy 'minsdk'
  Set-Content -LiteralPath (Join-Path $d 'twa-manifest.json') -Value '{ "shortcuts": [], "minSdkVersion": 19 }'
  Invoke-Case 'guard minSdkVersion 19'       1 $Guard @('-Root', $d, '-Baseline', (Join-Path $tmp 'minsdk-baseline.json'))

  # -UpdateBaseline must refuse to lower.
  $d = Join-Path $tmp 'lower'; Copy-Item -LiteralPath (Join-Path $Fx 'guard-bad') -Destination $d -Recurse
  $lb = Join-Path $tmp 'lower-baseline.json'; Copy-Item -LiteralPath $Bl -Destination $lb
  Invoke-Case 'UpdateBaseline refuses to lower' 1 $Guard @('-Root', $d, '-Baseline', $lb, '-UpdateBaseline')
  $script:n++
  $after = (Get-Content -LiteralPath $lb -Raw | ConvertFrom-Json).files.'app/build.gradle'
  if ($after -eq 2) { Write-Host '[ok]  baseline unchanged after refused update  (app/build.gradle = 2)' }
  else { Write-Host "[XX]  baseline changed to $after" -ForegroundColor Red; $script:bad++ }

  # -UpdateBaseline raises.
  $d = New-Copy 'raise'
  Add-Content -LiteralPath (Join-Path $d 'app\build.gradle') -Value '// HAND-OWNED (quick-log extra)'
  $rb = Join-Path $tmp 'raise-baseline.json'
  Invoke-Case 'UpdateBaseline raises'        0 $Guard @('-Root', $d, '-Baseline', $rb, '-UpdateBaseline')
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
