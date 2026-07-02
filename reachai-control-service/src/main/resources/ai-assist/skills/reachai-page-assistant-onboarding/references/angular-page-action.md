# Angular Page Action Onboarding

## Files

Copy the Angular templates into the business frontend, usually under:

```text
src/app/shared/reachai/
```

Then import `ReachAiPageActionService` in the target page component and register handlers during component initialization. Call `unregisterPage(pageKey)` during destroy.

## Page Component Pattern

```ts
constructor(private readonly reachAiPageActions: ReachAiPageActionService) {}

ngOnInit(): void {
  registerReachAiPageActions(this.reachAiPageActions, {
    getPageState: () => this.getReachAiPageState(),
    setFilters: (filters) => this.setReachAiFilters(filters),
    search: () => this.search(),
    reset: () => this.reset(),
    readTable: () => this.readReachAiTable(),
  });
}

ngOnDestroy(): void {
  this.reachAiPageActions.unregisterPage(reachAiPageKey);
}
```

Prefer existing component state and services. Do not duplicate query logic.

`setFilters` must update the live form/query model that the existing search flow actually reads. Updating only a default query template, a copied object, or the Page Action result payload is not enough. After invoking `setFilters`, verify that page state/getPageState reports the new filter value and that the next `search` request sent by the business page includes the corresponding query parameter.

### `@zhongruigroup/ngx-query` + `zr-table`

For pages built with `@zhongruigroup/ngx-query` and `zr-table`, do not assume `queryTemplates[0].template.rules` is the live query state. `ngx-query` may clone templates into the current plain/plainCollapse operation, and `zr-table` usually emits data from `getQueryTerm(ngxQuery)` when `executeQuery()` runs.

Implementation checklist:

- Inspect the template bindings. Inputs often bind to `rule.datas[dataIndex]`, while request params are derived later from `event.page.filters[].term`.
- Update the live rules used by the rendered query component, not only the original default template. When the component exposes cloned rules, update both the live rules and `queryTemplates[0].template.rules`.
- For each updated rule, keep `rule.datas[0]` and `rule.data` consistent so both the visible input and `getQueryTerm()` can read the value.
- Call the component's change detection hooks, such as `ngxQuery.markForCheck()`, `ngxQuery.resetFieldTemplates()`, and `ChangeDetectorRef.detectChanges()`, when the page uses OnPush or cloned query templates.
- If the existing `loadOrders(event)` only reads `event.page.filters`, add a fallback that extracts the current filter value from the live query rules when the event omits a term.
- Validate with the browser Network tab: after `setFilters({ managerName: "X" })` and `search()`, the real business request must contain `managerName: "X"` or the page's documented mapped parameter. A result payload that says `status: SUCCESS` is not enough.

## Verification

Run the business frontend's minimum build or type check. If a logged-in browser is available, verify:

```js
await window.__REACHAI_PAGE_BRIDGE__.execute('teamArchive.list', 'getPageState')
await window.__REACHAI_PAGE_BRIDGE__.execute('teamArchive.list', 'setFilters', { teamName: 'test' })
await window.__REACHAI_PAGE_BRIDGE__.execute('teamArchive.list', 'search')
await window.__REACHAI_PAGE_BRIDGE__.execute('teamArchive.list', 'readTable')
```

For query pages, collect one evidence chain before reporting PASS:

- `setFilters` args contain the non-empty test value
- the visible filter control or page query state contains that value
- `search` triggers the real business API request with that value in the request body or query string
- `readTable` returns rows/pagination from the refreshed table

