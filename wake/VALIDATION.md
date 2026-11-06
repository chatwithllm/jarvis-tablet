# hey_jack.onnx — Training & Validation Report

Date: 2026-11-06. Trainer: subagent (cloud sandbox, CPU-only).
Repo: `~/workspace/jarvis-tablet/wake/`.

## Deliverables

| File | Size | Notes |
|---|---|---|
| `hey_jack.onnx` | 430,183 bytes | Trained classifier, input `[1, 16, 96]` float32 → `[1, 1]` float32 |
| `melspectrogram.onnx` | 1,087,958 bytes | Pre-trained openWakeWord feature model (shipped in the pip package) |
| `embedding_model.onnx` | 1,328,103 bytes | Pre-trained openWakeWord feature model (shipped in the pip package) |

These three are the assets `WakeModel.kt` loads from `assets/wake/`.

## Tensor I/O (exact, verified with onnxruntime)

### melspectrogram.onnx
- IN: `input`, shape `['batch_size', 'samples']`, `tensor(float)`
- OUT: `output`, shape `['time', 1, 'Clipoutput_dim_2', 32]`, `tensor(float)`
- Note: `Clipoutput_dim_2` is a dynamic dim (depends on input length). WakeModel.kt feeds `[1, 1760]` and applies `spec/10 + 2` to the output rows.

### embedding_model.onnx
- IN: `input_1`, shape `['unk__316', 76, 32, 1]`, `tensor(float)`
- OUT: `conv2d_19`, shape `['unk__317', 1, 1, 96]`, `tensor(float)`
- Note: WakeModel.kt feeds `[1, 76, 32, 1]` and takes the first 96 values.

### hey_jack.onnx
- IN: `x.1`, shape `[1, 16, 96]`, `tensor(float)`
- OUT: `output`, shape `[1, 1]`, `tensor(float)` (sigmoid score 0..1)
- Matches `WakeModel.kt` exactly: it reads the frame count (16) from the
  input shape and feeds the last 16 embeddings as `[1, 16, 96]`.

## Versions

- openwakeword 0.4.0, torch 2.15.0+cpu, onnxruntime 1.31.1, onnx 1.23.2, piper-tts 1.8.0
- Python 3.12.3, venv at `wake/venv/`
- openWakeWord recipe: `training_models.ipynb` from
  github.com/dscripka/openWakeWord (cloned 2026-11-06), plus the
  synthetic-data doc.

## Training data (all Piper TTS, 16-bit 16 kHz mono WAV)

- 16 Piper voices: en_US amy, arctic, danny (low), joe, kathleen (low),
  kristin, lessac, libritts (high), ljspeech, norman, ryan, sam;
  en_GB alan, alba, aru, cori.
- Positives: "Hey Jack", 15 speed/noise variations per voice.
- Negatives: "Hey Jarvis", "hey mirror", "Hey John/Jake/James/Jacob/
  Jason/Jackson/Jackie/Zack/Mack/Hank", "Jack", "jackpot", "jacket",
  "hijack", plus 15 generic assistant phrases.
- Totals: 230 positive clips, 634 negative clips.
- Held out (validation only): voices en_GB-cori-medium, en_US-sam-medium
  (20 positive clips, 68 negative clips). All other voices are training.
- A further 120 positive + 264 negative clips from 24 extra libritts
  speakers are set aside in `data/clips_libritts/` (they HURT validation
  when included — see below).

## Training (final model)

- Window: 2 s → 16 embedding frames × 96 dims (matches WakeModel.kt).
- Features via `openwakeword.utils.AudioFeatures` (melspec + embedding).
- Positive windows: "Hey Jack" mixed with random negative background at
  SNR 5–15 dB, phrase ending 0–200 ms before window end (2 mixes/clip),
  plus clean "Hey Jack" in a silent 2 s window (1/clip).
- Negative windows: stacked clean speech (2 passes), mixed with
  background at SNR 5–15 dB at random position (2 mixes/clip), plus clean
  negative in a silent 2 s window (1/clip).
- Training set: 3,027 rows (630 positive, 2,397 negative).
- Model: FCN — Flatten → Linear(1536, 64) → LayerNorm → ReLU →
  Linear(64, 64) → LayerNorm → ReLU → Linear(64, 64) → LayerNorm →
  ReLU → Linear(64, 1) → Sigmoid. (Notebook recipe is layer_dim 32 /
  2 layers; 64 / 3 layers was tried and kept — it performed the same.)
- Loss BCE, Adam lr 0.001, 100 epochs, batch 64, positives weighted 0.5
  (negatives 2×). Train recall 1.000, precision 1.000.

## Validation (final model, held-out voices cori + sam)

Two methods, both via the full openWakeWord pipeline:

### A. Clean clips (openWakeWord `predict_clip`, phrase padded with silence)
- Positives: **20/20 = 1.00** hit rate @ 0.5 (also 1.00 @ 0.8)
- Negatives: **20/68 false accepts @ 0.5** (29%), 20/68 @ 0.8
- Train-voice positives (sanity, n=20): 18/20

### B. Mixed windows (phrase + background speech at SNR 5–15 dB, phrase
ending near window end — the realistic streaming condition)
- Positives: **50/60 = 0.83** hit rate @ 0.5 (0.83 @ 0.8; 3 mixes/clip)
- Negatives: **33/204 false accepts @ 0.5** (16%), 32/204 @ 0.8
- Threshold sweep: hit 0.85 @ 0.3, 0.83 @ 0.5, 0.82 @ 0.9; FA 0.17 @ 0.3,
  0.16 @ 0.5, 0.15 @ 0.9 — no threshold fixes the FA rate.

### C. Long negative stream (all negative clips concatenated, 12.9 min)
- ~974 false-accept frames/hour @ 0.5 (all confusable phrases back to
  back — a stress test, not a realistic room).

## Honest assessment

- **Hit rate meets the 9/10 target on clean clips (20/20) and is close
  on mixed windows (0.83–0.92 across runs).**
- **False-accept rate is too high on confusable phrases.** It is
  concentrated in "Hey Jackson", "Hey Jackie", "Hey Jarvis", "Hey John"
  (the closest phonetic neighbours of "Hey Jack"). On ordinary
  non-confusable speech the model rejects correctly.
- The built-in `hey_jarvis_v0.1` model scores 0.997–0.999 on my
  "Hey Jarvis" clips and 0.05–0.38 on my "Hey Jack" clips, proving the
  phrases ARE distinguishable and my clips are correctly labelled — my
  model just hasn't learned the distinction as robustly.
- Six training rounds were tried (more data, more confusables, mixed +
  clean negatives, larger model, more epochs, 24 extra libritts
  speakers). The FA rate stayed ~16% mixed / ~30% clean in every round.
  Synthetic-only data with 14–38 voices appears to be the limiting
  factor; the openWakeWord recipe reaches its published quality with
  much larger datasets including real human speech (Common Voice, etc.).

## Deviations from the standard recipe

1. **Window 2 s / 16 frames** instead of the notebook's 3 s / 28 frames,
   because `WakeModel.kt` is written for `[1, 16, 96]`.
2. **Mixing re-implemented in numpy** (`train.py: mix_clip`) instead of
   `openwakeword.data.mix_clips_batch`, because `openwakeword.data`
   imports speechbrain/torchaudio (not installed). Same SNR-mixing math.
3. **Batch 64, 100 epochs, positive weight 0.5** instead of batch 512 /
   10 epochs / weight 0.1 — the notebook's settings give only 2 steps
   per epoch on 3,027 rows and drove train recall to 0.
4. **Clean positives and clean negatives added** (phrase in a silent 2 s
   window) — the notebook only mixes. Without them the model learns
   acoustic-condition shortcuts ("has background" / "surrounded by
   silence" = positive).
5. **libritts multi-speaker data (24 extra speakers) tried and REMOVED**
   — it hurt validation (mixed hit 0.92 → 0.80). Set aside in
   `data/clips_libritts/` for future use.

## Recommended next steps (for the parent agent)

1. Ship this model for Phase 2 development and run the on-device tuning
   in `docs/wake-tuning.md` (20 tries at 1 m / 3 m, TV false-trigger
   run). Real-room performance may differ from synthetic validation, and
   the threshold is runtime-configurable.
2. If the on-device FA rate is unacceptable, the fix is more/better
   training data — real human speech negatives (Common Voice etc.) and
   more confusable variation — not more epochs or a bigger classifier.
3. Repro: `wake/venv/bin/python train.py` (regenerates features +
   `hey_jack.onnx` from `data/clips/`), `validate.py` (clean),
   `validate2.py` (mixed). Data generation: `gen_data.py`,
   `gen_data2.py`, `gen_data3.py` (libritts, set aside).
