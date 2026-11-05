# Wake tuning log (PRD v2.2 — Phase 2)

Status: **model not trained yet.** `wake/hey_jack.onnx` does not exist;
`WakeModel` reports `missing` and the service runs mic-level metering
only. No wake detection runs until this log records a trained model.

## Recipe (from the PRD)

1. Train `hey_jack.onnx` — openWakeWord synthetic-data notebook,
   Piper positives, Hey Jarvis / hey mirror / TV dialogue as negatives.
   If the notebook will not run (reported bit-rotted since 2023), train
   locally with the same recipe (Piper positives + precomputed negative
   features + `melspectrogram.onnx` / `embedding_model.onnx` + a small
   classifier) and record the substitution here. Do not pick a different
   wake engine without Simba's go-ahead.
2. Quiet room — 20 tries each at 1 m and 3 m, two adults and one child;
   record hit rate (target ≥ 9/10 at each distance).
3. TV on — 10 min false-trigger run, then 20 tries at 3 m over TV.
4. Tune and retrain — threshold, patience, extra negatives; repeat.
5. 3 m fallback — if the built-in mic misses, add a USB or satellite
   mic in Phase 2. Not a redesign.
6. Mute-after-wake tail is configurable, 0 ms default (FR-29) — a fixed
   tail clipped chained commands on the G2.

## Log

| Date | Model | Threshold | 1 m (quiet) | 3 m (quiet) | TV 10 min false | 3 m over TV | Notes |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | Not run yet |
