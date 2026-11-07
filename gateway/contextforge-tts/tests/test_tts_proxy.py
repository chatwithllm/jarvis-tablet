"""Mock tests for the ContextForge 1.1.0 TTS-only patch. No network."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import sys
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError

ROOT = Path(__file__).resolve().parents[1]
PROXY_PATH = ROOT / "mcpgateway" / "services" / "llm_tts_proxy.py"
ROUTER_PATH = ROOT / "mcpgateway" / "routers" / "llm_tts_router.py"
PATCH_PATH = ROOT / "contextforge-1.1.0-tts.patch"
ARTIFACT_PATH = ROOT / "ARTIFACT.txt"

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
        "provider_type": "openai_compatible",
        "api_base": "https://api.x.ai/v1",
        "api_key": "test-provider-key",
    }


_LOG_LINE = re.compile(r"^tts upstream HTTP \d+ bytes=\d+$")


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

    def test_production_logger_emits_nothing(self):
        import logging

        class Grab(logging.Handler):
            def __init__(self):
                super().__init__()
                self.messages = []

            def emit(self, record):
                self.messages.append(record.getMessage())

        handler = Grab()
        root = logging.getLogger()
        previous = root.level
        root.addHandler(handler)
        root.setLevel(logging.DEBUG)
        audio = b"AUDIOCANARYBYTES"
        try:
            client = proxy.TtsProxy()
            self.assertIs(client._log, proxy._noop_log)
            provider = _provider()
            provider["api_key"] = "KEYCANARY"
            client.speak(
                _access(),
                provider,
                "TEXTCANARY",
                "en",
                "leo",
                opener=lambda request, timeout=None: _Response(body=audio),
            )
        finally:
            root.removeHandler(handler)
            root.setLevel(previous)
        blob = "\n".join(handler.messages)
        self.assertNotIn("TEXTCANARY", blob)
        self.assertNotIn("KEYCANARY", blob)
        self.assertNotIn("AUDIOCANARYBYTES", blob)
        self.assertNotIn("tts upstream", blob)

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
        self.assertGreater(seen["timeout"], 29)
        self.assertLessEqual(seen["timeout"], 30)
        auth = dict(seen["headers"]).get("Authorization")
        self.assertEqual(auth, "Bearer test-provider-key")
        self.assertNotIn("tablet-bearer", auth)
        sent = json.loads(seen["body"].decode("utf-8"))
        self.assertEqual(sent["text"], "Good evening.")
        self.assertEqual(sent["language"], "en")
        self.assertEqual(sent["voice_id"], "leo")
        self.assertNotIn("with_timestamps", sent)
        # This list is the injected test sink, not the production logger.
        self.assertEqual(logs, ["tts upstream HTTP 200 bytes=%s" % len(audio)])
        for line in logs:
            self.assertIsNotNone(_LOG_LINE.match(line))
            self.assertNotIn("test-provider-key", line)
            self.assertNotIn("Good evening.", line)
            self.assertNotIn(audio.decode("utf-8"), line)
        names = [name.lower() for name in seen["headers"]]
        self.assertEqual(sorted(names), ["accept", "authorization", "content-type"])

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
        self.assertEqual(logs, ["tts upstream HTTP 500 bytes=0"])
        for line in logs:
            self.assertIsNotNone(_LOG_LINE.match(line))
            self.assertNotIn("test-provider-key", line)
            self.assertNotIn("phrase-must-not-be-logged", line)

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

    def test_reported_token_scopes_pass_scope_check_only(self):
        # Key Keeper metadata: the tablet token lists llm.read and llm.invoke.
        # Passing this check is not evidence the provider can synthesize speech.
        proxy.authorize(_access(permissions=("llm.read", "llm.invoke")))

    def test_model_override_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(override_keys=("model",)),
                _provider(),
                "Hello",
                "en",
                "leo",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 400)
        self.assertEqual(calls, [])

    def test_api_base_and_key_overrides_denied_before_upstream(self):
        for key in ("api_base", "api_key", "authorization", "x-api-key"):
            with self.assertRaises(proxy.SpeechDenied) as caught:
                proxy.authorize(_access(override_keys=(key,)))
            self.assertEqual(caught.exception.status, 400, key)

    def test_provider_switching_voice_denied_before_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(
                _access(),
                _provider(),
                "Hello",
                "en",
                "openai/gpt-4o",
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 400)
        self.assertEqual(calls, [])

    def test_wrong_provider_type_before_upstream(self):
        bad = _provider()
        bad["provider_type"] = "openai"
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.resolve_provider(bad)
        self.assertEqual(caught.exception.status, 503)

    def test_missing_api_base_before_upstream(self):
        bad = _provider()
        bad["api_base"] = ""
        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.resolve_provider(bad)
        self.assertEqual(caught.exception.status, 503)

    def test_speed_rate_out_of_range_before_upstream(self):
        calls = []

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
                speed=4.0,
                opener=opener,
            )
        self.assertEqual(caught.exception.status, 400)
        self.assertEqual(calls, [])

    def test_request_rate_before_second_upstream(self):
        calls = []

        def opener(request, timeout=None):
            calls.append(1)
            return _Response()

        client = proxy.TtsProxy(max_per_minute=1, clock=lambda: 1000.0)
        client.speak(_access(), _provider(), "Hello", "en", "leo", opener=opener)
        with self.assertRaises(proxy.SpeechDenied) as caught:
            client.speak(_access(), _provider(), "Again", "en", "leo", opener=opener)
        self.assertEqual(caught.exception.status, 429)
        self.assertEqual(len(calls), 1)

    def test_only_post_llm_v1_tts_pattern_is_added(self):
        added = re.compile(r"^/llm/v1/tts/?$")
        self.assertIsNotNone(added.match("/llm/v1/tts"))
        self.assertIsNotNone(added.match("/llm/v1/tts/"))
        for path in (
            "/llm/v1/stt",
            "/llm/v1/tts/voices",
            "/llm/v1/chat/completions",
            "/llm/v1/models",
            "/v1/tts",
        ):
            self.assertIsNone(added.match(path), path)
        diff = PATCH_PATH.read_text(encoding="utf-8")
        added_lines = [line[1:] for line in diff.splitlines() if line.startswith("+") and "re.compile" in line]
        self.assertEqual(added_lines, ['        ("POST", re.compile(rf"^{escaped_prefix}/tts/?$"), Permissions.LLM_INVOKE),'])

    def test_token_pin_is_not_a_second_hardcoded_address(self):
        proxy.authorize(_access(socket_ip="10.9.9.9", ip_restrictions=("10.9.9.9/32",)))

    def test_cancelled_awaiters_do_not_free_live_upstream_slots(self):
        import asyncio

        entered = []
        gates = []
        guard = threading.Lock()

        def opener(request, timeout=None):
            with guard:
                gate = threading.Event()
                gates.append(gate)
                entered.append(len(entered))
            gate.wait(3)
            response = _Response()
            response.close = lambda: None
            return response

        client = proxy.TtsProxy(max_in_flight=2, max_per_minute=100, timeout=5)

        async def one():
            return await asyncio.to_thread(
                client.speak,
                _access(),
                _provider(),
                "Hi",
                "en",
                "leo",
                opener=opener,
            )

        async def scenario():
            workers = [asyncio.create_task(one()) for _ in range(2)]
            for _ in range(100):
                if len(entered) >= 2:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(len(entered), 2)
            for task in workers:
                task.cancel()
            await asyncio.sleep(0.05)
            with self.assertRaises(proxy.SpeechBusy):
                client.speak(_access(), _provider(), "Blocked", "en", "leo", opener=opener)
            self.assertEqual(len(entered), 2)
            gates[0].set()
            for _ in range(100):
                if client._slots >= 1:
                    break
                await asyncio.sleep(0.01)
            self.assertGreaterEqual(client._slots, 1)
            third = asyncio.create_task(one())
            for _ in range(100):
                if len(entered) >= 3:
                    break
                await asyncio.sleep(0.01)
            self.assertEqual(len(entered), 3)
            for gate in gates:
                gate.set()
            await asyncio.wait(workers + [third], timeout=2)

        asyncio.run(scenario())

    def test_deadline_discards_late_body_and_closes(self):
        class Late:
            def __init__(self):
                self.closed = False

            status = 200
            headers = {"Content-Type": "audio/mpeg"}

            def read(self, n=65536):
                return b"LATECANARYAUDIO"

            def close(self):
                self.closed = True

        late = Late()
        gate = threading.Event()

        def opener(request, timeout=None):
            self.assertLessEqual(timeout, 0.05)
            gate.wait(2)
            return late

        client = proxy.TtsProxy(timeout=0.05, max_per_minute=100)

        timer = threading.Timer(0.08, gate.set)
        timer.start()
        self.addCleanup(timer.join)
        with self.assertRaises(proxy.SpeechDenied) as caught:
            client.speak(_access(), _provider(), "LATECANARYTEXT", "en", "leo", opener=opener)
        self.assertEqual(caught.exception.status, 504)
        self.assertTrue(late.closed)
        self.assertNotIn("LATECANARY", caught.exception.detail)

    def _socket_response(self, client_sock, server_sock):
        fp = client_sock.makefile("rb")
        closed = {"value": False}

        class Response:
            status = 200
            headers = {"Content-Type": "audio/mpeg"}

            def read(self, n=1):
                return fp.read(n)

            def close(self):
                closed["value"] = True
                try:
                    fp.close()
                except Exception:
                    pass
                for sock in (client_sock, server_sock):
                    try:
                        sock.close()
                    except Exception:
                        pass

        response = Response()
        response.fp = fp
        return response, closed

    def test_slow_trickle_stays_inside_the_deadline(self):
        import socket
        import time

        client_sock, server_sock = socket.socketpair()
        client_sock.settimeout(30)
        response, closed = self._socket_response(client_sock, server_sock)

        def trickle():
            try:
                for _ in range(40):
                    server_sock.send(b"\xff")
                    time.sleep(0.05)
                server_sock.shutdown(socket.SHUT_WR)
            except Exception:
                return

        threading.Thread(target=trickle, daemon=True).start()
        client = proxy.TtsProxy(timeout=1.0, max_per_minute=100)
        started = time.monotonic()
        with self.assertRaises(proxy.SpeechDenied) as caught:
            client.speak(_access(), _provider(), "TRICKLE_CANARY", "en", "leo", opener=lambda request, timeout=None: response)
        elapsed = time.monotonic() - started
        self.assertEqual(caught.exception.status, 504)
        self.assertLess(elapsed, 1.5)
        self.assertTrue(closed["value"])
        self.assertEqual(client._slots, 2)
        self.assertNotIn("TRICKLE_CANARY", caught.exception.detail)
        self.assertNotIn("xff", caught.exception.detail.lower())

    def test_blocked_read_times_out_with_the_deadline_and_releases_the_slot(self):
        import socket
        import time

        client_sock, server_sock = socket.socketpair()
        client_sock.settimeout(5)
        response, closed = self._socket_response(client_sock, server_sock)
        started_read = threading.Event()
        original = response.read

        def read(n=1):
            started_read.set()
            return original(n)

        response.read = read
        client = proxy.TtsProxy(timeout=0.4, max_in_flight=1, max_per_minute=100)
        holder = {}

        def run():
            try:
                client.speak(
                    _access(),
                    _provider(),
                    "BLOCK_CANARY",
                    "en",
                    "leo",
                    opener=lambda request, timeout=None: response,
                )
            except proxy.SpeechDenied as exc:
                holder["exc"] = exc

        started = time.monotonic()
        worker = threading.Thread(target=run)
        worker.start()
        self.assertTrue(started_read.wait(1))
        calls = []
        with self.assertRaises(proxy.SpeechBusy):
            client.speak(
                _access(),
                _provider(),
                "Other",
                "en",
                "leo",
                opener=lambda request, timeout=None: calls.append(1),
            )
        self.assertEqual(calls, [])
        worker.join(2)
        self.assertFalse(worker.is_alive())
        elapsed = time.monotonic() - started
        self.assertLess(elapsed, 1.5)
        self.assertEqual(holder["exc"].status, 504)
        self.assertTrue(closed["value"])
        self.assertEqual(client._slots, 1)
        self.assertNotIn("BLOCK_CANARY", holder["exc"].detail)
        follow = client.speak(
            _access(),
            _provider(),
            "Hi",
            "en",
            "leo",
            opener=lambda request, timeout=None: _Response(),
        )
        self.assertEqual(follow.status, 200)

    def test_oversized_upstream_body_is_closed_and_not_returned(self):
        class Big:
            def __init__(self):
                self.closed = False
                self.sent = 0

            status = 200
            headers = {"Content-Type": "audio/mpeg"}

            def read(self, n=1):
                if self.sent > proxy.MAX_RESPONSE_BYTES:
                    return b""
                chunk = 65536
                self.sent += chunk
                return b"z" * chunk

            def close(self):
                self.closed = True

        big = Big()

        def opener(request, timeout=None):
            return big

        with self.assertRaises(proxy.SpeechDenied) as caught:
            proxy.TtsProxy().speak(_access(), _provider(), "Hello", "en", "leo", opener=opener)
        self.assertEqual(caught.exception.status, 502)
        self.assertTrue(big.closed)
        self.assertNotIn("zzz", caught.exception.detail)

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

    def test_artifact_hash_matches_patch_bytes(self):
        raw = PATCH_PATH.read_bytes()
        recorded = dict(
            line.split("=", 1)
            for line in ARTIFACT_PATH.read_text(encoding="utf-8").splitlines()
            if "=" in line
        )
        self.assertEqual(recorded["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(int(recorded["bytes"]), len(raw))
        self.assertEqual(int(recorded["lines"]), raw.count(b"\n"))
        self.assertEqual(recorded["version"], "1.1.0+tablet-tts.5")


if __name__ == "__main__":
    unittest.main()
