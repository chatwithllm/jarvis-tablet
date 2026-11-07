# TTS route checklist for IT Admin

Artifact: `contextforge-1.1.0-tts.patch` (version `1.1.0+tablet-tts.5`). The sha256, byte count, and line count are in `ARTIFACT.txt` next to the patch. Recompute them before review; do not trust a copied hash from chat.

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

The handler hard-binds that id, that type, and that exact base. A client `model`, `provider`, `api_base`, or `api_key` in the query string or JSON body does not select a provider. The inbound `Authorization` header is the tablet token: it is required, and it is not an override. It is never copied upstream. `voice_id` must be one of the stock allowlist (`leo`, `altair`, `lux`, `eve`, `rex`, `sal`, `ara`, `rigel`). A value with `/` or `:` is rejected. The operation map adds `llm.invoke` for `POST /llm/v1/tts` only. `POST /llm/v1/stt`, `GET /llm/v1/tts/voices`, and every other unmapped path stay on the existing default deny.

## How the stored xAI key is used

The tablet sends `Authorization: Bearer` with `cf-jarvis-tablet-llm`. `get_current_user_with_permissions` validates that token. The route then requires `llm.invoke` with `allow_admin_bypass=False`. A token whose explicit scopes are only `llm.read` is denied. The reported pair `llm.read` plus `llm.invoke` satisfies that scope check. Satisfying it is not a TTS probe.

The upstream bearer is not the tablet token. No client header is copied. The handler loads `llm_providers.id = 78770f3fb044491fa06bfcc0d4b19825` and calls `decode_auth(row.api_key)`, which decrypts the ciphertext already stored on that row (`encode_auth({"api_key": ...})` in `mcpgateway/utils/services_auth.py`, secret `AUTH_ENCRYPTION_SECRET` on the gateway). `decoded["api_key"]` is the only upstream `Authorization` value. The stored `api_base` must be exactly `https://api.x.ai/v1`, and `provider_type` must be `openai_compatible`. Anything else is 503 before a connection.

The production logger on this route is a no-op. `TtsProxy()` is constructed with `_noop_log`, and the router does not pass another logger. No request text, audio, provider key, or status line is emitted. An injected test sink can record `tts upstream HTTP <status> bytes=<n>` and nothing else; that sink is not installed on the gateway. Do not add a logger to make the status line appear.

## Trust boundary

Do not change Caddy. On this gateway, Caddy on port 4444 replaces a client-supplied `X-Forwarded-For` with the TCP peer, and the backend listens on `127.0.0.1:4449`. Those two facts are the IP boundary. One serving process was evidenced. The configured maximum worker count was not verified.

ContextForge `v1.1.0` registers `ProxyHeadersMiddleware` with `trusted_hosts="*"` (`mcpgateway/main.py`). That middleware can rewrite `request.client.host` from `X-Forwarded-For` before this handler runs. A unit test that compares the header to `request.client.host` does not see the original TCP peer after that rewrite. This route therefore does not claim the header check is the pin. It applies the token's existing `scopes.ip_restrictions` to whatever peer the process sees, the same input `TokenScopingMiddleware._get_client_ip` uses. The household token is already pinned to `192.168.20.109/32`. This patch does not add a second hardcoded address and does not grant a wider pin.

An empty restriction list is still denied on this route. A forwarded header that names a different address than the peer the handler actually sees is denied, which only helps when the header and the peer disagree. After `trusted_hosts="*"`, they often agree because the peer was rewritten. Caddy's overwrite plus a loopback-only backend are what stop a direct client from supplying that header.

## Tablet v8 compatibility

PR #23 `CloudVoice.synthesize` already sends `language: "en"` with `text`, `voice_id`, `speed: 1.08`, and `output_format` `{codec: mp3, sample_rate: 24000, bit_rate: 128000}`. It does not set `with_timestamps`. Text is capped at 2,000 characters, under this route's 4,000. The voice ids it offers are the allowlist here, default `leo`. A non-2xx response, including today's 403, returns null and the app speaks with Android TTS. No app change is required for the `language` field.

Gaps, not fixed here:

- The app still calls `POST /llm/v1/stt`. This patch does not add STT, so that call stays 403 or 404 and the app keeps using Vosk. That is intentional.
- The app will accept a JSON body that starts with `{` and try to base64-decode an `audio` field. This route refuses `with_timestamps`, so a success body is raw MP3 (`audio/mpeg`), which the app plays as bytes. Do not enable timestamps later without an app change.
- The app's read timeout is 40 seconds. This route's upstream timeout is 30 seconds, so the app should see a finished error rather than hang.

## Backup

This patch does not change the database, the schema, or the provider row. Do not take a database snapshot and do not copy a database dump. A dump would contain the provider ciphertext.

1. Record the image id or the supervisor unit, and the git revision if the tree is a checkout.
2. Copy `mcpgateway/main.py` and `mcpgateway/middleware/token_scoping.py` to a directory outside the app tree. Those two files, plus deletion of the two new modules, are the rollback set.

## Runtime facts

IT confirmed the published patch at `7a463af` matched sha256 `a88ff204999cdcb23d1d442d1c1943d8245958e6fd0dbca11f3bd160e671d280` (24895 bytes, 674 lines), CI was green, the installed source matched that tree, and an isolated apply-check passed. This revision replaces that patch. Re-check the new `ARTIFACT.txt` hash. Do not deploy this revision on that confirmation.

IT also confirmed the backend listens on `127.0.0.1:4449` and Caddy on port 4444 overwrites `X-Forwarded-For` before the proxy. Runtime packages: ContextForge 1.1.0, FastAPI 0.162.0, Starlette 1.10.0, Pydantic 2.14.2, httpx 0.28.1. The mock HTTP tests install those FastAPI, Starlette, Pydantic, and httpx versions. They do not boot ContextForge.

One serving process was evidenced. There is no confirmed `--workers` value, and the configured maximum is unknown. The in-flight cap (2) and the per-minute cap (30) are per process. They are not global across workers, and they reset when the process restarts. A second worker would double both caps.

Key Keeper confirmed the active RBAC role is global `platform_admin` with permissions `*`, which grants `llm.invoke`. The token's team is null. Its scopes and `192.168.20.109/32` pin are unchanged. That is still not proof that this provider may call speech. Provider-console approval is pending. No paid call was made.

## Cancellation and billing

`asyncio.wait_for` around `asyncio.to_thread` does not kill the worker thread and does not undo upstream work. The router's 35 second wait is only a backstop for the awaiting task. The worker's own deadline (30 seconds unless constructed with another timeout) bounds the socket.

The body is read one byte at a time, and the socket timeout is set to the time remaining before each read. A peer that trickles a byte every few tens of milliseconds cannot sit inside `read(65536)` until the buffer fills. A peer that sends nothing returns when that socket timeout fires. The slot stays taken until the read returns, the response is closed, and the thread exits. A late body is discarded. Closing the socket does not cancel a provider bill, and this process cannot force the thread to die any sooner than the socket call returns.

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
3. `POST /llm/v1/tts` should fail closed again (403 from the scope map once that line is gone, or 404 if the router is gone and the map line is gone too). `POST /llm/v1/chat/completions` should behave as it did before.
4. There is no database rollback. Nothing in the database was changed.
