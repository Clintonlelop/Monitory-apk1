# Monitory — Integration, Repair & Build Stabilization Report

**Destination:** https://github.com/Clintonlelop/Monitory-apk1 (`main` @ `4c46b99`)
**Source:** https://github.com/Clintonlelop/Monitory-apk (`main` @ `bcd2e5a`)
**Integration branch:** `arena/c7ebd87c-monitory-apk1` → PR targeting `Monitory-apk1` `main`
**Date:** 2026-10-19
**Status:** All feasible checks executed and passing in this environment. Android
artifact builds are delegated to CodeMagic (see *Outstanding issues* for the
sandbox network limitation).

---

## 1. Repository changes

### Source commits reviewed (Monitory-apk, newest first)

| Commit | Summary | Decision |
|---|---|---|
| `bcd2e5a` | Merge PR #5 (arena/a6e658e6) | baseline for comparison |
| `7172bdf` | end-to-end command center — canonical contract, real capture, media pipeline | **already present** in destination tree |
| `8cce6f3` | working storage access, manual permission grants, permission center | already present |
| `7915674` | full-stack security remediation, data-integrity fixes, regression tests | already present (authz, WS auth, upload auth) |
| `aca1847` | QR-based device pairing and theme toggle | already present (QR payload parsing, pairing UI) |
| `8202b33`…`ee17f86` | network handling, Compose migration, CI keystore history | superseded |

Comparison method: full tree diff `Monitory-apk@bcd2e5a` vs `Monitory-apk1@4c46b99`
(`diff -rq` + per-file diffs), then deliberate per-file reconciliation.

### Changes integrated from source

| File(s) | Why |
|---|---|
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}` | **Missing entirely** in destination — `codemagic.yaml` runs `./gradlew`, so every CodeMagic build was structurally broken. Gradle 9.3.1 wrapper matches AGP 9.1.1. |
| `app/src/test/java/com/example/ExampleRobolectricTest.kt`, `GreetingScreenshotTest.kt`, `app/src/test/screenshots/greeting.png` | Source's instrumentation/unit tests; ported with their missing test dependencies (below). |
| Test deps in `app/build.gradle.kts` | Source's tests import Robolectric/Roborazzi/ui-test-junit4 but neither repo declared the dependencies — tests could not compile. Added `robolectric`, `androidx-compose-ui-test-junit4`, `roborazzi*` from the existing version catalog. |

### Destination-specific behavior preserved (deliberately NOT overwritten)

- `UNLOCK_DEVICE` feature (repaired, not removed — §4).
- `debugConfig` signing config for CodeHardened CI builds (rebuilt as env-var driven — §5).
- `ScreenCaptureService.obtainProjection` null-data guard.
- `AuthScreen` selectable server URL text.
- Destination's richer `ExampleUnitTest.kt` (kept over source's minimal one).
- Committed `data/app.db*` and `server/uploads/` history files (per “do not delete databases/user data”; new test uploads are git-ignored).

### Conflicts resolved

| Conflict | Resolution |
|---|---|
| Source: `DEFAULT_SERVER_URL = ""` vs destination: hardcoded `https://ais-dev-…run.app` | Adopted source policy (empty default) and extended it with validation UI/tests. The hardcoded Cloud Run URL is removed from `PreferenceManager`, `.env.example`. |
| Source's `getHostUrls()` server-side normalization vs destination's path-stripping tweak | Kept destination's `URL`-parsing path strip (`/admin/nodes|/admin|/dashboard`) — it is strictly safer; kept source's no-forced-https behavior. |
| `RECORD_AUDIO` default 10 (Android fallback) vs 30 (registry) | Aligned to 30 (registry is the contract source of truth). |
| Dashboard drift: `UNLOCK_DEVICE` UI existed only in `server/public/` while the served tree is `public/` | Ported button to `public/`, applied honest wording to both, re-synced the trees byte-identical. |

---

## 2. Hardcoded server URL — removed (requirements §3)

**Before:** `PreferenceManager.DEFAULT_SERVER_URL`/`DEFAULT_DASHBOARD_URL`/`getPublicWebUrl()`
hardcoded to `https://ais-dev-lii3mjsnmsi2wtqrn5nqd2-77493896896.europe-west3.run.app`;
sign-in silently coerced arbitrary input; pair-device “server discovery” could
overwrite the user's URL with the server's self-reported URL.

**After:**

- `DEFAULT_SERVER_URL = ""` — nothing is baked in; `isServerConfigured()` drives
  an explicit “No server configured yet” state in the UI.
- `validateServerUrl()` (pure, unit-tested in `ServerUrlValidationTest`):
  - accepts only `http://`/`https://` (scheme inferred only for bare host/IP);
  - rejects spaces, non-http schemes (`ftp:`, `javascript:` …), `user:pass@`
    userinfo, query strings, fragments, missing hosts, bad ports — each with a
    specific human-readable message;
  - strips trailing slashes and dashboard page paths (`/admin/nodes`, `/admin`,
    `/dashboard`) so API/WS segments are never duplicated.
- Endpoint derivation uses the backend's real routes: Retrofit base = normalized
  URL with **relative** `api/…` paths (works behind sub-path proxies), WebSocket
  = `ws(s)://<base>/ws/device` via `deriveWsDeviceUrl()` — the same configured
  server for QR pairing, manual pairing, auth, API and WS.
- `setServerUrl()` persists only validated URLs; invalid input **never**
  overwrites the saved value; blank input clears to “unconfigured”.
- `ServerUrlSection` (used by Pairing and Auth screens): edit/validate/save,
  inline errors, “Test Connection” (calls the real `/health` contract via
  `DeviceRepository.testConnection()`), and persistent display of the currently
  configured address. Switching servers needs no reinstall.
- Removed the silent “canonical URL” overwrite in `pairDevice()` and the
  pre-filled account email default.
- QR payloads set the URL only through the same validated path.

## 3. Release crash — root cause & fix (requirements §5)

**Crash:** `NoClassDefFoundError: androidx.concurrent.futures.AbstractResolvableFuture`
at `ProfileInstaller.writeProfile` ← `ProfileInstallerInitializer.writeInBackground`
runs on every app start via androidx.startup (pulled in transitively by
`androidx.activity`).

**Mechanism (verified against the androidx source mirror via GitHub API):**
`ProfileInstaller.writeProfile()` → `ProfileVerifier.writeProfileVerification()`
→ static field `ResolvableFuture<…> sFuture = ResolvableFuture.create()`.
`ResolvableFuture extends AbstractResolvableFuture`, which
`implements com.google.common.util.concurrent.ListenableFuture`. The two ways
this chain breaks at runtime in release builds:

1. **Dependency conflict:** the graph can resolve `com.google.guava:listenablefuture`
   to the intentionally-empty `9999.0-empty-to-avoid-conflict-with-guava`
   placeholder (a version chosen to win every comparison), shipping
   `AbstractResolvableFuture` **without the interface it implements** — ART then
   fails resolving the class exactly as reported. This failure mode is
   documented in the wild (e.g. VitruvianSoftware/vitruvian-core#2197, which
   confirms against a shipped APK that `AbstractResolvableFuture` was present
   while `ListenableFuture` was the empty jar).
2. **R8 shrinking/class-merging:** the futures types are not referenced by app
   code, so a minified build can drop or merge `AbstractResolvableFuture` away
   (documented R8 behavior), leaving `ResolvableFuture`'s superclass unresolved.

**Fix (root cause, not a workaround — ProfileInstaller is NOT disabled):**

- `gradle/libs.versions.toml` + `app/build.gradle.kts` pin the full chain as
  direct runtime deps: `androidx.profileinstaller:profileinstaller:1.4.1`,
  `androidx.concurrent:concurrent-futures:1.2.0`,
  `com.google.guava:listenablefuture:1.0`, plus
  `resolutionStrategy.force("com.google.guava:listenablefuture:1.0")` so the
  empty `9999.0` placeholder can never win conflict resolution.
- `app/proguard-rules.pro` keeps `androidx.concurrent.futures.**` and the
  `ListenableFuture` interface, so enabling minify later cannot re-introduce
  the crash.
- `codemagic.yaml` **verifies the built artifact**: every produced APK is
  unzipped and its DEX must define
  `Landroidx/concurrent/futures/AbstractResolvableFuture;` and
  `Lcom/google/common/util/concurrent/ListenableFuture;`, or the build fails.
  The exact failure the user saw is now mechanically impossible to ship
  silently.

**Verification status:** the APK rebuild itself could not run in this sandbox
(no Android SDK/Google Maven access — see *Outstanding issues*); the verification
step is executable and mandatory on CodeMagic.

## 4. Full crash diagnostics (requirements §6)

New `com.example.diagnostics` package:

- `CrashDiagnostics.formatThrowable()` expands **every** nested cause and
  suppressed exception with its **complete** stack trace — the `"…8 more"`
  abbreviation from `Throwable.printStackTrace` is never produced.
- `buildReport()` adds: exception class + message, full traces, operation/
  subsystem tag, Android version + device model, app version + build type,
  server connectivity state and pairing state.
- `redact()` masks passwords, tokens, authorization headers, bearer tokens,
  pairing codes, API keys in reports (defense-in-depth; command logging already
  redacts `Authorization`).
- `CrashReportStore` persists reports in `filesDir/crash-reports/` (capped at
  20); `CrashReporter.install()` records every uncaught exception app-wide.
- UI on the privacy screen: list, view, **copy**, **export/share** (FileProvider
  content URI), delete, and “Save Snapshot” for on-demand state capture.
  Reports never upload automatically.

## 5. CodeMagic CI/CD (requirements §7)

- **Gradle wrapper restored** (was missing — the workflow could never run
  `./gradlew`).
- `codemagic.yaml` rewritten: JDK 17, `chmod +x gradlew`, unit tests,
  `assembleDebug` **and** `assembleRelease`, artifacts from both variants plus
  `mapping.txt` and the `releaseRuntimeClasspath` dependency report.
- **No signing material in source.** The base64-embedded keystore blob is
  removed. `DEBUG_KEYSTORE_BASE64` (secure var) is decoded at build time; if
  absent, `keytool` generates a throwaway debug key so builds are never blocked.
  Release signing uses `ANDROID_KEYSTORE_BASE64/_PASSWORD/_KEY_ALIAS/_KEY_PASSWORD`
  secure vars; without them the release APK signs with the debug key and the log
  says so explicitly (documented, not store-ready).
- Post-build verification of the crash-fix classes (§3) fails the pipeline if
  packaging regresses.

## 6. Pairing & QR (requirements §4)

- Verified end-to-end (see §8 `scripts/e2e-flow.mjs`): dashboard generates a
  6-digit code + QR payload (`v, code, serverUrl, wsUrl, server, ws, expiresAt,
  environment`), Android parses both modern and legacy keys and enforces
  `expiresAt`; manual code entry always available; invalid/expired/used codes
  return explicit errors (`E2E_FLOW_PASS`).
- Codes: server-generated (`crypto`), single-use, TTL (30 s default /
  `PAIRING_TTL_SECONDS`), revocable; redeem is validated server-side and
  persists device id + device token.
- App→dashboard direction: the app displays the configured server/dashboard
  address (copyable) for the second device; the established architecture pairs
  via dashboard-issued codes, and the QR flow delivers the server URL to the
  scanner. Disconnect clears pairing state without touching the server URL.

## 7. Lock / Unlock (requirements §10)

- `LOCK_DEVICE` unchanged (`DevicePolicyManager.lockNow()` via Device Admin).
- `UNLOCK_DEVICE` rewritten: transparent `UnlockPromptActivity` wakes the
  display and calls `KeyguardManager.requestDismissKeyguard` — the only
  unlock-like API available to a normal app. Results are reported from the
  `KeyguardDismissCallback`: “keyguard dismissed” vs “wake signal only — a
  secure screen lock can never be bypassed; the device user must authenticate”
  vs “user cancelled”. The old behavior (wake lock + “Device wake and unlock
  signal sent”) is gone. Dashboard button now reads “Wake / Unlock · secure
  locks need the user”; `docs/COMMAND_CONTRACT.md` documents the semantics.

## 8. Media commands (requirements §11)

- Verified `TAKE_SCREENSHOT`, `TAKE_PHOTO`, `START_RECORDING` (+ legacy alias
  `RECORD_SCREEN`, now normalized server-side), `STOP_RECORDING`,
  `RECORDING_STATUS`, `RECORD_AUDIO` in the registry, dispatcher and dashboard —
  names match exactly (`data-cmd` carries registry keys).
- `RECORD_AUDIO` supports 1–60 s (clamped; default 30 s everywhere).
  Screen recording stays `EXECUTING` until `STOP_RECORDING` finalizes and
  uploads; capture requires the existing visible consent UI (`CaptureConsentActivity`,
  MediaProjection) — no covert capture is possible or added.

## 9. Test results (this environment)

| Suite | Command | Result |
|---|---|---|
| Backend unit/integration (auth, authz, commands, lifecycle, ingestion, media, WS) | `npm test` (node --test) | **85/85 PASS** (rerun after every change) |
| End-to-end flow | `npm run test:e2e` (`scripts/e2e-flow.mjs`) | **E2E_FLOW_PASS** — 13/13: health, config URL derivation, bootstrap, pairing-code + QR payload contract, device redeem, invalid/used-code errors, device WS connect at `/ws/device`, `UNLOCK_DEVICE` dispatch → WS delivery → COMPLETED report → dashboard history + device list |
| Dashboard smoke (Chromium 153) | `npm run test:smoke` | **SMOKE_PASS** + **MOBILE_PASS** — login, WS “Live Synced”, device detail (previously-thrown `consoleMap` error stays fixed), and 390×844: no horizontal overflow (scrollWidth=390), pairing QR fully visible & in-viewport, modal card fits, **body scroll locked behind open modal** (the scroll-behind-panel bug), unlock after close, slide drawer opens/closes (class-mismatch regression covered) |
| XSS regression | `npm run test:xss` | **XSS_REGRESSION_PASS** (stored-XSS PoC filename cannot execute) |
| URL validation rules | `ServerUrlValidationTest` (JVM) | written & wired into `./gradlew test` — executed on CodeMagic (needs Android SDK) |
| Android build / APK inspection | `./gradlew assembleDebug assembleRelease` | **Not executable in this sandbox** — see below |

Crash reproduction on a Samsung SM-G991B / Android 15 with a release APK:
**not possible here** (no device/emulator, no APK build — see below). The fix is
root-caused from source evidence and will be artifact-verified on CodeMagic.

## 10. Outstanding issues / environment blockers (confirmed, not assumed)

1. **Android toolchain cannot run in this sandbox (confirmed):** no JDK/Android
   SDK installed, and egress to `dl.google.com`, `maven.google.com`,
   `services.gradle.org` is blocked (TLS reset; verified with curl). Therefore
   `./gradlew` builds, Robolectric tests, APK DEX inspection, and on-device
   installation **could not be executed here**. Everything Android-side is
   statically verified (brace/AST checks, contract cross-checks) and
   mechanically verified on CodeMagic (`test`, `assemble*`, DEX-class check).
2. **CodeMagic run** requires the repository to be connected to CodeMagic; set
   `DEBUG_KEYSTORE_BASE64` (optional) and `ANDROID_KEYSTORE_*` (for
   store-ready release signing). Without them the pipeline still builds and
   verifies artifacts (debug-signed release is documented in the log).
3. **PostgreSQL mode** of the backend was not exercised (no container runtime /
   PG server here); the in-memory mode is fully tested and `server/schema.sql`
   is unchanged. `docker-compose.yml` now **refuses to start** until
   `JWT_SECRET` and `POSTGRES_PASSWORD` are supplied via `.env` (the previously
   committed `JWT_SECRET: production_secure_jwt_secret_key_89214710` must be
   treated as compromised and rotated).
4. **Physical/emulator Android testing** (UI, MediaProjection prompts,
   keyguard behavior across OEMs) remains to be done on real hardware.

## 11. Build reference (for CodeMagic)

- JDK 17, Gradle 9.3.1 (wrapper), AGP 9.1.1, Kotlin 2.2.10, KSP 2.2.10-2.0.2,
  compileSdk 36 / minSdk 26 / targetSdk 34.
- Workflow: `android-native-workflow` in `codemagic.yaml`
  (debug + release APKs → `app/build/outputs/apk/{debug,release}/*.apk`).
