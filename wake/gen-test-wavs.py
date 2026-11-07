#!/usr/bin/env python3
"""Generate 16 kHz mono wake-test WAVs with espeak-ng.

Positives: every armed phrase. Negatives: reject phrases (including
hey jarvis) and unrelated sentences. Several voices, speeds, and a
noisy copy of each. Output is wake/testdata/ (gitignored).

Requires espeak-ng on PATH. See docs/wake-vosk-test.md.
"""
import json
import random
import struct
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PHRASES = json.loads((ROOT / "phrases.json").read_text())
OUT = ROOT / "testdata"
SR = 16000

VOICES = [
    "en-us",
    "en-gb",
    "en-gb-scotland",
    "en-029",
    "en-us+f3",
    "en-us+m3",
]
SPEEDS = [120, 150, 180]
# Fraction of full scale added as noise. 0 is clean.
NOISE = [0.0, 0.08]
UNRELATED = [
    "what time is it",
    "turn on the kitchen light",
    "hey there",
    "the weather is nice today",
    "okay",
]


def resample(samples, src_rate, dst_rate):
    if src_rate == dst_rate:
        return samples
    out_n = int(len(samples) * dst_rate / src_rate)
    out = []
    for i in range(out_n):
        x = i * src_rate / dst_rate
        i0 = int(x)
        i1 = min(i0 + 1, len(samples) - 1)
        frac = x - i0
        value = samples[i0] * (1 - frac) + samples[i1] * frac
        out.append(int(max(-32768, min(32767, value))))
    return out


def read_wav(path):
    with wave.open(str(path)) as wf:
        if wf.getsampwidth() != 2 or wf.getnchannels() != 1:
            raise SystemExit(f"{path} is not mono 16-bit")
        n = wf.getnframes()
        samples = list(struct.unpack("<" + "h" * n, wf.readframes(n)))
        return samples, wf.getframerate()


def write_wav(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "w") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        wf.writeframes(struct.pack("<" + "h" * len(samples), *samples))


def synth(voice, speed, text, dest, noise, seed):
    raw = dest.with_suffix(".src.wav")
    subprocess.check_call(
        ["espeak-ng", "-v", voice, "-s", str(speed), "-w", str(raw), text],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    samples, rate = read_wav(raw)
    raw.unlink()
    samples = resample(samples, rate, SR)
    pad_h = [0] * int(0.4 * SR)
    pad_t = [0] * int(1.0 * SR)
    samples = pad_h + samples + pad_t
    if noise > 0:
        rng = random.Random(seed)
        peak = max(abs(s) for s in samples) or 1
        amp = noise * peak
        mixed = []
        for s in samples:
            mixed.append(int(max(-32768, min(32767, s + rng.uniform(-amp, amp)))))
        samples = mixed
    write_wav(dest, samples)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    jobs = []
    for phrase in PHRASES["choices"]:
        for voice in VOICES:
            for speed in SPEEDS:
                for noise in NOISE:
                    jobs.append(("positive", phrase, voice, speed, noise))
    for phrase in PHRASES["reject"]:
        for voice in VOICES[:3]:
            for speed in (140, 170):
                for noise in NOISE:
                    jobs.append(("reject", phrase, voice, speed, noise))
    for phrase in UNRELATED:
        for voice in VOICES[:3]:
            for speed in (140, 170):
                for noise in NOISE:
                    jobs.append(("unrelated", phrase, voice, speed, noise))

    manifest = []
    for i, (kind, phrase, voice, speed, noise) in enumerate(jobs):
        slug = (
            f"{i:04d}_{kind}_{phrase.replace(' ', '-')}_{voice.replace('+', '')}"
            f"_s{speed}_n{int(noise * 100)}"
        )
        dest = OUT / f"{slug}.wav"
        synth(voice, speed, phrase, dest, noise, seed=i + 1)
        manifest.append({
            "file": dest.name,
            "kind": kind,
            "phrase": phrase,
            "voice": voice,
            "speed": speed,
            "noise": noise,
        })
        if (i + 1) % 25 == 0:
            print(f"generated {i + 1}/{len(jobs)}", flush=True)
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"wrote {len(manifest)} wavs to {OUT}")


if __name__ == "__main__":
    main()
