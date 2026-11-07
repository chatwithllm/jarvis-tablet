# TTS route checklist for IT Admin

Artifact: `contextforge-1.1.0-tts.patch` (version `1.1.0+tablet-tts.2`).

This is a review package. Deploy, restart, permission changes, Caddy edits, and any call to xAI are not approved.

## What was inspected

Published ContextForge tag `v1.1.0`, commit `aee91ff6db63b6aafa77522684a9483c418bb2a3`. That is the version IT named. The running host's working tree was not copied. If `mcpgateway/main.py` or `mcpgateway/middleware/token_scoping.py` on the host differ from that tag, stop. `git apply --check` will fail, and the patch must not be forced.

`v1.1.0` has no HTTP plugin that can add a route. `llm_proxy_router.py` only registers `POST /chat/completions` (`llm.invoke`) and `GET /models` (`llm.read`). Unmatched paths are denied by `TokenScopingMiddleware` before FastAPI can return 404. That matches the diagnosis: a missing handler and a missing operation map both end in 403.

## What the patch does

Only `POST /llm/v1/tts` when `LLM_API_PREFIX=/llm/v1`.

- New `mcpgateway/routers/llm_tts_router.py`.
- New `mcpgateway/services/llm_tts_proxy.py`.
- `main.py`: a second `include_router` on `settings.llm_api_prefix`, in its own try/except so a TTS import error does not remove chat.
- `token_scoping.py`: one added line, `POST {prefix}/tts` → `Permissions.LLM_INVOKE`. The chat and models lines are unchanged.

No STT route, no `/tts/voices`, no generic proxy, no new token, no grant, no Caddy change.

## Key Keeper metadata (not a capability proof)

Read-only metadata, reviewed as labels only. It does not show that `POST /llm/v1/tts` is routed, and it does not show that the provider can synthesize speech. Provider health and the xAI console TTS capability were not checked. No paid upstream call is authorized.

- Active tablet token scopes: `llm.read` and `llm.invoke` only.
- That token's IP pin is still `192.168.20.109/32`.
- Enabled provider `xai-tablet`, id `78770f3fb044491fa06bfcc0d4b19825`, type `openai_compatible`, `api_base` `https://api.x.ai/v1`.

The handler hard-binds that id, that type, and that exact base. A client `model`, `provider`, `api_base`, `api_key`, `Authorization`, or `X-Api-Key` does not select a provider. `voice_id` must be one of the stock allowlist (`leo`, `altair`, `lux`, `eve`, `rex`, `sal`, `ara`, `rigel`). A value with `/` or `:` is rejected. The operation map adds `llm.invoke` for `POST /llm/v1/tts` only. `POST /llm/v1/stt`, `GET /llm/v1/tts/voices`, and every other unmapped path stay on the existing default deny.

## How the stored xAI key is used

The tablet sends `Authorization: Bearer` with `cf-jarvis-tablet-llm`. `get_current_user_with_permissions` validates that token. The route then requires `llm.invoke` with `allow_admin_bypass=False`. A token whose explicit scopes are only `llm.read` is denied. The reported pair `llm.read` plus `llm.invoke` satisfies that scope check. Satisfying it is not a TTS probe.

The upstream bearer is not the tablet token. No client header is copied. The handler loads `llm_providers.id = 78770f3fb044491fa06bfcc0d4b19825` and calls `decode_auth(row.api_key)`, which decrypts the ciphertext already stored on that row (`encode_auth({"api_key": ...})` in `mcpgateway/utils/services_auth.py`, secret `AUTH_ENCRYPTION_SECRET` on the gateway). `decoded["api_key"]` is the only upstream `Authorization` value. The stored `api_base` must be exactly `https://api.x.ai/v1`, and `provider_type` must be `openai_compatible`. Anything else is 503 before a connection.

Logs from this route are status and byte counts only (`tts upstream HTTP <status> bytes=<n>`). Do not treat an older "logging is off" note as evidence. The mock tests assert the log lines even when the logger is recording.

## IP pin and Caddy

Do not change the Caddy listener on port 4444. It replaces a client `X-Forwarded-For` with the TCP peer and stays the edge.

The speech handler does not treat `X-Forwarded-For` or `X-Real-IP` as the client. It uses `request.client.host`, the same peer `TokenScopingMiddleware._get_client_ip` uses. A header whose first address is not that peer is 403 before any upstream call. The token's `scopes.ip_restrictions` must be non-empty and must contain the peer. The tablet token's pin is `192.168.20.109/32`. An empty restriction list does not mean allow-all on this route.

## Tablet v8 compatibility

PR #23 `CloudVoice.synthesize` already sends `language: "en"` with `text`, `voice_id`, `speed: 1.08`, and `output_format` `{codec: mp3, sample_rate: 24000, bit_rate: 128000}`. It does not set `with_timestamps`. Text is capped at 2,000 characters, under this route's 4,000. The voice ids it offers are the allowlist here, default `leo`. A non-2xx response, including today's 403, returns null and the app speaks with Android TTS. No app change is required for the `language` field.

Gaps, not fixed here:

- The app still calls `POST /llm/v1/stt`. This patch does not add STT, so that call stays 403 or 404 and the app keeps using Vosk. That is intentional.
- The app will accept a JSON body that starts with `{` and try to base64-decode an `audio` field. This route refuses `with_timestamps`, so a success body is raw MP3 (`audio/mpeg`), which the app plays as bytes. Do not enable timestamps later without an app change.
- The app's read timeout is 40 seconds. This route's upstream timeout is 30 seconds, so the app should see a finished error rather than hang.

## Backup

1. Record the image id or the supervisor unit, and the git revision if the tree is a checkout.
2. Copy `mcpgateway/main.py` and `mcpgateway/middleware/token_scoping.py` to a directory outside the app tree.
3. Snapshot the database. This patch does not migrate it. The snapshot is so a bad restart can be reasoned about. Do not edit the `llm_providers` row.

## Install (not approved)

1. On a tree that `git apply --check` accepts, apply `contextforge-1.1.0-tts.patch`.
2. Confirm `llm_proxy_router.py` is untouched.
3. Restart is required. `main.py` and `token_scoping.py` load at process start. A restart drops every gateway consumer, not only the tablet. Do not restart until that is approved.

## Validation after a future restart

Use the existing tablet token from its secret store. Do not paste it into a ticket.

From `192.168.20.109`:

```bash
curl -sS -D - -o /tmp/out.mp3 -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Authorization: Bearer $CF_LLM_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en","speed":1.08,"output_format":{"codec":"mp3","sample_rate":24000,"bit_rate":128000}}'
```

Expect HTTP 200 and `Content-Type: audio/mpeg` only after a future approved restart. That result is not claimed here. If it is ever run, logs from this route must be status and size only, even if gateway logging is on. They must not show the text, the audio, or either bearer. Provider health remains unverified until that separate decision.

From any other source address:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Authorization: Bearer $CF_LLM_TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Forwarded-For: 192.168.20.109" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en"}'
```

Expect 403. If this returns 200, the pin is wrong. Roll back.

No token must be 401:

```bash
curl -sS -o /dev/null -w "%{http_code}\n" -X POST \
  http://192.168.20.217:4444/llm/v1/tts \
  -H "Content-Type: application/json" \
  -d '{"text":"Good evening.","voice_id":"leo","language":"en"}'
```

Chat and models stay on their old permissions. A `llm.invoke`-only token may still get 403 on `GET /llm/v1/models` because that route requires `llm.read`. That is the existing map, not a regression.

## Rollback

1. Restore the two backed-up files and delete the two new modules.
2. Restart the same way as the install.
3. `POST /llm/v1/tts` should fail closed again (403 from the scope map, or 404 if the map line was removed too). `POST /llm/v1/chat/completions` should behave as it did before.
4. No database rollback. The provider row is unchanged.
