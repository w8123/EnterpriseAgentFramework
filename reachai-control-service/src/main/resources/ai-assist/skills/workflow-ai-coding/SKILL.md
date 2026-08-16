---
name: workflow-ai-coding
description: Edit, validate, debug, publish, and inspect ReachAI Workflow drafts through the Workflow AI Coding REST API. Use when asked to create or modify a workflow graph, add/update/delete nodes or edges, validate GraphSpec, dry-run or debug-run a workflow, inspect trace/run output, check release readiness, publish a validated draft, or work on PAGE_ASSISTANT workflows from Cursor/Codex.
---

# Workflow AI Coding

## Operating Rules

Treat the ReachAI platform repository and live API responses as the source of truth. Do not edit `runtime_workflow` or `runtime_workflow_resource_binding` rows directly. All graph and binding changes go through Workflow AI Coding REST endpoints under `/api/workflows/.../ai-coding`.

凡是写入 ReachAI 或展示给业务用户的名称、标题、描述、说明、System Prompt、节点名称、审计原因、进度和结果，默认使用清晰的简体中文。不要仅因 API、Schema 或字段名为英文就生成英文业务文案。Token、MCP、AI、Agent、Supervisor、Workflow、Tool、API、SDK 等熟知专业术语，以及 keySlug、toolName、代码、路径、枚举值、协议字段和技术标识可保留英文；必要时使用“中文名称（英文术语）”。不要翻译或改写技术标识。

Core mental model:

- `GraphSpec` is runtime semantics; `canvas_json` is layout only.
- Create projects and lays out canvas from GraphSpec; patch defaults `layout.autoLayout=true`. The `layered` policy uses LR flow, 88px inter-layer boundary gaps, 56px same-layer gaps, and cycle-safe component packing. Before reporting back, confirm `canvas_json.nodes[].position` is non-overlapping and aligned; do not patch GraphSpec only and skip canvas synchronization.
- `START` and `END` are Studio-only virtual canvas nodes. They never appear in GraphSpec nodes or edges.
- Every workflow must set `graphSpec.entryNodeId` to a real node id and list every terminal node in `graphSpec.exitNodeIds`.
- Workflow AI Coding updates the **Working Copy** until an explicit publish request is made.
- Workflow AI Coding may publish a validated Working Copy through `POST /api/workflows/{workflowId}/ai-coding/publish`; publish still runs release validation and creates an ACTIVE `runtime_workflow_version`.
- Always read `GET .../context` before patching or publishing. Use the latest `workflow.updatedAt` as `baseRevision` when saving and publishing.
- First-class resource bindings may be replaced only while the Workflow is `DRAFT`, through `PUT .../resource-bindings` with the latest `baseRevision`. They are immutable after the first publish.
- Node openness can be variant-specific. Read `nodeTypes[].enabledVariants`; for `INTERACTION`, only `PRESENT_OUTPUT` is currently open. Do not infer that `COLLECT_INPUT`, `USER_CHOICE`, `CONFIRM_ACTION` or other pause/resume variants are available merely because the `INTERACTION` type appears in the catalog.
- Default patch behavior is `dryRun=true`. Only set `dryRun=false` after validation passes.

Authentication: Workflow AI Coding endpoints use **aiCodingKey only** (no platform Bearer). Obtain the project-level key from ReachAI **项目详情 → AI Coding 接入秘钥**. Send it on every request as header `X-ReachAI-AiCoding-Key`; do not put the key in generated URLs, scripts, logs, or browser runtime code. Missing key returns `401`; invalid or disabled key returns `403`. API base URL depends on deployment; use the base URL in the current project or task handoff context.

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
3. If an unbound DRAFT needs page scope, replace it once through `PUT .../resource-bindings`
4. Preview edits: `POST /api/workflows/{workflowId}/ai-coding/patch` with `dryRun=true`
5. Validate proposed graph: `POST /api/workflows/{workflowId}/ai-coding/validate` with `mode=PROPOSED`
6. Save draft: `POST /api/workflows/{workflowId}/ai-coding/patch` with `dryRun=false` and matching `baseRevision`
7. Debug:
   - `POST .../run` with `dryRun=true` first
   - then `POST .../run` with real input when safe
8. Inspect runs:
   - `GET .../runs?limit=&days=`
   - `GET .../runs/{traceId}`
9. Check release readiness: `GET .../versions`
10. Re-read context, then publish when release validation is valid: `POST .../publish` with the latest `workflow.updatedAt` as `baseRevision`
11. Report: changed nodes/edges, validation result, traceId, release readiness, published version, and any remaining issues

For any branch that returns structured business data, do not stop at an `ANSWER` that serializes the object as Markdown. Add a downstream display-only `INTERACTION/PRESENT_OUTPUT` node. Use `list_card` for list/page results, `output_card`/`card`/`detail` for one object, an explicit `dataExpression` such as `nodeOutput.read_table`, and `presentation.mode=card_only` or `text_and_card`. Browser acceptance must observe both the real `ui.requested` SSE event and the rendered card DOM.

## Endpoint Map

### Create workflow

`POST /api/workflows/ai-coding/workflows`

Required body fields:

- `name`
- `keySlug` (slug format enforced by platform)
- `projectId`
- `projectCode` (must match the registered project for `projectId`; comparison is case-insensitive)

Optional:

- `description`, `workflowKind` (default `GENERAL`), `executionEngine` (default `GRAPH_SPEC`)
- `defaultModelInstanceId`
- `graphSpec`, `canvas`, `extra`
- `resourceBindings`; required for `PAGE_ASSISTANT`
- `reason` (audit note)

Returns full `WorkflowAiCodingContextResponse`.

Example:

```json
{
  "name": "订单页面助手",
  "keySlug": "orders-page-assistant",
  "projectId": 7,
  "projectCode": "orders",
  "workflowKind": "PAGE_ASSISTANT",
  "resourceBindings": [
    {
      "resourceType": "PAGE",
      "resourceKey": "orders.list",
      "bindingRole": "TARGET"
    }
  ],
  "reason": "为 AI Coding 创建初始草稿"
}
```

`PAGE_ASSISTANT` requires exactly one `TARGET` `PAGE` binding. Add other in-scope pages as `RELATED` bindings. Do not encode page ownership in `extra` or invent page keys.

### Replace draft resource bindings

`PUT /api/workflows/{workflowId}/ai-coding/resource-bindings`

Use this only to repair or complete the resource scope of a `DRAFT` before its first publish. Send the latest context `workflow.updatedAt` as required `baseRevision`, plus the complete replacement `resourceBindings` list. PAGE_ASSISTANT still requires exactly one `TARGET PAGE`. The operation advances `workflow.updatedAt`; re-read context before any following save. Once status is `ACTIVE`, bindings are immutable and the platform rejects the request.

### Read context

`GET /api/workflows/{workflowId}/ai-coding/context`

Returns workflow metadata, `graphSpec`, `canvas`, release validation, node type catalog, runtime hints, page-assistant context with first-class `resourceBindings`, **`availableModels`**, **`availableTools`**, warnings.

Use `workflow.updatedAt` as patch and publish `baseRevision`.

When building LLM nodes, pick `modelInstanceId` from `availableModels[].id` only. When building TOOL/CAPABILITY nodes, pick tools from `availableTools[]` (`toolId` / `keySlug` / `displayName`). Never invent internal ids. If either array is empty and `warnings` contains `MODEL_CATALOG_UNAVAILABLE`, `CAPABILITY_CATALOG_UNAVAILABLE`, `NO_ACTIVE_LLM`, or `NO_PROJECT_TOOLS`, fix the dependency/warning first; empty+warning means “unavailable or missing”, not “safe to guess”.

Generic Workflow create defaults to `workflowKind=GENERAL`. A business-page Workflow must send `"workflowKind":"PAGE_ASSISTANT"` and its `resourceBindings` explicitly. Attach GENERAL or PAGE_ASSISTANT with:

Risk and routing are evaluated at the attached Workflow-tool boundary, not per
intent branch inside one graph. If the business experience must support both
page operations and an explicit page-independent API query (for example,
"use the business API and do not operate the page"), create two published
Workflows:

- keep page navigation, reads and writes in `PAGE_ASSISTANT`, attached as
  `PAGE_ACTION`;
- put the API-only, read-only Tool chain in a separate `GENERAL` Workflow and
  attach it with `riskLevel=READ`.

Do not hide a read-only API route inside a `PAGE_ASSISTANT` and then claim that
the Agent can select it independently. The Supervisor guard sees the attached
Workflow's declared risk, so a mixed graph cannot provide branch-sensitive
routing evidence.

`POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach`

Body uses `workflowId` plus optional `agentKeySlug` (preferred) or internal `agentId` (mutually exclusive). Prefer omitting agent identifiers so the project default page-copilot keySlug is used. Attachment is additive by default. Set `replaceWorkflowId` only after reading the current attached catalog and selecting the exact predecessor to supersede; ReachAI requires the same project and workflowKind, and for PAGE_ASSISTANT also the same exact TARGET PAGE. It replaces that one entry and preserves every other Workflow. Never infer replacement from pageKey or name. Invalid targets return `ai-coding-error.v1` with `WORKFLOW_REPLACEMENT_INVALID` and do not publish a new config. The Page Assistant-specific endpoint `/api/workflows/{id}/page-assistant/attach-tool` accepts PAGE_ASSISTANT only and returns `WORKFLOW_KIND_NOT_SUPPORTED` for GENERAL.

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
- `layout.autoLayout`: default `true`; `false` preserves existing positions while still synchronizing canvas nodes/edges and placing new nodes
- `layout.direction`: `LR`
- `layout.columnGap`: default `88` (card-boundary gap)
- `layout.rowGap`: default `56` (same-layer card-boundary gap)
- `reason`: audit note on save

Supported ops:

- `ADD_NODE`
- `UPDATE_NODE`
- `DELETE_NODE`
- `ADD_EDGE`
- `UPDATE_EDGE`
- `DELETE_EDGE`
- `SET_ENTRY_NODE`
- `SET_EXIT_NODES`

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
        "name": "生成回答"
      }
    },
    {
      "op": "SET_ENTRY_NODE",
      "entryNodeId": "answer"
    },
    {
      "op": "SET_EXIT_NODES",
      "exitNodeIds": ["answer"]
    }
  ]
}
```

Save draft:

```json
{
  "dryRun": false,
  "baseRevision": "2026-06-16T10:00:00",
  "reason": "添加回答节点",
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

When `releaseValidation.valid=true`, re-read context and call `POST /api/workflows/{workflowId}/ai-coding/publish` with a semantic version such as `v1.0.0` plus the latest `workflow.updatedAt` as `baseRevision`. A `409` means the working copy changed and no version was created; re-read context, validate again, and retry. If that version already exists, read `/versions` and choose the next version. Use only this canonical publish endpoint.

### Runs / trace

`GET /api/workflows/{workflowId}/ai-coding/runs?limit=20&days=7`

`GET /api/workflows/{workflowId}/ai-coding/runs/{traceId}`

Use these after debug runs to inspect node outputs, spans, tool calls, guard decisions, workflow path, and repair hints.
If a freshly returned `traceId` is not visible, first rely on `/run.nodeOutputs` for immediate debugging and report the trace lookup gap with the exact `traceId`.

## PAGE_ASSISTANT Extensions

When `workflowKind=PAGE_ASSISTANT`, also use:

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
