# Native ↔ WebView bridge contract (PRD v2.2)

JSON messages with `type` and `ts`. Transport is the build agent's pick
(`JavascriptInterface` + `evaluateJavascript`, or a `127.0.0.1`
WebSocket). **No credential ever crosses the bridge.** Native validates
confirm ids against the live pending action.

## Native → WebView (13 events)

| Event | Payload |
|---|---|
| `wake_detected` | source: wake or talk, score? |
| `listening_level` | level 0–1, about 15 Hz |
| `transcript_partial` | text |
| `transcript_final` | text |
| `thinking` | step? |
| `tts_started` | text, card? |
| `tts_finished` | none |
| `confirm_requested` | id, action {summary, domain, entity_id, room?, new_state}, expiry |
| `confirm_resolved` | id, outcome: confirmed, declined, expired or refused |
| `refused` | reason, message |
| `error` | code (ha_unreachable, auth_required, stt_failed…), message |
| `status` | ha, wake, quiet, mic |
| `navigate` | view: overview, ha, pve, updates, cams, bots |

## WebView → Native (6 events)

| Event | Payload |
|---|---|
| `talk_pressed` | none |
| `confirm_yes` | id |
| `confirm_no` | id |
| `cancel` | none |
| `ui_ready` | version |
| `data_request` | kind, params? (read-only, proxied by native) |
