# ContextForge speech routes — review plan

Status: plan only. No gateway code, tests, or live changes are in this pull request. IT Admin deploys later, after review. This does not change the tablet client (PR #23).

Pinned upstream: IBM ContextForge `v1.1.0`, commit `aee91ff6db63b6aafa77522684a9483c418bb2a3` (`github.com/IBM/mcp-context-forge`). The household gateway is that release. `LLM_API_PREFIX` is `/llm/v1`, so the public base the tablet already uses is `http://192.168.20.217:4444/llm/v1`.

## Extension point

`v1.1.0` has no safe plugin hook for adding HTTP routes.

`mcpgateway/plugins/` is the MCP tool-plugin framework (invoke hooks, not FastAPI mounts). Nothing there can register `POST /llm/v1/stt` without editing the process that builds the app.

`mcpgateway/routers/llm_proxy_router.py` is the OpenAI-compatible proxy. On this tag it exposes only:

- `POST /chat/completions`, permission `llm.invoke`
- `GET /models`, permission `llm.read`

`main.py` mounts that router only when `settings.llmchat_enabled` is true:

```python
app.include_router(llm_proxy_router, prefix=settings.llm_api_prefix, tags=["LLM Proxy"])
```

With `LLM_API_PREFIX=/llm/v1` that is `POST /llm/v1/chat/completions` and `GET /llm/v1/models`. A config change cannot add audio routes. The proxy service translates chat completions only.

There is a second, easy-to-miss gate. `TokenScopingMiddleware._check_permission_restrictions` denies any path that is not in its maps (`token_scoping.py`, default deny at the end of that function). LLM paths come from `_get_llm_permission_patterns`, which today lists only `chat/completions` and `models`. A new route that is mounted but not added there returns 403 for a normal `llm.invoke` token before the handler runs.

Recommended patch, and nothing wider:

1. New router, included on the same prefix, next to the existing include. Do not edit the chat or models handlers.
2. Add three entries to `_get_llm_permission_patterns` only. Leave the existing two lines as they are.
3. New service module for the upstream call, so the router stays a thin auth-then-forward wrapper.

Files that patch would touch:

| File | Change |
| --- | --- |
| `mcpgateway/routers/llm_speech_router.py` | new |
| `mcpgateway/services/llm_speech_proxy.py` | new |
| `mcpgateway/main.py` | one `include_router` immediately after the LLM proxy include |
| `mcpgateway/middleware/token_scoping.py` | three added patterns inside `_get_llm_permission_patterns` |

`llm_proxy_router.py` stays unmodified. That is the regression boundary.

Do not follow redirects on the upstream call. A redirect would send the provider key to another host.

## xAI paths and fields

Source: xAI speech-to-text and text-to-speech docs (checked against the pages published for `POST /v1/stt` and `POST /v1/tts`). xAI does not implement OpenAI `/v1/audio/transcriptions` or `/v1/audio/speech`. The tablet (PR #23) calls `{llmBase}/stt` and `{llmBase}/tts`, which on this gateway are the paths below.

### `POST /llm/v1/stt` → `POST https://api.x.ai/v1/stt`

Client body is `multipart/form-data`. xAI requires either `file` or `url`. `file` must be the last part. Fields sent after `file` can be ignored on a streaming parse.

| Field | xAI | What the proxy should do |
| --- | --- | --- |
| `file` | Audio upload, max 500 MB, last part | Accept a WAV/audio upload. Place it last. Bound the size (below). |
| `url` | xAI downloads that URL itself | Reject. A client URL is an arbitrary server-side fetch. |
| `model` | Default `grok-voice-transcribe-2.0` | Ignore any client value. Always send `grok-voice-transcribe-2.0`. |
| `language` | Optional bias, e.g. `en` | Forward `en` (the tablet sends it). |
| `audio_format`, `sample_rate`, `format`, `multichannel`, `channels`, `diarize`, `keyterm`, `filler_words`, `vad_threshold` | Optional | Do not forward. The tablet sends a WAV, which xAI detects without `audio_format`. |

Success body is JSON: `text` (string), `language`, `duration`, and optional `words`. Return that JSON to the tablet. The tablet reads `text`.

### `POST /llm/v1/tts` → `POST https://api.x.ai/v1/tts`

Client body is JSON.

| Field | xAI | What the proxy should do |
| --- | --- | --- |
| `text` | Required, max 60,000 characters. Speech tags allowed. | Required. Bound length (below). |
| `voice_id` | Optional, default `eve`. Case-insensitive. Stock ids include `leo` (British, authoritative), `eve` (British, energetic), `ara`, `rex`, `sal`, plus the longer roster (`altair`, `lux`, `rigel`, …). | Forward only an allowlist the tablet Settings offers: `leo`, `altair`, `lux`, `eve`, `rex`, `sal`, `ara`, `rigel`. Reject anything else, including a custom cloned-voice id. |
| `language` | Required. `en` or `auto`, plus the documented BCP-47 set. | Forward `en`. |
| `speed` | Optional, `0.7`–`1.5`, default `1.0`. | Forward if present and inside that range. The tablet default is `1.08`. |
| `output_format` | Optional object. Default MP3, 24 kHz, 128 kbps (`codec`, `sample_rate`, `bit_rate`). | Forward only `codec=mp3` with the tablet's `sample_rate` / `bit_rate`. |
| `with_timestamps` | When true, the body becomes JSON with base64 audio instead of raw bytes. | Do not set it. The tablet expects raw audio. |
| `optimize_streaming_latency`, `text_normalization`, `replace` | Optional | Do not forward. |

Success body is raw audio bytes. The default Content-Type is `audio/mpeg`. The proxy returns that body unchanged and copies the upstream `Content-Type`. It does not wrap the audio in JSON.

### `GET /llm/v1/tts/voices` → `GET https://api.x.ai/v1/tts/voices` (optional)

xAI returns JSON `{"voices": [{"voice_id", "name", "language"}, ...]}`. No request body. Same auth and provider pin as the other two routes. The tablet does not need this for v1; Settings has a fixed list. Ship it only if the extra route is cheap to review. If it ships, map it to `llm.invoke` as well, not `llm.read`, so the tablet token does not need a second permission.

## Upstream pin

One provider row, no exceptions:

- Id `78770f3fb044491fa06bfcc0d4b19825` (name `xai-tablet` in the household gateway).
- Table `llm_providers` (`LLMProvider` in `mcpgateway/db.py` on v1.1.0).
- API key is the stored ciphertext, decoded with `decode_auth` from `mcpgateway/utils/services_auth.py` (`auth_data["api_key"]`). That key is what `Authorization: Bearer` on the upstream call uses.
- The tablet bearer is never forwarded upstream.
- `api_base` on that row may be empty, `https://api.x.ai`, or `https://api.x.ai/v1`. Anything else is a 503 and no outbound call. Effective base is always `https://api.x.ai/v1`.
- Disabled row, missing row, or missing key is a 503 and no outbound call.

Reject, before any upstream call, a client attempt to choose the target: query or header names `provider`, `provider_id`, `url`, `upstream`, `base_url`, `model`, `api_key`, or an STT `url` part. `model` inside the STT form is ignored and replaced, not honored.

The upstream `Authorization` header is built in the proxy. No client header is copied onto the upstream request.

## Auth and the IP pin

Reuse the existing stack. Do not add a second token parser.

1. `get_current_user_with_permissions` (same dependency as chat). No token, bad token, or cookie-only API call is 401. This already runs `validate_token_user`.
2. `@require_permission("llm.invoke", allow_admin_bypass=False)`. Missing `llm.invoke` is 403, including for an admin. The chat route leaves the default admin bypass on; speech should not.
3. Token-scope map, so the middleware agrees with the decorator. Add, and do not rewrite the existing lines:

   - `POST {prefix}/stt` → `llm.invoke`
   - `POST {prefix}/tts` → `llm.invoke`
   - `GET {prefix}/tts/voices` → `llm.invoke` if that route is included

   `{prefix}` is the same normalized `llm_api_prefix` already used for chat and models. On this gateway that is `/llm/v1` (`_strip_v1_prefix` only strips a leading `/v1`, so `/llm/v1` stays intact).

4. IP pin, checked in the speech handler before the upstream client is opened. `TokenScopingMiddleware._get_client_ip` already uses `request.client.host` and does not read `X-Forwarded-For` or `X-Real-IP`. `ProxyHeadersMiddleware` may already have rewritten `request.client` when the peer is a trusted proxy; the speech handler still must not consult those headers itself.

   - Client IP = `request.client.host` only. Missing or unparseable host is 403.
   - If `X-Forwarded-For` or `X-Real-IP` is present and its first address is not exactly the socket IP, 403. Matching values are ignored, not trusted.
   - Read `ip_restrictions` from the token scopes (JWT `scopes.ip_restrictions`, the same list `_check_ip_restrictions` uses). Empty or missing restrictions are 403 on these routes. The general middleware treats an empty list as allow-all; speech must not.
   - Allow only when the socket IP is inside one of those CIDRs or equal to one of those addresses. The tablet token is pinned to `192.168.20.109/32`. A connection from any other address is 403 even if a forwarded header names the tablet.

All four denies (no auth, missing `llm.invoke`, wrong IP, spoofed forwarded header) happen before provider lookup and before any socket to `api.x.ai`.

## Limits

| Limit | Value | Result if exceeded |
| --- | --- | --- |
| STT upload | 8 MiB | 413, no upstream call. xAI allows 500 MB; a tablet turn is a few seconds of 16 kHz PCM. |
| TTS `text` | 4,000 characters, non-empty | 400, no upstream call. xAI allows 60,000. |
| TTS `speed` | 0.7–1.5 | 400 |
| TTS `voice_id` | allowlist above | 400 |
| Voices response | 1 MiB | 502, body discarded |
| In flight | 2 speech calls process-wide | 429, no upstream call. Count the slot only after auth succeeds. |
| Upstream timeout | 30 seconds | 502, generic body |

Upstream HTTP errors and connection failures become 502 with a fixed detail string. Do not return the upstream body. It can echo request data.

Logging: status code and byte counts only. Do not log the tablet bearer, the provider key, the audio, the transcript, or the TTS text. Do not log raw `Authorization` headers.

## Test plan

Mock the HTTP opener. No test may call `api.x.ai` or the household gateway.

Deny cases, each asserting the opener was not called:

- No `Authorization` → 401.
- Token present, permissions lack `llm.invoke` (for example only `llm.read`) → 403. Include an admin flag so bypass is visibly off.
- Socket `203.0.113.5`, restrictions `["192.168.20.109/32"]`, no forwarded headers → 403.
- Socket `203.0.113.5` and `X-Forwarded-For: 192.168.20.109` → 403 (the header must not promote the caller).
- Socket `192.168.20.109` and `X-Real-IP: 203.0.113.5` → 403 (disagreement is a spoof).
- Token with `ip_restrictions: []` from `192.168.20.109` → 403.
- Query `?url=https://example.invalid` or `?provider=other` → 400.
- STT part `url=https://example.invalid/a.wav` → 400.

Allow, opener called once:

- Socket `192.168.20.109`, restrictions `["192.168.20.109/32"]`, permission `llm.invoke`, no forwarded headers.
- Same, with `X-Forwarded-For: 192.168.20.109` (equal to the socket, ignored).

Multipart order:

- Built STT body contains `name="model"` then `name="language"` then `name="file"`, and the closing boundary is the only thing after the file bytes.
- A client-supplied `model=other-model` does not appear. The part value is `grok-voice-transcribe-2.0`.
- Upload of `MAX_STT_BYTES + 1` → 413 and the opener is not called.

TTS content type:

- Mock upstream returns `Content-Type: audio/mpeg` and raw bytes. The proxy result uses that media type and the same bytes, not a JSON wrapper.
- Upstream URL is exactly `https://api.x.ai/v1/tts` (and `/stt`, `/tts/voices`).
- Upstream `Authorization` is `Bearer` plus the decoded provider key, not the tablet token.
- Text longer than 4,000 characters → 400, opener not called.
- `voice_id` outside the allowlist → 400.
- Provider id other than `78770f3fb044491fa06bfcc0d4b19825`, or `api_base` of `https://evil.example/v1`, → 503, opener not called.

Chat and models regression:

- The diff does not modify `mcpgateway/routers/llm_proxy_router.py`.
- `_get_llm_permission_patterns` still contains exactly the original pair, plus the added speech lines: `POST …/chat/completions` → `llm.invoke`, `GET …/models` → `llm.read`.
- A unit check of that function (or of the patched source) shows those two patterns still match and still require those permissions.

## Deploy, validation, rollback

Prerequisites, all checked before the restart:

- Checkout is ContextForge `v1.1.0` (`aee91ff`). Confirm `mcpgateway/version.py` or the image tag. Do not apply this onto a newer tree without re-reading `main.py` and `token_scoping.py`.
- `MCPGATEWAY_LLMCHAT_ENABLED` is true and `LLM_API_PREFIX=/llm/v1`.
- Provider `78770f3fb044491fa06bfcc0d4b19825` exists, is enabled, and its `api_base` is empty or `https://api.x.ai` or `https://api.x.ai/v1`.
- Token `cf-jarvis-tablet-llm` has `llm.invoke` and `ip_restrictions` of `192.168.20.109/32`. No new token and no new xAI key. The gateway keeps using the key already stored on that provider row.
- Take the backup below first.

Backup:

- Copy the live tree, or at least `mcpgateway/main.py` and `mcpgateway/middleware/token_scoping.py`, to a dated directory outside the app tree.
- Snapshot the Postgres (or the configured) database. This patch does not migrate schema, but the rollback story should still start from a snapshot.
- Record the current process supervisor command and the image id if this is a container.

Install:

- Apply the reviewed patch on a `v1.1.0` tree. It adds the two new modules, the `include_router` line, and the three permission patterns.
- Do not edit `llm_proxy_router.py`.
- Install is a code drop plus process restart. There is no migration and no new Python dependency if the outbound call uses the stdlib or the HTTP client ContextForge already vendors.

Restart: yes. `main.py` and `token_scoping.py` are imported at process start. A reload of Python files on disk does nothing until the process is restarted. One replica is enough for this gateway.

Validation, after restart. Use the tablet token from the usual secret file. Do not paste it into the ticket.

From `192.168.20.109` (the tablet, or an SSH session whose source address is that host):

```bash
# STT. Expect HTTP 200 and JSON with a "text" field. Body is not logged.
curl -sS -D - -o /tmp/stt.json -X POST \
  http://192.168.20.217:4444/llm/v1/stt \
  -H "Authorization: Bearer $CF_LLM_TOKEN" \
  -F language=en \
  -F file=@turn.wav

# TTS. Expect HTTP 200 and Content-Type: audio/mpeg. File should be playable.
curl -sS -D - -o /tmp/out.mp3 -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Authorization: Bearer $CF_LLM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en","speed":1.08}'

# Unchanged chat surface. models needs llm.read; a 403 here is the old rule, not a regression.
curl -sS -o /dev/null -w "models %{http_code}\n" \
  http://192.168.20.217:4444/llm/v1/models \
  -H "Authorization: Bearer $CF_LLM_TOKEN"
```

Negative tests. Expect 403 and no new outbound call in the gateway log (status only, no audio):

```bash
# No token. Expect 401.
curl -sS -o /dev/null -w "%{http_code}\n" -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Content-Type: application/json" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en"}'

# Wrong source IP, header claims the tablet. Run this from any host that is not 192.168.20.109.
curl -sS -o /dev/null -w "%{http_code}\n" -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Authorization: Bearer $CF_LLM_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 192.168.20.109" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en"}'
```

The second call must be 403. If it is 200, the route trusted the forwarded header and the pin is broken. Stop and roll back.

Rollback:

- Restore `main.py` and `token_scoping.py` from the backup. Remove the two new modules.
- Restart the same way as the install.
- Confirm `POST /llm/v1/stt` is 404 again, and `POST /llm/v1/chat/completions` still answers for a known-good request.
- No database rollback is required. The provider row was not modified.

## Out of scope

- Applying this on the live gateway, rotating keys, or issuing a new token.
- OpenAI `/audio/transcriptions` and `/audio/speech`. xAI does not serve them.
- The realtime WebSocket `wss://api.x.ai/v1/realtime`.
- Changing PR #23. Until this proxy exists, that client already falls back to Vosk and Android TTS on 404.
