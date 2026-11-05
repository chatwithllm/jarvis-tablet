# web/ — bundled Jarvis HUD (Phase 1)

Plain HTML/CSS/JS, one page, Magic Mirror tokens (black background,
cyan #00E5FF, chamfered cards, thin hairlines, transform/opacity motion
only). Landscape 2000x1200: pickup banner (64 px), left nav rail
(96 px), center content, right result cards (max 2, 560 px), bottom
voice dock (120 px, orb 88 px). Layout dimensions are the implementation
choice — the PRD gives the views, banner timing, and dock contents, not
pixel sizes.

Views: Overview, HA, PVE, Updates, Cams, Bots. No Media in v1.

The live files are `android/app/src/main/assets/web/` — `index.html`,
`styles.css`, `app.js`. This folder holds the design notes only.

The WebView never holds a token or a key. It talks to native only
through the bridge contract — see `docs/bridge-contract.md` (PRD v2.2
event names: `talk_pressed`, `confirm_yes`/`confirm_no`, `cancel`,
`ui_ready`, `data_request`; there is no `data_response` event — native
answers `data_request` by calling `window.Jarvis.onData` directly).

What the HUD does in Phase 1: clock/date, HA state cards, pickup banner
(cyan −30..−10, amber −10..0, red 0..+15, hidden after +15), transcript
area, confirm card with a 10 s countdown, stale (5 min) / offline
(60 min or HA loss) stamps, quiet-hours badge, grey orb when HA is down.
Tap actions on HA entities and live camera streams arrive in Phase 2/3.
