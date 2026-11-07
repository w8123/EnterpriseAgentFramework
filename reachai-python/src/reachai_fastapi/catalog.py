"""Explicit capability declarations; unsupported contracts fail before synchronization."""
from __future__ import annotations

import inspect
import json
import re
from dataclasses import dataclass
from typing import Any, Callable, get_type_hints

from pydantic import TypeAdapter
from .business_index import BusinessIndexRequest, BusinessIndexResolution

_NAME = re.compile(r"^[A-Za-z0-9_.-]{1,192}$")


def _schema(annotation: Any) -> dict[str, Any]:
    if annotation in (Any, inspect.Signature.empty):
        raise ValueError("explicit concrete type annotation required")
    document = TypeAdapter(annotation).json_schema()
    definitions = document.pop("$defs", {})

    def resolve(node: dict[str, Any], stack: tuple[str, ...] = ()) -> dict[str, Any]:
        if "$ref" in node:
            ref = node["$ref"]
            if not ref.startswith("#/$defs/") or ref in stack:
                raise ValueError("unsupported or recursive capability DTO")
            key = ref.rsplit("/", 1)[-1]
            if key not in definitions: raise ValueError("unresolved capability DTO")
            return resolve(definitions[key], stack + (ref,))
        node = dict(node)
        union = node.get("anyOf")
        if union:
            variants = [x for x in union if x.get("type") != "null"]
            if len(variants) != 1 or len(variants) == len(union): raise ValueError("unsupported union")
            result = resolve(variants[0], stack)
            result["type"] = [result["type"], "null"]
            return result
        kind = node.get("type")
        if kind not in {"string", "integer", "number", "boolean", "array", "object", "null"}:
            raise ValueError("unsupported capability schema")
        result: dict[str, Any] = {"type": kind}
        if "enum" in node: result["enum"] = node["enum"]
        if "format" in node: result["format"] = node["format"]
        if kind == "array": result["items"] = resolve(node["items"], stack)
        if kind == "object":
            if node.get("additionalProperties") not in (None, False) or "properties" not in node:
                raise ValueError("mapping and dynamic object capability DTOs are unsupported")
            result["properties"] = {key: resolve(value, stack) for key, value in node["properties"].items()}
            result["required"] = node.get("required", [])
        return result
    return resolve(document)


def _base(schema: dict[str, Any]) -> str:
    kind = schema["type"]
    return next(x for x in kind if x != "null") if isinstance(kind, list) else kind


def _meta(schema: dict[str, Any]) -> dict[str, Any]:
    result: dict[str, Any] = {"nullable": isinstance(schema["type"], list)}
    for key in ("format", "enum"):
        if key in schema: result[key] = schema[key]
    if "items" in schema:
        result["itemsType"] = _base(schema["items"])
        result["itemsMetadata"] = _meta(schema["items"])
    return result


def _children(schema: dict[str, Any]) -> list[dict[str, Any]]:
    if _base(schema) == "array": schema = schema["items"]
    return [{"name": name, "type": _base(child), "required": name in schema.get("required", []),
             "location": "BODY", "children": _children(child), "metadata": _meta(child)}
            for name, child in schema.get("properties", {}).items()]


@dataclass(frozen=True)
class Capability:
    name: str
    function: Callable[..., Any]
    registration: dict[str, Any]
    parameters: dict[str, tuple[str, Any, bool]]
    required_roles: tuple[str, ...]
    business_identity_required: bool
    business_index_resolver: bool = False

    def bind(self, values: Any) -> dict[str, Any]:
        if self.business_index_resolver and isinstance(values, dict) and "request" not in values:
            values = {"request": values}
        if not isinstance(values, dict) or set(values) - set(self.parameters):
            raise ValueError("capability input must contain declared fields only")
        kwargs = {}
        for wire, (native, annotation, required) in self.parameters.items():
            if wire not in values:
                if required: raise ValueError("missing capability argument")
                continue
            # Validate as JSON, not a Python object, so JSON representations of
            # dates and UUIDs remain valid under Pydantic strict validation.
            kwargs[native] = TypeAdapter(annotation).validate_json(json.dumps(values[wire], allow_nan=False), strict=True)
        return kwargs


class CapabilityCatalog:
    def __init__(self, base_url: str, context_path: str):
        self._items: dict[str, Capability] = {}
        self._configured = False
        self.base_url, self.context_path = base_url, context_path

    def mark_empty_inventory_complete(self) -> None:
        self._configured = True

    def register(self, function: Callable[..., Any], *, name: str, title: str = "", description: str = "",
                 side_effect: str = "WRITE", business_identity_required: bool = False,
                 required_roles: tuple[str, ...] = (), parameter_names: dict[str, str] | None = None,
                 business_index_resolver: bool = False) -> Callable[..., Any]:
        if not _NAME.fullmatch(name) or name.lower() in self._items:
            raise ValueError("invalid or duplicate capability name")
        side_effect = side_effect.upper()
        if side_effect not in {"READ", "WRITE", "IDEMPOTENT_WRITE", "IRREVERSIBLE"}:
            raise ValueError("invalid side effect")
        if required_roles and (not business_identity_required or any(not isinstance(r, str) or not r.strip() for r in required_roles)):
            raise ValueError("required roles need a business identity")
        signature = inspect.signature(function)
        hints = get_type_hints(function)
        contains_index = BusinessIndexRequest in hints.values() or BusinessIndexResolution in hints.values()
        if contains_index or business_index_resolver:
            if (not business_index_resolver or not business_identity_required or len(signature.parameters) != 1
                    or list(signature.parameters) != ["request"] or hints.get("request") is not BusinessIndexRequest
                    or hints.get("return") is not BusinessIndexResolution):
                raise ValueError("business index resolver requires identity, typed request and typed resolution")
        renames = parameter_names or {}
        if set(renames) - set(signature.parameters): raise ValueError("unknown parameter alias")
        params: dict[str, tuple[str, Any, bool]] = {}
        registered = []
        for param in signature.parameters.values():
            if param.kind in (param.VAR_POSITIONAL, param.VAR_KEYWORD, param.POSITIONAL_ONLY): raise ValueError("unsupported parameter kind")
            annotation = hints.get(param.name, inspect.Signature.empty)
            schema = _schema(annotation)
            name_on_wire = renames.get(param.name, param.name)
            if not _NAME.fullmatch(name_on_wire) or name_on_wire in params: raise ValueError("invalid or duplicate parameter")
            required = param.default is param.empty
            if not required and param.default is None and not isinstance(schema["type"], list):
                raise ValueError("None default requires nullable annotation")
            params[name_on_wire] = (param.name, annotation, required)
            registered.append({"name": name_on_wire, "type": _base(schema), "description": "", "required": required,
                               "location": "BODY", "children": _children(schema), "metadata": _meta(schema)})
        result_type = hints.get("return", inspect.Signature.empty)
        if result_type is inspect.Signature.empty: raise ValueError("return annotation required")
        result_schema = None if result_type in (None, type(None), BusinessIndexResolution) else _schema(result_type)
        metadata = {"assetType": "BUSINESS_METHOD", "declared": True, "source": "ReachCapability",
                    "requiredRoles": list(required_roles), "businessIdentityRequired": business_identity_required,
                    "className": function.__module__, "methodName": function.__qualname__,
                    "invokeProtocol": "REACHAI_CAPABILITY_HTTP", "timeoutMs": 0, "retryLimit": -1}
        if result_schema is not None: metadata["resultSchema"] = result_schema
        if business_index_resolver: metadata["tags"] = ["business-index-resolver"]
        registration = {"name": name, "title": title, "description": description, "httpMethod": "POST",
                        "baseUrl": self.base_url, "contextPath": self.context_path,
                        "endpointPath": f"/reachai/capabilities/{name}/invoke", "requestBodyType": "object",
                        "responseType": "void" if result_type in (None, type(None)) else str(result_type), "sideEffect": side_effect,
                        "enabled": True, "parameters": registered, "metadata": metadata}
        self._items[name.lower()] = Capability(name, function, registration, params, tuple(required_roles), business_identity_required, business_index_resolver)
        self._configured = True
        return function

    def find(self, name: str) -> Capability | None:
        item = self._items.get(name.lower())
        return item if item and item.name == name else None

    def registrations(self) -> list[dict[str, Any]]:
        if not self._configured: raise RuntimeError("capability inventory has not been configured")
        return [x.registration for x in self._items.values()]
