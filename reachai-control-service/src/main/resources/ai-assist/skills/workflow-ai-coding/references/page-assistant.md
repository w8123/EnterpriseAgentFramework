# PAGE_ASSISTANT Workflow Rules

Use these endpoints only when `workflow.workflowType=PAGE_ASSISTANT`.

## Context Sources

`GET /api/workflows/{workflowId}/ai-coding/context` exposes:

- `pageAssistantContext.pageKey`
- `pageAssistantContext.routePattern`
- `pageAssistantContext.actionKeys`
- lightweight page action catalog

Prefer workflow `extraJson` and bindings as resolved by the platform; do not guess page keys.

## Graph Shape

- Always connect `START -> USER_INPUT` and set `graphSpec.entry` to the `USER_INPUT` node id.
- `START/END` are virtual endpoints, not `nodes`.
- Every intent route and default route must finish by connecting its terminal node to `END`.
- When extracting query/filter parameters from natural language, use `PARAMETER_EXTRACT` with `config.extractMode=llm` unless the user explicitly asks for expression-only extraction.

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
- For query flows, real browser execution requires more than queued PAGE_ACTION success. Verify that extracted filter values appear in `PAGE_ACTION(setFilters).args`, are visible in current page filters/state after `setFilters`, are included in the real business query request triggered by `search`, and are reflected by the refreshed table read through `readTable`.
- If Workflow extraction and `setFilters.args` are correct but the business query request is unfiltered, the defect is in the business page action handler/query-state binding, not in Workflow parameter extraction. Report it as runtime verification `FAIL`.

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
