# PAGE_ASSISTANT Workflow Rules

Use these endpoints only when `workflow.workflowType=PAGE_ASSISTANT`.

## Context Sources

`GET /api/workflows/{workflowId}/ai-coding/context` exposes:

- `pageAssistantContext.pageKey`
- `pageAssistantContext.routePattern`
- `pageAssistantContext.actionKeys`
- lightweight page action catalog

Prefer workflow `extraJson` and bindings as resolved by the platform; do not guess page keys.

## Catalog

`GET /api/workflows/{workflowId}/ai-coding/page-assistant/catalog`

Returns:

- each `PAGE_ACTION` node in GraphSpec
- full page action catalog for the workflow page
- match status per node

Match statuses include:

- `MATCHED`
- `PAGE_KEY_EMPTY`
- `ACTION_KEY_EMPTY`
- `PAGE_KEY_MISMATCH`
- `MISSING`
- `INACTIVE`

## Validate

`POST /api/workflows/{workflowId}/ai-coding/page-assistant/validate`

Optional body:

```json
{
  "graphSpec": { ... proposed graph ... }
}
```

If omitted, validates the stored draft.

Checks:

- node `config.pageKey` / `config.actionKey`
- catalog existence and `ACTIVE` status
- required args from catalog input schema

## Smoke Test

`POST /api/workflows/{workflowId}/ai-coding/page-assistant/smoke-test`

Default `dryRun=true`.

Body fields:

- `dryRun`
- `runtimeContext`
- `runtimeVerification`

Bridge context keys accepted by platform:

- `embedSessionId`
- `pageBridge`
- `pageContext`
- `bridgeGlobal`

Runtime verification evidence:

```json
{
  "runtimeVerification": {
    "browserRuntime": {
      "status": "PASS"
    }
  }
}
```

Node smoke statuses:

- `DRY_RUN`
- `SKIPPED`
- `NEED_CONFIRM`
- `READY_TO_QUEUE`
- `RUNTIME_PASS`
- `INVALID`

Important:

- AI Coding smoke-test does not prove real browser execution unless runtime verification evidence is supplied.
- Actions with `confirmRequired=true` cannot be treated as fully executed without explicit confirmation policy.

## PAGE_ACTION Node Config Shape

Typical config:

```json
{
  "pageKey": "orders.list",
  "actionKey": "openDetail",
  "args": {
    "orderId": "{{state.orderId}}"
  },
  "outputAlias": "openDetailResult"
}
```

Keep node `pageKey` aligned with workflow page context.
