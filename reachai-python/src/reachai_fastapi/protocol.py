"""Language-neutral ReachAI wire signatures and invocation-token verification."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import time
from typing import Any


def _field(value: str) -> str:
    value = value.strip()
    if not value or "\r" in value or "\n" in value:
        raise ValueError("invalid signature field")
    return value


def _mac(secret: str, message: str) -> str:
    return hmac.new(_field(secret).encode(), message.encode(), hashlib.sha256).hexdigest()


def registry_signature(project_code: str, timestamp: str, nonce: str, secret: str) -> str:
    return _mac(secret, "\n".join(map(_field, (project_code, timestamp, nonce))))


def body_signature(method: str, path: str, project_code: str, app_key: str,
                   timestamp: str, nonce: str, body: bytes, secret: str) -> str:
    path = _field(path)
    if not path.startswith("/") or any(x in path for x in ("?", "#", "..")):
        raise ValueError("invalid signed path")
    fields = ("REACHAI_PROJECT_REQUEST_V1", _field(method).upper(), path,
              _field(project_code), _field(app_key), _field(timestamp), _field(nonce),
              hashlib.sha256(body).hexdigest())
    return _mac(secret, "\n".join(fields))


def verify_invocation(token: str, app_key: str, secret: str, project_code: str,
                      capability_name: str, now: int | None = None) -> dict[str, Any]:
    try:
        if not isinstance(token, str) or len(token) > 16384:
            raise ValueError()
        head, payload, signature = token.split(".")
        def decode(value: str) -> bytes:
            return base64.b64decode(value + "=" * (-len(value) % 4), altchars=b"-_", validate=True)
        expected = hmac.new(_field(secret).encode(), (head + "." + payload).encode(), hashlib.sha256).digest()
        if not hmac.compare_digest(expected, decode(signature)):
            raise ValueError()
        header, claims = json.loads(decode(head)), json.loads(decode(payload))
        if not isinstance(header, dict) or header.get("alg") != "HS256" or not isinstance(claims, dict):
            raise ValueError()
        current = int(time.time()) if now is None else now
        expiry = claims.get("exp")
        if isinstance(expiry, bool) or not isinstance(expiry, int) or expiry <= current:
            raise ValueError()
        for field in ("iat", "nbf"):
            value = claims.get(field)
            if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value > current):
                raise ValueError()
        if any(claims.get(k) != v for k, v in (("projectCode", project_code), ("appKey", app_key), ("capabilityName", capability_name))):
            raise ValueError()
        return claims
    except (ValueError, TypeError, KeyError, UnicodeError, json.JSONDecodeError) as exc:
        raise PermissionError("invalid invocation token") from exc
