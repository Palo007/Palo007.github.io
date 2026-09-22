# Questa Android wrapper — Trusted Web Activity (TWA)

**TL;DR:** This repo is the Android app shell around the Questa PWA (`https://palo007.github.io/Questa/`). It works: **fullscreen TWA, verified App Links, signed sideload APK**, proven on Android 16 (Xiaomi/MIUI, Chrome 153). The single fact to never forget: **the fingerprint in `/.well-known/assetlinks.json` must be UPPERCASE and colon-separated** (`7E:9E:…:B6:8`). Chrome matches it with *raw string equality*; Android's own `pm` verifier normalizes and therefore lies ("verified" while Chrome silently refuses fullscreen). Every other failure seen during bring-up — CDN staleness, MIUI's security scanner, Chrome's cache, Brave — was a red herring stacked on top of that one line.

## 1. What lives where

| Thing | Location | Note |
|---|---|---|
| Domain-root trust file | this repo → `/.well-known/assetlinks.json` | served at `https://palo007.github.io/.well-known/assetlinks.json` (NOT under `/Questa/` — Android checks the domain root only) |
| `.nojekyll` | this repo root | required: GitHub Pages runs Jekyll, which **silently ignores dot-paths** like `.well-known/` |
| TWA project | `app/`, `twa-manifest.json`, Gradle files | Bubblewrap-generated, Android 11 `<queries>` + host-level `autoVerify` intent filter |
| Signing key | `android.keystore` (alias `android`) | **NOT in git. Back it up encrypted; it is the app's identity.** |
| Signed APK | `app-release-signed.apk` | rebuild steps in §5 |
| PWA source | separate repo `Palo007/Questa` (local: `Documents\Opti`) | never modified by wrapper work except agreed one-file changes |

The PWA deploys at `https://palo007.github.io/Questa/`; this repo owns the *domain root* `https://palo007.github.io/` — two different URLs, two different repos, both on the same GitHub Pages host. That split is required: a project repo can never serve files above its `/Questa/` prefix, and Android always fetches the trust file from the **domain** root.

## 2. How fullscreen verification works (the contract)

1. App manifest declares `android:autoVerify="true"` VIEW/BROWSABLE intent filter on `https://palo007.github.io` (host-level, no path prefix) plus an Android-11 `<queries>` element for `CustomTabsService` — without the queries element, the app cannot *see* any TWA-capable browser and silently falls back to Custom Tab mode.
2. On launch, `TWAProviderPicker` asks: which browser offers the TWA trusted service? It picks **Chrome** (`com.android.chrome`) — **regardless of the user's default browser** (Brave lacks the trusted TWA service, yet fullscreen still works with Brave as default).
3. Chrome fetches `https://palo007.github.io/.well-known/assetlinks.json` and compares each statement against the launching package (`io.github.palo007.twa`) using its **signing-cert SHA-256**.
4. Match → Trusted Web Activity: fullscreen, no bar. No match → Custom Tab fallback: **X / address / share / three-dots row** (the symptom this whole document exists to kill).

**The comparison is literal** — Chromium `components/content_relationship_verification/digital_asset_links_handler.cc`, `StatementHasMatchingFingerprint`:

```cpp
if (fingerprint.is_string() && fingerprint.GetString() == target_fingerprint)
```

No case folding, no colon stripping. Android hands Chrome `Signature.toCharsString()` → **uppercase, colon-separated**. The file must therefore be:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "io.github.palo007.twa",
      "sha256_cert_fingerprints": [
        "7E:9E:46:DF:CE:A2:C5:8C:BE:15:3D:64:49:DF:32:28:9D:43:1A:46:70:FF:05:05:B6:2D:2C:CE:4A:26:6B:68"
      ]
    }
  }
]
```

(Extra unknown fields like the `"check": "cb2"` cache-buster used during bring-up are ignored by verifiers and are safe to keep or remove.)

## 3. Root-cause ledger (symptom → cause → fix)

| # | Symptom | Actual root cause | Fix | Commit / where |
|---|---|---|---|---|
| 1 | Trust file 404 at domain root | Jekyll ignores dot-paths; root repo never served it | add `.nojekyll`, serve file at repo root of `Palo007.github.io` | this repo |
| 2 | PC fetch OK, phone fails | GitHub Pages CDN kept serving earlier broken bytes to the device | cache-bust redeploy (marker field in JSON), verify marker live *via the device's path* before testing | `2c7a3f7` |
| 3 | `pm get-app-links` = **verified** but Chrome logs `Statement failure matching fingerprint` | **fingerprint format**: file had lowercase-no-colons; Chrome does raw `string ==` against uppercase-colons; AOSP's verifier normalizes → "verified" | canonical uppercase colon-separated format | `7cb2f21` |
| 4 | Served file truncated to 63 chars while local copy looked correct | nested `user-site/` copy and repo-root copy had diverged | single source of truth: file at repo root | `6a04210`, `36a58ce` |
| 5 | `INSTALL_FAILED_USER_RESTRICTED` via adb | MIUI Security intercepts installs | disable "scan before install" in the Security app, or install via file manager after `adb push` | device setting |
| 6 | `pm get-app-links` stuck at `1024` (denied) after every reinstall | MIUI scan auto-launches app on install → Android records user-denied | `pm clear` → `pm set-app-links` → `pm verify-app-links --re-verify` **before first user launch**; ultimately moot once #3 is fixed | device |
| 7 | Fear that default browser (Brave) blocks fullscreen | Brave advertises CustomTabs but **not** the TWA trusted service | none needed — picker selects Chrome as TWA provider irrespective of default browser | — |

**Debug maxim:** when two verifiers disagree, read the *verifier's source* for its comparison semantics before touching caches, CDNs, or device settings. One look at `StatementHasMatchingFingerprint` would have short-circuited #2/#4/#5/#6.

## 4. Device debug playbook (adb)

```bat
:: verdict (expect: palo007.github.io: verified)
adb shell pm get-app-links io.github.palo007.twa

:: Chrome's own ground truth (pm lies; this does not)
adb logcat -d | findstr digital_asset_links Statement TWAProviderPicker
:: healthy launch shows: "TWAProviderPicker: Found TWA provider: com.android.chrome"
:: and NO: "Statement failure matching fingerprint"

:: what the device actually receives (rules out CDN divergence)
adb shell am start -a android.intent.action.VIEW -d https://palo007.github.io/.well-known/assetlinks.json

:: reset Chrome's stored verdict/cache after any bad-file episode
adb shell pm clear com.android.chrome

:: screenshot / UI hierarchy
adb shell screencap -p /sdcard/f.png & adb pull /sdcard/f.png
adb shell uiautomator dump /sdcard/ui.xml  & adb pull /sdcard/ui.xml
:: healthy TWA: url_bar / "Share" / "Close tab" all absent; PWA nodes present
```

Online cross-check: Digital Asset Links verifier — `https://developers.google.com/digital-asset-links/tools/generator`.

## 5. Rebuilding the APK (Windows quirks that cost hours)

Prereqs: JDK 17 (Eclipse Adoptium), Android SDK cmdline-tools at `%USERPROFILE%\.bubblewrap\android_sdk` (the *legacy bundled* sdkmanager is *incompatible with JDK 17* — install a fresh `commandlinetools` zip and accept licenses with the new `sdkmanager`), then `bubblewrap updateConfig --jdkPath … --androidSdkPath …`.

Known Bubblewrap-on-Windows/Node bugs hit during bring-up:

1. `bubblewrap init` hangs: default fetch engine broken on modern Node → run with `--fetchEngine=node-fetch`; see `_init-driver.js`, which also pipes answers to the interactive prompts.
2. Legacy `sdkmanager` fails on JDK 17 → fresh cmdline-tools (see Prereqs).
3. Bubblewrap's final signing step writes an **unquoted `C:\Program Files\…` path** and dies → sign manually:
   `apksigner sign --ks android.keystore --ks-key-alias android app-release-unsigned.apk --out app-release-signed.apk`
4. Keystore generation: `keytool -genkeypair -keystore android.keystore -alias android -keyalg RSA -keysize 2048 -validity 10000` (passwords stored with the keystore — **never** in git).

Rebuild flow: `_build-driver.js` → Gradle `assembleRelease` → manual apksigner (bug #3) → `apksigner verify --print-certs` must equal the §2 fingerprint **before** installing.

## 6. Open items

- [ ] **Proof B (plan device checklist #3):** two rapid taps on two habits while the TWA is open → two logs, no miss, no double. Not yet recorded; storage continuity was observed informally (profile visible on first launch) but deserves a formal transcript.
- [ ] **Keystore backup:** `android.keystore` + passwords into an encrypted backup. Losing it means losing update capability for any store build.
- [ ] **Play-store readiness** (separate work): privacy policy, Play App Signing, store listing; the `assetstatements` meta-data already embeds the statement the Play verifier expects.
- [ ] **`Palo007/Questa` repo hygiene:** experiment commit `6781237` added `.well-known/` under the `/Questa/` prefix (served nowhere Android reads; harmless). Revert or keep is an open user decision.

## 7. Evidence

Local (git-invisible, `Documents\Opti\.cline\`): `evidence/task-twa-spike-questa-native-wrapper.txt` (full transcript incl. logcat/UI dumps and the final fullscreen screenshot reference), `notepads/questa-native-wrapper/learnings.md` (append-only), `drafts/questa-native-wrapper.md` + `plans/questa-native-wrapper.md` (research + spike procedure). This README is the durable, shared copy of everything above.


