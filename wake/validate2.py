#!/usr/bin/env python3
"""Validation round 2: test hey_jack.onnx on MIXED windows.

The real runtime (WakeModel.kt) scores a sliding 2 s window of continuous
audio — the phrase ends near the window end with room/background audio
around it. That is exactly the training distribution, so this script
reproduces it: each test clip is mixed with a random background at SNR
10 dB, phrase ending 0-200 ms before the 2 s window end, then the
classifier is run directly on the [16, 96] features.

Also reports the clean-clip predict_clip numbers for comparison.
"""
import os
import numpy as np
import scipy.io.wavfile as wav
import onnxruntime as ort
from openwakeword.utils import AudioFeatures

import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from train import mix_positive, load_wav, VAL_VOICES, RNG

BASE = os.path.dirname(os.path.abspath(__file__))
WINDOW_SAMPLES = 32000


def read_list(name):
    p = os.path.join(BASE, name)
    return [l.strip() for l in open(p) if l.strip()]


def main():
    F = AudioFeatures()
    sess = ort.InferenceSession(os.path.join(BASE, "hey_jack.onnx"),
                                providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name

    val_pos = read_list("val_pos.txt")
    val_neg = read_list("val_neg.txt")
    train_neg = read_list("train_neg.txt")
    neg_audio = [load_wav(f) for f in train_neg]

    def score_mixed(clip_path, n_mixes=3):
        fg = load_wav(clip_path)
        scores = []
        for _ in range(n_mixes):
            jitter = int(RNG.uniform(0, 0.2) * 16000)
            start = max(WINDOW_SAMPLES - (len(fg) + jitter), 0)
            mixed = mix_positive(fg, neg_audio, start, float(RNG.uniform(5, 15)))
            feats = F.embed_clips(np.array([mixed], dtype=np.int16), batch_size=1, ncpu=1)
            s = sess.run(None, {in_name: feats.astype(np.float32)})[0]
            scores.append(float(s.flatten()[0]))
        return scores

    print(f"== Held-out positives (MIXED, unseen voices), n={len(val_pos)} ==")
    all_pos = []
    for f in val_pos:
        ss = score_mixed(f)
        all_pos.extend(ss)
        print(f"  max {max(ss):.3f}  mean {np.mean(ss):.3f}  {os.path.basename(f)}")
    all_pos = np.array(all_pos)
    for th in (0.5, 0.8):
        print(f"  hit rate @ {th}: {(all_pos >= th).sum()}/{len(all_pos)} = {(all_pos >= th).mean():.2f}")

    print(f"== Held-out negatives (MIXED, unseen voices), n={len(val_neg)} ==")
    all_neg = []
    for f in val_neg:
        ss = score_mixed(f)
        all_neg.extend(ss)
        print(f"  max {max(ss):.3f}  mean {np.mean(ss):.3f}  {os.path.basename(f)}")
    all_neg = np.array(all_neg)
    for th in (0.5, 0.8):
        print(f"  false accepts @ {th}: {(all_neg >= th).sum()}/{len(all_neg)}")

    # Threshold sweep: find the threshold that maximizes (hits - false accepts)
    print("== Threshold sweep (mixed) ==")
    for th in (0.3, 0.5, 0.7, 0.8, 0.9, 0.95):
        hr = (all_pos >= th).mean()
        fa = (all_neg >= th).mean()
        print(f"  th {th}: hit {hr:.2f}  FA {fa:.2f}")


if __name__ == "__main__":
    main()
