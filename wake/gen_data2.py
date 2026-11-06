#!/usr/bin/env python3
"""Round 2 data: fix the validation gaps found in round 1.

- 'Hey Jackson' was false-accepting (2/30 held-out negatives) — it starts
  with 'Jack'. Add many more Jackson/Jackie/Jack confusables.
- 'Hey Jack' hit rate on unseen voices was 17/20 — add 5 more speed/noise
  variations per TRAIN voice (held-out voices cori/sam untouched).
- Add 'jackpot' / 'jacket' / 'hijack' as extra confusables.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_data import VOICES, synth, OUT_DIR
from piper import PiperVoice

EXTRA_NEGATIVES = [
    ("hey_jackson", "Hey Jackson", 5),
    ("hey_jackie", "Hey Jackie", 5),
    ("jack", "Jack", 3),
    ("jackpot", "jackpot", 2),
    ("jacket", "jacket", 2),
    ("hijack", "hijack", 2),
]

# Extra positive variations (different speed/noise combos)
EXTRA_POS_VARIATIONS = [
    (0.85, 0.667, 0.8),
    (1.15, 0.667, 0.8),
    (1.00, 0.900, 0.9),
    (0.95, 0.800, 1.0),
    (1.05, 0.500, 0.9),
]

TRAIN_VOICES = [v for v in VOICES if v not in ("en_GB-cori-medium", "en_US-sam-medium")]


def main():
    voice_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "voices")
    n_pos = n_neg = 0
    for voice_name in VOICES:
        model = os.path.join(voice_dir, voice_name + ".onnx")
        config = os.path.join(voice_dir, voice_name + ".onnx.json")
        voice = PiperVoice.load(model, config)
        tag = voice_name.replace("en_US-", "us_").replace("en_GB-", "gb_")
        # Extra positives only for TRAIN voices (keep cori/sam held out)
        if voice_name in TRAIN_VOICES:
            for i, (ls, ns, nw) in enumerate(EXTRA_POS_VARIATIONS):
                out = os.path.join(OUT_DIR, "positive", f"heyjack_{tag}_x{i:02d}.wav")
                synth(voice, "Hey Jack", out, ls, ns, nw)
                n_pos += 1
        # Extra hard negatives for ALL voices (incl. held-out, for validation)
        for slug, text, reps in EXTRA_NEGATIVES:
            for r in range(reps):
                out = os.path.join(OUT_DIR, "negative", f"{slug}_{tag}_x{r}.wav")
                synth(voice, text, out)
                n_neg += 1
        print(f"{voice_name}: done (pos {n_pos}, neg {n_neg} cumulative)", flush=True)
    print(f"TOTAL extra positives: {n_pos}, negatives: {n_neg}")


if __name__ == "__main__":
    main()
