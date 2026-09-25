<#
.SYNOPSIS
  HAND-OWNED guard (TWA-10). Fails if `bubblewrap update` (or anything else) removed hand-owned code.

.DESCRIPTION
  Counts lines containing the fixed marker string (see $Marker) in git-tracked files (git ls-files),
  excluding *.md and tools/**, reading the WORKING TREE. Compares per file with tools/hand-owned-baseline.json.
  Also asserts twa-manifest.json has "shortcuts": [] and minSdkVersion 21.

  Exit 0 = PASS, 1 = FAIL. Prints file paths and counts only, never file contents.
  Read-only, except -UpdateBaseline which rewrites the baseline and can only RAISE counts.

.PARAMETER Root
  Repo root. Default: parent of this script's folder. A non-git folder (test fixture) is scanned recursively.
.PARAMETER Baseline
  Baseline JSON. Default: <Root>\tools\hand-owned-baseline.json.
.PARAMETER UpdateBaseline
  Raise baseline counts to the current counts and add new marker files. Never lowers or removes a row.
.PARAMETER MinSdk
  Expected minSdkVersion in twa-manifest.json (default 21).
#>
[CmdletBinding()]
param(
  [string]$Root,
  [string]$Baseline,
  [switch]$UpdateBaseline,
  [int]$MinSdk = 21
)

$ErrorActionPreference = 'Stop'
$Marker = 'HAND-OWNED (quick-log'

if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = (Resolve-Path -LiteralPath $Root).Path
if (-not $Baseline) { $Baseline = Join-Path $Root 'tools\hand-owned-baseline.json' }

function Get-TrackedFiles([string]$dir) {
  $isGit = $false
  if (Test-Path -LiteralPath (Join-Path $dir '.git')) { $isGit = $true }
  if ($isGit) {
    $list = & git -C $dir ls-files
    if ($LASTEXITCODE -ne 0) { throw "git ls-files failed in $dir" }
    # tools/ is excluded: the guard, its baseline and test fixtures contain the marker string by design.
    return @($list | Where-Object { $_ -and ($_ -notmatch '\.md$') -and ($_ -notmatch '^tools/') })
  }
  # Fixture mode: not a git work tree.
  $base = $dir.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
  return @(Get-ChildItem -LiteralPath $dir -Recurse -File |
    Where-Object { $_.Name -notmatch '\.md$' } |
    ForEach-Object { $_.FullName.Substring($base.Length).Replace('\', '/') })
}

function Get-MarkerCounts([string]$dir) {
  $counts = [ordered]@{}
  foreach ($rel in (Get-TrackedFiles $dir)) {
    $full = Join-Path $dir $rel
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }  # tracked but deleted in working tree
    $n = 0
    foreach ($line in [IO.File]::ReadLines($full)) {
      if ($line.Contains($Marker)) { $n++ }
    }
    if ($n -gt 0) { $counts[$rel] = $n }
  }
  return $counts
}

function Read-Baseline([string]$path) {
  if (-not (Test-Path -LiteralPath $path)) { return $null }
  $j = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
  $files = [ordered]@{}
  foreach ($p in $j.files.PSObject.Properties) { $files[$p.Name] = [int]$p.Value }
  return @{ json = $j; files = $files }
}

function Write-Baseline([string]$path, $files, [string]$commit) {
  $total = 0; foreach ($v in $files.Values) { $total += $v }
  $sorted = [ordered]@{}
  foreach ($k in ($files.Keys | Sort-Object)) { $sorted[$k] = $files[$k] }
  $obj = [ordered]@{
    marker     = $Marker
    definition = 'lines containing marker in git ls-files, excluding *.md and tools/**, working tree'
    commit     = $commit
    totalLines = $total
    totalFiles = $sorted.Count
    files      = $sorted
  }
  $json = $obj | ConvertTo-Json -Depth 5
  [IO.File]::WriteAllText($path, $json + "`n", (New-Object Text.UTF8Encoding($false)))
  return $total
}

$fail = 0
$current = Get-MarkerCounts $Root
$curTotal = 0; foreach ($v in $current.Values) { $curTotal += $v }
$bl = Read-Baseline $Baseline

if (-not $bl) {
  if ($UpdateBaseline) {
    $commit = ''
    try { $commit = (& git -C $Root rev-parse --short HEAD 2>$null) } catch { }
    $t = Write-Baseline $Baseline $current $commit
    Write-Host "BASELINE created: $t lines / $($current.Count) files -> $Baseline"
    exit 0
  }
  Write-Host "FAIL baseline not found: $Baseline" -ForegroundColor Red
  exit 1
}

$blTotal = 0; foreach ($v in $bl.files.Values) { $blTotal += $v }

foreach ($k in $bl.files.Keys) {
  $want = $bl.files[$k]
  $full = Join-Path $Root $k
  if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
    Write-Host "FAIL missing file: $k (baseline $want)" -ForegroundColor Red; $fail++; continue
  }
  $have = 0; if ($current.Contains($k)) { $have = $current[$k] }
  if ($have -lt $want) {
    Write-Host "FAIL $k : $have < baseline $want" -ForegroundColor Red; $fail++
  } elseif ($have -gt $want) {
    Write-Host "INFO $k : $have > baseline $want (raise with -UpdateBaseline in the same commit)"
  }
}
foreach ($k in $current.Keys) {
  if (-not $bl.files.Contains($k)) {
    Write-Host "INFO new marker file: $k ($($current[$k])) (add with -UpdateBaseline)"
  }
}
if ($curTotal -lt $blTotal) {
  Write-Host "FAIL total $curTotal < baseline $blTotal" -ForegroundColor Red; $fail++
}

# twa-manifest.json asserts (hand edits that cannot carry a comment).
$tm = Join-Path $Root 'twa-manifest.json'
if (-not (Test-Path -LiteralPath $tm)) {
  Write-Host "FAIL twa-manifest.json not found" -ForegroundColor Red; $fail++
} else {
  $m = Get-Content -LiteralPath $tm -Raw | ConvertFrom-Json
  $names = @($m.PSObject.Properties.Name)
  if (-not ($names -contains 'shortcuts')) {
    Write-Host 'FAIL twa-manifest.json: "shortcuts" key missing (expected [])' -ForegroundColor Red; $fail++
  } elseif (@($m.shortcuts).Count -ne 0) {
    Write-Host "FAIL twa-manifest.json: shortcuts has $(@($m.shortcuts).Count) entries (expected [])" -ForegroundColor Red; $fail++
  }
  if ([string]$m.minSdkVersion -ne [string]$MinSdk) {
    Write-Host "FAIL twa-manifest.json: minSdkVersion = '$($m.minSdkVersion)' (expected $MinSdk)" -ForegroundColor Red; $fail++
  }
}

if ($UpdateBaseline) {
  if ($fail -gt 0) {
    Write-Host 'REFUSED -UpdateBaseline: fix the failures above first (baseline can only be raised).' -ForegroundColor Red
    exit 1
  }
  $merged = [ordered]@{}
  foreach ($k in $bl.files.Keys) { $merged[$k] = [Math]::Max($bl.files[$k], $(if ($current.Contains($k)) { $current[$k] } else { 0 })) }
  foreach ($k in $current.Keys) { if (-not $merged.Contains($k)) { $merged[$k] = $current[$k] } }
  $commit = [string]$bl.json.commit
  try { $c = (& git -C $Root rev-parse --short HEAD 2>$null); if ($c) { $commit = $c } } catch { }
  $t = Write-Baseline $Baseline $merged $commit
  Write-Host "BASELINE updated: $t lines / $($merged.Count) files"
}

if ($fail -gt 0) {
  Write-Host "HAND-OWNED guard: FAIL ($fail) - current $curTotal lines / $($current.Count) files, baseline $blTotal / $($bl.files.Count)" -ForegroundColor Red
  exit 1
}
Write-Host "HAND-OWNED guard: PASS - $curTotal lines / $($current.Count) files (baseline $blTotal / $($bl.files.Count))" -ForegroundColor Green
exit 0
