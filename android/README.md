# android/ — Kotlin WebView shell APK (Phase 1)

Gradle project — build from `android/`: `gradle :app:assembleDebug`
(or CI on push to `main`).

Cloud-sandbox build (no Gradle daemon): `scripts/build-apk.sh` from the
repo root runs the manual pipeline (`android/build-manual.py`: aapt2 +
kotlinc + R8 + zipalign + apksigner, debug signing only). It exists
because the Gradle daemon cannot run in the cloud sandbox — the sandbox
network filter blocks loopback TCP from Java processes, so the Gradle
client can never talk to its daemon. It needs ANDROID_HOME (SDK 34),
JAVA_HOME (JDK 17), kotlinc, and `android/libs/resolved.txt`
(`android/libs/resolve.py` re-downloads the dependencies). The debug
APK lands at `android/build/jarvis-tablet-debug.apk`.

## What is here (written 2026-11-05, Phase 1)

- `app/src/main/java/net/npalakurla/jarvis/tablet/MainActivity.kt` —
  immersive sticky shell (FR-01), screen-on (FR-03), back reloads the
  HUD (FR-04), render-process crash recovery (FR-05), battery
  exemption (FR-06), 60 s HA heartbeat (FR-07), bridge host.
- `JarvisBridge.kt` — bridge contract (PRD v2.2 event names; see
  `docs/bridge-contract.md`). No credential crosses the bridge.
- `HaClient.kt` + `TokenStore` — ContextForge MCP client (issue #10);
  gateway URL + scoped `jarvis-ha` bearer in Keystore-backed
  EncryptedSharedPreferences, never in the WebView. The gateway is
  read-only, so the FR-07 heartbeat is a liveness probe, not an HA
  sensor write. 401 surfaces as `auth_required` (FR-54 re-auth prompt).
- `PolicyController.kt` — Phase 2 policy rules as pure logic: hard
  denylist (lock / alarm / garage, FR-25), 10 s confirm expiry
  (FR-24), source-device binding (FR-55), audit log (FR-51).
- `WakeService.kt` + `WakeModel.kt` — Phase 2 foreground mic service
  (16 kHz, 80 ms frames, START_STICKY, pinned notification). The
  `hey_jack.onnx` model is not trained yet, so detection is a stub
  until Phase 2; level metering + tap-to-talk path already work.
- `BootReceiver.kt` — FR-02/FR-08 reboot recovery.
- `SettingsActivity.kt` — first-run HA URL + token setup / re-auth.
- `app/src/main/assets/web/` — the bundled HUD (index.html, styles.css,
  app.js): six views, pickup banner (FR-11), dock (FR-13), transcript
  card (FR-15), Updated/stale/offline stamps (FR-16), confirm card
  (FR-24), quiet-hours badge (FR-26).

## Still open (needs LAN, not code)

- Phase 0 evidence: HA version, expose audit, Whisper + Piper,
  xai_conversation trial, HA user + token, keystore, wireless ADB
  reboot test — see `docs/phase0-checklist.md`.
- First CI build (runs on next push to `main`); sideload + on-device
  acceptance tests (PRD acceptance list, none run yet). A debug APK was
  already built in the cloud sandbox on 2026-11-05 via the manual
  pipeline (`android/build-manual.py` → `android/build/jarvis-tablet-debug.apk`,
  32,089,169 bytes, aapt2 badging + apksigner verified) — cloud-verified
  only, not yet run on the tablet.
- Phase 2 voice pipeline (STT → agent → TTS) and the trained
  `hey_jack.onnx` model.
