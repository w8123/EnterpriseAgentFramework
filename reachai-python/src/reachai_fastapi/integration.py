"""FastAPI integration for verified business invocations and Embed broker."""
from __future__ import annotations

import asyncio
import contextvars
import inspect
import json
import logging
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Any, Awaitable, Callable, Mapping
from urllib.parse import urlsplit

from fastapi import APIRouter, FastAPI, Request
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool
import httpx

from .catalog import CapabilityCatalog
from .client import ReachAIOptions, RegistryClient
from .credentials import CredentialStore
from .protocol import verify_invocation
from .business_index import BusinessIndexRequest, validate_resolution
from .nonce import CallbackNonceStore

logger = logging.getLogger("reachai_fastapi")
_MAX_INVOCATION_BYTES = 1024 * 1024


@dataclass(frozen=True)
class InvocationContext:
    project_code: str
    capability_name: str
    external_user_id: str | None
    global_user_id: str | None
    tenant_id: str | None
    roles: tuple[str, ...]
    trace_id: str | None
    claims: Mapping[str, Any] = field(default_factory=lambda: MappingProxyType({}), repr=False)


current_invocation: contextvars.ContextVar[InvocationContext | None] = contextvars.ContextVar("reachai_invocation", default=None)


@dataclass(frozen=True)
class EmbedPrincipal:
    external_user_id: str
    global_user_id: str | None = None
    user_name: str | None = None
    roles: list[str] = field(default_factory=list)
    attributes: dict[str, Any] = field(default_factory=dict)
    business_session_id: str | None = field(default=None, repr=False)
    business_session_expires_at: int | None = None


class _EmbedInput(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    agent_id: str = Field(alias="agentId", min_length=1, max_length=192)
    page_instance_id: str = Field(alias="pageInstanceId", min_length=1, max_length=192)
    page_key: str | None = Field(default=None, alias="pageKey")
    route: str | None = None
    origin: str | None = None


PrincipalResolver = Callable[[Request, str], Awaitable[EmbedPrincipal | None]]
SecurityBridge = Callable[[InvocationContext, Callable[[], Awaitable[Any]]], Awaitable[Any]]


def _freeze_claim(value: Any) -> Any:
    if isinstance(value, dict): return MappingProxyType({key: _freeze_claim(item) for key, item in value.items()})
    if isinstance(value, list): return tuple(_freeze_claim(item) for item in value)
    return value


def _origin(value: str) -> str:
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        raise ValueError("invalid origin")
    port = parsed.port
    if port == (443 if parsed.scheme == "https" else 80): port = None
    host = parsed.hostname.lower()
    if ":" in host: host = "[" + host + "]"
    return parsed.scheme.lower() + "://" + host + (":" + str(port) if port else "")


class ReachAI:
    def __init__(self, options: ReachAIOptions, *, credential_store: CredentialStore | None = None,
                 principal_resolver: PrincipalResolver | None = None, security_bridge: SecurityBridge | None = None,
                 http_client: httpx.AsyncClient | None = None, nonce_store: CallbackNonceStore | None = None):
        self.options = options
        self.catalog = CapabilityCatalog(options.base_url, options.context_path)
        self.registry = RegistryClient(options, credential_store, self.catalog, http_client, nonce_store)
        self.principal_resolver, self.security_bridge = principal_resolver, security_bridge
        self.allowed_origins = frozenset(_origin(value) for value in options.allowed_origins)
        self._mounted = False

    def capability(self, *, name: str, title: str = "", description: str = "", side_effect: str = "WRITE",
                   business_identity_required: bool = False, required_roles: tuple[str, ...] = (),
                   parameter_names: dict[str, str] | None = None, business_index_resolver: bool = False) -> Callable[[Callable[..., Any]], Callable[..., Any]]:
        if self._mounted: raise RuntimeError("register capabilities before mounting")
        def decorate(function: Callable[..., Any]) -> Callable[..., Any]:
            return self.catalog.register(function, name=name, title=title, description=description,
                                         side_effect=side_effect, business_identity_required=business_identity_required,
                                         required_roles=required_roles, parameter_names=parameter_names,
                                         business_index_resolver=business_index_resolver)
        return decorate

    def mark_empty_inventory_complete(self) -> None:
        if self._mounted: raise RuntimeError("configure inventory before mounting")
        self.catalog.mark_empty_inventory_complete()

    async def exchange_embed_token(self, request: _EmbedInput, principal: EmbedPrincipal, origin: str) -> dict[str, Any]:
        result = await self.registry.post("/api/embed/token/exchange", {
            "projectCode": self.options.project_code, "agentId": request.agent_id, "pageKey": request.page_key,
            "pageInstanceId": request.page_instance_id, "route": request.route, "origin": origin,
            "principal": {"externalUserId": principal.external_user_id, "globalUserId": principal.global_user_id,
                          "userName": principal.user_name, "roles": principal.roles, "attributes": principal.attributes},
            "businessSessionId": principal.business_session_id, "businessSessionExpiresAt": principal.business_session_expires_at,
        }, body_signed=True)
        if "code" in result and str(result["code"]) != "200": raise ValueError("Embed exchange rejected")
        result = result.get("data", result)
        if not isinstance(result, dict) or not isinstance(result.get("token"), str) or not result["token"]:
            raise ValueError("invalid Embed response")
        if isinstance(result.get("expiresIn"), bool) or not isinstance(result.get("expiresIn"), (int, float)) or result["expiresIn"] <= 0:
            raise ValueError("invalid Embed expiry")
        if principal.business_session_id and result.get("businessSessionBound") is not True:
            raise ValueError("session binding not confirmed")
        if principal.business_session_expires_at is not None and result.get("businessSessionExpiryApplied") is not True:
            raise ValueError("session expiry not confirmed")
        return result

    async def revoke_business_session(self, business_session_id: str) -> None:
        if not business_session_id.strip(): raise ValueError("business session required")
        result = await self.registry.post("/api/embed/token/business-session/revoke",
                                          {"projectCode": self.options.project_code, "businessSessionId": business_session_id},
                                          body_signed=True)
        if str(result.get("code")) != "200": raise ValueError("business session revocation not confirmed")

    async def index_upsert(self, index_code: str, record: dict[str, Any]) -> dict[str, Any]:
        return await self._index_send(index_code, "upsert", record)

    async def index_batch(self, index_code: str, records: list[dict[str, Any]]) -> dict[str, Any]:
        return await self._index_send(index_code, "batch", {"items": records})

    async def index_delete(self, index_code: str, biz_id: str) -> dict[str, Any]:
        return await self._index_send(index_code, "delete", {"bizId": biz_id})

    async def _index_send(self, index_code: str, operation: str, body: dict[str, Any]) -> dict[str, Any]:
        import re
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,95}", self.options.project_code) or not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{1,62}", index_code):
            raise ValueError("invalid business index path")
        path = f"/api/knowledge-ingress/projects/{self.options.project_code}/biz-index/{index_code}/{operation}"
        return await self.registry.post(path, body, body_signed=True, project_only=True)

    def mount(self, app: FastAPI) -> None:
        if self._mounted: raise RuntimeError("ReachAI already mounted")
        self.catalog.registrations()  # Fail closed before starting a partial/unknown inventory.
        if any(item.business_identity_required for item in self.catalog._items.values()) and self.security_bridge is None:
            raise ValueError("business capabilities requiring identity need a security bridge")
        self._mounted = True
        router = APIRouter(include_in_schema=False)

        @router.post("/reachai/registry/capabilities/sync")
        async def sync_callback(request: Request) -> JSONResponse:
            headers = request.headers
            if not await self.registry.verify_callback(headers.get("X-ReachAI-App-Key", ""), headers.get("X-ReachAI-Timestamp", ""),
                                                 headers.get("X-ReachAI-Nonce", ""), headers.get("X-ReachAI-Signature", "")):
                return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
            try:
                result = await self.registry.sync("SDK_CALLBACK")
                return JSONResponse({"instanceId": self.registry.instance_id, "capabilityCount": len(self.catalog._items),
                                     "httpApiCount": 0, "registryResponse": json.dumps(result, ensure_ascii=False, separators=(",", ":"))})
            except Exception:
                logger.warning("ReachAI capability sync failed")
                return JSONResponse({"error": "SYNC_FAILED"}, status_code=502)

        @router.post("/reachai/capabilities/{name}/invoke")
        async def invoke(name: str, request: Request) -> JSONResponse:
            handler = self.catalog.find(name)
            if handler is None: return JSONResponse({"error": "NOT_FOUND"}, status_code=404)
            try:
                credentials = self.registry.credentials
                if credentials is None: raise PermissionError()
                claims = verify_invocation(request.headers.get("X-ReachAI-Invocation-Token", ""), credentials.app_key,
                                           credentials.app_secret, self.options.project_code, name)
                def text(key: str) -> str | None:
                    value = claims.get(key)
                    return value if isinstance(value, str) and value.strip() else None
                roles = claims.get("roles", [])
                if not isinstance(roles, list) or any(not isinstance(role, str) for role in roles): raise PermissionError()
                identity = InvocationContext(self.options.project_code, name, text("externalUserId"), text("globalUserId"),
                                             text("tenantId"), tuple(roles), text("traceId"), _freeze_claim(claims))
                has_user = bool(identity.external_user_id or identity.global_user_id)
                if handler.business_identity_required and not has_user: raise PermissionError()
                if handler.required_roles and not set(handler.required_roles).intersection(identity.roles):
                    return JSONResponse({"error": "FORBIDDEN"}, status_code=403)
            except PermissionError:
                return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
            try:
                raw = await request.body()
                if len(raw) > _MAX_INVOCATION_BYTES: return JSONResponse({"error": "INVALID_ARGUMENTS"}, status_code=413)
                values = json.loads(raw)
                kwargs = handler.bind(values)
            except Exception:
                return JSONResponse({"error": "INVALID_ARGUMENTS"}, status_code=400)
            context_token = current_invocation.set(identity)
            invoked = False
            async def execute() -> Any:
                nonlocal invoked
                if invoked: raise RuntimeError("capability may only execute once")
                invoked = True
                if inspect.iscoroutinefunction(handler.function): return await handler.function(**kwargs)
                return await run_in_threadpool(handler.function, **kwargs)
            try:
                result = await self.security_bridge(identity, execute) if self.security_bridge else await execute()
                # Same contract as the other SDKs: a bridge that skips execution is a failure, not a business result.
                if not invoked: raise RuntimeError("security bridge did not invoke the capability")
                if handler.business_index_resolver:
                    result = validate_resolution(result, kwargs["request"], identity)
                return JSONResponse(jsonable_encoder(result))
            except Exception:
                return JSONResponse({"success": False, "code": "CAPABILITY_EXECUTION_FAILED", "message": "业务方法执行失败"}, status_code=500)
            finally:
                current_invocation.reset(context_token)

        @router.post("/reachai/embed/token")
        async def embed(input: _EmbedInput, request: Request) -> JSONResponse:
            try:
                origin = _origin(request.headers.get("Origin", ""))
                if origin not in self.allowed_origins or input.origin and _origin(input.origin) != origin:
                    return JSONResponse({"error": "FORBIDDEN"}, status_code=403)
            except ValueError:
                return JSONResponse({"error": "FORBIDDEN"}, status_code=403)
            if self.principal_resolver is None: return JSONResponse({"error": "UNAVAILABLE"}, status_code=503)
            principal = await self.principal_resolver(request, input.agent_id)
            if principal is None or not principal.external_user_id.strip(): return JSONResponse({"error": "UNAUTHORIZED"}, status_code=401)
            try:
                return JSONResponse(await self.exchange_embed_token(input, principal, origin))
            except httpx.HTTPStatusError as exc:
                return JSONResponse({"error": "EXCHANGE_FAILED"}, status_code=403 if exc.response.status_code in (401, 403) else 502)
            except Exception:
                return JSONResponse({"error": "EXCHANGE_FAILED"}, status_code=502)

        app.include_router(router)
        old_lifespan = app.router.lifespan_context

        @asynccontextmanager
        async def lifespan(instance: FastAPI):
            async with old_lifespan(instance) as state:
                stop = asyncio.Event()
                async def worker() -> None:
                    registered = False
                    while not stop.is_set():
                        try:
                            if not registered: await self.registry.register(); registered = True
                            await self.registry.heartbeat()
                        except asyncio.CancelledError: raise
                        except Exception as exc: logger.warning("ReachAI registry failed: %s", type(exc).__name__)
                        try: await asyncio.wait_for(stop.wait(), timeout=max(5, self.options.heartbeat_seconds))
                        except asyncio.TimeoutError: pass
                    if registered:
                        try: await self.registry.offline()
                        except Exception as exc: logger.warning("ReachAI offline failed: %s", type(exc).__name__)
                task = asyncio.create_task(worker())
                try: yield state
                finally:
                    stop.set()
                    try: await asyncio.wait_for(task, timeout=5)
                    except (asyncio.TimeoutError, asyncio.CancelledError): task.cancel()
                    await self.registry.close()
        app.router.lifespan_context = lifespan
