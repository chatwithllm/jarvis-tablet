# android/ — Kotlin WebView shell APK (Phase 1)

- WebView shell loading the bundled HUD from `web/`, full-screen
  immersive sticky, no system bars (FR-01).
- BootReceiver starts the activity and wake service after boot and after
  package replace, with no touch (FR-02). Screen stays on while Jarvis
  is foreground (FR-03).
- Native wake-word foreground service (openWakeWord, `hey_jack.onnx`)
  and native HA client. HA token in Keystore-backed
  EncryptedSharedPreferences — never in the WebView, bundle, or code.
- Heartbeat to HA every 60 s with wake-service state (FR-07).
- Battery-optimization exemption; pinned, non-dismissible foreground
  notification (FR-06).

Phase 0 does not build this. Phase 1 only, after Simba's go-ahead.
