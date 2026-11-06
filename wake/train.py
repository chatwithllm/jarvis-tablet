#!/usr/bin/env python3
"""Train the 'Hey Jack' openWakeWord classifier.

Recipe: openWakeWord training_models.ipynb (Apache-2.0), adapted to the
app's 2 s / 16-frame window (WakeModel.kt expects hey_jack.onnx input
[1, 16, 96] float32 -> [1, 1] float32 score).

- Positives: Piper 'Hey Jack' clips (14 train voices), mixed with random
  negative background at SNR 5-15 dB, phrase ending 0-200 ms before the
  2 s window end, volume augmentation. 2 mixes per clip.
- Negatives: Piper hard negatives + generic speech, BOTH as stacked clean
  2 s windows (2 passes) AND mixed with background at SNR 5-15 dB at a
  random position (2 mixes per clip). The mixed negatives are essential:
  round 2 trained negatives only as clean stacked windows, so the model
  learned 'has background noise = positive' and false-accepted 60% of
  mixed negatives. Clean positives (phrase in a silent 2 s window) are
  also included so clean 'Hey Jack' scores.
- Held out (validation only): voices en_GB-cori-medium, en_US-sam-medium.
- Model: notebook FCN (Flatten -> Linear(1536,32) -> LN -> ReLU ->
  Linear(32,32) -> LN -> ReLU -> Linear(32,1) -> Sigmoid).
- Loss: BCE, Adam lr 0.001, 10 epochs, batch 512, negatives weighted 10x.

DEVIATION from the notebook: the notebook's mixing helper lives in
openwakeword.data, which imports speechbrain/torchaudio (not installed
here). The mixing below re-implements the same SNR mixing in numpy
(foreground placed at start_index over a tiled background, background
scaled so the overlap-region SNR equals the target, then a random
volume gain). stack_clips is re-implemented identically to the notebook.
"""
import os
import re
import collections
import numpy as np
import scipy.io.wavfile as wav
import torch
import torch.nn as nn
from tqdm import tqdm

from openwakeword.utils import AudioFeatures

BASE = os.path.dirname(os.path.abspath(__file__))
CLIPS = os.path.join(BASE, "data", "clips")
WINDOW_S = 2.0
WINDOW_SAMPLES = int(16000 * WINDOW_S)
VAL_VOICES = {"en_GB-cori-medium", "en_US-sam-medium"}
RNG = np.random.default_rng(0)


def voice_of(fname):
    # Filenames embed a voice tag like us_amy-medium or gb_cori-medium.
    m = re.search(r"(us|gb)_([a-z]+)-(medium|low|high)", os.path.basename(fname))
    if not m:
        return "unknown"
    lang = "en_US" if m.group(1) == "us" else "en_GB"
    return f"{lang}-{m.group(2)}-{m.group(3)}"


def load_wav(path):
    sr, dat = wav.read(path)
    assert sr == 16000, path
    return dat.astype(np.int16)


def stack_clips(audio_data, clip_size=WINDOW_SAMPLES):
    """Concatenate 1-D arrays and cut into uniform clip_size windows
    (identical to openwakeword.data.stack_clips)."""
    combined = np.hstack(audio_data)
    out = []
    for i in range(0, combined.shape[0], clip_size):
        chunk = combined[i:i + clip_size]
        if chunk.shape[0] != clip_size:
            chunk = np.hstack((chunk, np.zeros(clip_size - chunk.shape[0], dtype=np.int16)))
        out.append(chunk)
    return np.array(out, dtype=np.int16)


def mix_clip(fg, bg_pool, start_index, snr_db, volume_aug=True):
    """Mix a foreground clip over a background at a target SNR (dB),
    foreground placed at start_index in a WINDOW_SAMPLES window."""
    # Build a background of exactly WINDOW_SAMPLES by tiling a random clip
    bg = bg_pool[int(RNG.integers(0, len(bg_pool)))]
    reps = int(np.ceil(WINDOW_SAMPLES / max(len(bg), 1)))
    bg_full = np.tile(bg, reps)[:WINDOW_SAMPLES].astype(np.float64)
    fg_f = fg.astype(np.float64)
    end = min(start_index + len(fg_f), WINDOW_SAMPLES)
    seg = fg_f[:end - start_index]
    # Scale background so overlap-region SNR == target
    bg_seg = bg_full[start_index:end]
    rms_fg = np.sqrt(np.mean(seg ** 2)) + 1e-9
    rms_bg = np.sqrt(np.mean(bg_seg ** 2)) + 1e-9
    bg_full *= (rms_fg / (10 ** (snr_db / 20.0))) / rms_bg
    out = bg_full.copy()
    out[start_index:end] += seg
    if volume_aug:
        out *= RNG.uniform(0.7, 1.3)
    return np.clip(out, -32768, 32767).astype(np.int16)


def mix_positive(fg, bg_pool, start_index, snr_db, volume_aug=True):
    return mix_clip(fg, bg_pool, start_index, snr_db, volume_aug)


def main():
    F = AudioFeatures()
    pos_files = sorted(
        os.path.join(CLIPS, "positive", f)
        for f in os.listdir(os.path.join(CLIPS, "positive"))
        if f.endswith(".wav")
    )
    neg_files = sorted(
        os.path.join(CLIPS, "negative", f)
        for f in os.listdir(os.path.join(CLIPS, "negative"))
        if f.endswith(".wav")
    )
    train_pos = [f for f in pos_files if voice_of(f) not in VAL_VOICES]
    val_pos = [f for f in pos_files if voice_of(f) in VAL_VOICES]
    train_neg = [f for f in neg_files if voice_of(f) not in VAL_VOICES]
    val_neg = [f for f in neg_files if voice_of(f) in VAL_VOICES]
    print(f"train pos: {len(train_pos)}, val pos: {len(val_pos)}")
    print(f"train neg: {len(train_neg)}, val neg: {len(val_neg)}")

    pos_audio = [load_wav(f) for f in train_pos]
    neg_audio = [load_wav(f) for f in train_neg]

    # ---- Positive features: 2 mixes per clip, phrase ends 0-200 ms
    #      before the window end, SNR 5-15 dB ----
    pos_feats = []
    for mix_pass in range(2):
        mixed = []
        for fg in pos_audio:
            jitter = int(RNG.uniform(0, 0.2) * 16000)
            start = WINDOW_SAMPLES - (len(fg) + jitter)
            start = max(start, 0)
            snr = float(RNG.uniform(5, 15))
            mixed.append(mix_positive(fg, neg_audio, start, snr))
        mixed = np.array(mixed, dtype=np.int16)
        feats = F.embed_clips(mixed, batch_size=256, ncpu=2)
        pos_feats.append(feats)
        print(f"pos mix pass {mix_pass}: {feats.shape}")
    # ---- Clean positives: phrase at a random position in a 2 s window
    #      of silence (teaches the model clean 'Hey Jack' is positive) ----
    clean_pos = []
    for fg in pos_audio:
        win = np.zeros(WINDOW_SAMPLES, dtype=np.int16)
        start = int(RNG.integers(0, max(WINDOW_SAMPLES - len(fg), 1)))
        win[start:start + len(fg)] = fg[:WINDOW_SAMPLES - start]
        clean_pos.append(win)
    clean_pos = np.array(clean_pos, dtype=np.int16)
    feats = F.embed_clips(clean_pos, batch_size=256, ncpu=2)
    pos_feats.append(feats)
    print(f"pos clean: {feats.shape}")
    positive_features = np.vstack(pos_feats)
    print("positive features total:", positive_features.shape)

    # ---- Negative features: (a) stacked clean speech windows, 2 passes;
    #      (b) MIXED negatives — foreground negative over background at
    #      SNR 5-15 dB, random position. (b) is essential: without it the
    #      model learns 'has background noise = positive' and accepts any
    #      mixed speech (round-2 bug: 60% FA on mixed negatives). ----
    neg_feats = []
    for offset in (0, 8000):
        combined = np.hstack(neg_audio)
        if offset:
            combined = np.concatenate([np.zeros(offset, dtype=np.int16), combined])
        stacked = stack_clips([combined])
        feats = F.embed_clips(stacked, batch_size=256, ncpu=2)
        neg_feats.append(feats)
        print(f"neg stack pass offset {offset}: {feats.shape}")
    for mix_pass in range(2):
        mixed = []
        for fg in neg_audio:
            start = int(RNG.integers(0, max(WINDOW_SAMPLES - len(fg), 1)))
            snr = float(RNG.uniform(5, 15))
            mixed.append(mix_clip(fg, neg_audio, start, snr))
        mixed = np.array(mixed, dtype=np.int16)
        feats = F.embed_clips(mixed, batch_size=256, ncpu=2)
        neg_feats.append(feats)
        print(f"neg mix pass {mix_pass}: {feats.shape}")
    # ---- Clean negatives: phrase at a random position in a 2 s window
    #      of silence. ESSENTIAL: without these, the model learns
    #      'speech surrounded by silence = positive' (round-4 bug: the
    #      only clean-in-silence training examples were positives, so
    #      clean negatives false-accepted at 48% via predict_clip). ----
    clean_neg = []
    for fg in neg_audio:
        win = np.zeros(WINDOW_SAMPLES, dtype=np.int16)
        start = int(RNG.integers(0, max(WINDOW_SAMPLES - len(fg), 1)))
        win[start:start + len(fg)] = fg[:WINDOW_SAMPLES - start]
        clean_neg.append(win)
    clean_neg = np.array(clean_neg, dtype=np.int16)
    feats = F.embed_clips(clean_neg, batch_size=256, ncpu=2)
    neg_feats.append(feats)
    print(f"neg clean: {feats.shape}")
    negative_features = np.vstack(neg_feats)
    print("negative features total:", negative_features.shape)

    np.save(os.path.join(BASE, "positive_features.npy"), positive_features)
    np.save(os.path.join(BASE, "negative_features.npy"), negative_features)
    for name, lst in [("val_pos", val_pos), ("val_neg", val_neg),
                      ("train_pos", train_pos), ("train_neg", train_neg)]:
        with open(os.path.join(BASE, f"{name}.txt"), "w") as fh:
            fh.write("\n".join(lst))

    # ---- Train the classifier (notebook FCN) ----
    X = np.vstack((negative_features, positive_features))
    y = np.array([0] * len(negative_features) + [1] * len(positive_features),
                 dtype=np.float32)[..., None]
    print(f"Training set: X {X.shape}, positives {int(y.sum())}, "
          f"negatives {len(y) - int(y.sum())}")
    # Small dataset: use a small batch and many epochs so the model gets
    # enough gradient steps (the notebook's batch 512 / 10 epochs only
    # gives 2 steps/epoch on 670 rows). Class weight is 2x on negatives
    # (positives 0.5) instead of the notebook's 10x — the set here is
    # near-balanced, and 10x drove recall to 0.
    batch_size = 64
    training_data = torch.utils.data.DataLoader(
        torch.utils.data.TensorDataset(torch.from_numpy(X), torch.from_numpy(y)),
        batch_size=batch_size, shuffle=True)
    layer_dim = 64
    fcn = nn.Sequential(
        nn.Flatten(),
        nn.Linear(X.shape[1] * X.shape[2], layer_dim),
        nn.LayerNorm(layer_dim),
        nn.ReLU(),
        nn.Linear(layer_dim, layer_dim),
        nn.LayerNorm(layer_dim),
        nn.ReLU(),
        nn.Linear(layer_dim, layer_dim),
        nn.LayerNorm(layer_dim),
        nn.ReLU(),
        nn.Linear(layer_dim, 1),
        nn.Sigmoid(),
    )
    loss_function = torch.nn.functional.binary_cross_entropy
    optimizer = torch.optim.Adam(fcn.parameters(), lr=0.001)
    n_epochs = 100
    history = collections.defaultdict(list)
    for i in tqdm(range(n_epochs), total=n_epochs, desc="epochs"):
        for batch in training_data:
            x, yb = batch[0], batch[1]
            weights = torch.ones(yb.shape[0])
            weights[yb.flatten() == 1] = 0.5
            optimizer.zero_grad()
            predictions = fcn(x)
            loss = loss_function(predictions, yb, weights[..., None])
            loss.backward()
            optimizer.step()
            history["loss"].append(float(loss.detach().numpy()))
            tp = sum(predictions.flatten()[yb.flatten() == 1] >= 0.5)
            fn = sum(predictions.flatten()[yb.flatten() == 1] < 0.5)
            history["recall"].append(float(tp / max(tp + fn, 1)))
    nb = len(training_data)
    print(f"Final epoch loss: {np.mean(history['loss'][-nb:]):.4f}, "
          f"train recall: {np.mean(history['recall'][-nb:]):.3f}")
    # Full train-set evaluation (sanity check before export)
    fcn.eval()
    with torch.no_grad():
        preds = fcn(torch.from_numpy(X)).numpy().flatten()
    tp = int(((preds >= 0.5) & (y.flatten() == 1)).sum())
    fn = int(((preds < 0.5) & (y.flatten() == 1)).sum())
    fp = int(((preds >= 0.5) & (y.flatten() == 0)).sum())
    tn = int(((preds < 0.5) & (y.flatten() == 0)).sum())
    print(f"Train set @0.5: TP {tp} FN {fn} FP {fp} TN {tn} | "
          f"recall {tp / max(tp + fn, 1):.3f} precision {tp / max(tp + fp, 1):.3f}")

    # ---- Export to ONNX ----
    out_path = os.path.join(BASE, "hey_jack.onnx")
    fcn.eval()
    torch.onnx.export(fcn, args=torch.zeros((1, 16, 96)), f=out_path,
                      input_names=["x.1"], output_names=["output"],
                      dynamo=False)
    print("Exported:", out_path)
    torch.save(fcn.state_dict(), os.path.join(BASE, "hey_jack.pt"))


if __name__ == "__main__":
    main()
