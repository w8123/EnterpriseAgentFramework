#!/usr/bin/env python3
"""Isolated ReMe shadow adapter with one workspace per HMAC owner key."""

from __future__ import annotations

import argparse
import asyncio
import json
import time
from collections import defaultdict
from pathlib import Path, PurePosixPath
from typing import Any

from reme import Application, __version__
from reme.config import resolve_app_config
from reme.enumeration import ComponentEnum


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
            raise ValueError(f"raw identity field reached ReMe adapter: {forbidden}")
    return payload


def markdown(item: dict[str, Any]) -> str:
    return (
        "---\n"
        f"name: {item['canonicalId']}\n"
        "description: ReachAI synthetic personal-memory shadow row\n"
        "---\n\n"
        f"# {item['canonicalId']}\n\n"
        f"{item['text'].strip()}\n"
    )


def minimal_config(workspace: Path, args: argparse.Namespace) -> dict[str, Any]:
    config = resolve_app_config(
        log_config=False,
        workspace_dir=str(workspace),
        log_to_console=False,
        log_to_file=False,
        enable_logo=False,
        service={"backend": "http", "web_enabled": False},
    )
    config["jobs"] = {name: config["jobs"][name] for name in ("reindex", "search")}
    keep = ("tokenizer", "file_graph", "file_chunker", "keyword_index", "file_store")
    config["components"] = {name: config["components"][name] for name in keep}
    config["components"]["as_embedding"] = {
        "default": {
            "backend": "openai",
            "model": args.model,
            "dimensions": args.dimensions,
            "credential": {
                "api_key": "benchmark-only",
                "base_url": args.base_url.rstrip("/"),
            },
            "parameters": {},
        }
    }
    config["components"]["embedding_store"] = {
        "default": {
            "backend": "local",
            "as_embedding": "default",
            "max_batch_size": 32,
        }
    }
    config["components"]["file_store"]["default"].update(
        {
            "backend": "local",
            "store_name": "local",
            "embedding_store": "default",
            "keyword_index": "default",
            "file_graph": "default",
        }
    )
    return config


def result_id(result: dict[str, Any]) -> str | None:
    path_value = result.get("path")
    if not path_value:
        return None
    return PurePosixPath(str(path_value).replace("\\", "/")).stem or None


def result_score(result: dict[str, Any]) -> float:
    scores = result.get("scores") if isinstance(result.get("scores"), dict) else {}
    return float(scores.get("score", result.get("score", 0.0)) or 0.0)


def passes_relevance_gate(result: dict[str, Any], min_score: float) -> bool:
    """Gate on raw vector similarity, while retaining genuine BM25 matches.

    ReMe's public ``min_score`` is applied after RRF when both branches hit,
    where the scale is not cosine-compatible. The adapter therefore performs
    the enterprise abstention gate from the structured per-branch scores.
    """
    scores = result.get("scores") if isinstance(result.get("scores"), dict) else {}
    if scores.get("keyword") is not None:
        return True
    vector = scores.get("vector", result.get("score"))
    return vector is not None and float(vector) >= min_score


async def run_owner(
    owner_key: str,
    memories: list[dict[str, Any]],
    queries: list[dict[str, Any]],
    root: Path,
    args: argparse.Namespace,
) -> tuple[list[dict[str, Any]], bool]:
    workspace = root / owner_key
    digest = workspace / "digest"
    digest.mkdir(parents=True, exist_ok=True)
    for item in memories:
        (digest / f"{item['canonicalId']}.md").write_text(markdown(item), encoding="utf-8")

    app = Application(**minimal_config(workspace, args))
    await app.start()
    deleted = [item for item in memories if item.get("status") == "DELETED" or item.get("lifecycle") == "ADD_THEN_DELETE"]
    try:
        first_index = await app.run_job("reindex")
        if not first_index.success:
            raise RuntimeError(f"ReMe initial reindex failed: {first_index.answer}")
        for item in deleted:
            (digest / f"{item['canonicalId']}.md").unlink(missing_ok=True)
        second_index = await app.run_job("reindex")
        if not second_index.success:
            raise RuntimeError(f"ReMe post-delete reindex failed: {second_index.answer}")

        store = app.context.components[ComponentEnum.FILE_STORE]["default"]
        deleted_paths = [f"digest/{item['canonicalId']}.md" for item in deleted]
        confirmed_absent = not deleted_paths or not await store.get_nodes(deleted_paths)

        responses: list[dict[str, Any]] = []
        for query in queries:
            started = time.perf_counter()
            response = await app.run_job("search", query=query["text"], limit=int(query["topK"]))
            if not response.success:
                raise RuntimeError(f"ReMe search failed for {query['queryId']}: {response.answer}")
            hits: list[dict[str, Any]] = []
            seen: set[str] = set()
            for result in (response.metadata or {}).get("results") or []:
                if not passes_relevance_gate(result, args.min_score):
                    continue
                item_id = result_id(result)
                if not item_id or item_id in seen:
                    continue
                seen.add(item_id)
                hits.append({"memoryId": item_id, "score": result_score(result)})
                if len(hits) >= int(query["topK"]):
                    break
            responses.append(
                {
                    "queryId": query["queryId"],
                    "latencyMs": (time.perf_counter() - started) * 1000,
                    "hits": hits,
                }
            )
        return responses, confirmed_absent
    finally:
        await app.close()


async def run(args: argparse.Namespace) -> dict[str, Any]:
    request = load_request(Path(args.input))
    root = Path(args.workspace).resolve()
    root.mkdir(parents=True, exist_ok=True)
    memories_by_owner: dict[str, list[dict[str, Any]]] = defaultdict(list)
    queries_by_owner: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for memory in request["memories"]:
        memories_by_owner[memory["ownerKey"]].append(memory)
    for query in request["queries"]:
        queries_by_owner[query["ownerKey"]].append(query)

    setup_started = time.perf_counter()
    query_results: list[dict[str, Any]] = []
    deletion_checks: list[bool] = []
    for owner_key, owner_queries in queries_by_owner.items():
        results, deleted_absent = await run_owner(
            owner_key,
            memories_by_owner.get(owner_key, []),
            owner_queries,
            root,
            args,
        )
        query_results.extend(results)
        deletion_checks.append(deleted_absent)
    total_ms = (time.perf_counter() - setup_started) * 1000
    deleted_ids = [
        item["canonicalId"]
        for item in request["memories"]
        if item.get("status") == "DELETED" or item.get("lifecycle") == "ADD_THEN_DELETE"
    ]
    order = {query["queryId"]: index for index, query in enumerate(request["queries"])}
    query_results.sort(key=lambda item: order[item["queryId"]])
    return {
        "schema": SCHEMA,
        "provider": "reme",
        "implementation": "ReMe OSS isolated owner workspaces with BM25 plus OpenAI-compatible TEI embeddings",
        "metadata": {
            "version": __version__,
            "fileStore": "local",
            "ownerIsolation": "one-workspace-per-hmac-owner-key",
            "embeddingModel": args.model,
            "dimensions": args.dimensions,
            "minScore": args.min_score,
            "calibration": "SYNTHETIC_DEVELOPER_CANARY_ONLY",
            "totalSetupAndQueryMs": total_ms,
        },
        "lifecycle": {
            "attemptedDeleteIds": deleted_ids,
            "confirmedAbsent": all(deletion_checks),
        },
        "queries": query_results,
    }


def main() -> None:
    args = arguments()
    result = asyncio.run(run(args))
    Path(args.output).write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
