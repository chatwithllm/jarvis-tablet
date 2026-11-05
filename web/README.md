# web/ — bundled Jarvis HUD (Phase 1)

Plain HTML/CSS/JS, one page, Magic Mirror tokens (black background,
cyan #00E5FF, chamfered cards, thin hairlines, transform/opacity motion
only). Landscape 2000x1200: pickup banner (64 px), left nav rail
(96 px), center content, right result cards (max 2, 560 px), bottom
voice dock (120 px, orb 88 px).

Views: Overview, HA, PVE, Updates, Cams, Bots. No Media in v1.

The WebView never holds a token or a key. It talks to native only
through the bridge contract — see `docs/bridge-contract.md`.

Phase 0 does not build this. Phase 1 only, after Simba's go-ahead.
