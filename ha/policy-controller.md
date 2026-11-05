# Policy controller (PRD v2.2 — outline for Phase 2)

The policy controller lives in HA and is mandatory. The voice agent has
no raw `call_service`.

## Rules

- **Allowlist + hard denylist** (lock, `alarm_control_panel`, every
  garage actuator). See `expose-audit-checklist.md`.
- The agent only ever calls **`propose_action`**. A pending action has a
  UUID, a 10 s expiry, and a source device.
- Every HA mutation and grocery write needs spoken yes or tap Yes within
  10 s; timeout or no cancels with no service call.
- Pending confirmations are bound to the originating device; a yes in
  another room is ignored (FR-55).
- **Audit log** of proposed / confirmed / refused / expired actions; no
  audio stored (FR-51).
- Quiet hours 21:00–07:00 America/New_York: TTS capped at 20% at the
  endpoint, proactive announcements suppressed, confirm cards and
  transcript still shown (FR-26).
- Kids use every allowed feature; same blocks and confirms for everyone
  (no speaker ID, FR-27).

## YAML tools (pajeronda/xai_conversation)

- `get_state`
- `propose_action`
- `grocery` (reads via the Mini proxy or an HA sensor fed by it; writes
  via `propose_action` → confirm → Mini proxy or HA `rest_command` with
  a write-enabled service-account token — blocked until Simba approves
  the write account, FR-45)
