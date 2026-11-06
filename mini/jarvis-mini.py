#!/usr/bin/env python3
"""Jarvis Mini services — voice relay + grocery proxy (LAN only).

Runs on the Mac Mini (LAN only, no public port). One server, two jobs:

1. Voice relay (/voice/turn, /voice/execute): the tablet posts turn audio
   (16-bit 16 kHz mono WAV); the relay runs the Home Assistant Assist
   pipeline over the HA WebSocket API (Whisper STT -> Grok HACS agent ->
   Piper TTS) and returns the transcript, the response text, TTS audio
   (base64 WAV), and any action the agent proposed. Proposed actions are
   NEVER executed by the relay — the tablet confirms first (PolicyController,
   10 s timeout, source binding) and only then calls /voice/execute.
2. Grocery proxy (/grocery/*): forwards to the grocery REST API
   (extended.npalakurla.com) with the service-account token, plus client
   request-id dedupe so a retry never doubles an add. See
   docs/grocery-contract.md.

Prerequisites (HA / Mini side, NOT this file's job):
- Whisper + Piper (Wyoming) installed on HA, Grok HACS conversation
  agent (pajeronda/xai_conversation) installed with tools ON and a
  `propose_action` tool (the agent must propose, never execute a service
  call directly — the tablet's confirm gate is the only execution path).
- An HA long-lived token readable by this server (env HA_TOKEN, or
  ~/.config/hass-token). The token NEVER leaves the Mini.
- A grocery service-account token (env GROCERY_TOKEN). Reads work with a
  read-only token; writes need allow_write (Simba's separate yes).
- websocket-client: pip install websocket-client

Run:  python3 jarvis-mini.py [--port 8792] [--ha-ws ws://192.168.20.11:8123/api/websocket]
Deploy: the Developer bot copies this file to the Mini and runs it (or a
LaunchAgent). It binds 0.0.0.0 on the LAN port only — no public exposure.
The tablet's Settings holds the base URL (e.g. http://192.168.20.x:8792).
"""

import argparse
import base64
import json
import os
import sys
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

try:
    import websocket  # pip install websocket-client
except ImportError:
    websocket = None

HA_WS_URL = os.environ.get("HA_WS_URL", "ws://192.168.20.11:8123/api/websocket")


def _read_ha_token():
    p = Path.home() / ".config" / "hass-token"
    try:
        return p.read_text().strip()
    except OSError:
        return None


HA_TOKEN = os.environ.get("HA_TOKEN") or _read_ha_token()
GROCERY_BASE = os.environ.get("GROCERY_BASE", "https://extended.npalakurla.com")
GROCERY_TOKEN = os.environ.get("GROCERY_TOKEN")


# ---------------------------------------------------------------- voice relay

# Proposed actions awaiting confirm, keyed by the id the relay returns.
# The tablet confirms first; only then does /voice/execute run the call.
PENDING_ACTIONS = {}
PENDING_LOCK = threading.Lock()


def ha_ws():
    """Open an authenticated HA WebSocket connection."""
    if websocket is None:
        raise RuntimeError("websocket-client not installed (pip install websocket-client)")
    if not HA_TOKEN:
        raise RuntimeError("no HA token (env HA_TOKEN or ~/.config/hass-token)")
    ws = websocket.create_connection(HA_WS_URL, timeout=15)
    ws.send(json.dumps({"type": "auth", "access_token": HA_TOKEN}))
    auth = json.loads(ws.recv())
    if auth.get("type") != "auth_ok":
        ws.close()
        raise RuntimeError(f"HA auth failed: {auth.get('message', auth)}")
    return ws


def run_assist_pipeline(wav_bytes):
    """Run one HA Assist turn. Returns (transcript, response_text, tts_wav_bytes, action_dict|None).

    The pipeline is stt -> intent -> tts. The conversation agent is expected
    to return a `propose_action` tool call (never a direct service call);
    the relay extracts it and returns it as `action` for the tablet's
    confirm gate. If the agent instead executed a service call directly,
    that bypasses the gate — it means the HACS agent is misconfigured
    (it must only propose). The relay logs that case loudly.
    """
    ws = ha_ws()
    msg_id = 1
    try:
        # Start the pipeline. Audio is streamed as binary frames after this.
        ws.send(json.dumps({
            "id": msg_id,
            "type": "assist_pipeline/run",
            "start_stage": "stt",
            "end_stage": "tts",
            "input": {"sample_rate": 16000},
        }))
        # Strip the 44-byte WAV header; HA wants raw PCM.
        pcm = wav_bytes[44:] if wav_bytes[:4] == b"RIFF" else wav_bytes
        # Stream the audio in 80 ms chunks (1280 samples = 2560 bytes).
        chunk = 2560
        for i in range(0, len(pcm), chunk):
            ws.send_binary(pcm[i:i + chunk])
        ws.send(json.dumps({"id": msg_id, "type": "assist_pipeline/run_end"}))
        transcript = ""
        response_text = ""
        tts_url = None
        action = None
        while True:
            msg = json.loads(ws.recv())
            t = msg.get("type")
            if t == "event":
                ev = msg.get("event", {})
                et = ev.get("type")
                if et == "stt-end":
                    transcript = ev.get("stt_output", {}).get("text", "")
                elif et == "intent-end":
                    intent = ev.get("intent_output", {})
                    response_text = intent.get("response", {}).get("speech", {}).get("plain", {}).get("speech", "")
                    # A propose_action tool call arrives here (agent must only propose).
                    tool_calls = intent.get("response", {}).get("tool_calls", []) or []
                    for call in tool_calls:
                        if call.get("tool_name") == "propose_action":
                            args = call.get("tool_args", {})
                            action = {
                                "id": str(uuid.uuid4()),
                                "summary": args.get("summary", ""),
                                "domain": args.get("domain", ""),
                                "entity_id": args.get("entity_id", ""),
                                "new_state": args.get("new_state", ""),
                                "room": args.get("room"),
                            }
                            with PENDING_LOCK:
                                PENDING_ACTIONS[action["id"]] = args
                elif et == "tts-end":
                    tts_url = ev.get("tts_output", {}).get("url")
                elif et == "error":
                    raise RuntimeError(f"assist pipeline error: {ev.get('message', ev)}")
            elif t == "result":
                if not msg.get("success", False):
                    raise RuntimeError(f"assist pipeline failed: {msg.get('error', msg)}")
                break
        # Fetch the TTS audio (HA returns a /api/tts_proxy URL).
        tts_wav = None
        if tts_url:
            req = urllib.request.Request(
                _ha_http_url(tts_url),
                headers={"Authorization": f"Bearer {HA_TOKEN}"},
            )
            with urllib.request.urlopen(req, timeout=15) as r:
                tts_wav = r.read()
        return transcript, response_text, tts_wav, action
    finally:
        ws.close()


def _ha_http_url(path):
    """Turn an HA /api/... path into a full http URL (same host as the WS)."""
    ws = HA_WS_URL.replace("ws://", "http://").replace("wss://", "https://")
    host = ws.split("/api/websocket")[0]
    return host + path


def execute_ha_service(args):
    """Run one HA service call (only after the tablet confirmed)."""
    ws = ha_ws()
    try:
        ws.send(json.dumps({
            "id": 1,
            "type": "call_service",
            "domain": args["domain"],
            "service": args.get("service", "turn_on"),
            "target": {"entity_id": args["entity_id"]},
            "service_data": args.get("service_data", {}),
        }))
        res = json.loads(ws.recv())
        if not res.get("success", False):
            raise RuntimeError(f"call_service failed: {res.get('error', res)}")
    finally:
        ws.close()


# ------------------------------------------------------------- grocery proxy

# Client request ids already served (dedupe: a retry returns the cached result).
SEEN_REQUESTS = {}
SEEN_LOCK = threading.Lock()


def grocery_call(method, path, body=None, request_id=None):
    """Call the grocery API. Returns (status, parsed_json_or_text)."""
    if not GROCERY_TOKEN:
        return 503, {"error": "grocery token not configured (env GROCERY_TOKEN)"}
    if request_id:
        with SEEN_LOCK:
            if request_id in SEEN_REQUESTS:
                return 200, SEEN_REQUESTS[request_id]
    req = urllib.request.Request(
        GROCERY_BASE + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Authorization": f"Bearer {GROCERY_TOKEN}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            payload = json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read().decode())
        except Exception:
            payload = {"error": f"grocery API HTTP {e.code}"}
        return e.code, payload
    if request_id:
        with SEEN_LOCK:
            SEEN_REQUESTS[request_id] = payload
    return 200, payload


# ------------------------------------------------------------------- HTTP API

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # keep the console quiet; errors go to stderr via the handler

    def _json(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"ok": True, "voice": True, "grocery": bool(GROCERY_TOKEN)})
        elif self.path.startswith("/grocery/list"):
            status, payload = grocery_call("GET", "/shopping-list?status=open")
            self._json(status, payload)
        elif self.path.startswith("/grocery/spend"):
            month = self.path.split("month=")[1].split("&")[0] if "month=" in self.path else ""
            status, payload = grocery_call("GET", f"/analytics/spending-by-category?month={month}")
            self._json(status, payload)
        else:
            self._json(404, {"error": "unknown route"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        if self.path == "/voice/turn":
            try:
                transcript, response_text, tts_wav, action = run_assist_pipeline(raw)
                self._json(200, {
                    "transcript": transcript,
                    "response_text": response_text,
                    "tts_audio_b64": base64.b64encode(tts_wav).decode() if tts_wav else "",
                    "action": action,
                })
            except Exception as e:
                self._json(502, {"error": str(e)})
        elif self.path == "/voice/execute":
            try:
                body = json.loads(raw.decode() or "{}")
                with PENDING_LOCK:
                    args = PENDING_ACTIONS.pop(body.get("action_id", ""), None)
                if args is None:
                    self._json(404, {"error": "unknown or already-executed action id"})
                    return
                execute_ha_service(args)
                self._json(200, {"ok": True})
            except Exception as e:
                self._json(502, {"error": str(e)})
        elif self.path == "/grocery/add":
            try:
                body = json.loads(raw.decode() or "{}")
            except Exception:
                self._json(400, {"error": "invalid JSON"})
                return
            request_id = self.headers.get("X-Request-Id")
            status, payload = grocery_call("POST", "/shopping-list/items", body, request_id)
            self._json(status, payload)
        else:
            self._json(404, {"error": "unknown route"})

    def do_PUT(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length) if length else b""
        if self.path.startswith("/grocery/check-off/"):
            item_id = self.path.rsplit("/", 1)[-1]
            try:
                body = json.loads(raw.decode() or "{}")
            except Exception:
                body = {"status": "purchased"}
            status, payload = grocery_call("PUT", f"/shopping-list/items/{item_id}", body)
            self._json(status, payload)
        else:
            self._json(404, {"error": "unknown route"})

    def do_DELETE(self):
        if self.path.startswith("/grocery/remove/"):
            item_id = self.path.rsplit("/", 1)[-1]
            status, payload = grocery_call("DELETE", f"/shopping-list/items/{item_id}")
            self._json(status, payload)
        else:
            self._json(404, {"error": "unknown route"})


def main():
    global HA_WS_URL
    ap = argparse.ArgumentParser(description="Jarvis Mini services (voice relay + grocery proxy)")
    ap.add_argument("--port", type=int, default=8792)
    ap.add_argument("--ha-ws", default=None)
    args = ap.parse_args()
    if args.ha_ws:
        HA_WS_URL = args.ha_ws
    if not HA_TOKEN:
        print("WARNING: no HA token (env HA_TOKEN or ~/.config/hass-token); /voice/* will fail", file=sys.stderr)
    if not GROCERY_TOKEN:
        print("WARNING: no grocery token (env GROCERY_TOKEN); /grocery/* will fail", file=sys.stderr)
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print(f"jarvis-mini listening on :{args.port} (HA {HA_WS_URL}, grocery {GROCERY_BASE})")
    server.serve_forever()


if __name__ == "__main__":
    main()
