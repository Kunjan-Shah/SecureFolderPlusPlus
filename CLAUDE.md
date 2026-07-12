# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Section 1 — Project Overview

**Secure Folder++** is an Android **Device Policy Controller (DPC)** app. It does not manage a
fleet of devices — it manages exactly one thing on the local device it's installed on: an isolated
container for a single banking app.

What it does, concretely:

- On first run it triggers Android's system **Managed Profile (Work Profile)** provisioning flow
  (`DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE`). This creates a second, isolated Android
  user profile on the device (the same underlying mechanism used by Samsung Secure Folder and
  corporate MDM "work profiles").
- The app's `SecureFolderAdminReceiver` (a `DeviceAdminReceiver`) becomes the **Profile Owner** of
  that new work profile — this is what grants it the privilege to call restricted
  `DevicePolicyManager` (DPM) APIs.
- Exactly **one app** — the banking app identified by
  `SecurityConstants.BANKING_APP_PACKAGE` — is meant to live inside that work profile.
  `ProfileManager` installs/enables it there and `SecureLauncher` is the only supported way to open it.
- All isolation and hardening is done through the **public `DevicePolicyManager` API** as a Profile
  Owner: blocking overlay windows, disabling screen capture, restricting accessibility services and
  keyboards to an allowlist, blocking cross-profile clipboard/share/USB/Bluetooth, disabling camera,
  disabling backups, enforcing a password policy, etc. See `PolicyEnforcer` — every policy call in
  the codebase lives there.
- **No Samsung Knox, no OEM APIs, no hardware-backed security modules, and no WindowManagerService
  patching.** Every protection is something any Profile Owner app can do on stock AOSP. Where OEM
  hardware protection would normally be used (e.g. a TEE-backed secure display), this app
  substitutes a combination of `DISALLOW_CREATE_WINDOWS` + `setScreenCaptureDisabled` +
  `FLAG_SECURE` instead — see the "NON-OEM BOUNDARY NOTES" comment block in `PolicyEnforcer.kt`.
- `minSdk 26` (Android 8.0) / `compileSdk 34` / `targetSdk 34`.
- `applicationId` / Kotlin package: **`com.securefolderplusplus.app`** (see "Known inconsistency"
  below — this differs from the on-disk source directory name).
- Language: **Kotlin**. Build system: **Gradle with Groovy DSL** (`build.gradle`, not `.kts`).

### Known inconsistency: package name vs. directory path

The task/spec for this project refers to the package as `com.securefolder.app`, and the on-disk
source directories are still `app/src/main/java/com/securefolder/app/...` and
`app/src/androidTest|test/java/com/securefolder/app/...`. However, **every `.kt` file's actual
`package` declaration, the Gradle `namespace`, and `applicationId` are all
`com.securefolderplusplus.app`** (note the "plusplus"). This mismatch works today only because
Kotlin/Gradle don't strictly require source directories to mirror the package statement, but it is
fragile: Android Studio will flag it, and any new file created via "New Kotlin Class" in the
existing directories will default to the wrong package. **When adding new files, always add them
under a path that matches `com/securefolderplusplus/app/...`** (or fix the directories to match, as
a deliberate refactor) rather than perpetuating the split. Do not silently rename one to match the
other without calling it out — it's a decision the project owner should confirm.

### The "banking app" is a real, specific target

`SecurityConstants.BANKING_APP_PACKAGE` is currently hardcoded to `com.sbi.lotusintouch` (SBI's
YONO/Lotus InTouch app) with a matching `BANKING_APP_CERT_SHA256` certificate fingerprint. This is
not a placeholder in the running code — treat changes to this constant, and to the cert pin, as
security-relevant.

## Section 2 — Project Directory Structure

Build outputs (`app/build/`, `.gradle/`), IDE metadata (`.idea/`), and the Gradle wrapper jar are
omitted below since they're generated/tooling files, not project source. Everything else is listed.

### Root

| File | Purpose |
|---|---|
| `build.gradle` | Top-level Gradle file. Declares the Android Gradle Plugin (8.2.2) and Kotlin plugin (1.9.0) versions, applied to the `:app` module. |
| `settings.gradle` | Gradle settings. Names the root project `SecureFolderPlusPlus`, configures `google()`/`mavenCentral()` repos, includes the single `:app` module. |
| `gradle.properties` | Gradle daemon JVM args, enables AndroidX, non-transitive R classes, official Kotlin code style. |
| `gradlew` / `gradlew.bat` | Gradle wrapper launch scripts (Unix / Windows). |
| `gradle/wrapper/gradle-wrapper.properties` | Pins the Gradle distribution version used by the wrapper. |
| `gradle/wrapper/gradle-wrapper.jar` | Wrapper bootstrap jar (binary, do not edit). |
| `local.properties` | Machine-local SDK path, gitignored; never commit real contents from a dev machine. |
| `.gitignore` | Root ignore rules (build outputs, IDE caches, `local.properties`, etc.). |

### `app/` module

| File | Purpose |
|---|---|
| `app/build.gradle` | Module build config: `applicationId com.securefolderplusplus.app`, `minSdk 26`/`compileSdk 34`/`targetSdk 34`, Java/Kotlin 17 target, release-only-style build types (debug is **not** debuggable — see Development Notes), ProGuard/R8 enabled with shrinking on both build types, `viewBinding`/`buildConfig` features, and the full dependency list (AndroidX core/appcompat/activity/fragment, Material, ConstraintLayout, Biometric, Lifecycle, WorkManager, Coroutines, `androidx.security:security-crypto` for encrypted prefs, Timber for logging, JUnit/Mockito-Kotlin/Espresso for tests). |
| `app/proguard-rules.pro` | Keeps the four system-invoked classes that Android calls by reflection/name (`SecureFolderAdminReceiver`, `BootReceiver`, `SecureKeyboardService`, `SecureMonitorService`) and strips `Timber.d/v/i` calls via `-assumenosideeffects` in optimized builds. |
| `app/.gitignore` | Module-level ignore (`/build`). |

### `app/src/main/java/com/securefolder/app/` (Kotlin sources — package statement is `com.securefolderplusplus.app`, see inconsistency note above)

| File | Purpose |
|---|---|
| `SecureFolderApp.kt` | `Application` subclass. Only responsibility: plants a `Timber.DebugTree()` when `BuildConfig.DEBUG` is true, so logging is a no-op in release. |
| `SecurityConstants.kt` | **Single source of truth for every security-relevant constant.** Banking app package name + SHA-256 cert pin, SharedPreferences keys, the accessibility-service allowlist, the keyboard/IME allowlist, the known-risky-package blocklist (remote control tools, screen recorders, chat-head overlay apps) and risky-permission set, patch-level thresholds, max failed-unlock attempts before wipe, monitor poll interval, notification channel/id constants, and redacted notification text. All values are compile-time — the file's doc comment explicitly forbids remote/dynamic config so a compromised backend can't weaken the security posture. |
| `dpc/SecureFolderAdminReceiver.kt` | The `DeviceAdminReceiver` — this **is** the DPC/Profile Owner. `onProfileProvisioningComplete()` and `onEnabled()` both call `PolicyEnforcer.applyAllInitialPolicies()` (once in the personal-profile context, once in the work-profile context — belt-and-suspenders). `onDisabled()` is treated as a critical security event: if device admin is revoked, all DPM-enforced protections instantly lift, so this wipes the entire work profile. `onPasswordFailed()` counts failures via `PolicyEnforcer.getFailedPasswordAttempts()` and wipes once `SecurityConstants.MAX_FAILED_UNLOCK_ATTEMPTS` is hit. |
| `dpc/PolicyEnforcer.kt` | **The heart of the security model.** Wraps `DevicePolicyManager` and applies every policy as a Profile Owner: (1) `DISALLOW_CREATE_WINDOWS` to block overlays/chat-heads, (2) `setScreenCaptureDisabled` to block screenshots/recording/casting, (3) `setPermittedAccessibilityServices` allowlist, (4) `setPermittedInputMethods` allowlist, (5) cross-profile leakage restrictions (clipboard, share-into-profile, USB, Bluetooth, caller-ID/contacts search), (6) installation lockdown (currently commented out — see Development Notes), (7) network restrictions (`DISALLOW_CONFIG_VPN`, `DISALLOW_NETWORK_RESET`), (8) debug/backup restrictions (`DISALLOW_DEBUGGING_FEATURES`, backup service disabled, `DISALLOW_FACTORY_RESET`), (9) password quality policy, (10) camera disable, (11) keyguard feature restrictions (unredacted notifications, trust agents), (12) notification/account-modification restriction. Also owns `wipeProfileData()`, `lockProfileNow()`, `getFailedPasswordAttempts()`, and `isProfileOwner()`/`isDeviceOwner()` state checks. `applyAllInitialPolicies()` runs the full set; `refreshRuntimePolicies()` re-applies the subset that can drift at runtime (accessibility, keyboard, overlay). |
| `dpc/BootReceiver.kt` | Listens for `ACTION_BOOT_COMPLETED`/`ACTION_LOCKED_BOOT_COMPLETED` (direct-boot aware). On boot, suspends the banking app (`ProfileManager.suspendBankingApp()`) so it requires re-authentication after every reboot, and re-asserts runtime policies via `PolicyEnforcer.refreshRuntimePolicies()` as a safety net (most DPM policies already survive reboot automatically). |
| `keyboard/SecureKeyboardService.kt` | An `InputMethodService`. **Currently a stub** — `onCreateInputView()` inflates a placeholder Android system layout (`android.R.layout.simple_list_item_1`). Exists so the manifest/IME metadata reference resolves and so `SecurityConstants.TRUSTED_INPUT_METHODS` can allowlist it. The real secure-keyboard implementation (custom keys, no clipboard access, numeric-subtype switching for OTP/PIN fields) is not yet built — see Development Notes. |
| `model/PolicyResult.kt` | Data model for a single security check's outcome (`PolicyResult`) plus the aggregate (`HealthCheckReport`). `PolicyResult.Severity` is `BLOCKING` / `WARNING` / `INFO`. `HealthCheckReport.isLaunchAllowed` is true only if there are zero `BLOCKING` failures; `primaryBlockReason` / `allRemediationHints` feed the UI. Companion factory helpers: `PolicyResult.pass()`, `.fail()`, `.warn()`. |
| `profile/ProfileManager.kt` | Manages the work profile's lifecycle: detects whether a managed profile already exists (`hasExistingManagedProfile()`, also handles the case where a *different* MDM already owns the device's one allowed work profile), builds the provisioning `Intent` (`buildProvisioningIntent()`), tracks "was profile ever created" in SharedPreferences, installs/enables the banking app in-profile (`installBankingAppInProfile()` via `dpm.enableSystemApp`, with a `PackageInstaller` fallback noted for non-system-app installs), verifies the banking app's SHA-256 signing certificate against `SecurityConstants.BANKING_APP_CERT_SHA256` (`isBankingAppCertificateValid()`), and suspends/unsuspends the banking app (`dpm.setPackagesSuspended`) around each secure session. `destroyProfile()` wipes the profile and clears local prefs. |
| `security/BankingAppInstallet.kt` | **Filename has a typo** ("Installet", not "Installer") — the class inside is correctly named `BankingAppInstaller`. Uses the `PackageInstaller` session API (`MODE_FULL_INSTALL`) to sideload an APK picked by the user via `ACTION_OPEN_DOCUMENT`, streaming it into an install session and committing with a `PendingIntent` that resolves via `InstallResultReceiver`. Used from the **work-profile instance** of `MainActivity` (see below) to get the banking APK physically installed inside the profile. |
| `security/HealthCheckEngine.kt` | Runs the pre-launch/runtime security checks and returns a `HealthCheckReport`. Only 4 checks are currently wired into `runAllChecks()`: `checkAdbEnabled()`, `checkDeviceRooted()` (su binary paths + known root-manager packages), `checkBootloaderStatus()` (test-keys/dev-keys tag, warning-only), `checkPatchLevel()` (compares `Build.VERSION.SECURITY_PATCH` against `SecurityConstants.MIN_PATCH_LEVEL_*`, warning-only). Several more checks exist as **fully implemented but commented-out** methods: `checkDeveloperOptions()`, `checkScreenRecording()` (scans running processes against `KNOWN_RISKY_PACKAGES`), `checkKnownRiskyPackages()` (scans installed apps), `checkUntrustedAccessibilityServices()`, `checkOverlayCapableApps()` (scans for `SYSTEM_ALERT_WINDOW` grants, with Samsung/Google package carve-outs), `checkSuspiciousKeyboard()`. These are disabled, not deleted — see Development Notes before assuming the app's live protection surface matches `PolicyEnforcer`'s intent. |
| `security/InstallResultReceiver.kt` | `BroadcastReceiver` target for `PackageInstaller` session results. Handles `STATUS_SUCCESS` (toast), `STATUS_PENDING_USER_ACTION` (launches the confirmation `Intent` Android hands back), and any other status as a failure toast. |
| `security/SecureLauncher.kt` | The **only** supported entry point for opening the banking app. `attemptLaunch()`: (1) runs `HealthCheckEngine.runAllChecks()` and aborts with `LaunchResult.Blocked` if not `isLaunchAllowed`, (2) unsuspends the banking app, (3) resolves the work-profile `UserHandle` (the profile in `userManager.userProfiles` that isn't the current process's handle) and launches the banking app's main activity in that profile via `LauncherApps.startMainActivity()`, re-suspending on failure, (4) starts `SecureMonitorService` as a foreground service for the duration of the session. Returns a sealed `LaunchResult` (`Success` / `Blocked(report)` / `Error(message)`). |
| `security/SecureMonitorService.kt` | Foreground `Service` started for the duration of a banking session. Every `SecurityConstants.MONITOR_POLL_INTERVAL_MS` (2s) it re-runs `HealthCheckEngine.runAllChecks()`; on any blocking failure it broadcasts `ACTION_SECURITY_VIOLATION` (with `EXTRA_REASON`) to `MainActivity` and calls `stopSelf()`, ending the session. Runs a low-importance, non-dismissible "Secure Folder++ Active" notification while live (required for a foreground service). |
| `ui/MainActivity.kt` | The single Activity, but it renders **two completely different UIs depending on which profile it's running in** (`UserManager.isManagedProfile`): in the **personal profile** it drives setup → health-check → launch (buttons: Set Up / Run Security Check / Open Banking App), listening for `SecureMonitorService.ACTION_SECURITY_VIOLATION` while resumed; in the **work profile** it instead shows only an APK picker (`btnInstallBanking`) that hands the picked file to `BankingAppInstaller`. This dual-mode design is why the same launcher activity appears twice in the app drawer after provisioning (once per profile) — that's intentional, not a bug. |

### `app/src/main/res/`

| File | Purpose |
|---|---|
| `layout/activity_main.xml` | Single layout shared by both `MainActivity` UI modes; visibility of `btnSetup` / `btnHealthCheck` / `btnLaunch` / `btnInstallBanking` / `tvError` / `tvHealthResults` is toggled entirely in Kotlin based on profile and state. |
| `values/strings.xml` | App name (`Secure Folder++`), secure keyboard label, and the two IME subtype labels (English (US), Numeric). |
| `values/colors.xml` | Leftover default Android-Studio-template purple palette (`purple_500`, `purple_700`, `white`) — not security-relevant, just the app theme's accent colors. |
| `values/themes.xml` | `Theme.SecureFolder`, extends `Theme.MaterialComponents.DayNight.DarkActionBar`, applies the colors above. |
| `xml/device_admin_policies.xml` | Declares every `DevicePolicyManager` policy category the app will invoke (`limit-password`, `watch-login`, `reset-password`, `force-lock`, `wipe-data`, `expire-password`, `encrypted-storage`, `disable-camera`, `disable-keyguard-features`). Referenced by `SecureFolderAdminReceiver`'s `android.app.device_admin` metadata; using a DPM policy not declared here throws `SecurityException` at runtime, so if `PolicyEnforcer` gains a new DPM call, check whether its policy category needs adding here too. |
| `xml/network_security_config.xml` | Disables cleartext traffic app-wide (`cleartextTrafficPermitted="false"`), trusts only system CAs. |
| `xml/secure_keyboard_method.xml` | IME metadata for `SecureKeyboardService`: empty `settingsActivity` (deliberately — no IME settings screen accessible from inside the profile), an English (US) default subtype and a numeric subtype (intended for OTP/PIN fields, matching the stubbed-out numeric-switching behavior mentioned in the keyboard service). |
| `xml/backup_rules.xml` | Auto Backup (API 31+) rules — currently a no-op template (rules commented out). |
| `xml/data_extraction_rules.xml` | Excludes all data (`root` + `sharedpref` domains) from both cloud backup and device-to-device transfer. |
| `drawable/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml` | Adaptive launcher icon layers (default Android Studio template art, not custom-designed). |
| `mipmap-anydpi/ic_launcher.xml`, `mipmap-anydpi/ic_launcher_round.xml` | Adaptive icon definitions referencing the background/foreground drawables above. |
| `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher{,_round}.webp` | Legacy density-specific launcher icon rasters (default template assets). |

### `app/src/main/AndroidManifest.xml`

Declares: boot-completed, foreground-service (+`FOREGROUND_SERVICE_SPECIAL_USE` with a declared
justification string for the monitor service), install-packages, `QUERY_ALL_PACKAGES` (needed for
the risky-package/overlay scans in `HealthCheckEngine`), biometric, network-state,
post-notifications, `BIND_INPUT_METHOD`, and USB-accessory permissions. Application-level:
`MainActivity` (`excludeFromRecents="true"` so the banking session never shows a task-switcher
thumbnail — belt-and-suspenders alongside `FLAG_SECURE`), `SecureFolderAdminReceiver` (exported,
`BIND_DEVICE_ADMIN`-protected, listens for `DEVICE_ADMIN_ENABLED` /
`PROFILE_PROVISIONING_COMPLETE` / `MANAGED_PROFILE_PROVISIONED`), `SecureKeyboardService`
(exported, `BIND_INPUT_METHOD`-protected), `SecureMonitorService` (not exported,
`foregroundServiceType="specialUse"`), `BootReceiver` (not exported, `directBootAware="true"`).

### Tests

| File | Purpose |
|---|---|
| `app/src/test/java/com/securefolder/app/ExampleUnitTest.kt` | Unmodified Android Studio template JVM unit test (`assertEquals(4, 2+2)`). No real unit tests exist yet for any DPC/security logic. |
| `app/src/androidTest/java/com/securefolder/app/ExampleInstrumentedTest.kt` | Unmodified Android Studio template instrumented test; asserts the target package name equals `com.securefolderplusplus.app`. No real instrumented tests exist yet. |

---

## Guide for future Claude Code sessions

### Build / lint / test commands

This is a standard single-module Android Gradle project (module `:app`). Use the wrapper, not a
system-installed Gradle:

```bash
# Windows
gradlew.bat assembleDebug
gradlew.bat assembleRelease
gradlew.bat lint
gradlew.bat test                       # all JVM unit tests (app/src/test)
gradlew.bat test --tests "*.ExampleUnitTest"   # a single test class
gradlew.bat connectedAndroidTest       # instrumented tests (app/src/androidTest) — needs an emulator/device attached
```

There is no CI config, no custom Gradle tasks, and no lint baseline file in the repo — `lint` runs
with AGP defaults.

### Architecture: the request flow that matters most

The single most important thing to understand before touching this codebase is the **provisioning
→ policy → launch pipeline**, because it spans multiple files and two separate Android user
profiles (processes):

1. **Personal profile**, `MainActivity` (`isManagedProfile == false`) calls
   `ProfileManager.buildProvisioningIntent()` and starts it for result. Android shows its own
   system provisioning UI — this cannot be skipped or themed.
2. Android creates the work profile, copies this same APK into it, and grants
   `SecureFolderAdminReceiver` Profile Owner status there.
3. `SecureFolderAdminReceiver.onProfileProvisioningComplete()` (fires in the **personal** profile)
   and `.onEnabled()` (fires in the **work** profile) both call
   `PolicyEnforcer.applyAllInitialPolicies()` — this is the only place all DPM policies get applied
   at once.
4. The user opens the **work-profile instance** of the same app (a second launcher icon appears)
   to sideload the banking APK via `BankingAppInstaller` → `InstallResultReceiver`.
5. Back in the **personal profile**, `MainActivity` → `HealthCheckEngine.runAllChecks()` gates
   `SecureLauncher.attemptLaunch()`, which unsuspends the banking app, resolves the work profile's
   `UserHandle`, and launches it cross-profile via `LauncherApps`.
6. `SecureMonitorService` then polls `HealthCheckEngine` every 2s for the life of the session and
   kills it (broadcast + `stopSelf()`) the moment any blocking check fails.
7. `BootReceiver` re-suspends the banking app and re-asserts runtime policies on every reboot.

If you're asked to add a new security control, the pattern is: add a constant to
`SecurityConstants`, add the enforcement call to `PolicyEnforcer` (and to
`device_admin_policies.xml` if it's a new DPM policy category), and — if it's something that can
drift at runtime rather than being permanently locked by DPM — add a corresponding check to
`HealthCheckEngine` so `SecureMonitorService` can detect a live violation, not just prevent one at
provisioning time.

### Development notes / current gaps (don't assume these are finished features)

- **`SecureKeyboardService` is a stub.** It satisfies the manifest/IME allowlist reference but has
  no real keyboard UI, no numeric-subtype switching, and no clipboard-blocking logic yet — despite
  what the XML/doc comments describe as the intent.
- **Several `HealthCheckEngine` checks are implemented but disabled** (commented out of
  `runAllChecks()`): developer-options detection, active screen-recording-app detection, known
  risky-package detection, untrusted-accessibility-service detection, overlay-capable-app
  detection, suspicious-keyboard detection. Only 4 of ~10 written checks currently run. Don't
  assume the "known risky packages" blocklist in `SecurityConstants` is actually being enforced at
  runtime until you check whether its consuming check is enabled.
- **`PolicyEnforcer.lockdownInstallation()` is a no-op** — all three `DISALLOW_INSTALL_*` calls
  inside it are commented out, so the work profile does not currently block app installation via
  DPM (this is presumably intentional short-term, since the work-profile flow relies on being able
  to sideload the banking APK, but it means nothing is stopping other installs either — check with
  the project owner before assuming this is closed).
- **No release signing config is populated** — `app/build.gradle`'s `signingConfigs.release` block
  is present but fully commented out. There's no keystore checked into the repo (correctly — do not
  add one).
- **Debug builds have `debuggable false`** — this is deliberate for a security container (prevents
  ADB attach to a debug build), but it also means you can't attach a debugger to `assembleDebug`
  output through normal means; keep this in mind if asked to "just debug it directly."
- **No dependency injection framework, no Repository/ViewModel layering, no tests beyond the
  unmodified Android Studio templates.** Classes are plain constructor-injected
  (`class Foo(private val context: Context)`) and instantiated directly where used (mostly in
  `MainActivity`). Don't introduce Hilt/Dagger/MVVM scaffolding unless asked — it isn't part of the
  existing pattern.
