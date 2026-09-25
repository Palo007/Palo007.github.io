<#
.SYNOPSIS
  Digital Asset Links check (TWA-04). Read-only. Exit 0 = PASS, 1 = FAIL.

.DESCRIPTION
  1. Lint local .well-known/assetlinks.json (package, relation, canonical SHA-256 fingerprints).
  2. .nojekyll exists at repo root.
  3. Lint every other git-tracked *assetlinks.json.
  4. Published https://palo007.github.io/.well-known/assetlinks.json equals local (package + fingerprints).
  5. DAL API statements:list returns exactly the local (package, fingerprint) set.
  6. Optional -Apk: the APK's PUBLIC signing cert (apksigner verify --print-certs) is in the local list.

  Fingerprints come only from assetlinks.json files and the DAL API. This script never opens the keystore,
  its password file, or any secret store. -Apk is for the user to run; it reads the APK's public cert only.

.PARAMETER Root       Repo root (default: parent of tools/).
.PARAMETER File       Local assetlinks.json to lint (default: <Root>\.well-known\assetlinks.json).
.PARAMETER Offline    Skip steps 4 and 5.
.PARAMETER LintOnly   Run step 1 only (used by tests).
.PARAMETER PublishedFile  Use this local file as the "published" copy instead of fetching it (tests).
.PARAMETER Apk        APK path for the optional cert compare (user-run only).
#>
[CmdletBinding()]
param(
  [string]$Root,
  [string]$File,
  [switch]$Offline,
  [switch]$LintOnly,
  [string]$PublishedFile,
  [string]$Apk
)

$ErrorActionPreference = 'Stop'
$Package   = 'io.github.palo007.twa'
$Relation  = 'delegate_permission/common.handle_all_urls'
$Site      = 'https://palo007.github.io'
$PubUrl    = "$Site/.well-known/assetlinks.json"
$DalUrl    = "https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=$Site&relation=$Relation"
$FpPattern = '^([0-9A-F]{2}:){31}[0-9A-F]{2}$'

if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
$Root = (Resolve-Path -LiteralPath $Root).Path
if (-not $File) { $File = Join-Path $Root '.well-known\assetlinks.json' }

$script:fails = 0
function Pass([string]$m) { Write-Host "PASS $m" -ForegroundColor Green }
function Fail([string]$m) { Write-Host "FAIL $m" -ForegroundColor Red; $script:fails++ }
function Skip([string]$m) { Write-Host "SKIP $m" -ForegroundColor Yellow }

# Returns @{ ok = bool; errors = string[]; pairs = string[] ("pkg|FP"); check = string }
function Test-AssetLinksText([string]$text) {
  $errors = New-Object System.Collections.Generic.List[string]
  $pairs  = New-Object System.Collections.Generic.List[string]
  $check  = ''
  try { $j = $text | ConvertFrom-Json } catch { return @{ ok = $false; errors = @('invalid JSON'); pairs = @(); check = '' } }
  $stmts = @($j)
  if ($text.TrimStart() -notmatch '^\[') { $errors.Add('top level is not a JSON array') }
  $matched = 0
  foreach ($s in $stmts) {
    $rel = @($s.relation)
    if (-not ($rel -contains $Relation)) { continue }
    $matched++
    $t = $s.target
    if (-not $t) { $errors.Add('statement without target'); continue }
    if ($t.namespace -ne 'android_app') { $errors.Add("namespace '$($t.namespace)' != android_app") }
    if ($t.package_name -cne $Package) { $errors.Add("package_name '$($t.package_name)' != $Package") }
    if ($t.check) { $check = [string]$t.check }
    $fps = @($t.sha256_cert_fingerprints)
    if ($fps.Count -eq 0) { $errors.Add('no sha256_cert_fingerprints') }
    foreach ($fp in $fps) {
      $f = [string]$fp
      if ($f -cnotmatch $FpPattern) {
        $pairsN = ($f -split ':').Count
        $errors.Add("fingerprint not canonical (uppercase hex, 32 colon-separated pairs; got $pairsN group(s), len $($f.Length)): $f")
      }
      $key = "$($t.package_name)|$f"
      if ($pairs.Contains($key)) { $errors.Add("duplicate fingerprint: $f") } else { $pairs.Add($key) }
    }
  }
  if ($matched -eq 0) { $errors.Add("no statement with relation $Relation") }
  return @{ ok = ($errors.Count -eq 0); errors = $errors.ToArray(); pairs = $pairs.ToArray(); check = $check }
}

function Test-AssetLinksFile([string]$path, [string]$label) {
  if (-not (Test-Path -LiteralPath $path)) { Fail "$label : file not found"; return $null }
  $r = Test-AssetLinksText (Get-Content -LiteralPath $path -Raw)
  if ($r.ok) { Pass "$label : lint ok ($($r.pairs.Count) fingerprint(s), check='$($r.check)')" }
  else { foreach ($e in $r.errors) { Fail "$label : $e" } }
  return $r
}

function Compare-Sets([string[]]$a, [string[]]$b) {
  $sa = @($a | Sort-Object -Unique); $sb = @($b | Sort-Object -Unique)
  if ($sa.Count -ne $sb.Count) { return $false }
  for ($i = 0; $i -lt $sa.Count; $i++) { if ($sa[$i] -cne $sb[$i]) { return $false } }
  return $true
}

function Get-Http([string]$url) {
  # No redirect following: a redirect on assetlinks.json breaks verification.
  $p = @{ Uri = $url; UseBasicParsing = $true; MaximumRedirection = 0; TimeoutSec = 30 }
  if ($PSVersionTable.PSVersion.Major -ge 7) { $p['SkipHttpErrorCheck'] = $true }
  try { $r = Invoke-WebRequest @p } catch {
    $resp = $_.Exception.Response
    if ($resp) { return @{ status = [int]$resp.StatusCode; type = ''; body = '' } }
    throw
  }
  $body = $r.Content
  if ($body -is [byte[]]) { $body = [Text.Encoding]::UTF8.GetString($body) }
  return @{ status = [int]$r.StatusCode; type = [string]$r.Headers['Content-Type']; body = [string]$body }
}

Write-Host "DAL check - root $Root"

# 1. local lint
$local = Test-AssetLinksFile $File ('local ' + (Split-Path -Leaf (Split-Path -Parent $File)) + '/' + (Split-Path -Leaf $File))
if ($LintOnly) { if ($script:fails -gt 0) { exit 1 } else { exit 0 } }

# 2. .nojekyll
if (Test-Path -LiteralPath (Join-Path $Root '.nojekyll')) { Pass '.nojekyll present' } else { Fail '.nojekyll missing at repo root (Jekyll would drop .well-known/)' }

# 3. other tracked assetlinks.json
if (Test-Path -LiteralPath (Join-Path $Root '.git')) {
  $others = @(& git -C $Root ls-files | Where-Object { $_ -match 'assetlinks\.json$' -and $_ -ne '.well-known/assetlinks.json' -and $_ -notmatch '^tools/' })
  if ($others.Count -eq 0) { Pass 'no other tracked assetlinks.json' }
  foreach ($o in $others) { [void](Test-AssetLinksFile (Join-Path $Root $o) "tracked $o") }
} else { Skip 'other tracked assetlinks.json (not a git work tree)' }

# 4. published copy
if ($PublishedFile) {
  $pub = Test-AssetLinksText (Get-Content -LiteralPath $PublishedFile -Raw)
  if ($local -and (Compare-Sets $local.pairs $pub.pairs)) { Pass "published (file) matches local" }
  else { Fail "published (file) differs from local" }
} elseif ($Offline) {
  Skip "published $PubUrl (-Offline)"
} else {
  try {
    $h = Get-Http $PubUrl
    if ($h.status -ne 200) { Fail "published: HTTP $($h.status) (expected 200, no redirect)" }
    else {
      if ($h.type -notmatch 'json') { Fail "published: Content-Type '$($h.type)' is not JSON" }
      $pub = Test-AssetLinksText $h.body
      if (-not $pub.ok) { foreach ($e in $pub.errors) { Fail "published: $e" } }
      if ($local -and (Compare-Sets $local.pairs $pub.pairs)) {
        Pass "published matches local (check local='$($local.check)' live='$($pub.check)')"
      } else {
        Fail "published differs from local (check local='$($local.check)' live='$($pub.check)'; CDN cache or not pushed?)"
      }
    }
  } catch { Fail "published: request failed: $($_.Exception.Message)" }
}

# 5. DAL API
if ($Offline -or $PublishedFile) {
  Skip 'DAL API (-Offline)'
} else {
  try {
    $h = Get-Http $DalUrl
    if ($h.status -ne 200) { Fail "DAL API: HTTP $($h.status)" }
    else {
      $d = $h.body | ConvertFrom-Json
      $api = @()
      foreach ($s in @($d.statements)) {
        $a = $s.target.androidApp
        if ($a) { $api += ("$($a.packageName)|$($a.certificate.sha256Fingerprint)") }
      }
      if ($api.Count -eq 0) { Fail "DAL API: no android_app statements (debug: $($d.debugString))" }
      elseif ($local -and (Compare-Sets $local.pairs $api)) { Pass "DAL API matches local ($($api.Count) statement(s))" }
      else { Fail "DAL API differs from local: api=[$($api -join ', ')]" }
    }
  } catch { Fail "DAL API: request failed: $($_.Exception.Message)" }
}

# 6. optional APK public-cert compare (user-run only)
if ($Apk) {
  if (-not (Test-Path -LiteralPath $Apk)) { Fail "APK not found: $Apk" }
  else {
    $sdk = $env:ANDROID_HOME; if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
    $signer = $null
    if ($sdk -and (Test-Path -LiteralPath (Join-Path $sdk 'build-tools'))) {
      $bt = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending | Select-Object -First 1
      if ($bt) { $c = Join-Path $bt.FullName 'apksigner.bat'; if (Test-Path -LiteralPath $c) { $signer = $c } }
    }
    if (-not $signer) { $cmd = Get-Command apksigner -ErrorAction SilentlyContinue; if ($cmd) { $signer = $cmd.Source } }
    if (-not $signer) { Fail 'apksigner not found (set ANDROID_HOME)' }
    else {
      $out = & $signer verify --print-certs $Apk 2>&1
      $digs = @($out | ForEach-Object { $l = [string]$_; if ($l -match 'SHA-256 digest:\s*([0-9a-fA-F]{64})') { $matches[1] } })
      if ($digs.Count -eq 0) { Fail "APK: no SHA-256 cert digest found (apksigner exit $LASTEXITCODE)" }
      foreach ($dg in $digs) {
        $canon = (($dg.ToUpper() -split '(..)' | Where-Object { $_ }) -join ':')
        if ($local -and ($local.pairs -contains "$Package|$canon")) { Pass "APK cert $canon is in assetlinks.json" }
        else { Fail "APK cert $canon is NOT in assetlinks.json" }
      }
    }
  }
}

if ($script:fails -gt 0) { Write-Host "DAL check: FAIL ($($script:fails))" -ForegroundColor Red; exit 1 }
Write-Host 'DAL check: PASS' -ForegroundColor Green
exit 0
