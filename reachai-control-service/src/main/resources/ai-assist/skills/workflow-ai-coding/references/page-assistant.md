# PAGE_ASSISTANT Workflow Rules

Use these endpoints only when `workflow.workflowKind=PAGE_ASSISTANT`.

## Context Sources

`GET /api/workflows/{workflowId}/ai-coding/context` exposes:

- `pageAssistantContext.resourceBindings`
- `pageAssistantContext.pageKey`
- `pageAssistantContext.actionKeys`

`pageKey` is the single `TARGET` `PAGE` binding. Other allowed pages use `RELATED` bindings. Treat these first-class bindings and the live page-action catalog as authoritative; do not derive page scope from `extraJson` or guess page keys.

## API-only requests use a separate Workflow

`PAGE_ASSISTANT` is an Agent Tool with page-action risk. It may call a business
API as part of a page flow, but it is not a substitute for an independently
routable API Tool. When users must be able to say "call the business API and do
not operate the page", create and publish a separate `GENERAL` Workflow that
contains only the required read-only `TOOL`/`CAPABILITY` chain, then attach it
to the same Agent with `riskLevel=READ`.

Do not add API-only classifier branches to a PAGE_ASSISTANT as the sole API
entry. Agent guard policy is applied to the entire attached Workflow and does
not downgrade risk after inspecting an internal branch.

## Graph Shape

- Declare exactly one `USER_INPUT` node and set `graphSpec.entryNodeId` to that node id.
- Set that node's `config.outputAlias` to `params`; use fields such as
  `question` with `source=input.message`.
- Keep `graphSpec.inputSchema` in sync with the `USER_INPUT` fields. The
  canonical first field is `question:string`, required.
- `START/END` are Studio-only canvas nodes and never belong in GraphSpec nodes or edges.
- List every terminal intent/default branch node in `graphSpec.exitNodeIds`.
- When extracting query/filter parameters from natural language, use `PARAMETER_EXTRACT` with `config.extractMode=llm` unless the user explicitly asks for expression-only extraction.
- Every branch returning structured read data must end in a downstream `INTERACTION/PRESENT_OUTPUT` card. Use `list_card` for page/list rows and `output_card`/`card`/`detail` for a single object, with an explicit `nodeOutput.<producerNodeId>` data expression. Blocking interaction variants are not part of the current PAGE_ASSISTANT authoring contract.

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

For a `PAGE_ACTION` smoke test, provide the current business-page
`embedSessionId`. ReachAI resolves the session's `projectCode`, `agentId`,
current page and page instance server-side; supplied project/Agent identities
are not trusted. A missing or expired session returns `CONTEXT_REQUIRED` with
`metadata.contextResolution` rather than a misleading Page Bridge failure.

Example:

```json
{
  "dryRun": true,
  "message": "查询当前班组",
  "runtimeContext": {
    "embedSessionId": "<current embed session id>"
  }
}
```

Important:

- The smoke-test endpoint reuses the real generic Workflow debug runner. Its `RunView` contains status, node outputs, trace/run ids, errors, warnings, and metadata; it does not manufacture page-specific node statuses.
- `runtimeContext`, `pageBridge`, `pageContext` and `bridgeGlobal` are normalized into the Runtime's top-level context. This is why a request that follows this Skill works identically in Smoke Test and actual graph execution.
- A successful debug run does not by itself prove real browser behavior. Run the actual page in a browser, verify the visible result and business request, then report acceptance through the current business-page workbench task.
- Actions with `confirmRequired=true` cannot be treated as fully executed without explicit confirmation policy.
- For query flows, real browser execution requires more than queued PAGE_ACTION success. Verify that extracted filter values appear in `PAGE_ACTION(setFilters).args`, are visible in current page filters/state after `setFilters`, are included in the real business query request triggered by `search`, and are reflected by the refreshed table read through `readTable`.
- For structured output, verify the real assistant SSE contains `ui.requested`, its component matches the declared card, and the browser DOM visibly renders the returned business rows/object. A successful node span without a card DOM is not a passed browser acceptance.
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
