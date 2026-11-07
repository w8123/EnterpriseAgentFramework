"""Registry and authenticated project requests; no automatic replay of writes."""
from __future__ import annotations

import asyncio
import hashlib
import json
import re
import socket
import time
import uuid
from dataclasses import dataclass, field
from urllib.parse import quote, urlsplit
from typing import Any

import httpx

from .catalog import CapabilityCatalog
from .credentials import CredentialStore, Credentials
from .protocol import body_signature, registry_signature
from .nonce import CallbackNonceStore, InMemoryCallbackNonceStore


@dataclass
class ReachAIOptions:
    registry_url: str
    project_code: str
    base_url: str
    project_name: str = ""
    context_path: str = ""
    environment: str = "default"
    instance_id: str = ""
    enrollment_token: str = field(default="", repr=False)
    app_key: str = ""
    app_secret: str = field(default="", repr=False)
    heartbeat_seconds: int = 30
    allowed_origins: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if not re.fullmatch(r"[a-z0-9_]+(?:-[a-z0-9_]+)*", self.project_code):
            raise ValueError("project_code must already be lowercase and normalized")
        for value in (self.registry_url, self.base_url):
            parts = urlsplit(value)
            if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
                raise ValueError("registry_url and base_url must be absolute HTTP(S) URLs without credentials")
        if bool(self.app_key) != bool(self.app_secret): raise ValueError("both project credentials are required")
        if self.enrollment_token and (self.app_key or self.app_secret): raise ValueError("do not mix enrollment and project credentials")


class RegistryClient:
    def __init__(self, options: ReachAIOptions, store: CredentialStore | None, catalog: CapabilityCatalog,
                 http: httpx.AsyncClient | None = None, nonce_store: CallbackNonceStore | None = None):
        self.options, self.store, self.catalog = options, store, catalog
        self.http = http or httpx.AsyncClient(timeout=30.0, follow_redirects=False, trust_env=False)
        self._owns_http = http is None
        self.credentials = Credentials(options.app_key, options.app_secret) if options.app_key else None
        self.instance_id = options.instance_id.strip() or "python-" + uuid.uuid4().hex
        self._lock = asyncio.Lock()
        self.nonce_store = nonce_store or InMemoryCallbackNonceStore()

    async def close(self) -> None:
        if self._owns_http: await self.http.aclose()

    async def register(self) -> None:
        async with self._lock:
            if self.credentials is None and self.store is not None:
                self.credentials = await self.store.load()
            enrollment = self.credentials is None
            if enrollment:
                if not self.store or not self.options.enrollment_token:
                    raise RuntimeError("enrollment requires token and durable credential store")
                if await self.store.enrollment_uncertain():
                    raise RuntimeError("enrollment outcome uncertain; recover credentials before retrying")
                # Persist before sending: a lost response or crash must never replay a one-time token.
                await self.store.mark_enrollment_started()
            body = {"projectCode": self.options.project_code, "name": self.options.project_name or self.options.project_code,
                    "environment": self.options.environment, "visibility": "PRIVATE", "baseUrl": self.options.base_url,
                    "contextPath": self.options.context_path, "appKey": self.credentials.app_key if self.credentials else None}
            response = await self.post("/api/registry/projects/register", body, enrollment=enrollment)
            response = response.get("data", response)
            if not isinstance(response, dict) or response.get("projectCode") != self.options.project_code:
                raise RuntimeError("registration project mismatch or incomplete response")
            if enrollment:
                pending = Credentials(response.get("appKey", ""), response.get("appSecret", ""))
                assert self.store is not None
                await self.store.save(pending)
                self.credentials = pending
                await self.store.clear_enrollment_started()
                self.options.enrollment_token = ""

    async def heartbeat(self) -> dict[str, Any]:
        result = await self.post(self._project_path("instances/heartbeat"), {
            "instanceId": self.instance_id, "baseUrl": self.options.base_url, "host": socket.gethostname(),
            "sdkVersion": "1.0.0", "metadata": {"runtimePlacement": "CAPABILITY_HOST",
            "runtimeTypes": ["FASTAPI_CAPABILITY_HOST"], "supportsTools": True, "sdkLanguage": "python"}})
        payload = result.get("data", result)
        instance = payload.get("instance") if isinstance(payload, dict) else None
        if not isinstance(instance, dict) or instance.get("instanceId") != self.instance_id:
            raise RuntimeError("heartbeat response is incomplete")
        return result

    async def offline(self) -> dict[str, Any]:
        return await self.post(self._project_path("instances/offline"), {"instanceId": self.instance_id})

    async def sync(self, source: str = "SDK") -> dict[str, Any]:
        sync_id = str(uuid.uuid4())
        inventory = self.catalog.registrations()
        result = await self.post(self._project_path("capabilities/sync"), {"syncId": sync_id,
            "source": source, "apply": False, "capabilities": inventory})
        payload = result.get("data", result)
        # Existing Capability versions return the reused snapshot's syncId when
        # an identical inventory is reported under a new request id.  It is a
        # snapshot identity, not proof that the response echoes this request.
        returned_id = payload.get("syncId") if isinstance(payload, dict) else None
        received = payload.get("received") if isinstance(payload, dict) else None
        if (not isinstance(returned_id, str) or not returned_id.strip() or len(returned_id) > 64
                or payload.get("projectCode") != self.options.project_code
                or isinstance(received, bool) or not isinstance(received, int) or received != len(inventory)):
            raise RuntimeError("capability sync response is incomplete")
        return result

    def _project_path(self, suffix: str) -> str:
        return "/api/registry/projects/" + quote(self.options.project_code, safe="") + "/" + suffix

    async def post(self, path: str, body: Any, *, enrollment: bool = False,
                   body_signed: bool = False, project_only: bool = False) -> dict[str, Any]:
        data = json.dumps(body, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if enrollment:
            headers["X-ReachAI-Registry-Enrollment-Token"] = self.options.enrollment_token
        else:
            credentials = self.credentials
            if credentials is None: raise RuntimeError("project credentials unavailable")
            timestamp, nonce = str(int(time.time() * 1000)), str(uuid.uuid4())
            headers.update({"X-ReachAI-App-Key": credentials.app_key, "X-ReachAI-Timestamp": timestamp,
                            "X-ReachAI-Nonce": nonce, "X-ReachAI-Signature": registry_signature(
                                self.options.project_code, timestamp, nonce, credentials.app_secret)})
            if body_signed:
                signed = body_signature("POST", path, self.options.project_code, credentials.app_key,
                                        timestamp, nonce, data, credentials.app_secret)
                headers["X-ReachAI-Body-SHA256"] = hashlib.sha256(data).hexdigest()
                headers["X-ReachAI-Signature" if project_only else "X-ReachAI-Body-Signature"] = signed
        async with self.http.stream("POST", self.options.registry_url.rstrip("/") + path,
                                    headers=headers, content=data, follow_redirects=False) as response:
            response.raise_for_status()
            chunks, size = [], 0
            async for chunk in response.aiter_bytes():
                size += len(chunk)
                if size > 8 * 1024 * 1024: raise ValueError("ReachAI response too large")
                chunks.append(chunk)
            value = json.loads(b"".join(chunks) or b"{}")
            if not isinstance(value, dict): raise ValueError("invalid ReachAI response")
            if "code" in value and str(value["code"]) != "200": raise RuntimeError("ReachAI application request failed")
            return value

    async def verify_callback(self, key: str, timestamp: str, nonce: str, signature: str) -> bool:
        import hmac
        try:
            cred = self.credentials
            now = int(time.time() * 1000); sent = int(timestamp)
            if cred is None or key != cred.app_key or not nonce or len(nonce) > 256 or abs(now - sent) > 300000: return False
            expected = registry_signature(self.options.project_code, timestamp, nonce, cred.app_secret)
            if not hmac.compare_digest(expected, signature.lower()): return False
            return await self.nonce_store.claim(self.options.project_code, nonce, sent + 300000)
        except (ValueError, TypeError): return False
