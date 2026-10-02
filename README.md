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

Why native code exists at all: a **closed web page cannot fire notifications or log a habit tap headlessly** — a PWA's JS timers and Notification API die with the tab/service-worker, so it cannot reliably wake up in the background to ring an alarm or record a long-press. Conversely, **native Android code cannot read the browser's storage** (`localStorage`/IndexedDB the PWA keeps its state in) — there is no API for one app to reach into another's browser profile. Dropbox's app folder is the one channel both sides can reach, so the two communicate only through small JSON files there (contracts in §4). This repo's git remote is `Palo007/Palo007.github.io`, a **public** GitHub Pages user site, which is also where the domain-root trust file below is served from.

**Role (decision 2026-09-26):** this TWA stays long-term as a **sideloaded backup Android app**. It does not go to Google Play. The main Android app is the native Kotlin app (see §10).

### Where your data lives (read this)

- **Chrome owns Questa's data.** The PWA keeps its state in Chrome's site storage (`localStorage`/IndexedDB) for `palo007.github.io`. The APK holds no copy.
- **Clearing Chrome data wipes Questa.** "Clear data" for Chrome, a Chrome uninstall or reset, or deleting the site data of `palo007.github.io` erases everything that is not yet in Dropbox. Android's "Clear storage" for Questa opens Chrome's site settings for the same data.
- **`allowBackup` does not cover it.** `android:allowBackup="true"` backs up only the TWA's own native files. It never includes Chrome's storage.
- **The Dropbox login is not backed up.** The backup rules (`res/xml/backup_rules.xml` for API 23–30, `res/xml/data_extraction_rules.xml` for API 31+) exclude the prefs file `questa_quicklog.xml` (Dropbox credential plus the reminder and habit cache). They also exclude `questa_reminders.xml` (the "Native reminders" switch and the one-time question, DL-31), so a new phone starts with the switch on and asks again. After a restore or a phone transfer, connect Dropbox again once; the cache fills again on the next sync. The upload queue (`files/inbox/*.json`) is backed up, so queued taps are kept (DL-15).
- **The only backups are Dropbox sync and the JSON export.** Sync often, and use **Export** in the PWA often. Keep the export file outside Chrome (Downloads and a copy off the phone).
- **Open Questa once while online after install.** The first launch needs the network: the PWA service worker is not cached yet, and Chrome fetches the trust file (§3). After that, the service worker serves the app offline.
- **No background execution on the web side.** A closed page cannot run code. Reminders and quick-log work in the background only because of the native code above.
- **If Chrome is off or missing**, Questa opens in another browser (for example Brave), which has its own, separate storage, so your data seems gone. The app shows a one-time Toast: "Chrome is off or missing. Questa opens in another browser with separate storage, so your data is not there. Turn Chrome back on." (DL-08, `quicklog/ProviderNotice.kt`). Turn Chrome back on; the data is still in Chrome.

## 2. Features

- **Fullscreen TWA** with verified Android App Links (no browser chrome) — see §3 below for the verification contract.
- **Headless quick-log**: long-press the Questa launcher icon → tap a habit → a toast confirms, Questa never opens. The tap is queued, uploaded to Dropbox, and credited by the PWA on its next sync.
- **Background reminders**: `AlarmManager`-scheduled notifications fire even when Questa is closed, swiped away, or the phone has rebooted, using a schedule the PWA publishes to Dropbox.

## 3. Fullscreen verification contract

1. App manifest declares `android:autoVerify="true"` VIEW/BROWSABLE intent filter on `https://palo007.github.io`, limited to the Questa paths (`android:path="/Questa"` and `android:pathPrefix="/Questa/"`, TWA-19), plus an Android-11 `<queries>` element for `CustomTabsService` — without the queries element, the app cannot *see* any TWA-capable browser and silently falls back to Custom Tab mode.
2. On launch, `TWAProviderPicker` asks: which browser offers the TWA trusted service? It picks **Chrome** (`com.android.chrome`) — **regardless of the user's default browser** (Brave lacks the trusted TWA service, yet fullscreen still works with Brave as default).
3. Chrome fetches `https://palo007.github.io/.well-known/assetlinks.json` (domain root, **not** `/Questa/` — served by this repo, not the PWA repo, because Android only ever checks the domain root and Jekyll silently drops dot-paths without `.nojekyll`) and compares each statement against the launching package (`io.github.palo007.twa`) using its **signing-cert SHA-256**.
4. Match → Trusted Web Activity: fullscreen, no bar. No match → Custom Tab fallback: **X / address / share / three-dots row**.

App Link scope: the app opens only `https://palo007.github.io/Questa` and `/Questa/…`. Other paths on the same host (for example another GitHub Pages repo) open in the browser. `bubblewrap update` drops the two `<data path…>` lines (HAND-OWNED, §9), so add them again.

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

Note on row #4: the old copy `user-site/.well-known/assetlinks.json` is still tracked and still published at `https://palo007.github.io/user-site/.well-known/assetlinks.json`. Android never reads it. Since P8 it holds the same canonical fingerprint string as the root file (DL-14, DL-20). Do not delete it without a decision: this repo is the public Pages site.

**Debug maxim:** when two verifiers disagree, read the *verifier's source* for its comparison semantics before touching caches, CDNs, or device settings.

Online cross-check: Digital Asset Links verifier — `https://developers.google.com/digital-asset-links/tools/generator`.

**Do not touch (while any phone has the TWA installed):**
- Do not delete, move or edit `.well-known/assetlinks.json` or `.nojekyll` at the root of this repo. If you do, every installed TWA shows the URL bar again.
- If a new app needs App Links for `palo007.github.io`, **add** a second statement to the array. Never replace the existing one.
- Run the read-only checks after each change and before each signed build (no signing, no secrets):

```powershell
pwsh tools\check.ps1              # env, assembleDebug, testDebugUnitTest, HAND-OWNED guard, DAL check
pwsh tools\check.ps1 -NoBuild     # env + guard + DAL check only (pre-check before build-signed-apk.bat)
pwsh tools\check.ps1 -Offline     # skip the published-file and DAL API network checks
pwsh tools\dal-check.ps1 -Apk app-release-signed.apk   # user-run: compare the APK's public cert with assetlinks.json
pwsh tools\test\run-tests.ps1     # self-test of the check scripts
```

`tools/dal-check.ps1` lints the fingerprint format, compares the published file with the local one, queries the Digital Asset Links API and checks `.nojekyll`. The HAND-OWNED guard is in §9.

## 4. Contracts between the PWA and the Android app

All of these live in the **same Dropbox app folder** both sides authenticate into (Dropbox Android SDK PKCE, same app key as the PWA — a public PKCE client id, already public in `sync.js`). Verified against the current code on both sides.

**One Dropbox app key for web, TWA and Kotlin.** The PWA, this TWA's native code and the Kotlin app all use the same Dropbox app (one public PKCE key). The key is public by design, so a leak is not the risk. The risk is one switch for all three:
- Revoking "Questa" under Dropbox > Settings > Connected apps logs out **all three** clients at once.
- Deleting the Dropbox app, or changing its permission scopes, breaks all three.
- "Disconnect" in the TWA settings screen revokes only the TWA's own token. The other clients keep working.
- Uninstalling the TWA does **not** revoke its token on the Dropbox server. Revoke it in Dropbox if you must.
- All redirect URIs (web, `db-<key>`, and the Kotlin app's own redirect) are on the same app in the Dropbox console. The Kotlin redirect is recorded as `questa://oauth` (to verify: it is not in this repo; check the Kotlin repo).
- Recovery after a revoke: connect Dropbox again in each client (web sync panel, TWA settings screen, Kotlin settings).

### 4.1 Headless quick-log — `/inbox/<uuid>.json`

- **Writer:** phone (`QuickLogActivity` → `InboxUploadWorker`, upload mode `add`, never overwrite).
- **Upload retries on the phone (DL-11, DL-24, DL-29; `UploadPolicy` in `InboxUploadWorker.kt`):** network errors, rate limits (429), server errors (5xx), token and access problems, a full Dropbox and "too many writes" never count; they retry with back-off. A name Dropbox refuses (bad name or path) moves the tap at once to `files/inbox/rejected/` on the phone. Other per-file errors count; after 30 counted failures (`MAX_ATTEMPTS`, about 4–5 days of back-off) the tap also moves to `inbox/rejected/`. The phone deletes a queued tap only after a good upload, or when Dropbox reports that the same file is already there (write conflict `FILE`).
- **Rejected taps (DL-25, DL-30):** Settings shows "Could not upload: N (kept on the phone)" and a **Try again** button. Try again moves the taps from `inbox/rejected/` back into the upload queue. N counts only `.json` taps; old half-written `.tmp` files stay on disk and are not counted.
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
- A habit can publish a `+1` shortcut, a `-1` shortcut, or both, per its `dirs`. Old files with no `dirs` fall back to `quickLog ? [1] : []`. A file that doesn't parse as v1 returns `null` on the phone and **never wipes the existing menu**: the worker does not store the bad file, keeps the current shortcuts, and Settings shows "Habit list file is not readable (v1 expected)." The same holds when the stored copy itself cannot be read (TWA-C1, `ShortcutPublisher.kt`).

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
  Title/body come from the PWA's own `getReminderNotificationPayload`, so native and web notifications read identically. A file that doesn't parse as v1 returns `null` and **never wipes existing alarms** (`ReminderScheduler.rearmPlan` returns `Keep`).
- **Dates (DL-09, CR-PWA-001 mirror):** the phone skips an item only when `date` is a blank string (a broken once item). `date: null` means a repeating item (every day when `days` is also `null`), so it is never skipped. An old undated once item may keep firing daily until the PWA writer fix ships.

### 4.4 Start URL param `nr=1` — native-ready signal

- **Writer:** phone. `LauncherActivity.getLaunchingUrl()` appends `?nr=1` to the TWA start URL only when `ReminderScheduler.nativeReady()` is true (the "Native reminders" switch is on, Dropbox connected, a parsed reminder list is stored, notifications enabled — exact-alarm permission is *not* required).
- **Reader:** PWA. `parseNativeRemindersParam` strips `nr=1` off `location.search` (preserving other deep-link params like `?tab=`) and, if present, sets `sessionStorage['questa.nativeReminders']='1'` (`nativeRemindersActive()` reads it back). **Currently this flag is read but not acted on**: `checkReminders()` fires both due and missed reminders on the web regardless, because the phone's copy of the schedule can be stale (a reminder created minutes ago) — muting the web risked losing reminders. Deduplication instead happens entirely on the Android side (§4.5).

### 4.5 Notification dedupe — slot identity `title|yyyy-MM-dd|HH:MM`

Because both the native alarm (`ReminderReceiver`) and the web app (delegated through `DelegationService`, which Chrome uses to post TWA notifications on the app's behalf) can show the same due reminder, `ReminderDedupe.kt` tracks per-slot "shown" records keyed by `title|yyyy-MM-dd|HH:MM` (phone-local date/time), separately for native (`markNative`/`nativeShown`) and web (`markWeb`/`webShown`) so neither side clobbers the other's record. Records older than 2 days are pruned on every write.

- `ReminderReceiver` computes the slot from the alarm's item time on **today's** date, skips posting only if `webShown` is already true, otherwise posts and calls `markNative`.
- `DelegationService.onNotifyNotificationWithChannel` reads the notification's `EXTRA_TEXT`: a body matching `^Missed at (\d\d:\d\d)` yields that time as the one candidate slot (see §4.6); otherwise both "now" and "now-1min" are tried as candidates, to absorb the gap between the native alarm firing and this delegated call arriving. If any candidate slot was already `nativeShown`, the web copy is dropped; otherwise all candidates are `markWeb`'d.
- Only titles present in the stored reminder list are ever deduped, so the web's own "Questa Test" button notification is never dropped.
- **Known gaps (DL-17: the key stays as it is; pinned by JVM tests in `ReminderDedupeParityTest.kt`):**
  - **P7-F1:** an on-time web copy for 23:59 that reaches `DelegationService` after midnight gets the next date, misses the native slot, and shows twice.
  - **P7-F2:** a web on-time copy that arrives more than 1 minute late misses the native slot and shows twice.

### 4.6 Missed-reminder body prefix — `Missed at HH:MM - `

A PWA cannot fire a notification while its page/tab is fully closed, so a reminder slot that passed while Questa was shut is reported late, exactly once, on next open. The web's `getReminderNotificationPayload(t, r, missed=true)` (`Opti/app.js`) prepends `'Missed at ' + r.time + ' - '` to the body. This exact prefix is what `ReminderDedupe.parseMissedTime` on the Android side parses back out to recover the intended slot for dedupe (§4.5) — **do not change this string shape** without updating both sides.

## 5. Code map

| File | Role |
|---|---|
| `app/src/main/java/io/github/palo007/twa/Application.java` | App entry point; calls `QuickLogBootstrap.onAppStart` |
| `app/src/main/java/io/github/palo007/twa/LauncherActivity.java` | TWA launcher; appends `?nr=1` when native reminders are ready; kicks off `ReminderSyncWorker.enqueueNow` and `LaunchSync.enqueueFollowUp`; calls `ProviderNotice.maybeShow` |
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
| `quicklog/ReminderBootReceiver.kt` | Re-arms alarms from prefs on `BOOT_COMPLETED`/`TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED`, and on API 31–32 when the exact-alarm permission changes (TWA-A6) |
| `quicklog/ReminderDedupe.kt` | Pure slot-key helpers + prefs-backed native/web "already shown" records |
| `quicklog/DropboxAuth.kt` | Dropbox Android SDK PKCE login; credential sealed with an AES-GCM Android Keystore key |
| `quicklog/SettingsActivity.kt` | Connect/disconnect Dropbox, queue status, "Could not upload" + Try again, last-synced times, "Native reminders" switch, battery and Autostart buttons, permission-request buttons |
| `quicklog/SettingsLogic.kt` | Pure helpers for Settings: battery button, OEM Autostart screens, "N min ago" text |
| `quicklog/ReminderPrefs.kt` | "Native reminders" switch and the one-time "who fires reminders" question (own prefs file `questa_reminders.xml`, not backed up, DL-31) |
| `quicklog/LaunchSync.kt` | One delayed (3 min) reminder sync after each launch |
| `quicklog/ProviderNotice.kt` | One-time Toast when Chrome is off or missing |
| `quicklog/QuickLogBootstrap.kt` | Wires periodic workers on app start / Dropbox connect |
| `app/src/test/java/io/github/palo007/twa/quicklog/*.kt` | JVM unit tests for the pure helpers (`gradlew testDebugUnitTest`) |

## 6. Build, sign, install (Windows)

**Prereqs:** JDK 17 (Eclipse Adoptium, e.g. `C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`), Android SDK cmdline-tools at `%USERPROFILE%\.bubblewrap\android_sdk` (the *legacy bundled* sdkmanager is *incompatible with JDK 17* — install a fresh `commandlinetools` zip and accept licenses with the new `sdkmanager`), then `bubblewrap updateConfig --jdkPath … --androidSdkPath …`.

**Toolchain (known good, 2026-09-25):**

| Tool | Version |
|---|---|
| JDK | 17.0.17 (Eclipse Adoptium) |
| Node | v24.13.0 |
| `@bubblewrap/cli` | 1.25.0 |
| Gradle (wrapper) | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin Gradle plugin | 2.1.21 |
| `compileSdkVersion` / `targetSdkVersion` | 36 |
| SDK platform / build-tools | android-36 / 35.0.0 and 36.1.0 |
| `minSdkVersion` | 21 (kept on purpose, see §9) |

**JDK 17 is required.** Gradle 8.11 does not run on JDK 25; use JDK 17. On a machine with only JDK 25, the build fails with "Unsupported class file major version 69". Install JDK 17 and point `JAVA_HOME` at it for the shell:
- cmd: `set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot`
- PowerShell: `$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"`

Use the real folder name of your JDK 17 install. `gradlew.bat --version` shows the JVM that Gradle uses.

`jcenter()` is removed from `build.gradle` (P8, marker `HAND-OWNED (build-hygiene P8)`), so it no longer blocks Gradle 9. Still bump Gradle only together with a matching AGP version.

**Line endings:** `.gitattributes` checks out `shortcuts.xml` with LF (P9), so a build leaves `git status` clean. After you pull this fix, run `git checkout -- app/src/main/res/xml/shortcuts.xml` once.

**Build from a fresh clone:** Gradle does not read Bubblewrap's `~/.bubblewrap/config.json`, and `local.properties` is git-ignored. Tell Gradle where the SDK is, with one of these:
- Set the variable for the shell: `set ANDROID_HOME=%USERPROFILE%\.bubblewrap\android_sdk` (PowerShell: `$env:ANDROID_HOME="$env:USERPROFILE\.bubblewrap\android_sdk"`).
- Or make an untracked `local.properties` in the repo root with one line: `sdk.dir=C\:\\Users\\<you>\\.bubblewrap\\android_sdk`.

Without it, Gradle stops with "SDK location not found".

**Debug build + unit tests:**
```bat
gradlew.bat testDebugUnitTest assembleDebug
```

**Release build + sign**, two supported paths:
- `build-signed-apk.bat` — cleans, runs unit tests, `assembleRelease`, zipaligns, signs, verifies, copies to `builds\Questa-v<versionName>-<versionCode>-<timestamp>.apk`. Password: if a `keystore.pass` file exists next to the script (first line = password, **git-ignored, never commit**), it's used automatically; otherwise `apksigner` prompts interactively. **Delete `keystore.pass` after the build.** Do not leave a plain-text password in the repo folder; prefer `sign-release.ps1` below.
- `sign-release.ps1` — same signing steps, but the password lives **encrypted with Windows DPAPI**, outside the repo, at `%USERPROFILE%\.questa-secrets\android-keystore-password.dpapi` (never in git). Save it once with `-SavePassword` (prompts interactively, never echoes it back); then `-Build` runs Gradle + align + sign + verify, or run with no flags to just re-sign the last build. DPAPI is tied to this Windows account/profile, so keep a second copy of the password in a password manager.

Output APKs land in `builds\`. Install with:
```bat
adb install -r builds\Questa-v<...>.apk
```

**Signature mismatch note:** a **debug**-signed APK cannot be installed over a **release**-signed install of the same package (or vice versa) — Android refuses with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first if you need to switch build types on the same device. Uninstalling the TWA does not remove the data in Chrome, but sync and export first anyway (§1).

### Install and developer verification

Google's Android developer verification starts on 30 Sep 2026 in four countries and goes global in 2027 (certified devices, Android 7+), as announced (to verify: these details are not checked against Google's current page; check it at the Q1 2027 review).
- **`adb install` is exempt.** Install this APK with `adb install -r` (above). This is the supported path.
- An unverified APK installed another way (file manager, browser download) needs a one-time "advanced flow" on the phone (developer mode, restart, 24 h wait, biometric). After that, installs work.
- **Decision (2026-09-26):** no developer registration now. Install with adb. **Review in Q1 2027**, when the global rules are out. If the APK must go to phones without adb (for example family phones), register a free limited-distribution account then.
- Registration binds the package name and the signing key. It needs the keystore, so do the key backup below first.

### Windows/Bubblewrap quirks that cost hours

1. `bubblewrap init` hangs: default fetch engine broken on modern Node → run with `--fetchEngine=node-fetch`.
2. Legacy `sdkmanager` fails on JDK 17 → install fresh cmdline-tools (see Prereqs).
3. Bubblewrap's final signing step writes an **unquoted `C:\Program Files\…` path** and dies → sign manually with `apksigner` (as the scripts above do).
4. Keystore generation (only if starting fresh): `keytool -genkeypair -keystore android.keystore -alias android -keyalg RSA -keysize 2048 -validity 10000`. `android.keystore` is **git-ignored, never commit** — it is the app's identity; losing it means losing update capability forever.

### Keystore backup (user only)

`android.keystore` is the only key that can sign updates for `io.github.palo007.twa`. There is no confirmed backup yet (§12). Only the owner does these steps. Agents never read the key or its password, and this README never holds them.

1. Close Android Studio and stop Gradle (`gradlew.bat --stop`).
2. Put `android.keystore` and its password into one **encrypted container** (for example 7-Zip AES-256 or VeraCrypt). Take the password from your password manager, not from `keystore.pass`. Use a separate, strong passphrase for the container.
3. Keep **2 copies in 2 places**: one offline (USB drive) and one off-site (a cloud drive or a second location).
4. In the same container, add a note with: alias `android`, package `io.github.palo007.twa`, and the SHA-256 fingerprint from the published `assetlinks.json` (§3).
5. Test the restore once: open the container on another path, run `keytool -list -keystore <restored path>`, and compare the fingerprint with `assetlinks.json`.
6. Tick the keystore item in §12.

Do the same for the Kotlin app's upload key. It is a separate key.

### Target-SDK bump checklist (yearly)

Trigger: a new Android major release, or an AGP/Gradle bump. There is no Play deadline (no Play). The bump is for behaviour-change coverage. Bump `targetSdkVersion` only together with an AGP bump that supports it.

1. Read "Behavior changes: apps targeting API N" for the new N. List every item that hits this app.
2. Toolchain: the AGP version supports the new compileSdk; JDK 17 is still accepted (`jcenter()` is already removed, so Gradle 9 is not blocked by it).
3. `androidbrowserhelper`: is there a version newer than 2.6.2, and does it still allow minSdk 21?
4. Exact alarms: `USE_EXACT_ALARM` is still granted at install for sideloaded apps; the `canScheduleExactAlarms()` path in `ReminderScheduler.kt` still works.
5. Notifications: the `POST_NOTIFICATIONS` request in `SettingsActivity.kt` and channel creation in `ReminderReceiver.kt`.
6. `PendingIntent` mutability rules (`ReminderReceiver.kt`, `ReminderScheduler.kt`).
7. Exported components, and the boot / time-change receivers in `AndroidManifest.xml`.
8. Background work: WorkManager periodic limits and any new foreground-service rules (no foreground service is used; keep it that way).
9. Backup rules: `allowBackup`, `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml` still exclude the Dropbox credential prefs (`questa_quicklog.xml`) and the reminder switch prefs (`questa_reminders.xml`, DL-31), and still keep the upload queue (DL-15, §1).
10. UI of the two native screens (`QuickLogActivity`, `SettingsActivity`): edge-to-edge and predictive back.
11. 16 KB page size: the APK has no native `.so` files.
12. Gates: `pwsh tools\check.ps1` (unit tests, debug build, HAND-OWNED guard §9, DAL check §3), `assembleRelease`, then the phone checks (§13).

## 7. Permissions and phone setup

| Permission | Why |
|---|---|
| `POST_NOTIFICATIONS` (API 33+) | Required to show reminder/quick-log toasts and notifications at all |
| `USE_EXACT_ALARM` | Sideloaded apps (not distributed via Play) are granted this automatically at install, no runtime prompt, on API 33+ |
| `SCHEDULE_EXACT_ALARM` (capped `android:maxSdkVersion="32"`) | Covers API 31–32 only, where `USE_EXACT_ALARM` does not apply; without it or `USE_EXACT_ALARM`, alarms silently fall back to an inexact ~1 h window even with an item due in 5 minutes |
| `RECEIVE_BOOT_COMPLETED` | Lets `ReminderBootReceiver` re-arm alarms after reboot without needing the network |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | The one-tap "Allow unrestricted battery" dialog (DL-12). Allowed because the app is sideloaded; remove it if the app ever goes to Play |

**Battery and Autostart (DL-12, sideload only):** in the app's Settings screen (Android 6+), tap **"Allow unrestricted battery"** and allow it in the system dialog. The line then says "unrestricted" and the button goes away. On Xiaomi/Redmi/POCO (and Huawei, OPPO, OnePlus, vivo) also tap **"Autostart (phone maker settings)"** and enable Questa. The app cannot read the Autostart setting, so it cannot show whether it is on. Without these, the OS can kill the background workers and alarms. Also disable "scan before install" under Security if sideload installs are being blocked (root-cause ledger #5).

**Settings screen:** open Android Settings → Apps → Questa → the app's own settings (gear icon, `quicklog.SettingsActivity`). There you connect Dropbox, see the queue ("Waiting to upload", "Could not upload: N" with **Try again**, §4.1), last upload, and reminder status (count scheduled, exact-alarm permission), and request the exact-alarm and notification permissions.
- **Last synced (DL-16):** "Questa last synced (any device)" is when any Questa web app last wrote `/state.json` to Dropbox (file time, the file is not downloaded). "Reminder list downloaded" is this phone's last good download of the reminder list.
- **Login could not be saved (DL-10):** if the Android Keystore fails during connect, the screen says "Could not save the Dropbox login on this phone (secure storage error). Taps stay queued. Try Connect again; if it keeps failing, restart the phone." The token is never stored in plain text.

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

**Known limit:** a reminder created or changed on the web is not instant on the phone. The native alarms get it:
- when Questa is opened with Dropbox connected (a sync right away, and one more sync 3 minutes after launch, so a reminder you edit just after opening the app also arrives; TWA-26, `LaunchSync.kt`), or
- within ~15 minutes, through the periodic background sync worker.

Returning to Questa from Recents may not count as a launch (it may not run `LauncherActivity.onCreate`), so it may not start these two syncs. Then only the 15-minute worker applies.

## 9. `bubblewrap update` survival

`bubblewrap update` regenerates Bubblewrap-owned files and will silently drop hand-written changes unless they're re-applied. Every hand edit carries a marker that starts with `HAND-OWNED (quick-log` — the plain `HAND-OWNED (quick-log)` and the longer kinds such as `HAND-OWNED (quick-log, P5 / DL-15)`. Check what is still in place with the guard. Run it **before and after** every `bubblewrap update`:
```powershell
pwsh tools\hand-owned-guard.ps1                    # exit 1 = hand-owned code was lost
pwsh tools\hand-owned-guard.ps1 -UpdateBaseline    # after you add markers; commit the baseline with the change
```
- **What it counts:** lines that contain `HAND-OWNED (quick-log` in the files from `git ls-files` (working tree), without `*.md` files and without `tools/`. It compares the count per file with the baseline.
- **Baseline:** `tools/hand-owned-baseline.json` (marker, definition, commit, total lines and files, count per file). A count below the baseline, or a missing file, is a failure. The guard also checks that `twa-manifest.json` still has `"shortcuts": []` and the kept `minSdkVersion`.
- **`-UpdateBaseline` only raises.** It raises counts and adds new marker files. It never lowers a count or removes a file, and it refuses to run while the guard fails. To lower a count on purpose, edit the baseline by hand in a reviewed commit.
- **Separate marker `HAND-OWNED (build-hygiene P8)`:** two lines in `build.gradle` that record the `jcenter()` removal. The guard does not count them. After `bubblewrap update`, check by hand that `jcenter()` did not come back: `git grep -n "jcenter" build.gradle`.

| File | What was added |
|---|---|
| `build.gradle` | Kotlin Gradle plugin `2.1.21` classpath, `mavenCentral()` |
| `app/build.gradle` | Kotlin plugin; top-level `kotlin { jvmTarget 1.8 }`; deps (Dropbox SDK 7.0.0, WorkManager 2.10.5, core 1.16.0, junit, org.json for tests); `proguardFiles 'proguard-rules.pro'`; `shortcuts: []` — no static shortcuts (habit items are the dynamic `ql-*` shortcuts built from `/inbox-meta/habits.json`) |
| `twa-manifest.json` | `"shortcuts": []` — no static shortcuts, so `bubblewrap update` does not bring Habits/Dailies/Quick log back; `res/xml/shortcuts.xml` is an empty `<shortcuts/>` |
| `app/src/main/AndroidManifest.xml` | `INTERNET` permission; `APPLICATION_PREFERENCES` filter moved to `quicklog.SettingsActivity`; `HAND-OWNED ... END HAND-OWNED` block with `QuickLogActivity`, `SettingsActivity`, Dropbox `AuthActivity`; `RECEIVE_BOOT_COMPLETED` + `SCHEDULE_EXACT_ALARM` (capped API 32) + `USE_EXACT_ALARM`; `ReminderReceiver` (not exported), `ReminderBootReceiver` (exported, boot/time/timezone/replace and exact-alarm permission change only); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`; `<queries>` entry for the Kotlin app's `REMINDER_OWNER` action; `android:fullBackupContent` + `android:dataExtractionRules` on `<application>`; App Link `<data android:path="/Questa">` + `<data android:pathPrefix="/Questa/">` |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Backup rules that exclude `questa_quicklog.xml` and `questa_reminders.xml` (new files, DL-15, DL-31) |
| `app/src/main/java/.../Application.java` | one line: `QuickLogBootstrap.onAppStart(this)` |
| `app/proguard-rules.pro` | `-dontwarn` for optional Dropbox SDK classes; a comment recording that release builds keep `Log.i` (no `-assumenosideeffects` strip) |
| `app/src/main/java/io/github/palo007/twa/quicklog/*.kt` | all quick-log + reminder code (new) |
| `app/src/main/java/io/github/palo007/twa/LauncherActivity.java` | `?nr=1` append + `ReminderSyncWorker.enqueueNow` + `LaunchSync.enqueueFollowUp` (TWA-26) + `ProviderNotice.maybeShow` (TWA-03) + `nativeReady` logging |
| `app/src/main/java/io/github/palo007/twa/DelegationService.java` | `onNotifyNotificationWithChannel` override for dedupe; `enqueueNow` on create |
| `app/src/test/java/io/github/palo007/twa/quicklog/*.kt` | JVM unit tests (new) |

Versions are pinned to keep **minSdk 21**: `dropbox-android-sdk` 8.x needs 26, `work-runtime` 2.11+ needs 23/24, `androidx.core` 1.17+ needs 23. Raise them only together with minSdk.

Note: `/app`, `build.gradle`, the Gradle wrapper and `twa-manifest.json` are **tracked** in git (since 2026-09-24), so the app can be rebuilt from a clone. `.gitignore` ignores only secrets (`android.keystore`, `keystore.pass`, `*.jks`, `local.properties`), build outputs and APKs, local tooling and logs, and every `*.md` file except `README.md`.

## 10. Using the TWA with the native Kotlin app

The Kotlin app has a different package, so both apps install side by side. Both show a launcher icon named "Questa". Both use the same Dropbox app folder (§4).

**Who owns reminders:** when both apps are installed, **the Kotlin app owns reminders**. The Kotlin app fires reminders from its own database (DL-03; Kotlin repo, not checked here). The TWA fires its own alarms from `/inbox-meta/reminders.json` (written by the PWA). There is no dedupe between the two apps, so with both on you get 2 notifications per slot.
- **"Native reminders" switch** (TWA Settings screen, DL-03). Off = the TWA cancels its alarms, arms none, and shows "Reminders: off (native reminders switched off)". The reminder list still downloads. The web app is then no longer told to stay silent (`?nr=1` is not sent, §4.4). On = alarms come back.
- The web app sees a switch change (the `nr=1` flag) only on the next launch of the TWA, not at once.
- **Ask once, never turn off silently (DL-03):** when the TWA finds the Kotlin app (its `io.github.palo007.questa.action.REMINDER_OWNER` action), it asks once: "Questa app is installed. Let it fire reminders and turn them off here?" Yes = the switch goes off. No = it stays on. The TWA asks only once and never turns itself off by itself.
- The question appears only when you open the TWA Settings screen. Until then, both apps can fire.
- With the switch off, the web app can still show a reminder while it is open, and the Kotlin app can show the same one.

**Quick-log:** a long-press shortcut on either icon writes `/inbox/<uuid>.json`. Only one client can claim a file (§4.1), so a tap counts once. Use one icon to avoid confusion.

**Move data from the TWA to the Kotlin app:**
1. In the TWA, open Questa online and let it sync to Dropbox.
2. In the TWA, run **Export** and save the JSON outside Chrome (Downloads and a copy off the phone). This is your safety copy.
3. On a desktop, check the Dropbox app folder: `/inbox/` is empty or almost empty (all taps claimed).
4. Install the Kotlin app. Grant notifications and exact alarms. On MIUI, set Autostart and battery "No restrictions" (§7).
5. In the Kotlin app, connect Dropbox (same account) and sync. If sync is not possible, import the JSON from step 2 instead. Do not do both without checking the result.
6. Check in the Kotlin app: habit and task counts, today's log and the reminder list match the export.
7. Next day: confirm one notification per reminder slot.

**Uninstall vs disable:**
- **Uninstall** removes the TWA's alarms, workers, shortcuts and its App Link claim for `palo007.github.io`. Chrome's site data for Questa stays, so the web app still has it.
- **Force stop** is not a switch. The next app start re-arms the alarms and workers.
- To keep the TWA as a backup with Kotlin as the main app, keep it installed and set the "Native reminders" switch to off.
- Uninstalling does not revoke the TWA's Dropbox token (§4).

**Keys and passwords:** keep `android.keystore` and its backup (§6). **Delete `keystore.pass` after each build**; keep the password in DPAPI (`sign-release.ps1`) and in your password manager. Leave `.well-known/assetlinks.json` and `.nojekyll` on the site (§3).

## 11. History

- **Bring-up (pre-Phase-1):** fullscreen TWA verification fixed (§3 root-cause ledger); signed sideload APK working.
- **Phase 1A (2026-09-24):** headless quick-log design — `/inbox/<uuid>.json`, claim-by-move, PWA-side consumer.
- **Phase 1B:** quick-log shipped — long-press shortcuts, `InboxUploadWorker`, `ShortcutRefreshWorker` reading `/inbox-meta/habits.json`, Dropbox connect screen.
- **Phase 1C:** background reminders added — `/inbox-meta/reminders.json`, `ReminderScheduler`/`ReminderReceiver`/`ReminderBootReceiver`, `?nr=1` web/native silence flag (later revised, see 1D/4.4).
- **Phase 1D:** dedupe introduced (title + 3-minute window) so native and web didn't double-notify; periodic reminder sync tightened to 15 min.
- **Phase 1E (2026-09-24):** `USE_EXACT_ALARM` added after finding `SCHEDULE_EXACT_ALARM` wasn't auto-granted on API 34+ HyperOS; dedupe rewritten from a time-window heuristic to exact **slot identity** (`title|yyyy-MM-dd|HH:MM`) with separate native/web records; `QuestaReminder` logging added throughout.
- **TWA fix plan (2026-10, packets P1a–P9, P2b):** reminders kept on a bad list, blank-date skip only, shortcuts kept on a bad list, bounded upload retries with `inbox/rejected/` and Try again, Keystore failure message, App Link limited to `/Questa`, backup excludes the Dropbox login, battery and Autostart buttons, last-synced lines, "Native reminders" switch, 3-minute launch sync, no-Chrome Toast, `jcenter()` removed, `tools/check.ps1` and the HAND-OWNED guard; P9: `.gitattributes` for `shortcuts.xml`, reminder prefs not backed up (DL-31). Decisions DL-01…DL-32 are in the fix-plan ledger (outside this repo).

Local, git-invisible evidence trail (`Documents\Opti\.cline\`): `evidence/task-twa-spike-questa-native-wrapper.txt`, `notepads/questa-native-wrapper/learnings.md`, `drafts/` + `plans/questa-native-wrapper.md`. This README is the durable, shared copy of everything above.

## 12. Open items

- [ ] **Proof B:** two rapid taps on two habits while the TWA is open → two logs, no miss, no double. Not yet formally recorded.
- [ ] **Keystore backup:** `android.keystore` + passwords into an encrypted backup, 2 places, restore tested (how-to in §6). Losing it means losing update capability.
- [x] **"Native reminders" switch** (done, §10) so the TWA can stay installed next to the Kotlin app without duplicate reminders. Still to check on a phone (§13).
- [x] **`tools/check.ps1`** (done, §3): local build, tests, HAND-OWNED guard and DAL check.
- [ ] **Developer verification review:** Q1 2027 (§6).
- **Play store: not planned** (decision 2026-09-26). The TWA stays sideload-only and keeps `USE_EXACT_ALARM` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. If this ever changes: remove the battery permission, and add the Play signing SHA-256 to `assetlinks.json` as a second entry before the first upload.
- [ ] **Owner device checklist** (plan `android-inbox-step1.md` todos 14, 17): reminder in 5 min, lock phone, swipe app away, reboot, MIUI Autostart/Battery settings.
- [ ] **Phone checks after the fix plan:** §13.
- [ ] **Known dedupe gaps P7-F1 / P7-F2** (§4.5): deferred with TWA-12.
- [ ] **`user-site/` copy** (§3): still tracked and published; untracking it needs a decision.

## 13. Checks to do on a phone

Do these with a release-signed APK on a real phone, unless the line says "debug build" (`run-as` works only on a debug build). Package: `io.github.palo007.twa`.

**App Link and fullscreen (TWA-19)**
- [ ] `adb shell pm get-app-links io.github.palo007.twa` → `palo007.github.io` shows `verified`.
- [ ] `adb shell am start -a android.intent.action.VIEW -d https://palo007.github.io/other-repo/` → opens the browser, not Questa.
- [ ] `adb shell am start -a android.intent.action.VIEW -d "https://palo007.github.io/Questa/?tab=x"` → opens the TWA full screen. Repeat with `https://palo007.github.io/Questa` (no slash).
- [ ] Connect Dropbox from the web inside the TWA → it completes. Connect in the native Settings screen → it still works.

**Chrome missing (TWA-03)**
- [ ] Disable Chrome, launch Questa → Toast once, Questa opens in another browser. Launch again → no Toast. Enable Chrome → full screen TWA. Disable again → Toast again. Logs: `adb logcat -s QuestaLauncher TWAProviderPicker`.

**Reminder sync after launch (TWA-26)**
- [ ] Launch, edit a reminder in the web app within 3 min → `adb logcat -s QuestaReminder` shows a second `downloaded ok` about 3 min after launch, and the alarm is armed. Launch several times within 3 min → only one follow-up run.

**Reminders and alarms**
- [ ] Debug build: write `{"v":2}` into `reminders_json` with `adb shell run-as io.github.palo007.twa`, then open Settings → `adb shell dumpsys alarm | findstr palo007` still lists the reminder alarms.
- [ ] Switch "Native reminders" off → "Reminders: off (native reminders switched off)"; `adb shell dumpsys alarm | findstr palo007` shows no reminder alarms. Switch on → the count and the alarms come back.
- [ ] With the Kotlin Questa app installed: open Settings → the question shows once. Yes → switch off. Open Settings again → no question. On a clean phone: No → switch stays on, no question again.
- [ ] API 31–32 phone: revoke the exact-alarm permission while alarms are armed → the alarm still fires in Doze.
- [ ] API 31–32 phone without the grant: arm an inexact alarm for 23:59 that fires after midnight → web and native together show one notification, for the intended day.
- [ ] API 31–32 phone: revoke, then grant "Alarms & reminders" → `adb logcat -s QuestaReminder` shows `rearm` without opening Settings (TWA-A6).
- [ ] API 31–32 phone with exact alarms denied: "Allow exact alarms" opens the system screen; if that screen is missing, App info opens and a hint line shows (A8).

**Shortcuts (TWA-C1)**
- [ ] Debug build: corrupt `habits_json` with `adb shell run-as io.github.palo007.twa`, then long-press the icon → the habit shortcuts are still there.

**Dropbox login and upload queue**
- [ ] Connect Dropbox → "connected", habit shortcuts appear.
- [ ] Force-stop, open Settings again → still connected. Disconnect, then Connect again → works.
- [ ] Debug build: `adb shell run-as io.github.palo007.twa mkdir -p files/inbox/rejected`, move one queued `<id>.json` there → Settings shows "Could not upload: 1" and "Try again". Tap it → the line goes away, "Waiting to upload" goes up by 1, then to 0; the tap appears in Dropbox `/inbox/`.
- [ ] Debug build: put an `x.json.tmp` in `files/inbox/rejected/` → it is not counted in "Could not upload" and stays on disk (DL-30).
- [ ] Kill the app with taps in the queue → the taps still upload later.

**Backup (DL-15)**
- [ ] `adb shell bmgr enable true`, `adb shell bmgr backupnow io.github.palo007.twa`, then uninstall and install again (or `adb shell bmgr restore`) → Dropbox shows disconnected or asks to connect again; debug build: `adb shell run-as io.github.palo007.twa ls files/inbox` still lists the queued taps.

**Battery, Autostart, last synced (DL-12, DL-16)**
- [ ] Settings: "Battery: optimized (reminders may be late)" and the button "Allow unrestricted battery" → system dialog → Allow → back: the line says "Battery: unrestricted", the button is gone.
- [ ] Xiaomi/POCO: "Autostart (phone maker settings)" opens the MIUI/HyperOS Autostart list (or App info and a hint). Enable Questa.
- [ ] Sync in the web app, wait up to 15 min → "Questa last synced (any device)" and "Reminder list downloaded" show minutes.

**Not testable on a healthy phone:** the Keystore failure message (DL-10) cannot be forced.
