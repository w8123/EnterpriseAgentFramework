---
name: workflow-ai-coding
description: Edit, validate, debug, and inspect ReachAI Workflow drafts through the Workflow AI Coding REST API. Use when asked to create or modify a workflow graph, add/update/delete nodes or edges, validate GraphSpec, dry-run or debug-run a workflow, inspect trace/run output, check release readiness, or work on PAGE_ASSISTANT workflows from Cursor/Codex.
---

# Workflow AI Coding

## Operating Rules

Treat the ReachAI platform repository and live API responses as the source of truth. Do not edit `ai_workflow` rows directly. All graph changes go through Workflow AI Coding REST endpoints under `/api/workflows/.../ai-coding`.

Core mental model:

- `GraphSpec` is runtime semantics; `canvas_json` is layout only.
- Workflow AI Coding updates **draft definition** only.
- **AI tools must not publish.** Publishing is a manual admin action after release validation passes.
- Always read `GET .../context` before patching. Use `workflow.updatedAt` as `baseRevision` when saving.
- Default patch behavior is `dryRun=true`. Only set `dryRun=false` after validation passes.

Authentication: Workflow AI Coding endpoints use **aiCodingKey only** (no platform Bearer). Obtain the project-level key from ReachAI **项目详情 → AI Coding 接入秘钥**. Send it on every request as query param `aiCodingKey` or header `X-ReachAI-AiCoding-Key`. Missing key returns `401`; invalid or disabled key returns `403`. API base URL depends on deployment; use relative paths from the platform manifest.

Do not use platform login cookies or Bearer tokens for `/api/workflows/**/ai-coding/**`.

## Standard Workflow Loop

1. Optional create: `POST /api/workflows/ai-coding/workflows`
2. Read world: `GET /api/workflows/{workflowId}/ai-coding/context`
3. Preview edits: `POST /api/workflows/{workflowId}/ai-coding/patch` with `dryRun=true`
4. Validate proposed graph: `POST /api/workflows/{workflowId}/ai-coding/validate` with `mode=PROPOSED`
5. Save draft: `POST /api/workflows/{workflowId}/ai-coding/patch` with `dryRun=false` and matching `baseRevision`
6. Debug:
   - `POST .../run` with `dryRun=true` first
   - then `POST .../run` with real input when safe
7. Inspect runs:
   - `GET .../runs?limit=&days=`
   - `GET .../runs/{traceId}`
8. Check release readiness: `GET .../versions`
9. Report: changed nodes/edges, validation result, traceId, release readiness, and **"ready for manual publish"** if applicable

## Endpoint Map

### Create workflow

`POST /api/workflows/ai-coding/workflows`

Required body fields:

- `name`
- `keySlug` (slug format enforced by platform)
- `projectId`
- `projectCode` (must match the registered project for `projectId`; comparison is case-insensitive)

Optional:

- `description`, `workflowType` (default `CHAT`), `runtimeType` (default `LANGGRAPH4J`)
- `defaultModelInstanceId`
- `graphSpec`, `canvas`, `extra`
- `reason` (audit note)

Returns full `WorkflowAiCodingContextResponse`.

Example:

```json
{
  "name": "Orders Copilot Flow",
  "keySlug": "orders-copilot-flow",
  "projectId": 7,
  "projectCode": "orders",
  "workflowType": "CHAT",
  "reason": "bootstrap draft for AI coding"
}
```

### Read context

`GET /api/workflows/{workflowId}/ai-coding/context`

Returns workflow metadata, `graphSpec`, `canvas`, release validation, node type catalog, runtime hints, bindings, page-assistant context, **`availableModels`**, **`availableTools`**, warnings.

Use `workflow.updatedAt` as patch `baseRevision`.

When building LLM nodes, pick `modelInstanceId` from `availableModels[].id`. When building TOOL/CAPABILITY nodes, pick tool names from `availableTools[].name` or `qualifiedName`. If `availableModels` is empty, ask the human operator to configure model instances first; do not invent ids.

### Validate

`POST /api/workflows/{workflowId}/ai-coding/validate`

Modes:

- `CURRENT` (default): validate stored draft
- `PROPOSED`: validate supplied `graphSpec`; required when sending `graphSpec`

Do not send `graphSpec` with `mode=CURRENT`.

### Patch graph

`POST /api/workflows/{workflowId}/ai-coding/patch`

Important fields:

- `operations`: list of patch ops
- `dryRun`: default `true`; set `false` only to persist
- `baseRevision`: use latest `workflow.updatedAt` when saving
- `layout.autoLayout`: default `true`
- `reason`: audit note on save

Supported ops:

- `ADD_NODE`
- `UPDATE_NODE`
- `DELETE_NODE`
- `ADD_EDGE`
- `DELETE_EDGE`
- `SET_ENTRY`

Example preview:

```json
{
  "dryRun": true,
  "operations": [
    {
      "op": "ADD_NODE",
      "node": {
        "id": "answer",
        "type": "ANSWER",
        "name": "Answer"
      }
    },
    {
      "op": "ADD_EDGE",
      "edge": {
        "from": "start",
        "to": "answer"
      }
    },
    {
      "op": "SET_ENTRY",
      "entry": "start"
    }
  ]
}
```

Save draft:

```json
{
  "dryRun": false,
  "baseRevision": "2026-06-16T10:00:00",
  "reason": "add answer node",
  "operations": [ ... ]
}
```

On save failure:

- `baseRevision mismatch` → re-read context, retry with fresh `updatedAt`
- validation errors → fix graph, dry-run again

### Debug run

`POST /api/workflows/{workflowId}/ai-coding/run`

Fields:

- `message`
- `input`
- `runtimeContext`
- `dryRun`

Safety gates:

- Side-effect nodes (`HTTP_REQUEST`, `TOOL`, `CAPABILITY`, `MCP_CALL`, `KNOWLEDGE_WRITE`) require `runtimeContext.confirmSideEffects=true`
- `PAGE_ACTION` nodes require page bridge context (`embedSessionId`, `pageBridge`, `pageContext`, or `bridgeGlobal`)

Example safe dry run:

```json
{
  "dryRun": true,
  "message": "hello"
}
```

Example execute with side effects:

```json
{
  "message": "hello",
  "runtimeContext": {
    "confirmSideEffects": true
  }
}
```

### Versions / release readiness

`GET /api/workflows/{workflowId}/ai-coding/versions`

Returns:

- current workflow status
- published version snapshot (if any)
- version history
- release validation for current draft
- `draftDirty` flag
- warnings, including explicit note that publish is manual

AI must stop at readiness reporting. Do not call legacy publish endpoints.

### Runs / trace

`GET /api/workflows/{workflowId}/ai-coding/runs?limit=20&days=7`

`GET /api/workflows/{workflowId}/ai-coding/runs/{traceId}`

Use these after debug runs to inspect node outputs, spans, tool calls, guard decisions, workflow path, and repair hints.

## PAGE_ASSISTANT Extensions

When `workflowType=PAGE_ASSISTANT`, also use:

- `GET .../page-assistant/catalog`
- `POST .../page-assistant/validate`
- `POST .../page-assistant/smoke-test`

Read `references/page-assistant.md` before editing `PAGE_ACTION` nodes.

## References

- GraphSpec and patch rules: `references/graphspec.md`
- REST endpoint map: `references/workflow-apis.md`
- PAGE_ASSISTANT rules: `references/page-assistant.md`
- Safety and governance: `references/safety.md`

## Output Contract

End with:

- Workflow id/keySlug and whether draft was saved
- Nodes/edges changed
- Validation result (`valid`, key errors/warnings)
- Debug status, traceId/runId if executed
- Release readiness from `/versions`
- Explicit statement whether manual publish is still required
