# Phase 0 checklist (PRD v2.2)

> Status note 2026-11-05: Phase 1 code (HUD APK + wake-service shell +
> CI build) was written on Simba's "i want you to build it" — the build
> itself needed no LAN. The evidence items below are still open: every
> one of them touches HA / the Mini / the tablet, so they need LAN
> access (muse-mgmt Tailscale approval, pending since 2026-10-27) or a
> Mini-side run. Nothing below is verified yet.

Status first, then complete what's missing. For each item record FOUND /
MISSING with exact evidence. Phase 1 needs Simba's go-ahead after this
report.

## 1. HA version + install type

- Expected: HA **2026.10.4 on HAOS 18.4** (from the 4 Nov 2026 G2
  discovery report). Verify live; do not trust the note.
- Evidence: _fill in_

## 2. Expose audit

- List what is currently exposed to Assist. Confirm locks,
  `alarm_control_panel`, and every garage actuator are NOT exposed (hard
  block by non-exposure). See `ha/expose-audit-checklist.md`.
- Evidence: _fill in_

## 3. Install Whisper + Piper (Wyoming) on HA

- Report versions and that each one answers.
- Evidence: _fill in_

## 4. Trial pajeronda/xai_conversation with tools OFF

- One simple query, latency. Fallback braytonstafford/grok_conversation
  ONLY if the trial fails — report it, do not switch silently.
- Evidence: _fill in_

## 5. HA user + token

- Dedicated non-admin user `jarvis-tablet`, revocable. Record where the
  token lives and that it is scoped and revocable. It goes into
  Keystore-backed EncryptedSharedPreferences on the tablet in a later
  phase; for now, never in the WebView or any bundled asset.
- Evidence: _fill in_

## 6. Grocery

- Base URL from the contract (`docs/grocery-contract.md`). Check the READ
  routes live (`GET /shopping-list?status=open`,
  `GET /analytics/spending-by-category?month=YYYY-MM`) ONLY if a
  read-only token already exists; if no token exists, say so and move on.
- Do NOT probe write routes live — the PRD states they come from the
  app's source on main and have not been probed.
- Do NOT create a write-enabled service account.
- Evidence: _fill in_

## 7. Repo + keystore

- Repo: `jarvis-tablet` (this repo). Layout `android/`, `web/`,
  `wake/`, `ha/`, `mini/`, `docs/`, `scripts/`.
- Signing keystore:
  `~/.config/grokbot-secrets/tablet-display/jarvis-tablet.keystore`,
  mode 600, on the Mini. Report location only — never print passwords or
  key material.
- Evidence: _fill in_

## 8. Wireless ADB survives reboot

- Reboot the tablet and confirm wireless ADB reconnects without USB
  (`adb connect 192.168.20.109:5555`).
- Evidence: _fill in_

## Hard gates

- Do NOT create a write-enabled grocery service account.
- Do NOT touch Mini Home Status (:8790 / :8791), the Magic Mirror, or the
  LG G2.
- Do NOT build or sideload the APK — that is Phase 1.
- No merges, deploys, or config edits beyond what Phase 0 names.

## Deliverable

One-page report: each item VERIFIED (with evidence) or STILL MISSING
(with what blocks it), max 5 owner-only questions, and a Phase 1
readiness verdict (ready / not ready + why).
