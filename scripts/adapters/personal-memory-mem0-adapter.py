#!/usr/bin/env python3
"""Isolated Mem0 shadow adapter.

The input is already projected by ReachAI: HMAC owner keys, canonical IDs and
synthetic text only. The output deliberately contains candidate IDs and scores,
never memory text.
"""

from __future__ import annotations

import argparse
import importlib.metadata
import json
import os
import time
from pathlib import Path
from typing import Any

os.environ.setdefault("MEM0_TELEMETRY", "False")

from mem0 import Memory  # noqa: E402


SCHEMA = "reachai-personal-memory-shadow-adapter-v1"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--workspace", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--dimensions", required=True, type=int)
    parser.add_argument("--min-score", required=True, type=float)
    return parser.parse_args()


def load_request(path: Path) -> dict[str, Any]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("schema") != SCHEMA:
        raise ValueError("unsupported adapter request schema")
    encoded = json.dumps(payload, ensure_ascii=False)
    for forbidden in ("tenantId", "runtimeUserId"):
        if forbidden in encoded:
            raise ValueError(f"raw identity field reached Mem0 adapter: {forbidden}")
    return payload


def package_version() -> str:
    for name in ("mem0ai", "mem0"):
        try:
            return importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            continue
    return "unknown"


def canonical_id(row: dict[str, Any]) -> str | None:
    metadata = row.get("metadata") if isinstance(row.get("metadata"), dict) else {}
    value = metadata.get("canonical_id") or row.get("canonical_id")
    return str(value) if value else None


def close_memory(memory: Memory) -> None:
    vector_store = getattr(memory, "vector_store", None)
    client = getattr(vector_store, "client", None)
    close = getattr(client, "close", None)
    if callable(close):
        close()


def main() -> None:
    args = arguments()
    request = load_request(Path(args.input))
    workspace = Path(args.workspace).resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    qdrant_path = workspace / "qdrant"
    history_path = workspace / "history.db"
    base_url = args.base_url.rstrip("/")

    config = {
        "version": "v1.1",
        "history_db_path": str(history_path),
        "vector_store": {
            "provider": "qdrant",
            "config": {
                "collection_name": "reachai_personal_memory_shadow",
                "path": str(qdrant_path),
                "embedding_model_dims": args.dimensions,
                "on_disk": False,
            },
        },
        "embedder": {
            "provider": "openai",
            "config": {
                "model": args.model,
                "api_key": "benchmark-only",
                "openai_base_url": base_url,
                "embedding_dims": args.dimensions,
            },
        },
        # infer=False below means this LLM is constructed but never invoked.
        "llm": {
            "provider": "openai",
            "config": {
                "model": "unused-in-infer-false-shadow-benchmark",
                "api_key": "benchmark-only",
                "openai_base_url": base_url,
            },
        },
    }

    setup_started = time.perf_counter()
    memory = Memory.from_config(config)
    mem0_ids: dict[str, str] = {}
    deleted_ids: list[str] = []
    try:
        for item in request["memories"]:
            result = memory.add(
                item["text"],
                user_id=item["ownerKey"],
                metadata={"canonical_id": item["canonicalId"]},
                infer=False,
            )
            rows = result.get("results") or []
            if len(rows) != 1 or not rows[0].get("id"):
                raise RuntimeError(f"Mem0 did not create exactly one row for {item['canonicalId']}")
            mem0_ids[item["canonicalId"]] = str(rows[0]["id"])

        for item in request["memories"]:
            if item.get("status") == "DELETED" or item.get("lifecycle") == "ADD_THEN_DELETE":
                memory.delete(mem0_ids[item["canonicalId"]])
                deleted_ids.append(item["canonicalId"])

        setup_ms = (time.perf_counter() - setup_started) * 1000
        query_results: list[dict[str, Any]] = []
        for query in request["queries"]:
            started = time.perf_counter()
            response = memory.search(
                query["text"],
                top_k=int(query["topK"]),
                filters={"user_id": query["ownerKey"]},
                threshold=args.min_score,
                rerank=False,
            )
            rows = response.get("results") or []
            hits: list[dict[str, Any]] = []
            seen: set[str] = set()
            for row in rows:
                item_id = canonical_id(row)
                if not item_id or item_id in seen:
                    continue
                seen.add(item_id)
                hits.append({"memoryId": item_id, "score": float(row.get("score") or 0.0)})
                if len(hits) >= int(query["topK"]):
                    break
            query_results.append(
                {
                    "queryId": query["queryId"],
                    "latencyMs": (time.perf_counter() - started) * 1000,
                    "hits": hits,
                }
            )

        confirmed_absent = all(
            memory.vector_store.get(vector_id=mem0_ids[item_id]) is None
            for item_id in deleted_ids
        )
        output = {
            "schema": SCHEMA,
            "provider": "mem0",
            "implementation": "Mem0 OSS with local Qdrant and OpenAI-compatible TEI embeddings",
            "metadata": {
                "version": package_version(),
                "vectorStore": "qdrant-local",
                "embeddingModel": args.model,
                "dimensions": args.dimensions,
                "minScore": args.min_score,
                "calibration": "SYNTHETIC_DEVELOPER_CANARY_ONLY",
                "setupMs": setup_ms,
                "infer": False,
                "telemetry": False,
            },
            "lifecycle": {
                "attemptedDeleteIds": deleted_ids,
                "confirmedAbsent": confirmed_absent,
            },
            "queries": query_results,
        }
        Path(args.output).write_text(
            json.dumps(output, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
    finally:
        close_memory(memory)


if __name__ == "__main__":
    main()
