# ReachAI Page Assistant Onboarding

Use this skill when a business frontend repository needs to connect one concrete page to ReachAI Page Assistant.

## Boundary

- This is page assistant onboarding, not project SDK onboarding.
- Do not add backend API Tools for simple page queries when the current page can expose Page Actions.
- Do not implement gateway, SDK, or embed token broker work unless the user explicitly switches to project AI quick access.
- Do not use the ReachAI platform base URL as a Maven repository, npm registry, or generic SDK file server. Page assistant onboarding may only use the manifest, `endpoints.scriptDownloadUrl`, `endpoints.skillPackageUrl`, and page-assistant endpoints.
- Do not invent dependency paths such as `/repository/**`, `/maven/**`, `/repository/maven/**`, `/api/embed/sdk`, or `/npm/**`. If SDK or browser packages are missing, report them as project AI quick access prerequisites.
- Do not create or manage Agents manually. Page assistant traffic enters through the project `PAGE_COPILOT` Agent, which SDK quick access provisioning creates or reuses.
- Registering a page through `endpoints.registerPageUrl` creates or reuses a `PAGE_ASSISTANT` Workflow and mounts it to the `PAGE_COPILOT` Agent through `ai_agent_workflow_binding`.
- Workflow graph editing, debugging, publishing, versions, traces, and replay belong to Workflow Studio; do not reintroduce a separate agent canvas.
- Never read, print, or commit app secrets. Only use the configured environment variable name.
- `aiCodingKey` is for AI tools, local shell, or server-side onboarding calls only. Browser runtime code must not call `/api/ai-coding/projects/{projectId}/page-assistant/**` endpoints and must not store `aiCodingKey`, `provisionAgentUrl`, or `appSecret` in front-end configuration or bundles.
- Page Assistant `/api/ai-coding/projects/{projectId}/page-assistant/**` endpoint URLs do not carry `aiCodingKey`. Use `-AiCodingKey $env:REACHAI_AI_CODING_KEY` with the helper script or send `X-ReachAI-AiCoding-Key` from curl/CLI/server-side scripts. Do not use platform Bearer login for external tool calls.
- Manifest `auth.mode=ai-coding-key` means Cursor/Codex/helper scripts must use `auth.externalToolPath` and `auth.headerName`; `auth.platformSessionPath` is for the ReachAI console UI only. Do not mix the two origins or copy platform-session URLs into local helper commands.

## Workflow

0. Ensure `scripts/reachai-page-assistant.ps1` exists in the business repository. If missing, download it from `endpoints.scriptDownloadUrl` or extract it from `endpoints.skillPackageUrl` / `scaffold.skillPackageUrl`, then save it to `scaffold.helperScriptPath` (default `scripts/reachai-page-assistant.ps1`).
1. Read the ReachAI page assistant manifest.
2. Ask the user to choose the target page when multiple routes or page components match.
3. Read `pageActionContract.bridgeApi` and implement handlers against `window.__REACHAI_PAGE_BRIDGE__` without guessing invoke protocol.
4. Scaffold the Angular bridge if the business frontend does not already have one using `scaffold.scaffoldCommand`.
5. Register query-first actions: `getPageState`, `setFilters`, `search`, `reset`, `readTable`.
6. Mark high-risk row actions with `confirmRequired=true` and metadata `riskLevel=HIGH`.
7. Run the business frontend build or type check.
8. Register the page through `endpoints.registerPageUrl` so ReachAI can create or reuse the `PAGE_ASSISTANT` Workflow and Agent binding.
9. If the business frontend already has a custom embedded chat service and this task touches it, make sure it executes `data.metadata.pageActionQueue` (preferred) or `data.uiRequest.extension.pageActionRequest` through `window.__REACHAI_PAGE_BRIDGE__.execute(...)` and posts each result to `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`. Do not only render `data.answer`.
10. For component query libraries such as `@zhongruigroup/ngx-query` + `zr-table`, inspect the live query state that the table search actually reads. Do not report PASS if `setFilters` only writes a default template while the real Network request remains unfiltered.
11. Report files, action keys, build/static/browser verification, and remaining blockers.

## Helper Script Delivery

- Direct script URL: `endpoints.scriptDownloadUrl`
- Full skill package: `endpoints.skillPackageUrl`
- Target path: `scaffold.helperScriptPath` (default `scripts/reachai-page-assistant.ps1`)
- Commands: `scaffold.scaffoldCommand`, `scaffold.verifyCommand`
- Header-auth commands expect `REACHAI_AI_CODING_KEY` to be set locally or `-AiCodingKey` to be passed explicitly; the helper sends it as `X-ReachAI-AiCoding-Key`.

## Register files

`files` accepts object evidence or string shorthand:

```json
"files": [
  "src/app/list.component.ts",
  { "path": "src/app/shared/reachai/reachai-page-action.service.ts", "role": "bridge-service" }
]
```

## Verification

- `browser-verify-static`: static catalog/route/action key alignment or local file scan (`static only`)
- `browser-verify-runtime`: authenticated browser + real bridge invoke via `window.__REACHAI_PAGE_BRIDGE__`
- Runtime PASS requires `runtimeVerification` / `verification.browserRuntime` with `invokedActions` and successful `redactedResults`
- FrontendUrl or HTTP 200 alone is WARN, not PASS
- Without FrontendUrl / login / StorageState / Cookie, runtime must be `SKIPPED` or `WARN`, never `PASS`
- Query-flow PASS requires an end-to-end evidence chain, not only handler `SUCCESS`: non-empty `setFilters` args, updated current page filters/state, a real business query request carrying the corresponding query parameter, and `readTable` from the refreshed table.
- Embedded-chat query PASS also requires evidence that the chat response action queue was consumed: a `pageActionQueue` or `pageActionRequest` entry, bridge invokes for the requested action keys, and a POST to `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result` for each request id.
- Helper verify uses Playwright when Node.js + `playwright` are available; optional `-StorageStatePath` for login state
- Helper verify returns structured `failureCode` values such as `PLAYWRIGHT_MISSING`, `JSON_PARSE_ERROR`, `LOGIN_REQUIRED`, and `FRONTEND_UNREACHABLE`; use those codes in the handoff instead of pasting raw stderr as the only diagnosis.
- Default readonly invoke: `getPageState`, `readTable`; never auto-invoke `openRowAction`
- Use `-ProbeMutatingActions` only when explicitly probing `setFilters/search/reset`
- Platform checks append `platformCheck` and do not overwrite Cursor-reported evidence
- String-only `files` shorthand is accepted but marked `HASH_MISSING`; run helper verify for sha256
- Embed sessions snapshot `bridgeActions` when the chat session is created. After adding or renaming actions in the business frontend, refresh the page or recreate the embed session before testing chat-triggered Page Actions.

## References

- `references/page-action-contract.md`
- `references/angular-page-action.md`

## Templates

- `templates/angular/reachai-page-action.types.ts`
- `templates/angular/reachai-page-action.service.ts`
- `templates/angular/page-registry.example.ts`

## Helper Script

- `scripts/reachai-page-assistant.ps1`
