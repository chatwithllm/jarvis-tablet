# Human recordings needed — Hey Jack wake-word retrain

## Why
The current `hey_jack.onnx` was trained only on Piper TTS synthetic voices.
Device tests show it does not detect real human "Hey Jack" / "Hi Jack" at
any threshold (0.3–0.9 barely matters). Public datasets were searched
(LibriSpeech clean: test + validation = 5,323 utterances, 0 hits for
"Hey Jack"/"Hi Jack" or any confusable; Common Voice is not downloadable
in a usable form) and do not contain usable recordings of the phrase.
The model must be retrained on real human speech of the exact phrases.

## What to record
Record on a phone (Voice Memos / Recorder app), in a normal room, speaking
naturally — not shouting, not whispering. Hold the phone 1–2 m away, or
set it on a table at normal talking distance. Record each phrase as its
own clip (say the phrase once, then stop). Two conditions per phrase:
- QUIET: no TV/music, quiet room.
- NOISY: TV or music on at normal volume in the background.

### Positives (the wake phrases — these are the critical ones)
| Phrase | Per speaker | Conditions |
|---|---|---|
| "Hey Jack" | 10 clips | 5 quiet + 5 noisy, vary speed slightly (normal / a bit fast / a bit slow) |
| "Hi Jack" | 10 clips | 5 quiet + 5 noisy, same variation |

### Negatives / confusables (so the tablet does NOT wake on similar phrases)
Say each of these 3 times per speaker (2 quiet + 1 noisy):
"Hey Jackson", "Hey Jackie", "Hey Jarvis", "Hey John", "Hey Jake",
"Hey James", "Hey Jacob", "Hey Jason", "Hey Zack", "Hey Mack", "Hey Hank"

## Speakers
Minimum 5 distinct speakers (Narsi + family members who use the tablet).
More is better — 10+ speakers is ideal. The tablet is used by the family,
so family voices are the most valuable training data of all. Children
count as speakers too (their voices differ from adults').

## Format
- WAV, 16-bit, 16 kHz, mono. (Most phone recorders save M4A/AAC — send
  that and it will be converted; or use an app that exports WAV.)
- One clip per utterance; filename like `hey_jack_narsi_quiet_01.wav`
  (phrase_speaker_condition_number).
- Total per speaker: ~20 positive + ~33 negative clips ≈ 50 clips,
  about 5 minutes of recording.

## Where to send
Drop the clips in a folder (Google Drive / shared folder) and share the
link, or hand them to the assistant. Training + validation + a new
`hey_jack.onnx` build takes ~1–2 hours once the clips arrive, then a
normal CI build + tablet sideload to test.

## What is NOT needed
- No studio, no special microphone — a phone in a normal room is exactly
  the condition the tablet hears.
- No full sentences — just the phrases above.
- General conversational speech is NOT needed from the family: the
  negative/background side can use public-domain audiobook speech.
