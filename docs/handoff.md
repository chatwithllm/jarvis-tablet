# Jarvis Tablet — Agent Handoff (2026-11-06)

## Repo

**https://github.com/chatwithllm/jarvis-tablet** (private, owner `chatwithllm`)
- Branch: `main`. **Head before the direct-client slice: `ea63811`**.
- Package: `net.npalakurla.jarvis.tablet`. Device: Lenovo Tab P11 TB-J606F, Android 11.
- This is a Kotlin WebView-shell APK + native Hey Jack wake-word foreground service.
  It renders a JARVIS-style HUD (HTML/JS in `android/app/src/main/assets/web/`)
  and talks to Home Assistant through an IBM ContextForge MCP gateway — never a
  raw HA token on the tablet.

## What this project is

One "Jarvis" voice assistant across three surfaces (tablet, Ubuntu smart mirror,
family phones). The tablet is the first surface. Wake words: tablet "Hey Jack",
mirror "hey mirror", **"Hey Jarvis" is FORBIDDEN on the tablet** (collides with
the rooted LG G2 phone, which already answers to Hey Jarvis). Rules: locks /
garage / alarm are never voice-controllable; other HA changes need a spoken
"yes" or tap confirm (10 s timeout = cancel); kids may use all other voice
features; quiet hours 21:00–07:00 at 20% volume.

Full spec: PRD v2.2 at https://jarvis-lenovo-apk-prd-v4lk.pages.grok.bot/
(PDF also at `~/workspace/user/files/Jarvis_Lenovo_APK_PRD.pdf` on the owner's
machine — not in this repo). Phases 0–4, each needing the owner's explicit
go-ahead. Phase 1 (MVP HUD) and Phase 2 (voice + policy) are built. Phase 3
(grocery read/write app view, PVE/cams parity, wake tuning, family week) is
not started. Phase 4 has the owner's go-ahead: the first slice is a direct
ContextForge `/llm/v1` voice client (the Mini relay stays the fallback).
Optional realtime is still not started.

## Current state — what is done

| Item | Status |
|---|---|
| Phase 1 MVP HUD (FR-01…55, 20 acceptance tests) | Done, device-validated |
| Phase 2 voice: Hey Jack service, Assist pipeline, confirm cards, quiet hours | Built (commit `21fc9c9`), CI green |
| `hey_jack.onnx` wake model (synthetic Piper TTS training) | Shipped (`a8075e5`), CI green — **does NOT detect real human speech, see below** |
| Mic permission at runtime + WakeService restart on grant | Done (`fb3ba23`), CI run #14 green, **device-validated PASS 2026-11-06 19:03 ET** |
| ContextForge MCP rework (no raw HA token on tablet) | Done (`2601d02`, `9fb16b3`), device-validated |
| Cleartext HTTP for the LAN gateway | Done (`62513f1`), device-validated |
| Stable CI debug signing (canonical key `e71000e0…`) | Done (`f4556f8`), tablet migrated, `install -r` works in place, no wipe |
| Mini voice relay (`mini/jarvis-mini.py`) | **Deployed and working** at `http://192.168.20.217:8793` (`GET /health` → ok, voice: true). URL saved on the tablet. Tap-to-talk works end-to-end. |
| Tap-to-talk | **PASS on device** — "What time is it?" → transcript + current time |
| Direct ContextForge `/llm/v1` voice client (Mini relay stays the fallback) | Built — needs on-device validation |

## What is blocked / pending

### 1. Wake word — Vosk grammar (onnx model retired on the tablet)
- Device tests: no `wake_detected` for real human "Hey Jack" / "Hi Jack" at any
  threshold (0.3–0.9 barely matters). The 0.5-threshold build was tested live
  and still missed both phrases, with no speech-to-text following.
- Root cause: `hey_jack.onnx` was trained only on Piper TTS synthetic voices.
  Worse, "Hi Jack" was literally a phrase the model learned to **ignore**
  (it was in the negative training set as a confusable).
- Public datasets were searched and contain nothing usable: LibriSpeech clean
  (5,323 utterances) — 0 hits for "Hey Jack"/"Hi Jack" or any confusable;
  Common Voice not downloadable in a usable form; GigaSpeech gated.
- **The tablet detector no longer uses `hey_jack.onnx`.** That model does
  not fire on real speech. Wake detection is a Vosk small en-US grammar
  (`VoskWakeEngine`): armed phrases default to okay computer, hey tablet,
  and hey jack; hey jarvis is a reject and must not wake this tablet.
  Confidence is configurable (default 0.85). On-device WAV inject and the
  desktop espeak loop are in `docs/wake-vosk-test.md`. Human recordings in
  `docs/human-recordings.md` are not required for this detector.
- Do not retrain or ship another `hey_jack.onnx` for this tablet. The
  owner asked for a recognizer that works without collecting human
  recordings. `docs/wake-tuning.md` describes the old model only.

### 2. ContextForge /llm/v1 direct client — BUILT, relay stays the fallback
- The owner approved Phase 4. This slice is the direct conversation client.
  Optional realtime is still not started.
- When the LLM bearer is saved, a tap-to-talk / wake turn uses on-device
  `SpeechRecognizer` when the device has one, otherwise the bundled Vosk
  small en-US model in free-form mode (no grammar), then
  `POST {base}/chat/completions`, then on-device
  `TextToSpeech`. When that bearer is missing, speech recognition cannot
  start, or the chat call fails (401/403, unreachable), the turn falls
  back to the Mini voice relay. A failed direct attempt listens a second
  time for the relay, because the recognizer already consumed the first
  utterance and the relay needs WAV audio.
- Endpoint (confirmed by the Developer, 2026-11-06 16:27 ET):
  - Base URL default: `http://192.168.20.217:4444/llm/v1` (Settings)
  - Bearer: token name `cf-jarvis-tablet-llm`. File:
    `~/.config/grokbot-secrets/it-admin/contextforge-token-jarvis-tablet-llm.txt`.
    Not the MCP bearer — that one returns 403 here.
  - Default model: `grok-4.7` (Settings)
  - Shape: `POST {base}/chat/completions`, `Authorization: Bearer`,
    body `model` + `messages` (+ tools). `stream` is false.
- HA reads from the model go through the existing ContextForge MCP tools
  (`ha-get-state` and the other read-only tools). HA changes are only
  `propose_action` → confirm card (spoken yes or tap, 10 s timeout
  cancels). Locks, garage, and alarm are refused. A confirmed change is
  sent to the MCP tool `ha-call-service`. The jarvis-ha virtual server is
  read-only today, so that write fails closed until IT Admin exposes a
  write tool. There is still no raw HA token on the tablet.
- Settings stores the LLM base URL, model, and bearer in `TokenStore`
  (EncryptedSharedPreferences), separate from the MCP bearer. Blank LLM
  bearer on Save keeps the saved one. "Clear LLM token" forces the relay.

### 3. Mini relay — DONE, no action needed
- `mini/jarvis-mini.py` is deployed on the Mac Mini at
  `http://192.168.20.217:8793`. It serves both the voice relay
  (`POST /voice/turn`, `POST /voice/execute`) and the grocery proxy.
  It holds the HA token server-side; the tablet never holds a raw HA token.
- The Mini relay is the fallback voice path. The direct client is preferred
  when the LLM bearer is set; the relay is used when it is missing or the
  direct call fails (see §2). Grocery still goes through this server.

## Architecture (short)

- `android/app/src/main/java/net/npalakurla/jarvis/tablet/`
  - `MainActivity.kt` — WebView host, bridge wiring, mic permission, battery exemption.
  - `WakeService.kt` — foreground service, 16 kHz mic loop, Vosk wake pipeline.
  - `VoskWakeEngine.kt` — grammar-restricted Vosk wake (replaces hey_jack.onnx).
  - `VoiceTurn.kt` — turn state machine (idle → recording → thinking → speaking),
    confirm cards (spoken yes or tap, 10 s), quiet-hours volume,
    `RoutingVoiceTransport` / `MiniRelayTransport`.
  - `ContextForgeLlmTransport.kt` — direct `/llm/v1/chat/completions` client.
  - `DeviceSpeech.kt` — on-device SpeechRecognizer + TextToSpeech for the direct path.
  - `HaClient.kt` — ContextForge MCP streamable HTTP (JSON-RPC 2.0, `tools/call`),
    `TokenStore` (EncryptedSharedPreferences: `cf_url`, `cf_token`,
    `voice_relay_url`, `llm_base_url`, `llm_token`, `llm_model`).
  - `SettingsActivity.kt` — MCP URL + bearer, voice relay URL, LLM base URL +
    model + bearer.
  - `JarvisBridge.kt` — WebView↔native events. WebView→Native: `talk_pressed`,
    `confirm_yes`, `confirm_no`, `cancel`, `ui_ready`, `data_request`.
    Native→WebView: 13 events (see PRD §bridge).
- `android/app/src/main/assets/web/app.js` — the HUD. Grocery branch is a no-op
  (grocery app view is Phase 3, deliberately not wired).
- `mini/jarvis-mini.py` — LAN-only server (voice relay + grocery proxy).
- `wake/` — openWakeWord training (scripts, `VALIDATION.md`, `hey_jack.onnx` +
  `hey_jack.pt`). Training data (`wake/data/`, 1.1 GB) and venv are gitignored.
- `docs/` — `phase0-checklist.md`, `signing.md`, `wake-tuning.md`,
  `human-recordings.md`, `bridge-contract.md`, `grocery-contract.md`.

## Key endpoints and credentials (paths/names only — never values)

- ContextForge LAN: `http://192.168.20.217:4444` (Wi-Fi) /
  `http://192.168.60.189:4444` (Ethernet). Virtual server `jarvis-ha`, id
  `a1345fe8da0d4971bd35621e294baed3`. MCP endpoint:
  `http://192.168.20.217:4444/servers/a1345fe8da0d4971bd35621e294baed3/mcp`.
  Read-only HA tools: `ha-get-states`, `ha-get-state`, `ha-get-history`,
  `ha-get-config`, `ha-get-logbook`. The tablet's own MCP bearer is
  `cf-jarvis-tablet` at
  `~/.config/grokbot-secrets/it-admin/contextforge-token-jarvis-tablet.txt`
  (the `jarvis-ha` file
  `~/.config/grokbot-secrets/it-admin/contextforge-token-jarvis-ha.txt`
  is the bot's token and must not be reused).
- /llm/v1: see §2 above. Token name `cf-jarvis-tablet-llm`. File:
  `~/.config/grokbot-secrets/it-admin/contextforge-token-jarvis-tablet-llm.txt`.
- Grocery API base: `extended.npalakurla.com` (PRD v2.2; differs from
  `extended.npalakurla.net` on file — Phase 0 must verify). Bearer service
  account; read-only 403 unless `allow_write`. **Do not create a write-enabled
  grocery service account** — needs the owner's separate yes.
- Mini relay: `http://192.168.20.217:8793` (deployed, working).
- Home Assistant: 192.168.20.11 (2026.10.4, HAOS 18.4). Tablet adb:
  `192.168.20.109:36427` (TLS wireless debugging; port 5555 closed).
- **Never put a key, token, or password in a commit, an issue comment, or chat.
  Key names, file paths, and endpoint shapes only.**

## Build, push, and validate

- **Cloud build (manual pipeline, Gradle cannot run in the sandbox):**
  `cd android && python3 build-manual.py` → `android/build/jarvis-tablet-debug.apk`
  (~34 MB). Verifies dex classes with `dex_defined_classes()`.
- **CI:** GitHub Actions, `.github/workflows/build-apk.yml` (setup-android v4,
  Gradle 8.7, JDK 17, `assembleDebug`). Fonts (Rajdhani + Share Tech Mono) are
  fetched from Google Fonts at build time — they are NOT committed (binaries).
  Every run logs the restored keystore's SHA-256 fingerprint. Public Actions
  pages show status/duration only; the signed-in browser session shows logs.
  Cache-service warnings on every run are non-fatal.
- **Signing (canonical, do not change):** CI restores the `DEBUG_KEYSTORE_BASE64`
  repo secret to `debug.keystore` in the project dir, and
  `android/app/build.gradle.kts` pins the debug signingConfig to it explicitly.
  Canonical keystore SHA-256:
  `e71000e022861dd8e56a54fe8f0f3f38476795d5e82cac706686aa8c62089919`.
  The tablet is on this key (signer `[462d1434]`). **Later builds install in
  place with `adb install -r` — no wipe.** Do not overwrite the secret.
  Never commit a keystore or APK.
- **Push route (the only one that works):** the GitHub App `push_files` is
  broken (403 ×8, do not retry). Push via the VM git bridge:
  `git -c core.sshCommand="ssh -F /home/hatch/workspace/ssh-muse-mgmt-config" push vm main`
  (the `-c` flag belongs to `git`, before the subcommand). The bridge is a bare
  repo on the management VM (muse-mgmt, Tailscale 100.100.21.54) with a
  post-receive hook that pushes to GitHub. Verify by reading the remote head
  back. Commit messages must separate cloud-verified from still-needs-on-device,
  with FR numbers.
- **Device validation loop (the Developer, Grok Bot, is evidence-only):**
  it deploys the APK, tests on the tablet, and files feedback as GitHub issues
  (title prefix `[Phase 2 test] `). It never changes code, never merges, never
  closes issues, and pushes zero PRs. When asking it to test something, ask it
  to confirm pickup; when it provides feedback, acknowledge it back on GitHub.
  Open issues: #1–4, #6, #8–18 (16 open). Closed: #5, #7. Leave open issues for
  the bot; never close them yourself. The current thread is issue #18.
- **Hard gates:** debug APK only, no release signing; no code changes from the
  evidence bot; do not touch Mini Home Status (:8790/:8791), the Magic Mirror,
  the LG G2 phone, or grocery write accounts.

## Recent commit history (main)

- `dcb1b81` — Docs: human voice recordings needed for Hey Jack retrain (#18)
- `fb3ba23` — Phase 2: restart WakeService when mic permission is granted (#18)
- `f4556f8` — CI: pin debug signing to the restored keystore (#18)
- `a8075e5` — Phase 2: ship hey_jack.onnx wake-word model (trained, validated)
- `8814aa1` — CI: log the debug keystore SHA-256 fingerprint on every run (#18)
- `21fc9c9` — Phase 2 — voice + policy (issues #13, #17)
- `581e980` — merge: stable debug signing (first stably-signed build)
- `62513f1` — cleartext HTTP fix for the LAN gateway (#15)
- `9fb16b3` — ContextForge payload-shape hardening (#12)
- `2601d02` — ContextForge MCP rework (#10)
- `8a9e216` — manifest: declare SettingsActivity (#7)
- `973b7b6` — dex fix: javac step + class-based verification (#5)
- `607dfb6` — Metaspace fix: Gradle `-Xmx2048m -XX:MaxMetaspaceSize=512m`
- `7214e55` — setup-android v4 + hardened R8 download
- `96e337c` — Phase 1 (first bridge push)
- `f79c505` — scaffold

## Known issues and gotchas

- **Hey Jack does not wake on real human speech** (see §1). Threshold changes
  do not help. Retrain on human recordings is the fix.
- **Mic-grant bug (fixed in `fb3ba23`):** WakeService started before the mic
  grant, so its AudioRecord never opened until force-stop + relaunch. Fixed by
  restarting the service on grant. Device-validated PASS.
- **"Connected" status is misleading:** MainActivity sets `haOk` from
  `TokenStore.hasCredentials` (credentials present), not from a live
  connection test. Do not treat it as proof of connectivity.
- **FR-11 pickup banner and FR-08 reboot-to-HUD** were not formally called PASS
  in the latest validation (banner was seen rendering, but not formally confirmed).
- **CI run #7 was once misread as "still in progress"** from a stale page —
  always re-read the run page fresh before concluding.
- **VM Gradle builds run ~84–87 MB** (unstripped onnxruntime libs); CI artifacts
  are ~31–35 MB. Only arm64-v8a is needed for the TB-J606F — a future ABI
  split can shrink the APK.
- The sandbox cannot reach the Mac Mini or the tablet LAN. The cloud can build
  and push; only the Developer (on the owner's side) can sideload and test on
  the real tablet. Name that visibility boundary plainly.

## Standing rules from the owner

- "Let me know first": assess, recommend, prepare, then wait for explicit
  approval before heavyweight builds. A terse "ok" is never consent.
  **A status question is NOT a go-ahead** — answer "is it started / when will
  it start" with status plus a proposed start, and wait for his explicit go.
- **No middleman:** when a task is blocked, ask the Developer over a GitHub
  issue and get feedback there — do not route the technical question through
  the owner. He only hears about a block when there is a result or a decision
  only he can make.
- **Developer loop:** ask the Developer to confirm pickup when assigning a
  test; acknowledge its feedback back on GitHub so it knows it was received.
- Never promise Mini-side work the cloud cannot do or verify. The Developer is
  evidence-only, never a deploy owner.
- Prices/totals first in price research. No unsolicited nudges after delivery,
  except corrections of the assistant's own wrong guidance.
- The owner is Narsi (nickname Simba, confirmed). The repo owner is `chatwithllm`.
