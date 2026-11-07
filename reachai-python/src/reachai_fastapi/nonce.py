"""Callback replay protection; shared multi-worker deployments inject an atomic store."""
from __future__ import annotations

import time
from typing import Protocol


class CallbackNonceStore(Protocol):
    async def claim(self, project_code: str, nonce: str, expires_at_ms: int) -> bool:
        """Atomically accept a previously unseen project/nonce until expiry."""
        ...


class InMemoryCallbackNonceStore:
    """Single-process default, not shared across workers or process restarts."""
    def __init__(self) -> None:
        self._seen: dict[tuple[str, str], int] = {}

    async def claim(self, project_code: str, nonce: str, expires_at_ms: int) -> bool:
        now = int(time.time() * 1000)
        self._seen = {key: expiry for key, expiry in self._seen.items() if expiry >= now}
        key = (project_code, nonce)
        if key in self._seen: return False
        self._seen[key] = expires_at_ms
        return True
