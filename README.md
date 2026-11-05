# Jarvis Tablet

Magic Mirror-like Jarvis HUD with always-on **Hey Jack** on the Lenovo Tab
P11 (TB-J606F, Android 11, 2000x1200), shipped as a sideloadable APK.

- Package: `net.npalakurla.jarvis.tablet`
- PRD: `projects/jarvis-lenovo-apk-prd.md` (v2.2, 5 Nov 2026) — also at
  https://jarvis-lenovo-apk-prd-v4lk.pages.grok.bot/
- Status: **Phase 1 — HUD APK code written 2026-11-05** (Kotlin WebView
  shell + bundled HUD + wake-service shell + CI debug build). No APK
  built yet — the first CI run happens on the next push to `main`.
  **Phase 0 evidence is still open** (HA version, expose audit, Whisper
  + Piper, Grok trial, HA user + token, keystore, wireless ADB) — those
  checks touch HA / the Mini / the tablet, so they need LAN access (see
  below). Phase 1 code was written on Simba's "i want you to build it"
  (2026-11-05); each phase still needs his explicit go-ahead before
  merge or sideload.

## What needs Tailscale, and what doesn't

- **Building the app: no Tailscale needed.** The code lives in this
  repo, GitHub Actions compiles the debug APK on every push, and the
  APK comes back as a downloadable artifact. That is how Phase 1 was
  built — entirely in the cloud.
- **Phase 0 checks: need LAN.** HA version, the expose audit, Whisper
  + Piper install, the Grok trial, the HA user + token, the keystore
  check, and the wireless-ADB reboot test all run on HA (192.168.20.11),
  the Mac Mini, or the tablet (192.168.20.109:5555). The cloud cannot
  reach those addresses. The path is the `muse-mgmt` VM's Tailscale
  approval (pending since 2026-10-27): once approved, the cloud SSHes
  in and runs those steps itself.
- **Sideload + on-device testing: need LAN.** `adb install -r` talks
  to the tablet over Wi-Fi adb. Alternatives without Tailscale: Hermes
  / Grok Bot runs the LAN steps from the Mini, or Simba runs
  `scripts/adb-install.sh` from the Mini himself after downloading the
  CI-built APK.

## Locked decisions (do not re-ask)

- Wake word on tablet: **Hey Jack** (openWakeWord on-device, custom
  `hey_jack.onnx` via the synthetic-data notebook). **Hey Jarvis is
  forbidden on the tablet** — it is the LG G2's wake word. Mirror: hey
  mirror.
- Grok agent: **pajeronda/xai_conversation** (YAML tools), fallback
  braytonstafford/grok_conversation. STT/TTS: **local Whisper + Piper** —
  audio stays home.
- Latency: wake to spoken reply **P50 ≤ 4 s**.
- Confirm: spoken yes or tap, **10 s timeout cancels**. Locks, garage and
  alarm are **never** voice-controllable, for anyone. Kids use everything
  else; rules are universal (HA cannot ID speakers).
- Quiet hours **21:00–07:00 America/New_York**, TTS capped at 20% at the
  endpoint, proactive speech suppressed, confirm cards still shown.
- Pickup banner: show from −30, cyan until −10, amber −10 to start, red
  start to +15.
- MVP voice is HA Assist + Grok HACS turn-based. Realtime Grok bridge is
  Phase 4, optional, only if turn-based fails family testing.
- xAI key lives in HA only. HA token lives in Keystore-backed
  EncryptedSharedPreferences on the tablet, never in the WebView.
- Media nav dropped from v1. Pure PWA ruled out; Capacitor is the accepted
  alternate shell if the Kotlin WebView shell stalls.

## Phases (each needs Simba's explicit go-ahead before merge or sideload)

0. **Preconditions** — HA version, expose audit, Whisper + Piper install,
   xai_conversation trial (tools off), HA user + token, grocery base URL,
   repo + keystore, wireless ADB survives reboot. See
   `docs/phase0-checklist.md`.
1. **HUD APK** — WebView shell, six views, banner, dock, offline stamps,
   boot + immersive + battery exemption, sideload over Wi-Fi adb.
2. **Voice + policy** — openWakeWord service, tuning week, tap-to-talk,
   pipeline, policy controller, confirm cards, quiet hours.
3. **Parity + grocery** — PVE, bots, Immich, cams polish; Mini grocery
   proxy and grocery read (if a read token exists); confirm flow for
   writes once Simba approves the write account.
4. **Optional realtime** — only if turn-based fails family testing; same
   policy controller; cost caps.

## Layout

- `android/` — Gradle project (`settings.gradle.kts`, `app/` module):
  Kotlin WebView shell APK + native wake-word foreground service +
  native HA client (token in Keystore). Sources under
  `app/src/main/java/net/npalakurla/jarvis/tablet/`; HUD assets under
  `app/src/main/assets/web/`. CI builds it (`.github/workflows/`).
- `web/` — bundled Jarvis HUD (HTML/CSS/JS, Magic Mirror tokens).
  The source of truth is `android/app/src/main/assets/web/`; this
  folder holds the design notes.
- `wake/` — openWakeWord model + training/tuning notes (`hey_jack.onnx`).
  Model not trained yet — see `docs/wake-tuning.md`.
- `ha/` — Assist pipeline, policy controller, expose audit, YAML tools.
- `mini/` — Mini grocery proxy (LAN only, holds the token + dedupe logic).
- `docs/` — phase checklists, grocery contract, bridge contract.
- `scripts/` — build + sideload helpers.

## Guardrails

- Do not break Mini Home Status (:8790 / :8791), the Magic Mirror, or the
  LG G2.
- Do NOT create a write-enabled grocery service account until Simba says
  yes. Grocery writes stay blocked; reads may ship once a read-only token
  exists.
- No secret (xAI key, HA token, grocery token, keystore password) ever
  goes in this repo, in the WebView, in logcat, or in chat. Keystore lives
  at `~/.config/grokbot-secrets/tablet-display/jarvis-tablet.keystore`
  (mode 600) on the Mini — never committed.
- Sideload: `adb connect 192.168.20.109:5555 && adb install -r
  android/build/jarvis-tablet.apk`
