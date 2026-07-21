---
name: reachai-onboarding
description: Integrate Java business systems with ReachAI SDK registration, SDK instance heartbeat, gateway/embed access, and optional API Management handoff. Use when asked to connect a Spring Boot service to ReachAI, add reachai-capability-sdk or reachai-spring-boot2-starter, configure reachai.registry/reachai.project/reachai.capability, prepare @ReachCapability metadata for later manual SDK sync, or verify SDK onboarding from a ReachAI manifest.
---

# ReachAI Onboarding

## Operating Rules

Treat the current business repository as the source of truth. Inspect its Maven modules, Java version, Spring Boot version, configuration files, existing controller/service boundaries, and test commands before editing.

Never paste, print, or commit the registry app secret. Use the environment variable named by the manifest, normally `REACHAI_REGISTRY_APP_SECRET`.

Platform AI Coding project APIs under `/api/ai-coding/projects/**` use the project `aiCodingKey`, not platform Bearer login. Send it as `X-ReachAI-AiCoding-Key`; do not add `aiCodingKey` to URLs generated from the platform prompt. Manifests do not echo the raw key in returned URLs; call returned platform URLs with the same header.

Prefer minimal, reviewable changes:
- Add ReachAI dependencies only to the modules that need them.
- Put `reachai-spring-boot2-starter` in the runnable Spring Boot application module.
- Put `reachai-capability-sdk` in modules that declare `@ReachCapability` methods or DTO field metadata.
- `@ReachCapability` is method-level, `@ReachParam` is parameter/field-level, and `@ReachOutput` is field-only on response DTO fields. Do not put `@ReachOutput` on methods.
- Do not use the ReachAI platform base URL as a Maven repository or npm registry. Manifest/skill/self-check URLs are not Maven/npm repositories.
- Unique recommended Embed SDK install (no ReachAI source checkout): read `sdkArtifacts` for `@reachai/embed-chat` (`packageName`, `version`, `downloadUrl`, `integritySha256`, `installCommand` / `installCommandTemplate`, `fallbackPolicy`, `artifactPathWithinSkill`, `installWorkingDirectory`). Extract this skill zip anywhere (may be outside the business repo). From the business frontend directory that contains `package.json`, run the expanded install command, typically `npm install "{skillExtractDir}/reachai-onboarding/artifacts/reachai-embed-chat-1.0.0-SNAPSHOT.tgz"`. Do not rely on a fixed `./artifacts` relative path. Authenticated `downloadUrl` needs platform/AI Coding auth headers; npm cannot send them, so prefer the skill-bundled tarball.
- Do not invent dependency download paths such as `/repository/**`, `/maven/**`, `/repository/maven/**`, `/api/embed/sdk`, or `/npm/**`. Do not use `cd ai-admin-front && npm run build:sdk` as the business-project install path.
- Gateway checklist is a top-level `gatewayChecklist` object list on the onboarding manifest (`id`, `description`, `required`, `verificationHint`, `failureImpact`). See `references/java-sdk-access.md`.
- Avoid changing unrelated business logic, package structure, formatting, or dependency versions.
- SDK onboarding must not scan or sync business APIs automatically. SDK interface scan/sync is manually triggered from ReachAI API Management（API 管理）after onboarding；中文产品口径是“API 管理手动触发 SDK 同步”。 If you prepare scan boundaries for that later manual sync, restrict ReachAI to business-owned packages and never include framework, platform, third-party, starter, or shared infrastructure controllers as business APIs.

## Workflow

1. Read the ReachAI onboarding manifest URL from the user prompt.
2. Download this skill package if it is not already installed, then read the reference files only as needed.
3. Detect the project layout:
   - Maven root and child modules.
   - Java source level.
   - Spring Boot version.
   - Runnable application module.
   - Business-owned Java base packages from application classes, controllers, services, and module names, only as the future API Management manual SDK sync boundary.
   - Framework/platform packages that should be excluded if API Management manual SDK sync is later used.
   - Existing `application.yml`, `bootstrap.yml`, profile-specific config, or config-center conventions.
   - Existing Spring Security, Sa-Token, Shiro, custom login interceptors, CSRF rules, gateway routes, and ingress/firewall boundaries that can affect the inbound SDK sync callback.
4. Add dependencies using `references/java-sdk-access.md` and `templates/pom-dependencies.xml`.
5. Add configuration using `templates/application-reachai.yml`. Do not add any capability startup-sync setting. Replace package placeholders only when preparing the later API Management manual SDK sync boundary. Set `reachai.project.base-url` to an address reachable from the ReachAI server; use `localhost`, `127.0.0.1`, or `::1` only when ReachAI and the business service actually share the same host or network namespace.
6. Do not scan or sync APIs during SDK onboarding. Only when the user explicitly asks to prepare API metadata, select one or two low-risk query-style business methods and annotate them with `@ReachCapability` / `@ReachParam`. Use `templates/reach-capability-example.java` only as a style example.
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
   - Implement the token endpoint server-side so it maps the current business user to `principal.externalUserId`, signs the platform call with the project `appKey/appSecret`, and calls ReachAI `POST /api/embed/token/exchange`.
   - Keep `/api/reachai/embed-token` on the normal business login token path. It reads the current business user and exchanges that identity for a ReachAI embed token.
   - Add the gateway authentication whitelist or dedicated security chain for `/api/reachai/embed/**`. This path carries ReachAI embed tokens, so business OAuth/JWT filters must not validate it as a business login token; forward `Authorization: Bearer <embedToken>` unchanged to ReachAI.
   - In Spring Security WebFlux / OAuth2 Resource Server, `permitAll()` on `/api/reachai/embed/**` is not enough by itself: the resource server can still try to authenticate the `Bearer <embedToken>` before routing and return 401. Add a higher-priority `SecurityWebFilterChain` with `securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/reachai/embed/**"))` that permits all and does not enable `oauth2ResourceServer()` for that matcher.
   - Inspect whitelist/anonymous filters that remove or rewrite JWT headers, such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or security filters that call `mutate().header("Authorization", "")`. Do not apply that header-clearing behavior to `/api/reachai/embed/**`; skipping business authentication must still preserve the embed token `Authorization` header.
   - If Spring Cloud Gateway proxies `/api/reachai/embed/**`, dedupe duplicate CORS response headers when both the gateway and ReachAI write them. A typical route filter is `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST`.
   - Before front-end embed work, call `manifest.agentProvisioning.provisionAgentUrl` from the AI coding tool, local shell, or server-side integration step when present. Send `X-ReachAI-AiCoding-Key` with the same project key used to fetch the manifest. It is idempotent and creates or reuses the project page copilot Agent, selects an active LLM model, and publishes an ACTIVE AgentScope Supervisor config. It does not create a placeholder Workflow.
   - Use the provisioning response `agent.keySlug` as the front-end `agentId`; write only that key slug into browser configuration. Fall back to `manifest.agentProvisioning.defaultKeySlug`, `manifest.agentSupervisor.globalAgentKeySlug`, or `manifest.embed.defaultAgentKeySlug` only when the provisioning API is unavailable.
   - Create Workflow drafts only for real business capabilities. Validate and publish each Workflow before adding it through `manifest.agentSupervisor.endpoints.workflowToolAttachUrlTemplate`; the attach operation publishes the next Agent config version containing that Workflow-as-Tool.
   - Do not call provisioning from browser runtime code, and do not expose `aiCodingKey` to the business front end.
   - Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.
   - Treat that Agent as the single embedded page copilot entry. AgentScope Supervisor uses the conversation plus page context to select zero, one, or multiple published Workflows from the Agent config's Workflow-as-Tool allow-list.
   - Never move `appSecret` into browser code.
9. Add or update the business front-end integration:
   - Add the ReachAI chat/embed entry in a real business page or shared shell, not only in documentation.
   - Do not call `manifest.agentProvisioning.provisionAgentUrl` from browser runtime code. Use the already provisioned bare JSON `agent.keySlug` as `agentId` (not `data.agent.keySlug`).
   - Use `@reachai/embed-chat` for browser embedding when available. Configure `apiBase` as the ReachAI platform origin by default; if the browser uses a gateway prefix, set `embedPathPrefix` such as `/api/reachai/embed`, or set `apiBase` directly to a recognized embed root such as `/api/reachai/embed`.
   - Configure `projectCode`, `agentId`, and a `tokenProvider` that calls the business gateway token broker. Use the already provisioned page copilot Agent `keySlug` for `agentId`.
   - Pass the same stable `pageKey`, `pageInstanceId`, `route`, and `origin` through token broker, `createEafChat({ page })`, and page actions.
   - Import `@reachai/embed-chat/style.css`, mount one visible global launcher, and keep the SDK's visible-first Token state: Token Broker pending/failure must remain visible and retryable instead of being replaced by a business-side hidden failure.
   - Do not reuse the business login token for ReachAI chat session or message calls. Use the broker-returned short-lived embed token for `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs.
   - Chat message calls must use `POST /api/embed/chat/sessions/{sessionId}/messages` or the `/messages/stream` variant with body `{ "message": "..." }`.
   - Do not send ReachAI chat requests as `{ "content": "..." }`, `{ "text": "..." }`, or `{ "question": "..." }`; map any business UI field to `message` at the ReachAI API boundary.
   - Chat responses are wrapped ApiResult objects. Top-level `code`/`message` describe transport status only; never render top-level `message: "success"` as the assistant reply. Render `data.answer` first, with old-shape fallback only under `data.reply`, `data.message`, or `data.content`.
   - Embed SSE ends with `message.completed`; there is no `done` event.
   - See `references/platform-apis.md` for ApiResult vs bare JSON response shapes and `apiBase` rules.
   - Treat `data.metadata.pageActionQueue` as the preferred UI/Page Action queue. Treat `data.uiRequest` and `data.uiRequest.extension.pageActionRequest` as compatible single-action instructions. Execute them through the page bridge and report each request id back to `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`; do not only render `data.answer`.
   - Cache embed tokens only until before their `expiresIn` boundary. If a session or message request returns `embed token is expired`, clear the cached embed token, call the broker again, and retry once.
10. Run the smallest meaningful verification commands for the touched backend, gateway, and front-end modules.
11. Call the manifest's `sdkAccessCheckUrl` only after local compile/config succeeds, including `gatewayBaseUrl` and `embedTokenPath`, or explain why a live check cannot run.
   - Interpret `CODE_READY`, `RUNTIME_READY`, and `E2E_READY` separately when they are returned. `CODE_READY` can pass while `RUNTIME_READY` or `E2E_READY` remains WARN because the business service is not running with `REACHAI_REGISTRY_APP_SECRET`, SDK heartbeat has not arrived, optional API Management manual sync has not run, or no real embed/API invocation was selected.
   - A computed SDK sync callback target is not proof of connectivity. The actual API Management manual sync must distinguish unreachable host/timeout, business-auth or CSRF 401/403, route/context-path 404/405, and Starter signature rejection.
12. If the prompt or manifest provides an access session URL under `/api/ai-coding/projects/{projectId}/access-sessions/...`, report progress after each major step. Use step keys such as `project-manifest`, `backend-sdk`, `reachai-config`, `api-management-handoff`, `gateway-route`, `embed-token-broker`, `gateway-whitelist`, `frontend-embed`, `connectivity-check`, and `handoff-summary`.
13. Report changed files, commands run, results, API Management handoff status, the computed SDK sync callback target, its route/login/CSRF handling, optional scan package choices, gateway route/token broker status, front-end integration status, and manual secrets still required.

## References

- For dependency and Java/Spring guidance, read `references/java-sdk-access.md`.
- For platform API contracts, read `references/platform-apis.md`.
- For credential handling and prompt safety, read `references/security.md`.
- For ready-to-copy snippets, use files under `templates/`.
- For an optional local verification helper, run `scripts/verify-reachai-access.py`.

## Output Contract

End with:
- Files changed.
- Dependency/configuration summary.
- Gateway route and embed token broker summary.
- Front-end embed/chat integration summary.
- API Management handoff status and any optional capability annotations prepared.
- Verification commands and results.
- Whether `REACHAI_REGISTRY_APP_SECRET` still needs to be configured outside the repository.
- Whether access session progress was reported to `/api/ai-coding/.../access-sessions/...`.
