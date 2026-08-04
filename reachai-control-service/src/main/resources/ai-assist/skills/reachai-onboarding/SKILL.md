---
name: reachai-onboarding
description: Integrate Java business systems with ReachAI SDK registration, SDK instance heartbeat, gateway/embed access, and optional API Management handoff. Use when asked to connect a Spring Boot service to ReachAI, add reachai-capability-sdk or reachai-spring-boot2-starter, configure reachai.registry/reachai.project/reachai.capability, prepare @ReachCapability metadata for later manual SDK sync, or verify SDK onboarding from a ReachAI manifest.
---

# ReachAI Onboarding

## Operating Rules

Treat the current business repository as the source of truth. Inspect its Maven modules, Java version, Spring Boot version, configuration files, existing controller/service boundaries, and test commands before editing.

凡是写入 ReachAI 或展示给业务用户的名称、标题、描述、说明、System Prompt、节点名称、审计原因、进度和结果，默认使用清晰的简体中文。不要仅因 API、Schema 或字段名为英文就生成英文业务文案。Token、MCP、AI、Agent、Supervisor、Workflow、Tool、API、SDK 等熟知专业术语，以及 keySlug、toolName、代码、路径、枚举值、协议字段和技术标识可保留英文；必要时使用“中文名称（英文术语）”。不要翻译或改写技术标识。

Never paste, print, or commit the registry app secret. Use the environment variable named by the manifest, normally `REACHAI_REGISTRY_APP_SECRET`.

ReachAI task handoffs use a one-time activation code. Activate it once, keep the returned short-lived task token only in the current process, and call `/api/ai-coding/tasks/{taskId}/**` with `Authorization: Bearer <taskToken>`. Never reuse a project-level `aiCodingKey` on task protocol routes.

Separate project/Workflow AI Coding APIs under `/api/ai-coding/projects/**` and `/api/workflows/**/ai-coding/**` can still use the explicit project `aiCodingKey` when the user independently supplies one. Send it as `X-ReachAI-AiCoding-Key`; never put it in a URL, browser bundle, task artifact, or progress event.

Prefer minimal, reviewable changes:
- Add ReachAI dependencies only to the modules that need them.
- Put `reachai-spring-boot2-starter` in the runnable Spring Boot application module.
- Put `reachai-capability-sdk` in modules that declare `@ReachCapability` methods or DTO field metadata.
- `@ReachCapability` is method-level, `@ReachParam` is parameter/field-level, and `@ReachOutput` is field-only on response DTO fields. Do not put `@ReachOutput` on methods.
- Do not use the ReachAI platform base URL as a Maven repository or npm registry. Manifest/skill/self-check URLs are not Maven/npm repositories.
- Unique recommended Java SDK install (no ReachAI source checkout): read the absolute Java entries in the onboarding manifest's `sdkArtifacts`, expand `{skillExtractDir}` in each `installCommandTemplate`, and run `reachai-capability-sdk` before `reachai-spring-boot2-starter`. The bundled `scripts/install-java-sdk.ps1` downloads the declared JAR and standalone consumer POM, verifies both declared SHA-256 values, and installs that exact coordinate into the business system's Maven local repository. Fail if a URL or hash is absent or mismatched; do not guess another URL and do not require access to the ReachAI repository.
- Unique recommended Embed SDK install (no ReachAI source checkout): read `sdkArtifacts` for `@reachai/embed-chat`, extract this Skill zip anywhere, then run the expanded `installCommandTemplate` from the business frontend directory that contains `package.json`. The bundled `scripts/install-embed-chat.mjs` verifies `integritySha256`, copies the tgz to the stable repo-local `vendor/reachai/` directory, and runs npm with that relative path. Never run `npm install` directly against a temporary Skill extract path, and never leave `%TEMP%`, `.cursor`, `.trae` or another machine-specific absolute path in `package.json` / lockfiles. Authenticated `downloadUrl` needs auth headers that npm cannot send, so prefer this Skill-bundled installer.
- Do not invent dependency download paths such as `/repository/**`, `/maven/**`, `/repository/maven/**`, `/api/embed/sdk`, or `/npm/**`. Do not use `cd ai-admin-front && npm run build:sdk` as the business-project install path.
- Gateway checklist is a top-level `gatewayChecklist` object list on the onboarding manifest (`id`, `description`, `required`, `verificationHint`, `failureImpact`). See `references/java-sdk-access.md`.
- Avoid changing unrelated business logic, package structure, formatting, or dependency versions.
- SDK onboarding must not scan or sync business APIs on application startup. After compile, registration and heartbeat succeed, an active ReachAI `PROJECT_ONBOARDING` task may explicitly trigger exactly one audited SDK sync with `POST <taskRoot>/verifications/SDK_SYNC`; the task token scopes that operation to its own project. The equivalent console action remains API Management（API 管理）手动触发的 SDK 同步. Restrict both paths to business-owned packages and never include framework, platform, third-party, starter, or shared infrastructure controllers as business APIs.

## Workflow

1. If the prompt is a ReachAI task handoff, activate the one-time code and read `GET <taskRoot>/context` first. Otherwise read the explicitly supplied onboarding manifest URL.
2. Download this skill package if it is not already installed, then read the reference files only as needed.
3. Detect the project layout:
   - Maven root and child modules.
   - Java source level.
   - Spring Boot version.
   - Runnable application module.
   - Business-owned Java base packages from application classes, controllers, services, and module names, as the explicit SDK sync boundary.
   - Framework/platform packages that must be excluded from task-scoped or API Management SDK sync.
   - Existing `application.yml`, `bootstrap.yml`, profile-specific config, or config-center conventions.
   - Existing Spring Security, Sa-Token, Shiro, custom login interceptors, CSRF rules, gateway routes, and ingress/firewall boundaries that can affect the inbound SDK sync callback.
4. Resolve and add dependencies using the manifest `sdkArtifacts`, `references/java-sdk-access.md`, and `templates/pom-dependencies.xml`. Platform artifact links are the default when no corporate Maven publication exists.
5. Add configuration using `templates/application-reachai.yml`. Do not add any capability startup-sync setting. Replace package placeholders only when preparing the explicit SDK sync boundary. Set `reachai.project.base-url` to an address reachable from the ReachAI server; use `localhost`, `127.0.0.1`, or `::1` only when ReachAI and the business service actually share the same host or network namespace.
6. Do not scan or sync APIs at application startup. Only when the user explicitly asks to prepare API metadata, select one or two low-risk query-style business methods and annotate them with `@ReachCapability` / `@ReachParam`. Use `templates/reach-capability-example.java` only as a style example.
7. Inspect the business gateway boundary before declaring onboarding complete:
   - Spring Cloud Gateway, Nginx, backend-for-frontend, or front-end dev proxy configuration.
   - Existing authentication headers and current-user extraction.
   - Whether a server-side token broker already exists.
   - Whether ReachAI can send `POST /reachai/registry/capabilities/sync` to the Starter service through the configured `base-url` and `context-path`.
8. Add or update the gateway route/token broker:
   - Route ReachAI capability traffic to the business service and preserve `X-ReachAI-Invocation-Token`, `X-ReachAI-Trace-Id`, `X-ReachAI-Run-Id`, and the business identity headers required by the service.
   - If `reachai.project.base-url` points to a gateway or ingress, route `/reachai/registry/**` to the business service that contains `reachai-spring-boot2-starter`. Preserve `X-ReachAI-App-Key`, `X-ReachAI-Timestamp`, `X-ReachAI-Nonce`, and `X-ReachAI-Signature`.
   - Let `POST /reachai/registry/capabilities/sync` bypass normal business login/JWT filters and CSRF so the request reaches the Starter controller. Apply the equivalent exclusion for Spring Security, Sa-Token, Shiro, or custom interceptors. Do not remove authentication from the endpoint: the Starter must still validate the ReachAI registry signature and return 401 for invalid requests.
   - Treat this callback as server-to-server traffic. CORS is irrelevant; restrict network exposure to the ReachAI service or trusted network when infrastructure supports it.
   - Expose a front-end token endpoint such as `/api/reachai/embed-token`.
   - Implement the token endpoint server-side with the Starter-provided `ReachAiEmbedTokenClient`. Business code maps the current authenticated user to `ReachAiEmbedPrincipal` and forwards the SDK-owned page identity; the client owns project signing, transport, and wrapped `data.token` parsing.
   - Keep `/api/reachai/embed-token` on the normal business login token path. It reads the current business user and exchanges that identity for a ReachAI embed token.
   - Add the gateway authentication whitelist or dedicated security chain for `/api/reachai/embed/**`. This path carries ReachAI embed tokens, so business OAuth/JWT filters must not validate it as a business login token; forward `Authorization: Bearer <embedToken>` unchanged to ReachAI.
   - In Spring Security WebFlux / OAuth2 Resource Server, `permitAll()` on `/api/reachai/embed/**` is not enough by itself: the resource server can still try to authenticate the `Bearer <embedToken>` before routing and return 401. Add a higher-priority `SecurityWebFilterChain` with `securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/reachai/embed/**"))` that permits all and does not enable `oauth2ResourceServer()` for that matcher.
   - Inspect whitelist/anonymous filters that remove or rewrite JWT headers, such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or security filters that call `mutate().header("Authorization", "")`. Do not apply that header-clearing behavior to `/api/reachai/embed/**`; skipping business authentication must still preserve the embed token `Authorization` header.
   - If Spring Cloud Gateway proxies `/api/reachai/embed/**`, dedupe duplicate CORS response headers when both the gateway and ReachAI write them. A typical route filter is `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST`.
   - For a task handoff, use `domainContext.implementationGuidance.projectCopilotKeySlug` as the front-end `agentId`; ReachAI Control owns idempotent Agent/Supervisor provisioning before handoff. Do not request a project key from the user or call project-key provisioning APIs from the task.
   - For an independently authenticated manifest flow, `manifest.agentProvisioning.provisionAgentUrl` remains available to an AI coding tool, local shell, or server-side integration. It is idempotent and creates or reuses the project page copilot Agent, selects an active LLM model, and publishes an ACTIVE AgentScope Supervisor config. It does not create a placeholder Workflow.
   - Write only the supplied project copilot key slug into browser configuration. Never write an internal Agent id or any credential.
   - Create Workflow drafts only for real business capabilities. Validate and publish each Workflow before adding it through `manifest.agentSupervisor.endpoints.workflowToolAttachUrlTemplate`; the attach operation publishes the next Agent config version containing that Workflow-as-Tool.
   - Do not call provisioning from browser runtime code, and do not expose `aiCodingKey` to the business front end.
   - Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.
   - Treat that Agent as the single embedded page copilot entry. AgentScope Supervisor uses the conversation plus page context to select zero, one, or multiple published Workflows from the Agent config's Workflow-as-Tool allow-list.
   - Never move `appSecret` into browser code.
9. Add or update the business front-end integration:
   - Add the ReachAI chat/embed entry in a real business page or shared shell, not only in documentation.
   - Mount the launcher only after the authenticated application shell is ready. Do not initialize it on login, logout, silent-refresh, OAuth callback, or public routes. If authentication is lost, destroy the chat client before redirecting; an unauthenticated Token Broker request from an auth page is a defect, not runtime proof.
   - Do not call `manifest.agentProvisioning.provisionAgentUrl` from browser runtime code. Use the already provisioned bare JSON `agent.keySlug` as `agentId` (not `data.agent.keySlug`).
   - Use `@reachai/embed-chat` for browser embedding when available. Configure `apiBase` as the ReachAI platform origin by default; if the browser uses a gateway prefix, set `embedPathPrefix` such as `/api/reachai/embed`, or set `apiBase` directly to a recognized embed root such as `/api/reachai/embed`.
   - Configure `projectCode`, `agentId`, and a `tokenProvider` that calls the business gateway token broker. Use the already provisioned page copilot Agent `keySlug` for `agentId`.
   - Read `pageKey`, `pageInstanceId`, `route`, and `origin` from the `@reachai/embed-chat` `tokenProvider` context and forward them unchanged through the business token broker. The SDK reuses the same Page Bridge identity for Chat Session creation and page actions.
   - Never generate a fallback `pageInstanceId` in the token provider or business broker. A replacement UUID may make token exchange pass while causing Chat Session or Page Action identity mismatch.
   - Import `@reachai/embed-chat/style.css`, mount one visible global launcher, and keep the SDK's visible-first Token state: Token Broker pending/failure must remain visible and retryable instead of being replaced by a business-side hidden failure.
   - Do not reuse the business login token for ReachAI chat session or message calls. Use the broker-returned short-lived embed token for `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs.
   - Chat message calls must use `POST /api/embed/chat/sessions/{sessionId}/messages` or the `/messages/stream` variant with body `{ "message": "..." }`.
   - Do not send ReachAI chat requests as `{ "content": "..." }`, `{ "text": "..." }`, or `{ "question": "..." }`; map any business UI field to `message` at the ReachAI API boundary.
   - Chat responses are wrapped ApiResult objects. Top-level `code`/`message` describe transport status only; never render top-level `message: "success"` as the assistant reply. Render `data.answer` first, with old-shape fallback only under `data.reply`, `data.message`, or `data.content`.
   - Embed SSE ends with `message.completed`; there is no `done` event.
   - See `references/platform-apis.md` for ApiResult vs bare JSON response shapes and `apiBase` rules.
   - Treat `data.metadata.pageActionQueue` as the preferred UI/Page Action queue. Treat `data.uiRequest` and `data.uiRequest.extension.pageActionRequest` as compatible single-action instructions. Execute them through the page bridge and report each request id back to `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`; do not only render `data.answer`.
   - When the selected business page needs Page Actions, read `references/page-action-contract.md`. For Angular, reuse `references/angular-page-action.md` and `templates/angular/` rather than inventing a second bridge protocol.
   - The optional helper can scaffold the Angular bridge without overwriting existing files:
     `powershell -File scripts/reachai-page-actions.ps1 -Mode scaffold-angular -FrontendRoot <frontend> -PageKey <pageKey>`.
     Adapt the generated registry example to the page's real component methods. Scaffolding is not runtime verification.
   - Run static alignment with
     `powershell -File scripts/reachai-page-actions.ps1 -Mode verify-static -FrontendRoot <frontend> -PageKey <pageKey> -ActionKeys <keys>`.
     A static PASS proves only source alignment; use an authenticated browser and a fresh Embed session before reporting runtime PASS. Use only an existing authorized business-system test session or account supplied outside ReachAI. If none is available, keep `browserVerification` null and report browser acceptance as `NOT RUN`; never fabricate evidence or place login credentials in task artifacts.
   - Cache embed tokens only until before their `expiresIn` boundary. If a session or message request returns `embed token is expired`, clear the cached embed token, call the broker again, and retry once.
10. Run the smallest meaningful verification commands for the touched backend, gateway, and front-end modules.
11. Call the manifest's `sdkAccessCheckUrl` only after local compile/config succeeds, or explain why a live check cannot run.
   - Interpret the platform SDK self-check separately: `CODE_READY` requires observed Starter registration, `RUNTIME_READY` requires a fresh instance heartbeat, and `SDK_CALLBACK_READY` requires a successful signed callback plus a received capability snapshot.
   - For an active task handoff, after `CODE_READY` and `RUNTIME_READY` are observed, explicitly call `POST <taskRoot>/verifications/SDK_SYNC` with the task Bearer token. No body is required. This operation is project-scoped, succeeds only for a `RUNNING` onboarding task, and writes a verification event back to the task.
   - For a non-task manifest flow, use the API Management manual SDK sync action; do not invent project-key or public trigger endpoints.
   - The SDK self-check does not prove browser acceptance. Task readiness `E2E_READY` remains pending until ReachAI observes a real Embed session, user message and assistant reply created after the current task started.
   - A computed SDK sync callback target is not proof of connectivity. The actual task-scoped or API Management sync must distinguish unreachable host/timeout, business-auth or CSRF 401/403, route/context-path 404/405, and Starter signature rejection.
12. For a task handoff, write real `STARTED` / `PROGRESS` events to the current task. Submit blocking ambiguities through `/questions`, poll for the user's answer, then write `RESUMED`. Finish by submitting exactly one artifact matching the JSON Schema in task context; never invent success evidence.
13. Report changed files, commands run, results, SDK sync verification status, the observed SDK sync callback target, its route/login/CSRF handling, optional scan package choices, gateway route/token broker status, front-end integration status, and whether the user-scoped secret still needs to be configured.

## References

- For dependency and Java/Spring guidance, read `references/java-sdk-access.md`.
- For platform API contracts, read `references/platform-apis.md`.
- For credential handling and prompt safety, read `references/security.md`.
- For Page Bridge and action safety, read `references/page-action-contract.md`.
- For Angular Page Action integration, read `references/angular-page-action.md`.
- For ready-to-copy snippets, use files under `templates/`.
- For an optional local verification helper, run `scripts/verify-reachai-access.py`.
- For a prompt-only hidden secret setup, run `scripts/set-reachai-registry-secret.ps1`. It stores the value in the current Windows user environment and never prints it; start a new terminal/process before launching the business service.
- For optional Angular Page Action scaffolding/static alignment, run `scripts/reachai-page-actions.ps1`.

## Output Contract

End with:
- Files changed.
- Dependency/configuration summary.
- Gateway route and embed token broker summary.
- Front-end embed/chat integration summary.
- SDK sync verification/API Management handoff status and any optional capability annotations prepared.
- Verification commands and results.
- Whether `REACHAI_REGISTRY_APP_SECRET` still needs to be configured outside the repository.
- Task id, whether progress/questions were written back, and the submitted artifact key.
