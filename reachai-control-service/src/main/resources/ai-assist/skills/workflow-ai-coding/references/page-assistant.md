# PAGE_ASSISTANT Workflow Rules

Use these endpoints only when `workflow.workflowKind=PAGE_ASSISTANT`.

## Context Sources

`GET /api/workflows/{workflowId}/ai-coding/context` exposes:

- `pageAssistantContext.resourceBindings`
- `pageAssistantContext.pageKey`
- `pageAssistantContext.actionKeys`

`pageKey` is the single `TARGET` `PAGE` binding. Other allowed pages use `RELATED` bindings. Treat these first-class bindings and the live page-action catalog as authoritative; do not derive page scope from `extraJson` or guess page keys.

## Graph Shape

- Set `graphSpec.entryNodeId` to the `USER_INPUT` node id.
- `START/END` are Studio-only canvas nodes and never belong in GraphSpec nodes or edges.
- List every terminal intent/default branch node in `graphSpec.exitNodeIds`.
- When extracting query/filter parameters from natural language, use `PARAMETER_EXTRACT` with `config.extractMode=llm` unless the user explicitly asks for expression-only extraction.

## Catalog

`GET /api/workflows/{workflowId}/ai-coding/page-assistant/catalog`

Returns:

- each `PAGE_ACTION` node in GraphSpec
- page action catalog for every bound `TARGET` or `RELATED` page
- match status per node

Match statuses include:

- `MATCHED`
- `PAGE_KEY_EMPTY`
- `ACTION_KEY_EMPTY`
- `UNBOUND_PAGE`
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
- `message`
- `input`
- `runtimeContext`

Bridge context keys accepted by platform:

- `embedSessionId`
- `pageBridge`
- `pageContext`
- `bridgeGlobal`

Important:

- The smoke-test endpoint reuses the real generic Workflow debug runner. Its `RunView` contains status, node outputs, trace/run ids, errors, warnings, and metadata; it does not manufacture page-specific node statuses.
- A successful debug run does not by itself prove real browser behavior. Run the actual page in a browser, verify the visible result and business request, then report acceptance through the current business-page workbench task.
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

Keep node `pageKey` inside the Workflow's `TARGET` or `RELATED` page bindings.
