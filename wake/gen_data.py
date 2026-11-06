#!/usr/bin/env python3
"""Generate Piper TTS training data for the 'Hey Jack' openWakeWord model.

Positives: 'Hey Jack' across 16 Piper voices, varied speed/noise.
Hard negatives: 'Hey Jarvis', 'hey mirror', other 'Hey <name>' phrases.
Generic negatives: common assistant phrases / dialogue-like speech.
All output: 16-bit 16 kHz mono WAV.
"""
import os
import sys
import wave
import itertools
import numpy as np

VOICE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "voices")
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "clips")

VOICES = [
    "en_US-amy-medium", "en_US-arctic-medium", "en_US-danny-low",
    "en_US-joe-medium", "en_US-kathleen-low", "en_US-kristin-medium",
    "en_US-lessac-medium", "en_US-libritts-high", "en_US-ljspeech-medium",
    "en_US-norman-medium", "en_US-ryan-medium", "en_US-sam-medium",
    "en_GB-alan-medium", "en_GB-alba-medium", "en_GB-aru-medium",
    "en_GB-cori-medium",
]

HARD_NEGATIVES = [
    ("hey_jarvis", "Hey Jarvis", 3),
    ("hey_mirror", "hey mirror", 3),
    ("hey_john", "Hey John", 1),
    ("hey_jake", "Hey Jake", 1),
    ("hey_james", "Hey James", 1),
    ("hey_jacob", "Hey Jacob", 1),
    ("hey_jason", "Hey Jason", 1),
    ("hey_jackson", "Hey Jackson", 1),
    ("hey_zack", "Hey Zack", 1),
    ("hey_mack", "Hey Mack", 1),
    ("hey_hank", "Hey Hank", 1),
]

GENERIC_NEGATIVES = [
    "turn on the lights",
    "what is the weather today",
    "play some music",
    "set a timer for ten minutes",
    "good morning",
    "thank you very much",
    "how are you doing today",
    "what time is it",
    "open the garage door",
    "call mom on the phone",
    "turn off the bedroom light",
    "what is on my calendar",
    "read me the news",
    "lock the front door",
    "turn up the volume",
]

# (length_scale, noise_scale, noise_w) combos for positive variation
POS_VARIATIONS = [
    (1.00, 0.667, 0.8),
    (0.90, 0.667, 0.8),
    (1.10, 0.667, 0.8),
    (1.00, 0.500, 0.9),
    (1.00, 0.800, 0.9),
    (0.95, 0.667, 1.0),
    (1.05, 0.667, 0.8),
    (1.00, 0.667, 1.0),
    (0.90, 0.800, 0.9),
    (1.10, 0.500, 0.8),
]

from piper import PiperVoice, SynthesisConfig


def synth(voice, text, out_path, length_scale=1.0, noise_scale=0.667, noise_w=0.8):
    if os.path.exists(out_path):
        return True
    cfg = SynthesisConfig(length_scale=length_scale, noise_scale=noise_scale,
                          noise_w_scale=noise_w, volume=1.0)
    chunks = []
    for chunk in voice.synthesize(text, syn_config=cfg):
        chunks.append(chunk.audio_int16_array)
    audio = np.concatenate(chunks) if chunks else np.zeros(0, dtype=np.int16)
    with wave.open(out_path, "wb") as wav:
        wav.setframerate(16000)
        wav.setsampwidth(2)
        wav.setnchannels(1)
        wav.writeframes(audio.astype(np.int16).tobytes())
    return True


def main():
    os.makedirs(os.path.join(OUT_DIR, "positive"), exist_ok=True)
    os.makedirs(os.path.join(OUT_DIR, "negative"), exist_ok=True)
    n_pos = n_neg = 0
    for voice_name in VOICES:
        model = os.path.join(VOICE_DIR, voice_name + ".onnx")
        config = os.path.join(VOICE_DIR, voice_name + ".onnx.json")
        if not os.path.exists(model):
            print(f"SKIP {voice_name}: model missing", flush=True)
            continue
        voice = PiperVoice.load(model, config)
        tag = voice_name.replace("en_US-", "us_").replace("en_GB-", "gb_")
        # Positives: 10 varied samples of "Hey Jack"
        for i, (ls, ns, nw) in enumerate(POS_VARIATIONS):
            out = os.path.join(OUT_DIR, "positive", f"heyjack_{tag}_{i:02d}.wav")
            synth(voice, "Hey Jack", out, ls, ns, nw)
            n_pos += 1
        # Hard negatives
        for slug, text, reps in HARD_NEGATIVES:
            for r in range(reps):
                out = os.path.join(OUT_DIR, "negative", f"{slug}_{tag}_{r}.wav")
                synth(voice, text, out)
                n_neg += 1
        print(f"{voice_name}: done (pos {n_pos}, neg {n_neg} cumulative)", flush=True)
    # Generic negatives: subset of 6 voices for speed
    generic_voices = VOICES[:6]
    for voice_name in generic_voices:
        model = os.path.join(VOICE_DIR, voice_name + ".onnx")
        config = os.path.join(VOICE_DIR, voice_name + ".onnx.json")
        voice = PiperVoice.load(model, config)
        tag = voice_name.replace("en_US-", "us_").replace("en_GB-", "gb_")
        for j, text in enumerate(GENERIC_NEGATIVES):
            slug = text.replace(" ", "_").replace("'", "").replace("?", "")[:24]
            out = os.path.join(OUT_DIR, "negative", f"gen_{slug}_{tag}.wav")
            synth(voice, text, out)
            n_neg += 1
    print(f"TOTAL positives: {n_pos}, negatives: {n_neg}")


if __name__ == "__main__":
    main()
