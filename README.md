# Questa Android wrapper — Trusted Web Activity (TWA)

**TL;DR:** This repo is the Android app shell around the Questa PWA (`https://palo007.github.io/Questa/`). It works: **fullscreen TWA, verified App Links, signed sideload APK, headless quick-log shortcuts, background reminders**, proven on Android 16 (Xiaomi/MIUI, Chrome 153). The single fact to never forget for fullscreen: **the fingerprint in `/.well-known/assetlinks.json` must be UPPERCASE and colon-separated**. Chrome matches it with *raw string equality*; Android's own `pm` verifier normalizes and therefore lies.

## 1. What this is

This repo wraps the Questa PWA (source: sibling repo `Palo007/Questa`, local `Documents\Opti`, served at `https://palo007.github.io/Questa/`) in a Bubblewrap-generated Trusted Web Activity so it installs and behaves like a native Android app — plus a small amount of hand-written native code (`app/src/main/java/io/github/palo007/twa/`, package `io.github.palo007.twa`) that the PWA cannot do for itself.

```
   Questa PWA (Opti repo)              Dropbox app folder                Android native code
   https://palo007.github.io/   <-->   /inbox/*.json               <-->  (this repo)
   /Questa/  (sync.js, app.js)         /inbox-claimed/<deviceId>/         quicklog/*.kt
                                        /inbox-rejected/*.json             DelegationService.java
                                        /inbox-meta/habits.json            LauncherActivity.java
                                        /inbox-meta/reminders.json
```

Why native code exists at all: a **closed web page cannot fire notifications or log a habit tap headlessly** — a PWA's JS timers and Notification API die with the tab/service-worker, so it cannot reliably wake up in the background to ring an alarm or record a long-press. Conversely, **native Android code cannot read the browser's storage** (`localStorage`/IndexedDB the PWA keeps its state in) — there is no API for one app to reach into another's browser profile. Dropbox's app folder is the one channel both sides can reach, so the two communicate only through small JSON files there (contracts in §3). This repo's git remote is `Palo007/Palo007.github.io`, a **public** GitHub Pages user site, which is also where the domain-root trust file below is served from.

## 2. Features

- **Fullscreen TWA** with verified Android App Links (no browser chrome) — see §3 below for the verification contract.
- **Headless quick-log**: long-press the Questa launcher icon → tap a habit → a toast confirms, Questa never opens. The tap is queued, uploaded to Dropbox, and credited by the PWA on its next sync.
- **Background reminders**: `AlarmManager`-scheduled notifications fire even when Questa is closed, swiped away, or the phone has rebooted, using a schedule the PWA publishes to Dropbox.

## 3. Fullscreen verification contract

1. App manifest declares `android:autoVerify="true"` VIEW/BROWSABLE intent filter on `https://palo007.github.io` (host-level, no path prefix) plus an Android-11 `<queries>` element for `CustomTabsService` — without the queries element, the app cannot *see* any TWA-capable browser and silently falls back to Custom Tab mode.
2. On launch, `TWAProviderPicker` asks: which browser offers the TWA trusted service? It picks **Chrome** (`com.android.chrome`) — **regardless of the user's default browser** (Brave lacks the trusted TWA service, yet fullscreen still works with Brave as default).
3. Chrome fetches `https://palo007.github.io/.well-known/assetlinks.json` (domain root, **not** `/Questa/` — served by this repo, not the PWA repo, because Android only ever checks the domain root and Jekyll silently drops dot-paths without `.nojekyll`) and compares each statement against the launching package (`io.github.palo007.twa`) using its **signing-cert SHA-256**.
4. Match → Trusted Web Activity: fullscreen, no bar. No match → Custom Tab fallback: **X / address / share / three-dots row**.

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

### Root-cause ledger (symptom → cause → fix)

| # | Symptom | Actual root cause | Fix | Commit / where |
|---|---|---|---|---|
| 1 | Trust file 404 at domain root | Jekyll ignores dot-paths; root repo never served it | add `.nojekyll`, serve file at repo root of `Palo007.github.io` | this repo |
| 2 | PC fetch OK, phone fails | GitHub Pages CDN kept serving earlier broken bytes to the device | cache-bust redeploy (marker field in JSON), verify marker live *via the device's path* before testing | `2c7a3f7` |
| 3 | `pm get-app-links` = **verified** but Chrome logs `Statement failure matching fingerprint` | **fingerprint format**: file had lowercase-no-colons; Chrome does raw `string ==` against uppercase-colons; AOSP's verifier normalizes → "verified" | canonical uppercase colon-separated format | `7cb2f21` |
| 4 | Served file truncated to 63 chars while local copy looked correct | nested `user-site/` copy and repo-root copy had diverged | single source of truth: file at repo root | `6a04210`, `36a58ce` |
| 5 | `INSTALL_FAILED_USER_RESTRICTED` via adb | MIUI Security intercepts installs | disable "scan before install" in the Security app, or install via file manager after `adb push` | device setting |
| 6 | `pm get-app-links` stuck at `1024` (denied) after every reinstall | MIUI scan auto-launches app on install → Android records user-denied | `pm clear` → `pm set-app-links` → `pm verify-app-links --re-verify` **before first user launch**; ultimately moot once #3 is fixed | device |
| 7 | Fear that default browser (Brave) blocks fullscreen | Brave advertises CustomTabs but **not** the TWA trusted service | none needed — picker selects Chrome as TWA provider irrespective of default browser | — |

**Debug maxim:** when two verifiers disagree, read the *verifier's source* for its comparison semantics before touching caches, CDNs, or device settings.

Online cross-check: Digital Asset Links verifier — `https://developers.google.com/digital-asset-links/tools/generator`.

## 4. Contracts between the PWA and the Android app

All of these live in the **same Dropbox app folder** both sides authenticate into (Dropbox Android SDK PKCE, same app key as the PWA — a public PKCE client id, already public in `sync.js`). Verified against the current code on both sides.

### 4.1 Headless quick-log — `/inbox/<uuid>.json`

- **Writer:** phone (`QuickLogActivity` → `InboxUploadWorker`, upload mode `add`, never overwrite).
- **Reader/claimer:** PWA (`sync.js` `syncInboxConsume`), on every sync.
- **Shape (frozen, v1):**
  ```json
  {"v":1,"id":"<uuid>","kind":"habit","habitId":"<task id>","dir":1,"ts":<ms epoch>,"tzOffsetMin":<int>,"src":"android-shortcut"}
  ```
- **Claim:** the PWA claims a file with Dropbox `files/move_v2` from `/inbox/<id>.json` to `/inbox-claimed/<deviceId>/<id>.json`. Only one device can win the move (loser gets `not_found`, i.e. `dbxMove` returns `false`); on each sync a device first re-processes anything already in its **own** `/inbox-claimed/<deviceId>/` (crash recovery).
- **Reject:** a record that fails `inboxParseRecord` (bad shape, filename doesn't match `<id>.json`, unknown/not-yet-merged habit that will never appear) is moved to `/inbox-rejected/<name>.json` instead of being retried forever.
- **Finish:** the claimed file is deleted only **after** the state upload containing the applied log succeeded (`syncInboxFinish`) — never before, so a crash mid-round just gets it re-processed, and idempotency (`evtHasUid("inbox-"+id)`) turns that into a no-op finish rather than a double count.

### 4.2 Habit list for shortcuts — `/inbox-meta/habits.json`

- **Writer:** PWA (`syncInboxWriteMeta`), after every sync where the list changed (hash-gated, upload-only-on-change).
- **Reader:** phone (`ShortcutRefreshWorker` / `HabitList.kt` `parseHabitList`).
- **Shape (v1):**
  ```json
  {"v":1,"updatedAt":<ms>,"habits":[{"id":"...","title":"...","quickLog":true,"dirs":[1,-1]}]}
  ```
- A habit can publish a `+1` shortcut, a `-1` shortcut, or both, per its `dirs`. Old files with no `dirs` fall back to `quickLog ? [1] : []`. A file that doesn't parse as v1 returns `null` on the phone and **never wipes the existing menu**.

### 4.3 Reminder schedule — `/inbox-meta/reminders.json`

- **Writer:** PWA (`syncInboxWriteReminders`), same hash-gated upload-only-on-change pattern.
- **Reader:** phone (`ReminderSyncWorker` / `ReminderList.kt` `parseReminderList`).
- **Shape (v1):**
  ```json
  {"v":1,"updatedAt":<ms>,"items":[
    {"key":"<taskId>#<n>","taskId":"<id>","type":"habit|daily|todo",
     "title":"...","body":"...","time":"HH:MM",
     "date":"YYYY-MM-DD"|null,"days":[7 booleans, 0=Sunday]|null}]}
  ```
  Title/body come from the PWA's own `getReminderNotificationPayload`, so native and web notifications read identically. A file that doesn't parse as v1 returns `null` and **never wipes existing alarms**.

### 4.4 Start URL param `nr=1` — native-ready signal

- **Writer:** phone. `LauncherActivity.getLaunchingUrl()` appends `?nr=1` to the TWA start URL only when `ReminderScheduler.nativeReady()` is true (Dropbox connected, a parsed reminder list is stored, notifications enabled — exact-alarm permission is *not* required).
- **Reader:** PWA. `parseNativeRemindersParam` strips `nr=1` off `location.search` (preserving other deep-link params like `?tab=`) and, if present, sets `sessionStorage['questa.nativeReminders']='1'` (`nativeRemindersActive()` reads it back). **Currently this flag is read but not acted on**: `checkReminders()` fires both due and missed reminders on the web regardless, because the phone's copy of the schedule can be stale (a reminder created minutes ago) — muting the web risked losing reminders. Deduplication instead happens entirely on the Android side (§4.5).

### 4.5 Notification dedupe — slot identity `title|yyyy-MM-dd|HH:MM`

Because both the native alarm (`ReminderReceiver`) and the web app (delegated through `DelegationService`, which Chrome uses to post TWA notifications on the app's behalf) can show the same due reminder, `ReminderDedupe.kt` tracks per-slot "shown" records keyed by `title|yyyy-MM-dd|HH:MM` (phone-local date/time), separately for native (`markNative`/`nativeShown`) and web (`markWeb`/`webShown`) so neither side clobbers the other's record. Records older than 2 days are pruned on every write.

- `ReminderReceiver` computes the slot from the alarm's item time on **today's** date, skips posting only if `webShown` is already true, otherwise posts and calls `markNative`.
- `DelegationService.onNotifyNotificationWithChannel` reads the notification's `EXTRA_TEXT`: a body matching `^Missed at (\d\d:\d\d)` yields that time as the one candidate slot (see §4.6); otherwise both "now" and "now-1min" are tried as candidates, to absorb the gap between the native alarm firing and this delegated call arriving. If any candidate slot was already `nativeShown`, the web copy is dropped; otherwise all candidates are `markWeb`'d.
- Only titles present in the stored reminder list are ever deduped, so the web's own "Questa Test" button notification is never dropped.

### 4.6 Missed-reminder body prefix — `Missed at HH:MM - `

A PWA cannot fire a notification while its page/tab is fully closed, so a reminder slot that passed while Questa was shut is reported late, exactly once, on next open. The web's `getReminderNotificationPayload(t, r, missed=true)` (`Opti/app.js`) prepends `'Missed at ' + r.time + ' - '` to the body. This exact prefix is what `ReminderDedupe.parseMissedTime` on the Android side parses back out to recover the intended slot for dedupe (§4.5) — **do not change this string shape** without updating both sides.

## 5. Code map

| File | Role |
|---|---|
| `app/src/main/java/io/github/palo007/twa/Application.java` | App entry point; calls `QuickLogBootstrap.onAppStart` |
| `app/src/main/java/io/github/palo007/twa/LauncherActivity.java` | TWA launcher; appends `?nr=1` when native reminders are ready; kicks off `ReminderSyncWorker.enqueueNow` |
| `app/src/main/java/io/github/palo007/twa/DelegationService.java` | Handles delegated notifications from Chrome; drops a web reminder already shown natively |
| `quicklog/InboxRecord.kt` | The `/inbox/<uuid>.json` record: build + validate + JSON-encode |
| `quicklog/InboxStore.kt` | Local on-disk queue (`filesDir/inbox/`) surviving reboot/update |
| `quicklog/InboxUploadWorker.kt` | WorkManager job: uploads queued taps to Dropbox `/inbox/<id>.json` |
| `quicklog/QuickLogActivity.kt` | Translucent, no-history activity behind the long-press shortcuts; writes + enqueues a tap, never opens Questa |
| `quicklog/ShortcutPublisher.kt` | Publishes/refreshes the dynamic `ql-*` long-press shortcuts (cap 3) |
| `quicklog/ShortcutRefreshWorker.kt` | Downloads `/inbox-meta/habits.json` and republishes shortcuts |
| `quicklog/HabitList.kt` | Parses `/inbox-meta/habits.json` (v1, null-on-bad-file) |
| `quicklog/ReminderList.kt` | Parses `/inbox-meta/reminders.json` and computes next fire time (`nextFireMs`) |
| `quicklog/ReminderSyncWorker.kt` | Downloads reminders, keeps last-good JSON in prefs, triggers `ReminderScheduler.rearm` |
| `quicklog/ReminderScheduler.kt` | Owns all `AlarmManager` alarms for reminder items; exact vs inexact scheduling |
| `quicklog/ReminderReceiver.kt` | Fires on alarm; re-reads current text by key; posts/skips per dedupe; re-arms next slot |
| `quicklog/ReminderBootReceiver.kt` | Re-arms alarms from prefs on `BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED` |
| `quicklog/ReminderDedupe.kt` | Pure slot-key helpers + prefs-backed native/web "already shown" records |
| `quicklog/DropboxAuth.kt` | Dropbox Android SDK PKCE login; credential sealed with an AES-GCM Android Keystore key |
| `quicklog/SettingsActivity.kt` | Connect/disconnect Dropbox, queue status, reminder status, permission-request buttons |
| `quicklog/QuickLogBootstrap.kt` | Wires periodic workers on app start / Dropbox connect |
| `app/src/test/java/io/github/palo007/twa/quicklog/*.kt` | JVM unit tests for the pure helpers (`gradlew testDebugUnitTest`) |

## 6. Build, sign, install (Windows)

**Prereqs:** JDK 17 (Eclipse Adoptium, e.g. `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`), Android SDK cmdline-tools at `%USERPROFILE%\.bubblewrap\android_sdk` (the *legacy bundled* sdkmanager is *incompatible with JDK 17* — install a fresh `commandlinetools` zip and accept licenses with the new `sdkmanager`), then `bubblewrap updateConfig --jdkPath … --androidSdkPath …`.

**Debug build + unit tests:**
```bat
gradlew.bat testDebugUnitTest assembleDebug
```

**Release build + sign**, two supported paths:
- `build-signed-apk.bat` — cleans, runs unit tests, `assembleRelease`, zipaligns, signs, verifies, copies to `builds\Questa-v<versionName>-<versionCode>-<timestamp>.apk`. Password: if a `keystore.pass` file exists next to the script (first line = password, **git-ignored, never commit**), it's used automatically; otherwise `apksigner` prompts interactively.
- `sign-release.ps1` — same signing steps, but the password lives **encrypted with Windows DPAPI**, outside the repo, at `%USERPROFILE%\.questa-secrets\android-keystore-password.dpapi` (never in git). Save it once with `-SavePassword` (prompts interactively, never echoes it back); then `-Build` runs Gradle + align + sign + verify, or run with no flags to just re-sign the last build. DPAPI is tied to this Windows account/profile, so keep a second copy of the password in a password manager.

Output APKs land in `builds\`. Install with:
```bat
adb install -r builds\Questa-v<...>.apk
```

**Signature mismatch note:** a **debug**-signed APK cannot be installed over a **release**-signed install of the same package (or vice versa) — Android refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first if you need to switch build types on the same device.

### Windows/Bubblewrap quirks that cost hours

1. `bubblewrap init` hangs: default fetch engine broken on modern Node → run with `--fetchEngine=node-fetch`.
2. Legacy `sdkmanager` fails on JDK 17 → install fresh cmdline-tools (see Prereqs).
3. Bubblewrap's final signing step writes an **unquoted `C:\Program Files\…` path** and dies → sign manually with `apksigner` (as the scripts above do).
4. Keystore generation (only if starting fresh): `keytool -genkeypair -keystore android.keystore -alias android -keyalg RSA -keysize 2048 -validity 10000`. `android.keystore` is **git-ignored, never commit** — it is the app's identity; losing it means losing update capability forever.

## 7. Permissions and phone setup

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` (API 33+) | Required to show reminder/quick-log toasts and notifications at all |
| `USE_EXACT_ALARM` | Sideloaded apps (not distributed via Play) are granted this automatically at install, no runtime prompt, on API 33+ |
| `SCHEDULE_EXACT_ALARM` (capped `android:maxSdkVersion="32"`) | Covers API 31–32 only, where `USE_EXACT_ALARM` does not apply; without it or `USE_EXACT_ALARM`, alarms silently fall back to an inexact ~1 h window even with an item due in 5 minutes |
| `RECEIVE_BOOT_COMPLETED` | Lets `ReminderBootReceiver` re-arm alarms after reboot without needing the network |

**MIUI/HyperOS device setup** (observed necessary on Xiaomi/POCO): enable **Autostart** for Questa, and set battery usage to **"No restrictions"** — otherwise the OS kills the background workers/alarms outside its own idea of normal use. Also disable "scan before install" under Security if sideload installs are being blocked (root-cause ledger #5).

**Dropbox connect:** open Android Settings → Apps → Questa → the app's own settings (gear icon, `quicklog.SettingsActivity`) to connect Dropbox, see queue size, last upload, and reminder status (count scheduled, last checked, exact-alarm permission), and to request the exact-alarm / notification permissions if not yet granted.

## 8. Troubleshooting

```bat
:: verdict (expect: palo007.github.io: verified)
adb shell pm get-app-links io.github.palo007.twa

:: Chrome's own ground truth (pm lies; this does not)
adb logcat -d | findstr digital_asset_links Statement TWAProviderPicker

:: reminder/quick-log native logging (Log.i tag "QuestaReminder")
adb logcat -s QuestaReminder

:: armed alarms — look for window=0 in the entry, which means exact
adb shell dumpsys alarm | findstr io.github.palo007.twa

:: is the exact-alarm op actually allowed for this app right now
adb shell appops get io.github.palo007.twa SCHEDULE_EXACT_ALARM

:: app standby bucket — a restricted bucket delays/batches alarms and jobs
adb shell am get-standby-bucket io.github.palo007.twa

:: published long-press shortcuts / clear the launcher rate limit
adb shell dumpsys shortcut | findstr ql-
adb shell cmd shortcut reset-throttling io.github.palo007.twa
```

**adb over Wi-Fi** (no cable needed once paired):
```bat
adb tcpip 5555
adb connect <phone-ip>:5555
```
(`<phone-ip>` is a placeholder — use the device's actual LAN IP from its Wi-Fi settings.)

**Known limit:** a reminder created or changed on the web only reaches the phone's native alarms when Questa is next opened on that device, or within ~15 minutes via the periodic background sync worker — it is not instant.

## 9. `bubblewrap update` survival

`bubblewrap update` regenerates Bubblewrap-owned files and will silently drop hand-written changes unless they're re-applied. Every hand edit carries the marker `HAND-OWNED (quick-log)`; check what's still in place with:
```bash
grep -rn "HAND-OWNED (quick-log)" build.gradle app/build.gradle app/src app/proguard-rules.pro
```

| File | What was added |
|---|---|
| `build.gradle` | Kotlin Gradle plugin `2.1.21` classpath, `mavenCentral()` |
| `app/build.gradle` | Kotlin plugin; top-level `kotlin { jvmTarget 1.8 }`; deps (Dropbox SDK 7.0.0, WorkManager 2.10.5, core 1.16.0, junit, org.json for tests); `proguardFiles 'proguard-rules.pro'`; `shortcuts:` reduced to Quick log |
| `twa-manifest.json` | `shortcuts` reduced to Quick log (so `bubblewrap update` does not bring Habits/Dailies back) |
| `app/src/main/AndroidManifest.xml` | `INTERNET` permission; `APPLICATION_PREFERENCES` filter moved to `quicklog.SettingsActivity`; `HAND-OWNED ... END HAND-OWNED` block with `QuickLogActivity`, `SettingsActivity`, Dropbox `AuthActivity`; `RECEIVE_BOOT_COMPLETED` + `SCHEDULE_EXACT_ALARM` (capped API 32) + `USE_EXACT_ALARM`; `ReminderReceiver` (not exported), `ReminderBootReceiver` (exported, boot/time/timezone/replace only) |
| `app/src/main/java/.../Application.java` | one line: `QuickLogBootstrap.onAppStart(this)` |
| `app/proguard-rules.pro` | `-dontwarn` for optional Dropbox SDK classes; a comment recording that release builds keep `Log.i` (no `-assumenosideeffects` strip) |
| `app/src/main/java/io/github/palo007/twa/quicklog/*.kt` | all quick-log + reminder code (new) |
| `app/src/main/java/io/github/palo007/twa/LauncherActivity.java` | `?nr=1` append + `ReminderSyncWorker.enqueueNow` + `nativeReady` logging |
| `app/src/main/java/io/github/palo007/twa/DelegationService.java` | `onNotifyNotificationWithChannel` override for dedupe; `enqueueNow` on create |
| `app/src/test/java/io/github/palo007/twa/quicklog/*.kt` | JVM unit tests (new) |

Versions are pinned to keep **minSdk 21**: `dropbox-android-sdk` 8.x needs 26, `work-runtime` 2.11+ needs 23/24, `androidx.core` 1.17+ needs 23. Raise them only together with minSdk.

Note: `.gitignore` ignores `/app`, `build.gradle` and `twa-manifest.json`. New files are not in git unless added with `git add -f`.

## 10. History

- **Bring-up (pre-Phase-1):** fullscreen TWA verification fixed (§3 root-cause ledger); signed sideload APK working.
- **Phase 1A (2026-09-24):** headless quick-log design — `/inbox/<uuid>.json`, claim-by-move, PWA-side consumer.
- **Phase 1B:** quick-log shipped — long-press shortcuts, `InboxUploadWorker`, `ShortcutRefreshWorker` reading `/inbox-meta/habits.json`, Dropbox connect screen.
- **Phase 1C:** background reminders added — `/inbox-meta/reminders.json`, `ReminderScheduler`/`ReminderReceiver`/`ReminderBootReceiver`, `?nr=1` web/native silence flag (later revised, see 1D/4.4).
- **Phase 1D:** dedupe introduced (title + 3-minute window) so native and web didn't double-notify; periodic reminder sync tightened to 15 min.
- **Phase 1E (2026-09-24):** `USE_EXACT_ALARM` added after finding `SCHEDULE_EXACT_ALARM` wasn't auto-granted on API 34+ HyperOS; dedupe rewritten from a time-window heuristic to exact **slot identity** (`title|yyyy-MM-dd|HH:MM`) with separate native/web records; `QuestaReminder` logging added throughout.

Local, git-invisible evidence trail (`Documents\Opti\.cline\`): `evidence/task-twa-spike-questa-native-wrapper.txt`, `notepads/questa-native-wrapper/learnings.md`, `drafts/` + `plans/questa-native-wrapper.md`. This README is the durable, shared copy of everything above.

## 11. Open items

- [ ] **Proof B:** two rapid taps on two habits while the TWA is open → two logs, no miss, no double. Not yet formally recorded.
- [ ] **Keystore backup:** `android.keystore` + passwords into an encrypted backup. Losing it means losing update capability for any store build.
- [ ] **Play-store readiness** (separate work): privacy policy, Play App Signing, store listing; the `assetstatements` meta-data already embeds the statement the Play verifier expects.
- [ ] **Owner device checklist** (plan `android-inbox-step1.md` todos 14, 17): reminder in 5 min, lock phone, swipe app away, reboot, MIUI Autostart/Battery settings.
