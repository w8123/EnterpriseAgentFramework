# Workflow AI Coding REST API

Base path pattern:

`/api/workflows/{workflowId}/ai-coding`

Collection create path:

`POST /api/workflows/ai-coding/workflows`

## Authentication

All Workflow AI Coding endpoints require the project **AI Coding 接入秘钥** (`aiCodingKey`).

- Query param: `?aiCodingKey=rac_...`
- Header: `X-ReachAI-AiCoding-Key: rac_...`

Obtain/rotate the key in ReachAI admin: **项目详情 → AI Coding 接入秘钥**.

Responses:

- `401` — missing `aiCodingKey`
- `403` — invalid key or AI Coding access disabled for the project

These endpoints do **not** accept platform Bearer login. Admin UI (Workflow Studio `/api/workflows/studio/**`) continues to use Bearer separately.

API base URL depends on deployment; prefer relative paths from onboarding manifest.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/api/workflows/ai-coding/workflows` | Create workflow draft |
| `GET` | `/api/workflows/{workflowId}/ai-coding/context` | Read workflow + graph + validation |
| `POST` | `/api/workflows/{workflowId}/ai-coding/validate` | Validate current or proposed graph |
| `POST` | `/api/workflows/{workflowId}/ai-coding/patch` | Preview or save graph patch |
| `POST` | `/api/workflows/{workflowId}/ai-coding/run` | Debug run draft workflow |
| `GET` | `/api/workflows/{workflowId}/ai-coding/versions` | Version history + release readiness |
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
- `availableModels`: ACTIVE LLM instances
  - fields: `id`, `name`, `provider`, `modelName`, `modelType`, `status`
- `availableTools`: project-scoped tools/capabilities
  - fields: `name`, `kind`, `title`, `description`, `enabled`, `qualifiedName`
- `warnings`

Rules:

- Pick LLM `modelInstanceId` only from `availableModels`
- Pick TOOL names only from `availableTools`
- If `availableModels` is empty, ask operator to configure model instances; do not guess ids

## Patch Request

Important fields:

- `operations[]`
- `dryRun` (default `true`)
- `baseRevision` (required for save; use `workflow.updatedAt` from context)
- `reason`

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

Workflow AI Coding does **not** expose publish.

Use:

- `GET .../versions` for release validation and readiness
- manual publish in admin UI when human operator approves

## Error Semantics

- `400`: invalid payload, patch failure, revision mismatch
- `403`: project permission denied
- `404`: workflow or trace not found
