"""Mock tests for the ContextForge 1.1.0 TTS-only patch. No network."""

from __future__ import annotations

import importlib.util
import json
import sys
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
PROXY_PATH = ROOT / "mcpgateway" / "services" / "llm_tts_proxy.py"
ROUTER_PATH = ROOT / "mcpgateway" / "routers" / "llm_tts_router.py"
PATCH_PATH = ROOT / "contextforge-1.1.0-tts.patch"

spec = importlib.util.spec_from_file_location("llm_tts_proxy", PROXY_PATH)
proxy = importlib.util.module_from_spec(spec)
sys.modules["llm_tts_proxy"] = proxy
spec.loader.exec_module(proxy)


def _access(**overrides):
    base = dict(
        has_token=True,
        permissions=("llm.invoke",),
        socket_ip="192.168.20.109",
        x_forwarded_for=None,
        x_real_ip=None,
        ip_restrictions=("192.168.20.109/32",),
        override_keys=(),
    )
    base.update(overrides)
    return proxy.AccessRequest(**base)


def _provider():
    return {
        "id": proxy.FIXED_PROVIDER_ID,
        "enabled": True,
        "api_base": "https://api.x.ai/v1",
        "api_key": "test-provider-key",
    }


class _Response:
    def __init__(self, status=200, content_type="audio/mpeg", body=b"ID3fake"):
        self.status = status
        self.headers = {"Content-Type": content_type}
        self._body = body

    def read(self):
        return self._body


class TtsProxyTests(unittest.TestCase):
    def _speak(self, access=None, opener=None, logs=None, **kwargs):
        seen = {}

        def _opener(request, timeout=None):
            seen["url"] = request.full_url
            seen["method"] = request.method
            seen["headers"] = dict(request.header_items())
            seen["body"] = request.data
            seen["timeout"] = timeout
            if opener is not None:
                return opener(request, timeout)
            return _Response()

        sink = logs if logs is not None else []
        client = proxy.TtsProxy(log=sink.append)
        result = client.speak(
            access if access is not None else _access(),
            kwargs.pop("provider", _provider()),
            kwargs.pop("text", "Good evening."),
            kwargs.pop("language", "en"),
            kwargs.pop("voice_id", "leo"),
            opener=_opener,
            **kwargs,
        )
        return result, seen

    def test_missing_token_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(request)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(has_token=False),
                _provider(),
                "Good evening.",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 401)
        self.assertEqual(calls, [])

    def test_wrong_scope_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(permissions=("llm.read",)),
                _provider(),
                "Good evening.",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 403)
        self.assertEqual(calls, [])

    def test_empty_scope_denied_before_upstream(self):
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.authorize(_access(permissions=()))
        self.assertEqual(caught.exception.status, 403)

    def test_wrong_ip_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(socket_ip="203.0.113.5"),
                _provider(),
                "Good evening.",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 403)
        self.assertEqual(calls, [])

    def test_spoofed_forwarded_for_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(socket_ip="203.0.113.5", x_forwarded_for="192.168.20.109"),
                _provider(),
                "Good evening.",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 403)
        self.assertEqual(calls, [])

    def test_spoofed_real_ip_on_tablet_socket_denied(self):
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.authorize(_access(x_real_ip="10.1.1.1"))
        self.assertEqual(caught.exception.status, 403)

    def test_unpinned_token_denied(self):
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.authorize(_access(ip_restrictions=()))
        self.assertEqual(caught.exception.status, 403)

    def test_success_uses_stored_key_and_mpeg_type(self):
        logs = []
        audio = b"ID3not-a-real-frame"
        result, seen = self._speak(
            logs=logs,
            opener=lambda request, timeout=None: _Response(body=audio, content_type="audio/mpeg"),
        )
        self.assertEqual(result.content_type, "audio/mpeg")
        self.assertEqual(result.body, audio)
        self.assertEqual(seen["url"], "https://api.x.ai/v1/tts")
        self.assertEqual(seen["method"], "POST")
        self.assertEqual(seen["timeout"], 30.0)
        auth = dict(seen["headers"]).get("Authorization")
        self.assertEqual(auth, "Bearer test-provider-key")
        self.assertNotIn("tablet-bearer", auth)
        sent = json.loads(seen["body"].decode("utf-8"))
        self.assertEqual(sent["text"], "Good evening.")
        self.assertEqual(sent["language"], "en")
        self.assertEqual(sent["voice_id"], "leo")
        self.assertNotIn("with_timestamps", sent)
        joined = "\n".join(logs)
        self.assertNotIn("test-provider-key", joined)
        self.assertNotIn("Good evening.", joined)
        self.assertNotIn(audio.decode("utf-8"), joined)
        self.assertIn("bytes=%s" % len(audio), joined)

    def test_tablet_bearer_is_not_an_upstream_header(self):
        _, seen = self._speak()
        for _name, value in seen["headers"].items():
            self.assertNotIn("cf-jarvis-tablet-llm", value)

    def test_timestamps_rejected_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(),
                _provider(),
                "Good evening.",
                "en",
                "leo",
                with_timestamps=True,
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 400)
        self.assertEqual(calls, [])

    def test_text_limit_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(),
                _provider(),
                "x" * (proxy.MAX_TTS_CHARS + 1),
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 400)
        self.assertEqual(calls, [])

    def test_missing_language_before_upstream(self):
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.build_tts_payload("Hello", "", "leo")
        self.assertEqual(caught.exception.status, 400)

    def test_upstream_http_error_is_generic(self):
        def opener(request, timeout=None):
            raise HTTPError(request.full_url, 500, "nope", hdrs=None, fp=None)

        logs = []
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy(log=logs.append).speak(
                _access(),
                _provider(),
                "phrase-must-not-be-logged",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 502)
        self.assertNotIn("phrase-must-not-be-logged", caught.exception.detail)
        self.assertNotIn("test-provider-key", " ".join(logs))

    def test_non_mpeg_upstream_rejected(self):
        def opener(request, timeout=None):
            return _Response(content_type="application/json", body=b'{"audio":"secret"}')

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(_access(), _provider(), "Hello", "en", "leo", opener=opener)
        self.assertEqual(caught.exception.status, 502)

    def test_cancel_before_upstream(self):
        calls = []
        cancel = threading.Event()
        cancel.set()

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(),
                _provider(),
                "Hello",
                "en",
                "leo",
                opener=opener,
                cancel=cancel,
            )
        self.assertEqual(caught.exception.status, 499)
        self.assertEqual(calls, [])

    def test_concurrency_limit_before_second_upstream(self):
        started = threading.Event()
        release = threading.Event()
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            started.set()
            release.wait(2)
            return _Response()

        client = proxy.TtsProxy(max_in_flight=1)
        holder = []

        def _run():
            try:
                holder.append(client.speak(_access(), _provider(), "Hello", "en", "leo", opener=opener))
            except Exception as exc:  # pragma: no cover - holder reports it
                holder.append(exc)

        thread = threading.Thread(target=_run)
        thread.start()
        self.assertTrue(started.wait(2))
        with self.assertRaises(proxy.SpeechBusy):
            client.speak(_access(), _provider(), "Second", "en", "leo", opener=opener)
        release.set()
        thread.join(2)
        self.assertEqual(len(calls), 1)

    def test_wrong_provider_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        bad = _provider()
        bad["id"] = "someone-else"
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(_access(), bad, "Hello", "en", "leo", opener=opener)
        self.assertEqual(caught.exception.status, 503)
        self.assertEqual(calls, [])

    def test_foreign_api_base_before_upstream(self):
        bad = _provider()
        bad["api_base"] = "https://evil.example/v1"
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.resolve_provider(bad)
        self.assertEqual(caught.exception.status, 503)

    def test_v8_payload_shape(self):
        payload = proxy.build_tts_payload(
            "Good evening.",
            "en",
            "leo",
            1.08,
            {"codec": "mp3", "sample_rate": 24000, "bit_rate": 128000},
            None,
        )
        self.assertEqual(
            payload,
            {
                "text": "Good evening.",
                "language": "en",
                "voice_id": "leo",
                "speed": 1.08,
                "output_format": {"codec": "mp3", "sample_rate": 24000, "bit_rate": 128000},
            },
        )


class PatchRegressionTests(unittest.TestCase):
    def test_router_is_tts_only_and_uses_llm_invoke(self):
        text = ROUTER_PATH.read_text(encoding="utf-8")
        self.assertIn('@require_permission("llm.invoke", allow_admin_bypass=False)', text)
        self.assertIn("get_current_user_with_permissions", text)
        self.assertIn('FIXED_PROVIDER_ID', text)
        self.assertIn('@llm_tts_router.post("/tts"', text)
        self.assertNotIn('post("/stt"', text)
        self.assertNotIn('"/tts/voices"', text)
        self.assertIn("decode_auth", text)
        self.assertNotIn("cf-jarvis", text)

    def test_patch_keeps_chat_and_models_and_adds_only_tts(self):
        diff = PATCH_PATH.read_text(encoding="utf-8")
        self.assertNotIn("llm_proxy_router.py", diff)
        self.assertIn('post("/tts"', diff)
        self.assertIn("/tts/?$", diff)
        self.assertIn("Permissions.LLM_INVOKE", diff)
        self.assertIn("/chat/completions/?$", diff)
        self.assertIn("/models/?$", diff)
        self.assertIn("Permissions.LLM_READ", diff)
        self.assertNotIn("/stt/", diff)
        self.assertNotIn("tts/voices", diff)
        # The chat and models lines are context, not deletions.
        self.assertNotIn("-        (\"POST\", re.compile", diff)
        self.assertNotIn("-        (\"GET\", re.compile", diff)


if __name__ == "__main__":
    unittest.main()
