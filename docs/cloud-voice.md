# Cloud voice (STT and TTS)

The tablet records 16 kHz mono PCM after the wake word, then asks the
ContextForge LLM base (`http://192.168.20.217:4444/llm/v1` by default)
for speech-to-text and, after the chat reply, for text-to-speech.
The saved LLM bearer (`cf-jarvis-tablet-llm`) is sent as
`Authorization: Bearer`. No xAI key is stored on the tablet or in git.

## What the gateway serves today

ContextForge's OpenAI-compatible proxy (`mcpgateway/routers/llm_proxy_router.py`)
mounts only:

- `POST /llm/v1/chat/completions` (`llm.invoke`)
- `GET /llm/v1/models` (`llm.read`)

It does not proxy audio. These all 404 on the current gateway:

- `POST /llm/v1/audio/transcriptions`
- `POST /llm/v1/audio/speech`
- `POST /llm/v1/stt`
- `POST /llm/v1/tts`
- `GET /llm/v1/tts/voices`
- `wss://…/llm/v1/realtime`

xAI does not implement the OpenAI `/audio/*` paths. Its stock routes are
`POST https://api.x.ai/v1/stt` and `POST https://api.x.ai/v1/tts`.
Realtime speech-to-speech is `wss://api.x.ai/v1/realtime` (model
`grok-voice-latest`). The tablet does not use realtime.

Until Key Keeper adds the routes, the tablet logs `stt_http=404` (or
`tts_http=404`) and falls back to free-form Vosk for STT and Android
`TextToSpeech` for TTS. Wake-word detection stays on the Vosk grammar
model either way.

## What Key Keeper needs to add

Pass-through on the existing `/llm/v1` prefix, using the gateway's own
xAI key upstream (do not put that key on the tablet):

| Tablet request | Upstream |
| --- | --- |
| `POST /llm/v1/stt` | `POST https://api.x.ai/v1/stt` |
| `POST /llm/v1/tts` | `POST https://api.x.ai/v1/tts` |
| `GET /llm/v1/tts/voices` (optional) | `GET https://api.x.ai/v1/tts/voices` |

Authorize them with the same `llm.invoke` permission as
`chat/completions`, so `cf-jarvis-tablet-llm` works without a new token.
Do not follow redirects on the tablet side; the gateway should not
redirect the bearer either.

STT body: `multipart/form-data`, fields `model=grok-voice-transcribe-2.0`,
`language=en`, and `file` (WAV) last. Response JSON `{"text": "..."}`.

TTS body: JSON `text`, `voice_id`, `language` (`en`), `speed` (`1.08`),
`output_format` `{codec: mp3, sample_rate: 24000, bit_rate: 128000}`.
Response: raw MP3 bytes (or JSON `audio` base64 if timestamps are on).

## Voice

Default `leo`: the stock xAI voice documented as a British accent and
"authoritative and strong". That is the closest preset to a calm
British butler. It is not a clone of a real actor. Settings can switch
to `altair`, `lux`, `eve`, `rex`, `sal`, `ara`, or `rigel`. The persona
prompt asks for a dry, polite, slightly crisp butler and forbids
imitating a named actor.
