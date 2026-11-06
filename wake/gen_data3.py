#!/usr/bin/env python3
"""Round 3 data: multi-speaker diversity from libritts-high (904 speakers).

The round-5 model generalizes well for positives but false-accepts
confusables on unseen voices — the training set has only 14 distinct
voices. Adding 24 more distinct libritts speakers (IDs 1-24) for both
'Hey Jack' and the confusable phrases should close the generalization gap.
All of these are TRAINING voices; cori/sam stay held out.
"""
import os
import numpy as np
from piper import PiperVoice, SynthesisConfig
import wave

BASE = os.path.dirname(os.path.abspath(__file__))
VOICE = os.path.join(BASE, "data", "voices", "en_US-libritts-high.onnx")
CONFIG = os.path.join(BASE, "data", "voices", "en_US-libritts-high.onnx.json")
OUT_POS = os.path.join(BASE, "data", "clips", "positive")
OUT_NEG = os.path.join(BASE, "data", "clips", "negative")

POS_VARS = [
    (1.00, 0.667, 0.8),
    (0.90, 0.667, 0.8),
    (1.10, 0.667, 0.8),
    (1.00, 0.800, 0.9),
    (0.95, 0.667, 1.0),
]
NEG_PHRASES = [
    ("hey_jarvis", "Hey Jarvis", 2),
    ("hey_mirror", "hey mirror", 2),
    ("hey_jackson", "Hey Jackson", 2),
    ("hey_jackie", "Hey Jackie", 2),
    ("hey_john", "Hey John", 1),
    ("hey_jake", "Hey Jake", 1),
    ("hey_james", "Hey James", 1),
]


def synth_spk(voice, speaker_id, text, out_path, ls=1.0, ns=0.667, nw=0.8):
    if os.path.exists(out_path):
        return
    cfg = SynthesisConfig(speaker_id=speaker_id, length_scale=ls,
                          noise_scale=ns, noise_w_scale=nw, volume=1.0)
    chunks = [c.audio_int16_array for c in voice.synthesize(text, syn_config=cfg)]
    audio = np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.int16)
    with wave.open(out_path, "wb") as w:
        w.setframerate(16000)
        w.setsampwidth(2)
        w.setnchannels(1)
        w.writeframes(audio.astype(np.int16).tobytes())


def main():
    voice = PiperVoice.load(VOICE, CONFIG)
    n_pos = n_neg = 0
    for spk in range(1, 25):
        tag = f"libritts_spk{spk:03d}"
        for i, (ls, ns, nw) in enumerate(POS_VARS):
            synth_spk(voice, spk, "Hey Jack",
                      os.path.join(OUT_POS, f"heyjack_{tag}_{i:02d}.wav"), ls, ns, nw)
            n_pos += 1
        for slug, text, reps in NEG_PHRASES:
            for r in range(reps):
                synth_spk(voice, spk, text,
                          os.path.join(OUT_NEG, f"{slug}_{tag}_{r}.wav"))
                n_neg += 1
        print(f"speaker {spk}: done (pos {n_pos}, neg {n_neg} cumulative)", flush=True)
    print(f"TOTAL libritts positives: {n_pos}, negatives: {n_neg}")


if __name__ == "__main__":
    main()
