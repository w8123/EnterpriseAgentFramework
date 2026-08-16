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

Use the public outcome vocabulary below. `SUCCESS` means the requested page
operation completed. `NO_DATA`, `PRECONDITION_FAILED`, and `USER_CANCELLED`
are **business-terminal outcomes**: the bridge handled the request correctly,
but there is no business effect to continue with. Include a short `message`
for every business-terminal outcome. Do not throw an exception for those
expected business states.

| Outcome | Use it when | ReachAI treatment |
| --- | --- | --- |
| `SUCCESS` | The page operation completed. | Completed action |
| `NO_DATA` | A read/query found no matching business data. | Completed business terminal |
| `PRECONDITION_FAILED` | A selected row, workflow state, required filter, or other business precondition is absent. | Completed business terminal |
| `USER_CANCELLED` | The end user rejects the SDK confirmation. | Completed business terminal |
| `FAILED`, `ACTION_NOT_FOUND`, `FORBIDDEN`, `TIMEOUT` | The action/bridge could not execute. | Technical failure |

Legacy bridge values `WARN`, `ERROR`, and `CANCELLED` are accepted at the
boundary and normalized to `PRECONDITION_FAILED`, `FAILED`, and
`USER_CANCELLED` respectively. New code must return the public values above.
High-risk actions must set `confirmRequired=true`, identify `riskLevel=HIGH`,
and refuse execution unless `options.confirmed` is true.

When `confirmRequired=true`, the Embed SDK renders an accessible, non-blocking
ReachAI confirmation dialog in the business page. The sequence is fixed:
user confirmation -> atomic request claim -> business handler -> result POST.
Do not add a second `window.confirm` in the business handler. Native browser
dialogs block Page Action polling and are not part of the supported contract.
For an accepted confirmation, the SDK result includes `userConfirmed: true`.
Runtime and final-answer generation must treat this as verified evidence and
must not tell the user that confirmation was skipped.

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
