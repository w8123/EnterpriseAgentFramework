# Page Action Contract

## Bridge

The business page exposes a browser global:

```ts
window.__REACHAI_PAGE_BRIDGE__
```

Read `pageActionContract.bridgeApi` from the page assistant manifest for the machine-readable invoke protocol. Do not guess handler signatures.

Minimum API:

```ts
register(pageKey, actionKey, handler, metadata?)
unregisterPage(pageKey)
execute(pageKey, actionKey, args?, options?)
list(pageKey?)
```

Handlers must operate the current page instance. They must not bypass page permissions, login state, button guards, or data scope.

## bridgeApi

Manifest field: `pageActionContract.bridgeApi`

- `global`: `window.__REACHAI_PAGE_BRIDGE__`
- `methods`:
  - `register(pageKey, actionKey, handler, metadata?)`
  - `unregisterPage(pageKey)`
  - `execute(pageKey, actionKey, args?, options?)`
  - `list(pageKey?)`
- `schemas.executeRequest`: JSON Schema-like object with `pageKey`, `actionKey`, optional `args`, optional `options.confirm`
- `schemas.executeResponse`: JSON Schema-like object with `status`, `message`, `data`, `error.code`, `metadata`
- `statusValues`: `SUCCESS`, `WARN`, `ERROR`
- `errorCodes`: `HANDLER_NOT_FOUND`, `HANDLER_ERROR`, `CONFIRM_REQUIRED`, `PENDING_CONFIRM`

### Examples

`getPageState`:

```json
{
  "pageKey": "teamArchive.list",
  "actionKey": "getPageState",
  "args": {}
}
```

Response:

```json
{
  "status": "SUCCESS",
  "data": {
    "filters": { "teamName": "A" },
    "pagination": { "page": 1, "pageSize": 10 },
    "rows": []
  }
}
```

High-risk pending confirm:

```json
{
  "status": "ERROR",
  "error": {
    "code": "PENDING_CONFIRM",
    "message": "High-risk action requires user confirmation"
  }
}
```

## Result Shape

```json
{
  "status": "SUCCESS",
  "message": "optional",
  "data": {},
  "error": null,
  "metadata": {}
}
```

Allowed status values are `SUCCESS`, `WARN`, and `ERROR`.

## Embed API Result Boundary

When posting results to `POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`, the platform DTO (`EmbedChatController.PageActionResultRequest`) accepts `status` as a string and `error` as a **string**, not a structured object.

Bridge handlers and Angular templates may use richer internal shapes such as `error: { code, message }`. Map those to string `error` (and an appropriate `status`) at the Embed API boundary. SDK internal statuses like `FAILED`, `CANCELLED`, or `TIMEOUT` should be mapped before posting; successful execution should use `status: SUCCESS`.

## Register Page files

`endpoints.registerPageUrl` accepts:

```json
"files": [
  "src/app/list.component.ts",
  { "path": "src/app/shared/reachai/reachai-page-action.service.ts", "role": "bridge-service" }
]
```

String entries are shorthand for `{ "path": "...", "role": "unknown" }` and return `validationStatus=HASH_MISSING` until helper verify adds `exists/sha256`.

## Verification

Distinguish static and runtime browser checks:

- `browser-verify-static`: catalog/route/action key alignment; message should include `static only`
- `browser-verify-runtime`: authenticated browser + bridge invoke through `window.__REACHAI_PAGE_BRIDGE__`

Runtime PASS rules:

- Requires invoke evidence: `invokedActions` plus `redactedResults` with at least one `SUCCESS`
- `bridgeExists=true` or HTTP 200 alone is WARN, not PASS
- Missing FrontendUrl/login/StorageState/Cookie => `SKIPPED` or `WARN`
- For query flows, `status=SUCCESS` from `setFilters`, `search`, or `readTable` is not enough. Evidence must show that non-empty `setFilters` args were written into current page filters/state, that the following real business query request carried the corresponding query parameters, and that `readTable` read the refreshed visible table.
- If `setFilters` receives a non-empty field but the next business query request is still unfiltered, mark runtime verification as `FAIL`; the likely issue is the business page handler writing only a template/default object instead of the current form/query model used by the page search flow.
- For component libraries such as `@zhongruigroup/ngx-query` + `zr-table`, verify the handler writes the live query rules/model used by `executeQuery()` and the table data event. If the request payload still contains only pagination or null organization fields after a non-empty filter, the runtime check must fail even when the Page Action result says `SUCCESS`.

Helper verify emits `verification.browserRuntime`:

```json
{
  "status": "PASS",
  "message": "Runtime bridge invoke succeeded for readonly actions.",
  "frontendUrl": "http://localhost:9200",
  "route": "/teams/archive",
  "pageKey": "teamArchive.list",
  "bridgeExists": true,
  "listedActions": ["getPageState", "readTable"],
  "invokedActions": ["getPageState"],
  "redactedResults": [{ "actionKey": "getPageState", "status": "SUCCESS", "rowCount": 10 }]
}
```

When runtime verification cannot PASS, helper verify also emits a structured `failureCode` so agents do not have to infer the blocker from raw stderr:

| `failureCode` | Meaning |
| --- | --- |
| `PLAYWRIGHT_MISSING` | Node is available but the `playwright` package is not installed. |
| `JSON_PARSE_ERROR` | The Node probe printed non-JSON output; inspect bounded `rawOutput`. |
| `LOGIN_REQUIRED` | The target page likely needs browser login, cookie, or `-StorageStatePath`. |
| `FRONTEND_UNREACHABLE` | The dev server or route could not be reached. |
| `BRIDGE_NOT_FOUND` | The page loaded, but the ReachAI bridge global was not present. |
| `ACTIONS_NOT_REGISTERED` | The bridge exists, but no actions are registered for the target `pageKey`. |

Probe rules:

- Default readonly invoke: `getPageState`, `readTable`
- `-ProbeMutatingActions` enables `setFilters/search/reset`
- Never auto-invoke `openRowAction` or other high-risk actions
- Do not output tokens, credentials, or full row payloads

Platform merge:

- AI/Cursor step evidence is preserved; platform checks append `platformCheck` instead of overwriting `reportedBy/message/evidence`

## Embed Session Refresh

`POST /api/embed/chat/sessions` snapshots the browser-provided `bridgeActions` for the new embed session. If a business frontend registers or renames Page Actions after a session already exists, chat runtime can still say the action is not registered in the current session. Refresh the page or recreate the chat session before testing the new action catalog. Catalog registration in ReachAI and bridge registration in the browser both have to line up for runtime PASS.

## Recommended Actions

- `getPageState`: read filters, pagination, visible table rows, selected rows, and loading state.
- `setFilters`: set existing filter controls without triggering unrelated business logic.
- `search`: reuse the page's existing search flow.
- `reset`: reuse the page's existing reset flow.
- `readTable`: read visible table columns, rows, pagination, and row keys.
- `openRowAction`: optional and high-risk; require user confirmation.

## Safety

- Default to read-only and query actions.
- Creation, editing, deletion, approval, export, batch processing, status changes, and cross-page navigation are high-risk.
- High-risk actions must set `confirmRequired=true` and metadata `riskLevel=HIGH`.
- `bridgeApi.safety.highRiskActionsRequireConfirm=true`; handlers must not bypass page permissions.
