# Native ↔ WebView bridge contract (PRD v2.2)

JSON messages with `type` and `ts`. Transport: `JavascriptInterface` +
`evaluateJavascript` (see `android/.../JarvisBridge.kt`). **No credential
ever crosses the bridge.** Native validates confirm ids against the live
pending action.

Source: PRD v2.2 bridge section, read verbatim from the live PRD page
(2026-11-05 12:52 EST). The event names below are the PRD's exact names.

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
| `cancel` | id? |
| `ui_ready` | none |
| `data_request` | scope: overview, ha, pve, updates, cams, bots, grocery or weather; from?, to? |

## Data: how `data_request` is answered

`data_request` IS in the PRD (it is not an extension). There is NO
`data_response` event in the PRD, so the reply is **not** a bridge event:
native answers a `data_request` by invoking
`window.Jarvis.onData(scope, payload)` directly via `evaluateJavascript`
(see `JarvisBridge.pushData`). The payload shape is
`{ok, states?, events?, error?}` — `states` is the HA state array,
`events` the calendar entities for the pickup banner (overview scope
only). `from`/`to` (ISO timestamps, optional) bound the overview
calendar window; other scopes ignore them. The grocery scope is not
served in Phase 1 (the grocery proxy is Phase 2/3) and answers
`{ok: false, error: "grocery arrives in Phase 3"}`.
