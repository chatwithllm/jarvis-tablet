# Expose audit checklist (Phase 0, item 2)

Hard block by non-exposure: the voice agent can only touch what Assist
is allowed to see. Audit the current exposure and fix it before Phase 2.

## Denylist — must NOT be exposed to Assist / LLM device control

- Every `lock.*` entity
- Every `alarm_control_panel.*` entity
- Every garage actuator — covers, switches, buttons, scripts that move a
  garage door
- The 28 high-risk entities from the 4 Nov 2026 G2 discovery report
  (locks, garage, car, water valve) stay under Tier B confirmations
  where the design allows them at all; the tablet path refuses them
  server-side (PRD FR-25) — UI refusal is cosmetic.

## What to record

- Total entities exposed to Assist (the G2 report found 232; verify live).
- Which of the denylist above is exposed today, if any.
- The dedicated `jarvis-tablet` HA user and what it can see.

## Rule

If a denylisted entity is exposed, remove it from exposure and record
the change. Do not expose anything new to make the audit pass.
