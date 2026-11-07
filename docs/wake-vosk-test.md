# Wake-word test loop (Vosk grammar)

The tablet no longer scores `hey_jack.onnx`. Wake detection is a
grammar-restricted Vosk small en-US recognizer (`VoskWakeEngine`).
Armed phrases default to **okay computer**, **hey tablet**, and **hey jack** (okay computer first; hey jack stays armed but is the weakest on non-US accents).
**hey jarvis** is in the grammar only as a reject: this tablet must not
wake on it.

Desktop rates below use espeak-ng, not a person in the room. The loop
further down plays a WAV through the same engine the microphone uses.

## Desktop rates (espeak-ng, threshold 0.85)

348 clips: 6 voices × 3 speeds × clean/noisy for each armed phrase (36
each), 180 reject clips (including hey jarvis), 60 unrelated sentences.
Same frame gate as `VoskWakeEngine`.

| set | result |
|---|---|
| hey jack | 23/36 hits (0.639). en-us clean and noisy were 6/6. Misses are mostly en-gb-scotland, en-029, and noisy en-gb, heard as hey john / hey zack or with jack under 0.85. |
| hey tablet | 36/36 (1.000) |
| okay computer | 36/36 (1.000) |
| reject phrases | 0/180 false accepts |
| unrelated speech | 0/60 false accepts |
| hey jarvis | 0/12 false accepts |

Wrong-phrase hits on positives: 0. hey jarvis never armed a wake.

## What you need

- A debug APK (`versionCode` 3, `versionName` `0.4.1-vosk-wake`) installed
  and running (the foreground wake service registers the receiver).
- `adb` to the tablet.

## Push a clip and inject it

App-specific storage needs no extra permission:

```bash
DEST=/sdcard/Android/data/net.npalakurla.jarvis.tablet/files
adb shell mkdir -p "$DEST"
adb push wake/testdata/SOME.wav "$DEST/x.wav"
adb shell am broadcast -a net.npalakurla.jarvis.tablet.DEBUG_WAKE_WAV \
  --es path "$DEST/x.wav"
adb logcat -d -s JarvisWake:I
```

`/sdcard/Download` on Android 11 is not readable until the debug app is
allowed to manage files (the permission is in the debug manifest only):

```bash
adb push wake/testdata/SOME.wav /sdcard/Download/x.wav
adb shell appops set net.npalakurla.jarvis.tablet MANAGE_EXTERNAL_STORAGE allow
adb shell am broadcast -a net.npalakurla.jarvis.tablet.DEBUG_WAKE_WAV \
  --es path /sdcard/Download/x.wav
adb logcat -d -s JarvisWake:I
```

Success is one line:

```text
wake_detected phrase=hey jack conf=0.91
```

A clip that is not an armed phrase, including hey jarvis, is:

```text
wake_none
```

`wake_wav_error ...` means the file was missing or not 16 kHz mono 16-bit,
and is followed by `wake_none`. The receiver exists only in debug builds.

Settings (debug builds): a gear on the dock, or a long-press on the clock.
`adb shell am start -n net.npalakurla.jarvis.tablet/.SettingsActivity`
opens Settings without retyping a saved token (the field shows `saved`).

A full voice turn from a WAV, with no microphone, logs `JarvisVoice`.
`--ez direct true` transcribes the clip with the bundled Vosk model (no
grammar) and posts that text to `/llm/v1`. Without the extra, the WAV is
posted to the relay. A 401 or 403 on the direct path still falls back to
the relay. Debug builds log the transcript as `JarvisVoice: heard="..."`.
Lock, garage, and alarm speech is refused locally before the relay or
`/llm/v1`, including Vosk splits and mishears (`un lock`, `and lock`,
`dis arm`, `the arm`). That logs `JarvisVoice: refused=safety` and is
not sent. Tool calls for `lock.*`, `cover.garage*`, and
`alarm_control_panel.*` are refused the same way.

```bash
adb shell am broadcast -a net.npalakurla.jarvis.tablet.DEBUG_TURN_WAV \
  --es path "$DEST/x.wav" --ez direct true
adb logcat -d -s JarvisVoice:I
```

`path=relay` is used when the LLM token is missing and when `/llm/v1` returns 401 or 403. On a device with no Android SpeechRecognizer, a live direct turn records PCM and uses the same free-form Vosk model (`stt=vosk`) before `/llm/v1`. Token fields are password inputs; `content-desc` is `field_llm_token` and `field_cf_token`. The HUD hint says “okay computer”.

If the broadcast is dropped, add the package:

```bash
adb shell am broadcast -a net.npalakurla.jarvis.tablet.DEBUG_WAKE_WAV \
  -p net.npalakurla.jarvis.tablet \
  --es path /sdcard/Download/x.wav
```

## Make the clips

From the repo, with `espeak-ng` installed:

```bash
python3 wake/gen-test-wavs.py
```

That writes `wake/testdata/` (gitignored): positives for each armed phrase,
reject phrases (hey jarvis and look-alikes), and unrelated sentences.
Several espeak voices and speeds, each clean and with noise. Every file
is 16 kHz mono 16-bit PCM.

## Score them on a desktop (same rules)

```bash
python3 -m pip install vosk
python3 wake/eval-wake.py
```

The script downloads `vosk-model-small-en-us-0.15` into `wake/.vosk-model`
(the zip SHA-256 is pinned; it is the same model the APK unpacks). It
frames the audio the way `VoskWakeEngine` does: energy gate, 300 ms
pre-roll, 0.8 s hang, early finalize after the partial has ended in an
armed phrase for ~300 ms, then every word of the final text must be that
phrase at confidence >= 0.85. hey jarvis is never a hit.

Settings on the tablet can turn individual phrases off and can move the
confidence floor between 0.50 and 0.99. The desktop script uses the
defaults in `wake/phrases.json`.

## Tradeoff

Vosk small plus `libvosk` (arm64 only) replaces the ONNX Runtime wake
stack. The model is about 40 MB unpacked inside the APK. The Helio P22T
only runs the recognizer while the mic is above the noise floor, not on
every silent frame. Library and model are Apache-2.0.
