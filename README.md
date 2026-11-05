# Jarvis Tablet

Magic Mirror-like Jarvis HUD with always-on **Hey Jack** on the Lenovo Tab
P11 (TB-J606F, Android 11, 2000x1200), shipped as a sideloadable APK.

- Package: `net.npalakurla.jarvis.tablet`
- PRD: `projects/jarvis-lenovo-apk-prd.md` (v2.2, 5 Nov 2026) — also at
  https://jarvis-lenovo-apk-prd-v4lk.pages.grok.bot/
- Status: **Phase 0 — preconditions.** No APK built yet.

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

- `android/` — Kotlin WebView shell APK + native wake-word foreground
  service + native HA client (token in Keystore).
- `web/` — bundled Jarvis HUD (HTML/CSS/JS, Magic Mirror tokens).
- `wake/` — openWakeWord model + training/tuning notes (`hey_jack.onnx`).
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
