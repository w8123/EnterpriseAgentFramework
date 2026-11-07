"""Credential-store contract and encrypted, atomic, single-writer file storage."""
from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
from dataclasses import dataclass, field
from typing import Protocol

from cryptography.fernet import Fernet


@dataclass(frozen=True)
class Credentials:
    app_key: str
    app_secret: str = field(repr=False)

    def __post_init__(self) -> None:
        if not self.app_key.strip() or not self.app_secret.strip():
            raise ValueError("both project credentials are required")


class CredentialStore(Protocol):
    async def load(self) -> Credentials | None: ...
    async def save(self, credentials: Credentials) -> None: ...
    async def enrollment_uncertain(self) -> bool: ...
    async def mark_enrollment_started(self) -> None: ...
    async def clear_enrollment_started(self) -> None: ...


class EncryptedFileCredentialStore:
    """Persist ciphertext; key must come from a host secret manager, never the file itself.

    The path must be private to the service account. A single process owns enrollment.
    Deployments with multiple workers should provision a shared credential externally.
    """
    def __init__(self, path: str | Path, encryption_key: str | bytes):
        self.path = Path(path).expanduser().resolve()
        self.fernet = Fernet(encryption_key.encode() if isinstance(encryption_key, str) else encryption_key)

    async def load(self) -> Credentials | None:
        if not self.path.exists():
            return None
        data = json.loads(self.fernet.decrypt(self.path.read_bytes()))
        return Credentials(data["appKey"], data["appSecret"])

    async def save(self, credentials: Credentials) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        payload = self.fernet.encrypt(json.dumps({"appKey": credentials.app_key, "appSecret": credentials.app_secret}, separators=(",", ":")).encode())
        fd, temp = tempfile.mkstemp(prefix=".reachai-", dir=self.path.parent)
        try:
            if os.name != "nt": os.fchmod(fd, 0o600)
            with os.fdopen(fd, "wb") as out:
                out.write(payload); out.flush(); os.fsync(out.fileno())
            # The caller promises a single enrollment writer. Never overwrite existing credentials.
            if self.path.exists(): raise FileExistsError("credential store already exists")
            os.link(temp, self.path)
        finally:
            try: os.unlink(temp)
            except FileNotFoundError: pass

    @property
    def marker(self) -> Path:
        return self.path.with_name(self.path.name + ".enrollment-pending")

    async def enrollment_uncertain(self) -> bool:
        return self.marker.exists()

    async def mark_enrollment_started(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self.marker, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            os.write(fd, b"Enrollment outcome may be uncertain; recover credentials before retrying.\n")
            os.fsync(fd)
        finally: os.close(fd)

    async def clear_enrollment_started(self) -> None:
        try: self.marker.unlink()
        except FileNotFoundError: pass
