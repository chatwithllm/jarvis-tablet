#!/usr/bin/env python3
"""Validate hey_jack.onnx on held-out data via the full openWakeWord pipeline.

Held-out positives: 'Hey Jack' from voices NOT in training (cori, sam).
Held-out negatives: confusable phrases from the same held-out voices.
Also: train-voice positives (sanity), and a long negative stream for
false-accept rate. A hit / false accept is max frame score >= threshold.
"""
import os
import numpy as np
import scipy.io.wavfile as wav
import openwakeword

BASE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(BASE, "hey_jack.onnx")


def read_list(name):
    p = os.path.join(BASE, name)
    if not os.path.exists(p):
        return []
    return [l.strip() for l in open(p) if l.strip()]


def max_score(oww, path):
    scores = oww.predict_clip(path)
    vals = [s["hey_jack"] for s in scores]
    return max(vals) if vals else 0.0


def main():
    oww = openwakeword.Model(
        wakeword_model_paths=[MODEL],
        enable_speex_noise_suppression=False,
        vad_threshold=0.5,
    )
    val_pos = read_list("val_pos.txt")
    val_neg = read_list("val_neg.txt")
    train_pos = read_list("train_pos.txt")
    train_neg = read_list("train_neg.txt")

    print(f"== Held-out positives (unseen voices), n={len(val_pos)} ==")
    pos_scores = []
    for f in val_pos:
        s = max_score(oww, f)
        pos_scores.append(s)
        print(f"  {s:.3f}  {os.path.basename(f)}")
    pos_scores = np.array(pos_scores)
    for th in (0.5, 0.8):
        print(f"  hit rate @ {th}: {(pos_scores >= th).sum()}/{len(pos_scores)} "
              f"= {(pos_scores >= th).mean():.2f}")

    print(f"== Held-out negatives (unseen voices), n={len(val_neg)} ==")
    neg_scores = []
    for f in val_neg:
        s = max_score(oww, f)
        neg_scores.append(s)
        print(f"  {s:.3f}  {os.path.basename(f)}")
    neg_scores = np.array(neg_scores)
    for th in (0.5, 0.8):
        print(f"  false accepts @ {th}: {(neg_scores >= th).sum()}/{len(neg_scores)}")

    print("== Train-voice positives (sanity, sample of 20) ==")
    import random
    random.seed(0)
    sample = random.sample(train_pos, min(20, len(train_pos)))
    tr_scores = np.array([max_score(oww, f) for f in sample])
    print(f"  hit rate @ 0.5: {(tr_scores >= 0.5).sum()}/{len(tr_scores)}")

    print("== Long negative stream (all 330 negative clips concatenated) ==")
    stream = np.hstack([wav.read(f)[1].astype(np.int16) for f in train_neg + val_neg])
    wav.write(os.path.join(BASE, "val_stream.wav"), 16000, stream)
    dur_h = len(stream) / 16000 / 3600
    scores = oww.predict_clip(os.path.join(BASE, "val_stream.wav"))
    vals = np.array([s["hey_jack"] for s in scores])
    fa = int((vals >= 0.5).sum())
    print(f"  stream {dur_h * 60:.1f} min, frames {len(vals)}, "
          f"max score {vals.max():.3f}, false-accept frames @0.5: {fa} "
          f"(~{fa / max(dur_h, 1e-9):.1f}/hour)")
    print(f"  frames >= 0.8: {int((vals >= 0.8).sum())}")


if __name__ == "__main__":
    main()
