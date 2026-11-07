"""Authorized business-index resolution contracts; host owns real row authorization."""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, ConfigDict, Field

_PROOF = object()


def _required(value: str, maximum: int, identifier: bool = False) -> str:
    if not isinstance(value, str): raise ValueError("invalid business index field")
    value = value.strip()
    if not value or len(value) > maximum or identifier and not re.fullmatch(r"[A-Za-z0-9._:-]+", value):
        raise ValueError("invalid business index field")
    return value


class BusinessIndexRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)
    resource_type: str = Field(alias="resourceType")
    resource_id: str = Field(alias="resourceId")
    expected_source_version: str = Field(alias="expectedSourceVersion")


@dataclass(frozen=True)
class BusinessIndexAccessScope:
    tenant_id: str
    user_id: str
    project_code: str
    resource_type: str
    resource_id: str
    _proof: object = field(repr=False, compare=False)

    @classmethod
    def authorize_row(cls, invocation_tenant_id: str, authoritative_row_tenant_id: str, current_user_id: str,
                      project_code: str, resource_type: str, requested_resource_id: str,
                      authoritative_resource_id: str, visible_to_current_user: bool) -> "BusinessIndexAccessScope":
        tenant = _required(invocation_tenant_id, 96, True)
        actual_tenant = _required(authoritative_row_tenant_id, 96, True)
        user = _required(current_user_id, 128)
        project, kind = _required(project_code, 96, True), _required(resource_type, 96, True)
        requested, actual = _required(requested_resource_id, 256), _required(authoritative_resource_id, 256)
        if tenant != actual_tenant or requested != actual or visible_to_current_user is not True:
            raise PermissionError("business row authorization failed")
        return cls(tenant, user, project, kind, actual, _PROOF)


@dataclass(frozen=True)
class BusinessIndexResolution:
    scope: BusinessIndexAccessScope
    source_system: str
    source_version: str
    data: dict[str, Any]
    resolved_at: str

    @classmethod
    def create_authorized(cls, scope: BusinessIndexAccessScope, source_system: str,
                          source_version: str, data: dict[str, Any]) -> "BusinessIndexResolution":
        if not isinstance(scope, BusinessIndexAccessScope) or scope._proof is not _PROOF or not isinstance(data, dict):
            raise PermissionError("authorized row scope required")
        return cls(scope, _required(source_system, 96, True), _required(source_version, 128),
                   dict(data), datetime.now(timezone.utc).isoformat())

    def wire(self) -> dict[str, Any]:
        scope = self.scope
        return {"schema": "reachai-business-index-resolution-v1", "tenantId": scope.tenant_id,
                "projectCode": scope.project_code, "sourceSystem": self.source_system,
                "resourceType": scope.resource_type, "resourceId": scope.resource_id,
                "sourceVersion": self.source_version, "data": self.data, "resolvedAt": self.resolved_at}


def validate_resolution(result: Any, request: BusinessIndexRequest, identity: Any) -> dict[str, Any]:
    if not isinstance(result, BusinessIndexResolution) or result.scope._proof is not _PROOF:
        raise PermissionError("authorized business index resolution required")
    scope = result.scope
    if (scope.tenant_id != _required(identity.tenant_id, 96, True)
            or scope.user_id != _required(identity.external_user_id or identity.global_user_id, 128)
            or scope.project_code != identity.project_code
            or scope.resource_type != _required(request.resource_type, 96, True)
            or scope.resource_id != _required(request.resource_id, 256)):
        raise PermissionError("business index resolution scope mismatch")
    _required(request.expected_source_version, 128)
    return result.wire()
