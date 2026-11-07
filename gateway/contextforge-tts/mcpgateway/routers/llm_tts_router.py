"""POST /tts mounted beside the ContextForge 1.1.0 LLM proxy.

Review artifact for tag v1.1.0 (aee91ff6db63b6aafa77522684a9483c418bb2a3).
The household prefix is /llm/v1, so the public path is POST /llm/v1/tts.

Chat completions and GET /models are not defined here. This module does
not read Caddy config and does not grant permissions.
"""

from __future__ import annotations

# Third-Party
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

# First-Party
from mcpgateway.db import LLMProvider, get_db
from mcpgateway.middleware.rbac import get_current_user_with_permissions, require_permission
from mcpgateway.services.llm_tts_proxy import (
    FIXED_PROVIDER_ID,
    FORBIDDEN_OVERRIDE_KEYS,
    AccessRequest,
    SpeechDenied,
    TtsProxy,
)
from mcpgateway.utils.services_auth import decode_auth
from mcpgateway.utils.verify_credentials import (
    get_auth_bearer_token_from_request,
    verify_jwt_token_cached,
)

llm_tts_router = APIRouter()
_proxy = TtsProxy()


class TtsBody(BaseModel):
    """Fields the tablet sends. Extra keys are rejected.

    with_timestamps is accepted only so a true value can be refused.
    xAI would otherwise switch the success body from raw MP3 to JSON.
    """

    model_config = ConfigDict(extra="forbid")

    text: str = Field(min_length=1, max_length=4000)
    language: str
    voice_id: str
    speed: float = 1.08
    output_format: dict | None = None
    with_timestamps: bool | None = None


def _denied(exc: SpeechDenied) -> HTTPException:
    return HTTPException(status_code=exc.status, detail=exc.detail)


async def _ip_restrictions(request: Request) -> tuple:
    """Read the pin from the JWT scopes claim. Do not log the token."""
    token = get_auth_bearer_token_from_request(request)
    if not token:
        return tuple()
    try:
        payload = await verify_jwt_token_cached(token, request)
    except Exception:
        return tuple()
    scopes = payload.get("scopes") if isinstance(payload, dict) else None
    if not isinstance(scopes, dict):
        return tuple()
    raw = scopes.get("ip_restrictions") or []
    if not isinstance(raw, list):
        return tuple()
    return tuple(str(item) for item in raw)


def _provider_view(db: Session) -> dict | None:
    """Load the pinned row and decode its stored key. The key is not logged."""
    row = db.execute(select(LLMProvider).where(LLMProvider.id == FIXED_PROVIDER_ID)).scalar_one_or_none()
    if row is None:
        return None
    plaintext = ""
    if row.api_key:
        try:
            decoded = decode_auth(row.api_key)
        except Exception:
            decoded = {}
        if isinstance(decoded, dict):
            value = decoded.get("api_key")
            if isinstance(value, str):
                plaintext = value
    return {
        "id": row.id,
        "enabled": bool(row.enabled),
        "provider_type": row.provider_type,
        "api_base": row.api_base,
        "api_key": plaintext,
    }


# Public route: POST /tts  (full path is {LLM_API_PREFIX}/tts, household /llm/v1/tts).
@llm_tts_router.post("/tts", summary="Tablet text to speech")
@require_permission("llm.invoke", allow_admin_bypass=False)
async def text_to_speech(
    request: Request,
    body: TtsBody,
    db: Session = Depends(get_db),
    current_user: dict = Depends(get_current_user_with_permissions),
):
    """Speak one reply. Auth and the IP pin run before the upstream call.

    Credentials: llm_providers.id 78770f3fb044491fa06bfcc0d4b19825, then
    decode_auth(row.api_key)["api_key"]. The inbound Authorization header
    is the tablet token and is not copied upstream.
    """
    if current_user is None or "email" not in current_user:
        raise HTTPException(status_code=401, detail="Authorization token required")
    override = tuple(
        key
        for key in FORBIDDEN_OVERRIDE_KEYS
        if key in request.query_params or key.lower() in {name.lower() for name in request.headers.keys()}
    )
    try:
        restrictions = await _ip_restrictions(request)
        access = AccessRequest(
            has_token=True,
            permissions=tuple(current_user.get("token_scopes") or ()),
            socket_ip=request.client.host if request.client else None,
            x_forwarded_for=request.headers.get("x-forwarded-for"),
            x_real_ip=request.headers.get("x-real-ip"),
            ip_restrictions=restrictions,
            override_keys=override,
        )
        if await request.is_disconnected():
            raise SpeechDenied(499, "Client cancelled")
        result = _proxy.speak(
            access,
            _provider_view(db),
            body.text,
            body.language,
            body.voice_id,
            body.speed,
            body.output_format,
            body.with_timestamps,
        )
    except SpeechDenied as exc:
        raise _denied(exc) from None
    return Response(content=result.body, media_type=result.content_type, status_code=result.status)
