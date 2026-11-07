"""HTTP tests for the TTS router with representative tablet requests.

ContextForge itself is not installed. The modules it would import are
fakes with the same call shape. The route, auth dependency, and proxy
are the real review code. Nothing calls xAI.
"""

from __future__ import annotations

import importlib.util
import json
import sys
import types
import unittest
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
PROXY_PATH = ROOT / "mcpgateway" / "services" / "llm_tts_proxy.py"
ROUTER_PATH = ROOT / "mcpgateway" / "routers" / "llm_tts_router.py"

TABLET_BEARER = "tablet-canary-BEARER"
PROVIDER_KEY = "provider-canary-KEY"
TEXT_CANARY = "CANARY_TEXT_do_not_echo"
MODEL_CANARY = "CANARY_MODEL_do_not_echo"

STATE = {
    "scopes": ["llm.read", "llm.invoke"],
    "restrictions": ["192.168.20.109/32"],
    "client_host": "192.168.20.109",
}


def _mod(name):
    module = types.ModuleType(name)
    sys.modules[name] = module
    return module


def _install_fakes():
    mg = _mod("mcpgateway")
    mg.__path__ = []
    db = _mod("mcpgateway.db")

    class LLMProvider:
        id = "id"
        api_key = "api_key"
        api_base = "api_base"
        provider_type = "provider_type"
        enabled = "enabled"

    def get_db():
        yield object()

    db.LLMProvider = LLMProvider
    db.get_db = get_db

    sqlalchemy = _mod("sqlalchemy")

    def select(*_args, **_kwargs):
        return ("select", _args)

    sqlalchemy.select = select
    orm = _mod("sqlalchemy.orm")

    class Session:
        pass

    orm.Session = Session

    mw = _mod("mcpgateway.middleware")
    mw.__path__ = []
    rbac = _mod("mcpgateway.middleware.rbac")

    def require_permission(permission, allow_admin_bypass=True, **_kwargs):
        def decorator(func):
            async def wrapper(*args, **kwargs):
                user = kwargs.get("current_user")
                if not user or "email" not in user:
                    raise HTTPException(status_code=401, detail="Authorization token required")
                scopes = list(user.get("token_scopes") or [])
                if permission not in scopes and "*" not in scopes:
                    raise HTTPException(status_code=403, detail="Missing llm.invoke")
                return await func(*args, **kwargs)

            wrapper.__signature__ = __import__("inspect").signature(func)
            wrapper._required_permission = permission
            wrapper._allow_admin_bypass = allow_admin_bypass
            return wrapper

        return decorator

    async def get_current_user_with_permissions(request: Request):
        header = request.headers.get("authorization")
        if not header or not header.lower().startswith("bearer "):
            raise HTTPException(status_code=401, detail="Authorization token required")
        return {"email": "tablet@example", "token_scopes": list(STATE["scopes"])}

    rbac.require_permission = require_permission
    rbac.get_current_user_with_permissions = get_current_user_with_permissions

    utils = _mod("mcpgateway.utils")
    utils.__path__ = []
    services_auth = _mod("mcpgateway.utils.services_auth")

    def decode_auth(value, secret=None):
        return {"api_key": PROVIDER_KEY}

    services_auth.decode_auth = decode_auth

    verify = _mod("mcpgateway.utils.verify_credentials")

    def get_auth_bearer_token_from_request(request):
        header = request.headers.get("authorization") or ""
        if header.lower().startswith("bearer "):
            return header.split(" ", 1)[1]
        return None

    async def verify_jwt_token_cached(token, request=None):
        return {"scopes": {"permissions": list(STATE["scopes"]), "ip_restrictions": list(STATE["restrictions"])}}

    verify.get_auth_bearer_token_from_request = get_auth_bearer_token_from_request
    verify.verify_jwt_token_cached = verify_jwt_token_cached

    services = _mod("mcpgateway.services")
    services.__path__ = [str(PROXY_PATH.parent)]
    spec = importlib.util.spec_from_file_location("mcpgateway.services.llm_tts_proxy", PROXY_PATH)
    proxy = importlib.util.module_from_spec(spec)
    sys.modules["mcpgateway.services.llm_tts_proxy"] = proxy
    spec.loader.exec_module(proxy)

    routers = _mod("mcpgateway.routers")
    routers.__path__ = [str(ROUTER_PATH.parent)]
    return proxy


class _Peer:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") == "http":
            scope = dict(scope)
            scope["client"] = (STATE["client_host"], 443)
        await self.app(scope, receive, send)


class _Audio:
    def __init__(self):
        self.calls = []
        self.closed = False

    def __call__(self, request, timeout=None):
        self.calls.append(request)
        return self

    @property
    def status(self):
        return 200

    @property
    def headers(self):
        return {"Content-Type": "audio/mpeg"}

    def read(self, _n=65536):
        if self.closed:
            return b""
        self.closed = True
        return b"ID3canary-audio"

    def close(self):
        self.closed = True


class RouterHttpTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.proxy = _install_fakes()
        spec = importlib.util.spec_from_file_location("mcpgateway.routers.llm_tts_router", ROUTER_PATH)
        cls.router_mod = importlib.util.module_from_spec(spec)
        sys.modules["mcpgateway.routers.llm_tts_router"] = cls.router_mod
        spec.loader.exec_module(cls.router_mod)

    def setUp(self):
        STATE["scopes"] = ["llm.read", "llm.invoke"]
        STATE["restrictions"] = ["192.168.20.109/32"]
        STATE["client_host"] = "192.168.20.109"
        self.logs = []
        self.audio = _Audio()
        self.router_mod._proxy = self.proxy.TtsProxy(log=self.logs.append, opener=self.audio)

        def _view(_db):
            return {
                "id": self.proxy.FIXED_PROVIDER_ID,
                "enabled": True,
                "provider_type": "openai_compatible",
                "api_base": "https://api.x.ai/v1",
                "api_key": PROVIDER_KEY,
            }

        self.router_mod._provider_view = _view
        app = FastAPI()
        app.include_router(self.router_mod.llm_tts_router, prefix="/llm/v1")
        self.client = TestClient(_Peer(app), raise_server_exceptions=False)

    def _post(self, payload, headers=None, params=None):
        base = {"Authorization": "Bearer " + TABLET_BEARER}
        if headers:
            base.update(headers)
        return self.client.post("/llm/v1/tts", json=payload, headers=base, params=params)

    def test_tablet_authorization_header_is_accepted_and_not_forwarded(self):
        response = self._post(
            {
                "text": "Good evening.",
                "language": "en",
                "voice_id": "leo",
                "speed": 1.08,
                "output_format": {"codec": "mp3", "sample_rate": 24000, "bit_rate": 128000},
            }
        )
        self.assertEqual(response.status_code, 200, response.text)
        self.assertTrue(response.headers["content-type"].startswith("audio/mpeg"))
        self.assertEqual(len(self.audio.calls), 1)
        upstream = dict(self.audio.calls[0].header_items())
        self.assertEqual(upstream.get("Authorization"), "Bearer " + PROVIDER_KEY)
        self.assertNotEqual(upstream.get("Authorization"), "Bearer " + TABLET_BEARER)
        self.assertNotIn(TABLET_BEARER, response.content.decode("utf-8", "replace"))
        self.assertNotIn(PROVIDER_KEY, "\n".join(self.logs))
        self.assertNotIn("Good evening.", "\n".join(self.logs))
        self.assertEqual(self.logs, ["tts upstream HTTP 200 bytes=15"])

    def test_missing_authorization_is_401_before_upstream(self):
        response = self.client.post(
            "/llm/v1/tts",
            json={"text": TEXT_CANARY, "language": "en", "voice_id": "leo"},
        )
        self.assertEqual(response.status_code, 401)
        self.assertEqual(self.audio.calls, [])
        self.assertNotIn(TEXT_CANARY, response.text)

    def test_read_only_scope_is_403_before_upstream(self):
        STATE["scopes"] = ["llm.read"]
        response = self._post({"text": TEXT_CANARY, "language": "en", "voice_id": "leo"})
        self.assertEqual(response.status_code, 403)
        self.assertEqual(self.audio.calls, [])
        self.assertNotIn(TEXT_CANARY, response.text)

    def test_wrong_peer_is_403_even_with_spoofed_forwarded_for(self):
        STATE["client_host"] = "203.0.113.5"
        response = self._post(
            {"text": TEXT_CANARY, "language": "en", "voice_id": "leo"},
            headers={"X-Forwarded-For": "192.168.20.109"},
        )
        self.assertEqual(response.status_code, 403)
        self.assertEqual(self.audio.calls, [])
        self.assertNotIn(TEXT_CANARY, response.text)

    def test_query_override_rejected_without_echo(self):
        response = self._post(
            {"text": TEXT_CANARY, "language": "en", "voice_id": "leo"},
            params={"api_key": MODEL_CANARY},
        )
        self.assertEqual(response.status_code, 400)
        self.assertEqual(self.audio.calls, [])
        self.assertNotIn(TEXT_CANARY, response.text)
        self.assertNotIn(MODEL_CANARY, response.text)

    def test_body_model_override_422_does_not_echo_canaries(self):
        response = self._post(
            {
                "text": TEXT_CANARY,
                "language": "en",
                "voice_id": "leo",
                "model": MODEL_CANARY,
            }
        )
        self.assertEqual(response.status_code, 422)
        self.assertEqual(self.audio.calls, [])
        self.assertNotIn(TEXT_CANARY, response.text)
        self.assertNotIn(MODEL_CANARY, response.text)
        self.assertEqual(response.json()["detail"], "Invalid TTS request")


class MountIsolationTests(unittest.TestCase):
    def test_router_import_failure_returns_503_and_chat_still_works(self):
        import importlib.util

        for name in ("mcpgateway", "mcpgateway.routers", "mcpgateway.services"):
            if name not in sys.modules:
                module = types.ModuleType(name)
                module.__path__ = []
                sys.modules[name] = module
        spec = importlib.util.spec_from_file_location(
            "mcpgateway.services.llm_tts_proxy_mount",
            PROXY_PATH,
        )
        proxy = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = proxy
        spec.loader.exec_module(proxy)
        app = FastAPI()

        @app.post("/llm/v1/chat/completions")
        def chat():
            return {"id": "chat-ok"}

        saved = sys.modules.get("mcpgateway.routers.llm_tts_router")
        sys.modules["mcpgateway.routers.llm_tts_router"] = types.ModuleType(
            "mcpgateway.routers.llm_tts_router"
        )
        logs = []

        class Logger:
            def info(self, *_args, **_kwargs):
                return None

            def error(self, message, *args):
                logs.append(message % args if args else message)

        try:
            status = proxy.mount_tts(app, "/llm/v1", Logger())
        finally:
            if saved is not None:
                sys.modules["mcpgateway.routers.llm_tts_router"] = saved
        self.assertEqual(status, "unavailable")
        self.assertEqual(logs, ["LLM TTS router unavailable: ImportError"])
        client = TestClient(app)
        self.assertEqual(client.post("/llm/v1/chat/completions").json(), {"id": "chat-ok"})
        denied = client.post("/llm/v1/tts", json={"text": TEXT_CANARY})
        self.assertEqual(denied.status_code, 503)
        self.assertEqual(denied.json()["detail"], "TTS unavailable")
        self.assertNotIn(TEXT_CANARY, denied.text)


class SafeOpenerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("llm_tts_proxy_safe", PROXY_PATH)
        cls.proxy = importlib.util.module_from_spec(spec)
        sys.modules["llm_tts_proxy_safe"] = cls.proxy
        spec.loader.exec_module(cls.proxy)

    def test_opener_has_no_env_proxy_and_refuses_redirects(self):
        import os
        import urllib.request

        os.environ["https_proxy"] = "http://canary-proxy.invalid:9"
        try:
            opener = self.proxy.build_safe_opener()
        finally:
            del os.environ["https_proxy"]
        no_proxy = [h for h in opener.handlers if type(h).__name__ == "_NoEnvProxy"]
        self.assertEqual(len(no_proxy), 1)
        self.assertEqual(no_proxy[0].proxies, {})
        self.assertEqual(
            [h for h in opener.handlers if type(h) is urllib.request.ProxyHandler],
            [],
        )
        plain = [h for h in opener.handlers if type(h) is urllib.request.HTTPRedirectHandler]
        self.assertEqual(plain, [])
        refusers = [h for h in opener.handlers if type(h).__name__ == "_RefuseRedirect"]
        self.assertEqual(len(refusers), 1)
        with self.assertRaises(self.proxy.SpeechDenied) as caught:
            refusers[0].redirect_request(None, None, 302, "moved", {}, "https://evil.example/collect")
        self.assertEqual(caught.exception.status, 502)


if __name__ == "__main__":
    unittest.main()
