"""POST /tts proxy core for ContextForge 1.1.0.

No ContextForge import. The router mounts this on the gateway. Tests
drive it with a fake opener, so nothing here contacts xAI.

Upstream is fixed to provider id 78770f3fb044491fa06bfcc0d4b19825
(xai-tablet) and https://api.x.ai/v1/tts. The tablet bearer is never
placed on the upstream request.
"""

from __future__ import annotations

import ipaddress
import json
import threading
import time
from dataclasses import dataclass
from typing import Callable, Mapping, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PATCH_VERSION = "1.1.0+tablet-tts.2"
FIXED_PROVIDER_ID = "78770f3fb044491fa06bfcc0d4b19825"
REQUIRED_PROVIDER_TYPE = "openai_compatible"
REQUIRED_API_BASE = "https://api.x.ai/v1"
UPSTREAM_TTS = "https://api.x.ai/v1/tts"
TABLET_CIDR = "192.168.20.109/32"
REQUIRED_PERMISSION = "llm.invoke"

MAX_TTS_CHARS = 4_000
MAX_IN_FLIGHT = 2
MAX_PER_MINUTE = 30
UPSTREAM_TIMEOUT_SECONDS = 30.0

ALLOWED_VOICES = frozenset(
    {"leo", "altair", "lux", "eve", "rex", "sal", "ara", "rigel"}
)
FORBIDDEN_OVERRIDE_KEYS = frozenset(
    {
        "provider",
        "provider_id",
        "url",
        "upstream",
        "base_url",
        "api_base",
        "model",
        "api_key",
        "authorization",
        "x-api-key",
    }
)


class SpeechDenied(Exception):
    def __init__(self, status: int, detail: str):
        super().__init__(detail)
        self.status = status
        self.detail = detail


class SpeechBusy(SpeechDenied):
    def __init__(self):
        super().__init__(429, "Too many speech requests")


@dataclass(frozen=True)
class AccessRequest:
    has_token: bool
    permissions: tuple
    socket_ip: Optional[str]
    x_forwarded_for: Optional[str]
    x_real_ip: Optional[str]
    ip_restrictions: tuple
    override_keys: tuple = ()


@dataclass(frozen=True)
class UpstreamResult:
    status: int
    content_type: str
    body: bytes


LogFn = Callable[[str], None]


def _noop_log(_message: str) -> None:
    return None


def authorize(access: AccessRequest) -> None:
    """Reject before any upstream call.

    The socket peer is the only IP that can pass. X-Forwarded-For and
    X-Real-IP never grant access. Caddy on :4444 is the edge that
    replaces a client forwarded address with the TCP peer; this check
    does not edit that edge, and it still refuses a header that names
    a different address than the peer the gateway process sees.
    """
    if access.override_keys:
        raise SpeechDenied(400, "Client overrides are not accepted")
    if not access.has_token:
        raise SpeechDenied(401, "Authorization token required")
    if not _scope_allows(access.permissions):
        raise SpeechDenied(403, "Missing llm.invoke")
    if _forwarded_disagrees(access.socket_ip, access.x_forwarded_for, access.x_real_ip):
        raise SpeechDenied(403, "Forwarded IP header does not match the connection")
    if not access.ip_restrictions:
        raise SpeechDenied(403, "Token is not IP-pinned")
    if not _ip_allowed(access.socket_ip, access.ip_restrictions):
        raise SpeechDenied(403, "Connection IP is not allowed by the token pin")
    if not _ip_allowed(access.socket_ip, (TABLET_CIDR,)):
        raise SpeechDenied(403, "Connection IP is not the tablet")


def _scope_allows(permissions: tuple) -> bool:
    """Match ContextForge token_scope_grants for a non-empty scope list.

    None is not used here. An explicit list must contain llm.invoke, *,
    or llm.*. An empty list is not llm.invoke.
    """
    perms = {str(item) for item in permissions}
    if not perms:
        return False
    if "*" in perms or "llm.*" in perms or REQUIRED_PERMISSION in perms:
        return True
    return False


def _forwarded_disagrees(socket_ip: Optional[str], xff: Optional[str], real_ip: Optional[str]) -> bool:
    for raw in (xff, real_ip):
        if raw is None:
            continue
        text = str(raw).strip()
        if not text:
            continue
        first = text.split(",")[0].strip()
        if first != (socket_ip or ""):
            return True
    return False


def _ip_allowed(client_ip: Optional[str], restrictions: tuple) -> bool:
    if not client_ip or client_ip == "unknown":
        return False
    try:
        client = ipaddress.ip_address(client_ip)
    except ValueError:
        return False
    for restriction in restrictions:
        try:
            if "/" in str(restriction):
                if client in ipaddress.ip_network(str(restriction), strict=False):
                    return True
            elif client == ipaddress.ip_address(str(restriction)):
                return True
        except ValueError:
            continue
    return False


def resolve_provider(provider: Optional[Mapping]) -> str:
    """Return the stored xAI key for the pinned xai-tablet row only.

    The id, provider_type, and api_base must match the Key Keeper
    metadata. That metadata is not a TTS capability check. The key is
    the plaintext already produced by decode_auth on the stored
    ciphertext. Callers must not log it. A client api_base or key is
    not read here.
    """
    if not provider or provider.get("id") != FIXED_PROVIDER_ID:
        raise SpeechDenied(503, "Speech provider is not configured")
    if provider.get("enabled") is False:
        raise SpeechDenied(503, "Speech provider is disabled")
    if str(provider.get("provider_type") or "") != REQUIRED_PROVIDER_TYPE:
        raise SpeechDenied(503, "Speech provider type is not openai_compatible")
    base = str(provider.get("api_base") or "").strip().rstrip("/")
    if base != REQUIRED_API_BASE:
        raise SpeechDenied(503, "Speech provider host is not api.x.ai")
    key = provider.get("api_key") or ""
    if not isinstance(key, str) or not key.strip():
        raise SpeechDenied(503, "Speech provider key is missing")
    return key.strip()


def build_tts_payload(
    text: str,
    language: str,
    voice_id: str,
    speed: float = 1.08,
    output_format: Optional[Mapping] = None,
    with_timestamps: Optional[bool] = None,
) -> dict:
    """xAI TTS JSON. text and language are required. Timestamps are rejected.

    The default xAI response is raw audio, not a JSON envelope. Setting
    with_timestamps would change that, and this route does not implement it.
    """
    if with_timestamps:
        raise SpeechDenied(400, "Timestamps are not supported")
    if not isinstance(text, str) or not text.strip():
        raise SpeechDenied(400, "TTS text is empty")
    if len(text) > MAX_TTS_CHARS:
        raise SpeechDenied(400, "TTS text is too long")
    if not isinstance(language, str) or language.strip().lower() != "en":
        raise SpeechDenied(400, "language must be en")
    voice = (voice_id or "").strip().lower()
    if "/" in voice or ":" in voice or voice not in ALLOWED_VOICES:
        raise SpeechDenied(400, "TTS voice is not allowed")
    try:
        pace = float(speed)
    except (TypeError, ValueError):
        raise SpeechDenied(400, "TTS speed is invalid") from None
    if pace < 0.7 or pace > 1.5:
        raise SpeechDenied(400, "TTS speed is out of range")
    payload = {
        "text": text,
        "language": "en",
        "voice_id": voice,
        "speed": pace,
    }
    if output_format is not None:
        if not isinstance(output_format, Mapping):
            raise SpeechDenied(400, "output_format is invalid")
        codec = str(output_format.get("codec") or "mp3").lower()
        if codec != "mp3":
            raise SpeechDenied(400, "codec must be mp3")
        try:
            sample_rate = int(output_format.get("sample_rate", 24000))
            bit_rate = int(output_format.get("bit_rate", 128000))
        except (TypeError, ValueError):
            raise SpeechDenied(400, "output_format is invalid") from None
        if sample_rate not in (8000, 16000, 22050, 24000, 44100, 48000):
            raise SpeechDenied(400, "sample_rate is not allowed")
        if bit_rate < 32000 or bit_rate > 320000:
            raise SpeechDenied(400, "bit_rate is not allowed")
        payload["output_format"] = {
            "codec": "mp3",
            "sample_rate": sample_rate,
            "bit_rate": bit_rate,
        }
    return payload


class TtsProxy:
    def __init__(
        self,
        max_in_flight: int = MAX_IN_FLIGHT,
        max_per_minute: int = MAX_PER_MINUTE,
        timeout: float = UPSTREAM_TIMEOUT_SECONDS,
        log: LogFn = _noop_log,
        clock: Callable[[], float] = time.monotonic,
    ):
        self._slots = max_in_flight
        self._max_per_minute = max_per_minute
        self._timeout = timeout
        self._log = log
        self._clock = clock
        self._recent: list = []
        self._lock = threading.Lock()

    def speak(
        self,
        access: AccessRequest,
        provider: Optional[Mapping],
        text: str,
        language: str,
        voice_id: str,
        speed: float = 1.08,
        output_format: Optional[Mapping] = None,
        with_timestamps: Optional[bool] = None,
        opener: Callable = urlopen,
        cancel: Optional[threading.Event] = None,
    ) -> UpstreamResult:
        authorize(access)
        if cancel is not None and cancel.is_set():
            raise SpeechDenied(499, "Client cancelled")
        key = resolve_provider(provider)
        payload = build_tts_payload(
            text,
            language,
            voice_id,
            speed,
            output_format,
            with_timestamps,
        )
        body = json.dumps(payload).encode("utf-8")
        self._charge_rate()
        return self._send(key, body, opener, cancel)

    def _charge_rate(self) -> None:
        now = self._clock()
        with self._lock:
            self._recent = [stamp for stamp in self._recent if now - stamp < 60.0]
            if len(self._recent) >= self._max_per_minute:
                raise SpeechDenied(429, "TTS rate limit")
            self._recent.append(now)

    def _send(
        self,
        api_key: str,
        body: bytes,
        opener: Callable,
        cancel: Optional[threading.Event],
    ) -> UpstreamResult:
        if cancel is not None and cancel.is_set():
            raise SpeechDenied(499, "Client cancelled")
        self._acquire()
        try:
            if cancel is not None and cancel.is_set():
                raise SpeechDenied(499, "Client cancelled")
            headers = {
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json; charset=utf-8",
                "Accept": "audio/mpeg, application/octet-stream",
            }
            request = Request(UPSTREAM_TTS, data=body, headers=headers, method="POST")

            def _open(req, timeout):
                if cancel is not None and cancel.is_set():
                    raise SpeechDenied(499, "Client cancelled")
                return opener(req, timeout=timeout)

            try:
                response = _open(request, self._timeout)
            except SpeechDenied:
                raise
            except HTTPError as exc:
                self._log("tts upstream HTTP %s bytes=0" % exc.code)
                raise SpeechDenied(502, "Upstream speech request failed") from None
            except URLError:
                self._log("tts upstream HTTP 0 bytes=0")
                raise SpeechDenied(502, "Upstream speech request failed") from None
            except Exception:
                self._log("tts upstream HTTP 0 bytes=0")
                raise SpeechDenied(502, "Upstream speech request failed") from None
            status = getattr(response, "status", None) or getattr(response, "code", 200)
            raw_type = ""
            if getattr(response, "headers", None) is not None:
                raw_type = response.headers.get("Content-Type") or response.headers.get("content-type") or ""
            payload = response.read() if hasattr(response, "read") else b""
            if cancel is not None and cancel.is_set():
                raise SpeechDenied(499, "Client cancelled")
            self._log("tts upstream HTTP %s bytes=%s" % (status, len(payload)))
            if status < 200 or status >= 300:
                raise SpeechDenied(502, "Upstream speech request failed")
            if not raw_type:
                raw_type = "audio/mpeg"
            media = raw_type.split(";")[0].strip().lower()
            if media != "audio/mpeg":
                raise SpeechDenied(502, "Upstream speech response was not audio/mpeg")
            return UpstreamResult(status=int(status), content_type=raw_type, body=payload)
        finally:
            self._release()

    def _acquire(self) -> None:
        with self._lock:
            if self._slots <= 0:
                raise SpeechBusy()
            self._slots -= 1

    def _release(self) -> None:
        with self._lock:
            self._slots += 1
