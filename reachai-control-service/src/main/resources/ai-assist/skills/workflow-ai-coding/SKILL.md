---
name: workflow-ai-coding
description: Edit, validate, debug, publish, and inspect ReachAI Workflow drafts through the Workflow AI Coding REST API. Use when asked to create or modify a workflow graph, add/update/delete nodes or edges, validate GraphSpec, dry-run or debug-run a workflow, inspect trace/run output, check release readiness, publish a validated draft, or work on PAGE_ASSISTANT workflows from Cursor/Codex.
---

# Workflow AI Coding

## Operating Rules

Treat the ReachAI platform repository and live API responses as the source of truth. Do not edit `ai_workflow` rows directly. All graph changes go through Workflow AI Coding REST endpoints under `/api/workflows/.../ai-coding`.

Core mental model:

- `GraphSpec` is runtime semantics; `canvas_json` is layout only.
- Patch/create default `layout.autoLayout=true`: platform saves rank-based `canvas_json.nodes[].position` (Studio-aligned). Before reporting back, confirm positions are spread across levels/lanes; do not patch GraphSpec only and skip canvas layout.
- `START` and `END` are virtual GraphSpec edge endpoints, not node types. Do not add them to `nodes`.
- Every workflow must include a real entry node, set `graphSpec.entry` to that node id, add `START -> <entryNode>` with `condition=always`, and connect every terminal branch to `END`.
- Workflow AI Coding updates **draft definition** until an explicit publish request is made.
- Workflow AI Coding may publish a validated draft through `POST /api/workflows/{workflowId}/ai-coding/publish`; publish still runs release validation and creates an ACTIVE `ai_workflow_version`.
- Always read `GET .../context` before patching. Use `workflow.updatedAt` as `baseRevision` when saving.
- Default patch behavior is `dryRun=true`. Only set `dryRun=false` after validation passes.

Authentication: Workflow AI Coding endpoints use **aiCodingKey only** (no platform Bearer). Obtain the project-level key from ReachAI **项目详情 → AI Coding 接入秘钥**. Send it on every request as header `X-ReachAI-AiCoding-Key`; do not put the key in generated URLs, scripts, logs, or browser runtime code. Missing key returns `401`; invalid or disabled key returns `403`. API base URL depends on deployment; use relative paths from the platform manifest.

Do not use platform login cookies or Bearer tokens for `/api/workflows/**/ai-coding/**`.

## Windows / PowerShell UTF-8 Requirements

When calling Workflow AI Coding APIs from Windows, prevent Chinese text from being stored as `????`:

1. Prefer PowerShell 7+ (`pwsh`).
2. At the top of scripts, set UTF-8 explicitly:

   ```powershell
   [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
   [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
   $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
   ```

3. Write complex request bodies to standalone `.json` files saved as UTF-8.
4. Use `curl.exe`, not the PowerShell `curl` alias:

   ```powershell
   curl.exe -X POST $url -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json; charset=utf-8" --data-binary "@request.json"
   ```

5. If using `Invoke-RestMethod`, send UTF-8 bytes rather than a plain string body:

   ```powershell
   $json = Get-Content .\request.json -Raw -Encoding utf8
   $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
   Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json; charset=utf-8" -Body $bodyBytes
   ```

6. Save generated script files as UTF-8. In Windows PowerShell 5.1, specify UTF-8 explicitly when writing files.
7. Do not inline JSON containing Chinese text directly in the command line.

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
9. Publish when release validation is valid: `POST .../publish`
10. Report: changed nodes/edges, validation result, traceId, release readiness, published version, and any remaining issues

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

When building LLM nodes, pick `modelInstanceId` from `availableModels[].id`. When building TOOL/CAPABILITY nodes, pick tool names from `availableTools[].name` or `qualifiedName`. If `availableModels` is empty, ask the human operator to configure model instances first; do not invent ids. `availableModels` lists ACTIVE registry instances; it is not a live credential probe, so provider auth errors during `/run` mean the operator should fix credentials or choose another listed model.

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
- warnings, including publish readiness and the AI Coding publish endpoint

When `releaseValidation.valid=true`, call `POST /api/workflows/{workflowId}/ai-coding/publish` with a semantic version such as `v1.0.0`. If that version already exists, read `/versions` and choose the next version. Do not call legacy admin publish endpoints.

### Runs / trace

`GET /api/workflows/{workflowId}/ai-coding/runs?limit=20&days=7`

`GET /api/workflows/{workflowId}/ai-coding/runs/{traceId}`

Use these after debug runs to inspect node outputs, spans, tool calls, guard decisions, workflow path, and repair hints.
If a freshly returned `traceId` is not visible, first rely on `/run.nodeOutputs` for immediate debugging and report the trace lookup gap with the exact `traceId`.

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
- Publish result (`version`, `versionId`, `status`) or the validation/version-conflict reason publish was not completed
