#!/usr/bin/env python3
"""Score wake/testdata with desktop Vosk using the tablet's rules.

Same grammar, energy gate, pre-roll, hang, early-finalize, and per-word
confidence floor as VoskWakeEngine.kt. The model is
vosk-model-small-en-us-0.15 (downloaded next to this script if needed).

Usage:
  python3 wake/eval-wake.py [--model DIR] [--testdata DIR]
"""
import argparse
import json
import struct
import urllib.request
import wave
import zipfile
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PHRASES = json.loads((ROOT / "phrases.json").read_text())
MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
MODEL_SHA = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"


def normalize(text):
    out = []
    for ch in text.lower():
        if ch.isalpha() or ch in " []":
            out.append(ch)
        else:
            out.append(" ")
    return " ".join("".join(out).split())


def ending_phrase(text, phrases):
    found = [p for p in phrases if text == p or text.endswith(" " + p)]
    if not found:
        return None
    return max(found, key=len)


def evaluate(payload, enabled, rejects, threshold):
    text = normalize(payload.get("text") or "")
    if not text or text == "[unk]":
        return None
    if text not in enabled or text in rejects or text == "hey jarvis":
        return None
    words = payload.get("result") or []
    if not words:
        return None
    spoken = []
    min_conf = 1.0
    for word in words:
        conf = float(word.get("conf", 0.0))
        if conf < threshold:
            return None
        min_conf = min(min_conf, conf)
        spoken.append(normalize(word.get("word", "")))
    if " ".join(spoken) != text:
        return None
    return text, min_conf


class Session:
    def __init__(self, rec, catalog):
        self.rec = rec
        self.catalog = catalog
        self.pre_roll = []
        self.pre_roll_n = 0
        self.feeding = False
        self.hang_left = 0
        self.noise = 0.02
        self.match = None
        self.match_n = 0
        sr = catalog["sampleRate"]
        self.pre_max = sr * catalog["preRollMs"] // 1000
        self.hang_n = sr * catalog["hangMs"] // 1000
        self.early_n = sr * catalog["earlyFinalizeMs"] // 1000
        self.floor = catalog["energyFloor"]
        self.mult = catalog["energyMultiplier"]

    def reset(self):
        self.pre_roll.clear()
        self.pre_roll_n = 0
        self.feeding = False
        self.hang_left = 0
        self.match = None
        self.match_n = 0
        self.rec.Reset()

    def _rms(self, samples):
        if not samples:
            return 0.0
        acc = 0.0
        for s in samples:
            v = s / 32768.0
            acc += v * v
        return (acc / len(samples)) ** 0.5

    def _accept(self, samples, enabled, rejects, threshold):
        raw = struct.pack("<" + "h" * len(samples), *samples)
        ended = self.rec.AcceptWaveform(raw)
        if ended:
            hit = evaluate(json.loads(self.rec.Result()), enabled, rejects, threshold)
            self.rec.Reset()
            self.feeding = False
            self.match = None
            self.match_n = 0
            return hit
        partial = normalize(json.loads(self.rec.PartialResult()).get("partial", ""))
        if ending_phrase(partial, rejects):
            self.match = None
            self.match_n = 0
            return None
        phrase = ending_phrase(partial, enabled)
        if phrase is None or phrase != self.match:
            self.match = phrase
            self.match_n = 0 if phrase is None else len(samples)
            return None
        self.match_n += len(samples)
        if self.match_n < self.early_n:
            return None
        # Do not force FinalResult: a low score on the last word would
        # discard the rest of the utterance. Accept only when the partial
        # already carries per-word confidence at or above the floor.
        partial = json.loads(self.rec.PartialResult())
        words = partial.get("partial_result")
        if not words:
            return None
        hit = evaluate(
            {"text": partial.get("partial", ""), "result": words},
            enabled, rejects, threshold,
        )
        if not hit:
            return None
        self.rec.Reset()
        self.feeding = False
        self.match = None
        self.match_n = 0
        return hit

    def on_frame(self, samples, enabled, rejects, threshold):
        level = self._rms(samples)
        hot = level >= max(self.noise * self.mult, self.floor)
        if not hot and not self.feeding:
            self.noise = self.noise * 0.98 + level * 0.02
            self.pre_roll.append(samples)
            self.pre_roll_n += len(samples)
            while self.pre_roll_n > self.pre_max and self.pre_roll:
                self.pre_roll_n -= len(self.pre_roll.pop(0))
            return None
        if not self.feeding:
            self.feeding = True
            self.hang_left = self.hang_n
            for chunk in self.pre_roll:
                hit = self._accept(chunk, enabled, rejects, threshold)
                if hit:
                    return hit
            self.pre_roll.clear()
            self.pre_roll_n = 0
        if hot:
            self.hang_left = self.hang_n
        else:
            self.hang_left -= len(samples)
        hit = self._accept(samples, enabled, rejects, threshold)
        if hit:
            return hit
        if self.hang_left <= 0:
            return self.finish(enabled, rejects, threshold)
        return None

    def finish(self, enabled, rejects, threshold):
        if not self.feeding and self.match is None:
            return None
        hit = evaluate(json.loads(self.rec.FinalResult()), enabled, rejects, threshold)
        self.reset()
        return hit


def ensure_model(dest: Path) -> Path:
    marker = dest / "conf" / "model.conf"
    if marker.exists():
        return dest
    zip_path = dest.parent / "vosk-model.zip"
    print(f"downloading {MODEL_URL}")
    urllib.request.urlretrieve(MODEL_URL, zip_path)
    import hashlib
    digest = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    if digest != MODEL_SHA:
        raise SystemExit(f"model sha256 mismatch: {digest}")
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(dest.parent)
    extracted = dest.parent / "vosk-model-small-en-us-0.15"
    if extracted != dest and extracted.exists():
        extracted.rename(dest)
    return dest


def load_pcm(path):
    with wave.open(str(path)) as wf:
        if wf.getframerate() != 16000 or wf.getnchannels() != 1 or wf.getsampwidth() != 2:
            raise SystemExit(f"bad wav {path}")
        n = wf.getnframes()
        return list(struct.unpack("<" + "h" * n, wf.readframes(n)))


def run_pcm(rec_factory, pcm, catalog, enabled, rejects, threshold):
    session = Session(rec_factory(), catalog)
    frame = catalog["frameSamples"]
    for i in range(0, len(pcm), frame):
        hit = session.on_frame(pcm[i:i + frame], enabled, rejects, threshold)
        if hit:
            return hit
    return session.finish(enabled, rejects, threshold)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", type=Path, default=ROOT / ".vosk-model")
    ap.add_argument("--testdata", type=Path, default=ROOT / "testdata")
    args = ap.parse_args()
    from vosk import Model, KaldiRecognizer, SetLogLevel
    SetLogLevel(-1)
    model_dir = ensure_model(args.model)
    model = Model(str(model_dir))
    grammar = json.dumps(PHRASES["choices"] + PHRASES["reject"] + ["[unk]"])
    enabled = set(PHRASES["defaults"])
    rejects = set(PHRASES["reject"]) | {"hey jarvis"}
    threshold = float(PHRASES["confidenceDefault"])
    manifest = json.loads((args.testdata / "manifest.json").read_text())

    def factory():
        rec = KaldiRecognizer(model, PHRASES["sampleRate"], grammar)
        rec.SetWords(True)
        if hasattr(rec, "SetPartialWords"):
            rec.SetPartialWords(True)
        return rec

    pos_hit = defaultdict(int)
    pos_total = defaultdict(int)
    pos_wrong = defaultdict(int)
    fa = defaultdict(int)
    fa_total = defaultdict(int)
    jarvis_fa = 0
    jarvis_n = 0
    for row in manifest:
        pcm = load_pcm(args.testdata / row["file"])
        hit = run_pcm(factory, pcm, PHRASES, enabled, rejects, threshold)
        kind = row["kind"]
        if kind == "positive":
            pos_total[row["phrase"]] += 1
            if hit and hit[0] == row["phrase"]:
                pos_hit[row["phrase"]] += 1
            elif hit:
                pos_wrong[row["phrase"]] += 1
        else:
            fa_total[kind] += 1
            if hit:
                fa[kind] += 1
            if row["phrase"] == "hey jarvis":
                jarvis_n += 1
                if hit:
                    jarvis_fa += 1
    print(f"threshold={threshold} enabled={sorted(enabled)}")
    print("phrase\thits\ttotal\thit_rate\twrong_phrase")
    for phrase in PHRASES["choices"]:
        total = pos_total[phrase]
        hits = pos_hit[phrase]
        rate = hits / total if total else 0
        print(f"{phrase}\t{hits}\t{total}\t{rate:.3f}\t{pos_wrong[phrase]}")
    print("negatives\tfalse_accepts\ttotal\trate")
    for kind in ("reject", "unrelated"):
        total = fa_total[kind]
        bad = fa[kind]
        rate = bad / total if total else 0
        print(f"{kind}\t{bad}\t{total}\t{rate:.3f}")
    print(f"hey_jarvis_false_accepts\t{jarvis_fa}\t{jarvis_n}")


if __name__ == "__main__":
    main()
