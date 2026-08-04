# Page Action Contract

Use Page Actions when the current browser page can expose its existing state and
operations directly. Do not create a backend API Tool merely to read data that
is already present on the page.

## Browser bridge

The business frontend exposes one global bridge:

```ts
window.__REACHAI_PAGE_BRIDGE__
```

Minimum methods:

```ts
register(pageKey, actionKey, handler, metadata?)
unregisterPage(pageKey)
execute(pageKey, actionKey, args?, options?)
list(pageKey?)
```

The `pageKey` and action keys must exactly match the ReachAI page catalog. A
handler operates the current page instance and reuses the page's real form,
query, table and permission logic. It must not bypass login, data scope, button
guards or confirmation.

The normalized handler result is:

```json
{
  "status": "SUCCESS",
  "message": "optional",
  "data": {},
  "error": null,
  "metadata": {}
}
```

Allowed status values are `SUCCESS`, `WARN` and `ERROR`. High-risk actions must
set `confirmRequired=true`, identify `riskLevel=HIGH`, and refuse execution
unless `options.confirmed` is true.

## Chat action queue

`data.metadata.pageActionQueue` is the preferred queue. The compatible
single-action shape is `data.uiRequest.extension.pageActionRequest`. For each
request:

1. Call `window.__REACHAI_PAGE_BRIDGE__.execute(pageKey, actionKey, args, options)`.
2. Map any rich bridge error object to a string at the Embed API boundary.
3. Post one result to
   `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`.

Rendering only `data.answer` is not Page Action integration.

## Verification

Static verification proves only that the global bridge, page key and expected
action keys are present in source. Runtime verification requires a logged-in
browser, a fresh Embed session and real invokes.

For a query flow, PASS requires the whole chain:

- `setFilters` receives a non-empty value;
- the visible/live page query state changes;
- `search` sends that value in the real business API request;
- `readTable` reads the refreshed table.

A handler returning `SUCCESS` without the real request/state change is FAIL.
Embed sessions snapshot `bridgeActions`; refresh the page or create a new
session after adding or renaming actions.
