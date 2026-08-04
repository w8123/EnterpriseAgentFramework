# Angular Page Action Integration

The templates under `templates/angular/` provide a small framework-neutral
bridge service. Copy them to a shared frontend directory such as:

```text
src/app/shared/reachai/
```

Register the target page's handlers during initialization and unregister them
during destroy:

```ts
constructor(private readonly reachAiPageActions: ReachAiPageActionService) {}

ngOnInit(): void {
  registerReachAiPageActions(this.reachAiPageActions, {
    getPageState: () => this.currentPageState(),
    setFilters: (filters) => this.applyRealFilters(filters),
    search: () => this.search(),
    reset: () => this.reset(),
    readTable: () => this.currentVisibleTable(),
  });
}

ngOnDestroy(): void {
  this.reachAiPageActions.unregisterPage(reachAiPageKey);
}
```

Reuse the component's existing methods and state. Do not duplicate business
queries inside the bridge.

When a component library clones query templates, update the live model used by
the rendered form and request builder, not only the original template object.
For `@zhongruigroup/ngx-query` and `zr-table`, inspect the rules consumed by
`executeQuery()` / `getQueryTerm()` and verify the resulting Network request.

Run the frontend's normal type check/build. If authenticated browser access is
available, verify the read-only actions first:

```js
await window.__REACHAI_PAGE_BRIDGE__.list('replace.with.pageKey')
await window.__REACHAI_PAGE_BRIDGE__.execute('replace.with.pageKey', 'getPageState')
await window.__REACHAI_PAGE_BRIDGE__.execute('replace.with.pageKey', 'readTable')
```

Only probe mutating or high-risk actions with explicit user approval.
