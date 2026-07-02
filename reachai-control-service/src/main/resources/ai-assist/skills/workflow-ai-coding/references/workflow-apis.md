# Workflow AI Coding REST API

Base path pattern:

`/api/workflows/{workflowId}/ai-coding`

Collection create path:

`POST /api/workflows/ai-coding/workflows`

## Authentication

All Workflow AI Coding endpoints require the project **AI Coding 接入秘钥** (`aiCodingKey`).

- Required header: `X-ReachAI-AiCoding-Key: rac_...`
- Do not put the key in generated URLs, scripts, logs, or browser runtime code.

Obtain/rotate the key in ReachAI admin: **项目详情 → AI Coding 接入秘钥**.

Responses:

- `401` — missing `aiCodingKey`
- `403` — invalid key or AI Coding access disabled for the project

These endpoints do **not** accept platform Bearer login. Admin UI (Workflow Studio `/api/workflows/studio/**`) continues to use Bearer separately.

API base URL depends on deployment; prefer relative paths from onboarding manifest.

## Windows / PowerShell UTF-8 Requirements

If requests are sent from Windows, the POST body must be UTF-8 bytes. Terminal display encoding alone is not enough.

1. Prefer PowerShell 7+ (`pwsh`).
2. Set script encoding explicitly:

   ```powershell
   [Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
   [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
   $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
   ```

3. Save complex JSON as a standalone UTF-8 `.json` file.
4. Prefer `curl.exe --data-binary @file` with an explicit charset:

   ```powershell
   curl.exe -X POST $url -H "X-ReachAI-AiCoding-Key: $AI_CODING_KEY" -H "Content-Type: application/json; charset=utf-8" --data-binary "@request.json"
   ```

5. If using `Invoke-RestMethod`, convert JSON to UTF-8 bytes:

   ```powershell
   $json = Get-Content .\request.json -Raw -Encoding utf8
   $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($json)
   Invoke-RestMethod -Method Post -Uri $url -ContentType "application/json; charset=utf-8" -Body $bodyBytes
   ```

Do not inline JSON containing Chinese text directly in the command line.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/workflows/ai-coding/workflows` | Create workflow draft |
| `GET` | `/api/workflows/{workflowId}/ai-coding/context` | Read workflow + graph + validation |
| `POST` | `/api/workflows/{workflowId}/ai-coding/validate` | Validate current or proposed graph |
| `POST` | `/api/workflows/{workflowId}/ai-coding/patch` | Preview or save graph patch |
| `POST` | `/api/workflows/{workflowId}/ai-coding/run` | Debug run draft workflow |
| `GET` | `/api/workflows/{workflowId}/ai-coding/versions` | Version history + release readiness |
| `POST` | `/api/workflows/{workflowId}/ai-coding/publish` | Publish validated draft as active workflow version |
| `GET` | `/api/workflows/{workflowId}/ai-coding/runs` | Recent debug runs |
| `GET` | `/api/workflows/{workflowId}/ai-coding/runs/{traceId}` | Trace detail |
| `GET` | `/api/workflows/{workflowId}/ai-coding/page-assistant/catalog` | PAGE_ASSISTANT catalog |
| `POST` | `/api/workflows/{workflowId}/ai-coding/page-assistant/validate` | PAGE_ACTION validation |
| `POST` | `/api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test` | PAGE_ACTION smoke test |

## Create Request

Required:

- `name`
- `keySlug`
- `projectId`
- `projectCode` (must match the registered project for `projectId`; comparison is case-insensitive)

Returns the same shape as `GET .../context`.

## Context Response

`GET /api/workflows/{workflowId}/ai-coding/context` returns:

- `workflow`: metadata including `defaultModelInstanceId`, `updatedAt`
- `graphSpec`, `canvas`
- `validation`: current release validation errors/warnings
- `nodeTypes`: supported node descriptors
- `availableModels`: ACTIVE LLM instances; this catalog does not prove provider credentials are currently valid
  - fields: `id`, `name`, `provider`, `modelName`, `modelType`, `status`
- `availableTools`: project-scoped tools/capabilities
  - fields: `name`, `kind`, `title`, `description`, `enabled`, `qualifiedName`
- `warnings`

Rules:

- Pick LLM `modelInstanceId` only from `availableModels`
- If `/run` returns a provider auth error, choose another listed model or ask the operator to repair that model instance credential
- Pick TOOL names only from `availableTools`
- If `availableModels` is empty, ask operator to configure model instances; do not guess ids

## Patch Request

Important fields:

- `operations[]`
- `dryRun` (default `true`)
- `baseRevision` (required for save; use `workflow.updatedAt` from context)
- `reason`
- `layout.autoLayout` (default `true`): when enabled, patch/create saves rank-based canvas positions aligned with Workflow Studio auto-layout (`x = 80 + level * 260`, `y = 120 + lane * 150`). Verify `canvas.nodes[].position` before reporting back; do not patch GraphSpec only and ignore canvas layout.

## Validate Request

- `mode`: `CURRENT` or `PROPOSED`
- `graphSpec`: required only for `PROPOSED`

## Run Request

- `message`
- `input`
- `runtimeContext`
- `dryRun`

Runtime safety keys:

- `runtimeContext.confirmSideEffects=true` for side-effect nodes
- `runtimeContext.embedSessionId` / `pageBridge` / `pageContext` / `bridgeGlobal` for PAGE_ACTION flows

## Publish Boundary

Workflow AI Coding exposes a project-key protected publish endpoint:

`POST /api/workflows/{workflowId}/ai-coding/publish`

Request body:

```json
{
  "version": "v1.0.0",
  "rolloutPercent": 100,
  "note": "initial AI Coding publish",
  "publishedBy": "Codex"
}
```

Rules:

- Call `GET .../versions` first and publish only when `releaseValidation.valid=true`.
- Publish still runs server-side release validation and fails if the draft is not releasable.
- Use `POST .../publish`, not legacy admin publish endpoints.
- If `v1.0.0` already exists, read version history and choose the next semantic version.

## Error Semantics

- `400`: invalid payload, patch failure, revision mismatch
- `403`: project permission denied
- `404`: workflow or trace not found
