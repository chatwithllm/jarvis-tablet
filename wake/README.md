# wake/ — openWakeWord model + tuning (Phase 2)

- Model: `hey_jack.onnx` (openWakeWord, ONNX Runtime; 16 kHz, 80 ms
  frames; all tunables runtime config).
- Train with the openWakeWord synthetic-data notebook: Piper positives,
  Hey Jarvis / hey mirror / TV dialogue as negatives (~1 h GPU).
- Tune: 20 tries each at 1 m and 3 m (two adults, one child), quiet room;
  10 min TV false-trigger run, then 20 tries at 3 m over TV; tune
  threshold/patience, add negatives, repeat. Log in `docs/wake-tuning.md`.
- Mute-after-wake tail is configurable with a 0 ms default (G2 lesson:
  a fixed 0.3 s tail clipped chained commands).
- If 3 m fails on the built-in mic, a USB or satellite mic is in scope
  for Phase 2 — not a redesign.

Phase 0 does not build this. Phase 2 only, after Simba's go-ahead.
