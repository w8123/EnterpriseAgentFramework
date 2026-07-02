# Java SDK Access

Use this reference after reading the onboarding manifest.

## Platform Response Shapes

| API family | `ApiResult`? | Read from |
| --- | --- | --- |
| Embed: token exchange, sessions, messages, page-actions | Yes | `data.xxx` |
| Agent provisioning | No | `agent.keySlug` |
| Access sessions, sdk-access-check, onboarding-manifest | No | top-level fields |

Do not default every platform API to `data.` or to top-level fields. See `references/platform-apis.md` for details.

## Module Placement

- Add `reachai-spring-boot2-starter` to the runnable Spring Boot application module.
- Add `reachai-capability-sdk` to every module that declares `@ReachCapability`, `@ReachParam`, or response DTO fields annotated with `@ReachOutput`.
- In a single-module service, both dependencies usually go into the root `pom.xml`.
- In a multi-module service, do not add the starter to pure API, DTO, or library modules.

## Maven Dependencies

Use the versions from the manifest. If no manifest version is available, use the platform-provided template.

## Maven Artifact Resolution

Do not use the ReachAI platform base URL as a Maven repository. The platform URLs in the onboarding prompt and manifest are API endpoints, not artifact repositories.

Do not add repository URLs or probe paths such as:
- `/repository/**`
- `/maven/**`
- `/repository/maven/**`

Resolve `reachai-capability-sdk` and `reachai-spring-boot2-starter` from one of these sources:
- The business repository's existing corporate Maven repository or dependency-management setup.
- A Maven repository where the ReachAI artifacts have already been published.
- The local Maven repository after running, in the ReachAI repository, `mvn -pl reachai-spring-boot2-starter -am install -DskipTests`.

If the artifacts still cannot be resolved, stop and report that the Java artifacts must be installed or published. Do not invent platform download URLs.

The same rule applies to front-end packages: do not fetch a browser SDK from `/api/embed/sdk`, `/npm/**`, or any other guessed ReachAI platform path. Use an installed package, an already built local SDK artifact, or implement against the documented embed HTTP contract and report the packaging gap.

```xml
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-capability-sdk</artifactId>
  <version>${reachai.sdk.version}</version>
</dependency>
<dependency>
  <groupId>com.enterprise.ai</groupId>
  <artifactId>reachai-spring-boot2-starter</artifactId>
  <version>${reachai.sdk.version}</version>
</dependency>
```

Prefer an existing dependency-management pattern if the business repo already centralizes versions.

## Spring Configuration

Add `reachai.registry`, `reachai.project`, and `reachai.capability` configuration to the active service configuration. Keep the app secret as an environment variable.

The starter scans Spring beans for `@ReachCapability`, registers the project and instance, sends heartbeat data, and syncs capability snapshots on startup when enabled.

## Scan Boundary

ReachAI should sync business APIs, not every controller visible in the Spring application context.

Before writing `reachai.capability` configuration:
- Identify the runnable application's real business package from the `@SpringBootApplication` class, business controller/service packages, and Maven module names.
- If the application class sits in a broad platform root package, do not use that root as the scan package. Choose narrower business packages instead.
- Configure `reachai.capability.scan-packages` with business-owned packages only.
- Configure `reachai.capability.exclude-packages` for framework, platform, starter, generated, and third-party packages.
- If the business package cannot be determined confidently, stop and ask the user to choose from the candidate packages.

Recommended examples:

```yaml
reachai:
  capability:
    scan-beans: true
    scan-packages:
      - com.company.order
      - com.company.customer
    exclude-packages:
      - org.springframework
      - springfox
      - org.springdoc
      - com.enterprise.ai.reach
      - com.company.framework
      - com.company.platform
```

Do not set `scan-packages` to generic roots such as `com`, `com.company`, or a platform framework root unless the user explicitly confirms that all controllers under that root are business APIs.

## Capability Selection

Start with low-risk read-only methods:
- Query by id/code/name.
- List or search methods with simple request DTOs.
- Methods already exposed through a controller and covered by tests.

Avoid first-pass annotations on:
- Payment, deletion, approval, or mutation methods.
- Methods with unclear permissions or tenant boundaries.
- Methods that require interactive captcha, file upload, or long-running transactions.

## Annotation Style

Use stable capability names such as `contract.query` or `team.search`.

Always provide explicit `@ReachParam(name = "...")` for simple parameters, especially in JDK8 projects where compiler parameter names may not be retained.

For request DTOs, annotate important fields with `@ReachParam` so generated parameter metadata is useful for agents.

Use `@ReachOutput` only on response DTO fields that should be visible to later workflow nodes or agent reasoning. Do not put `@ReachOutput` on controller or service methods.

## Self-Check Readiness Levels

The SDK access check may return both detailed `checks` and summarized `readiness` levels:

- `CODE_READY`: manifest, dependencies/configuration, registry credentials, gateway base URL, and token broker path are present.
- `RUNTIME_READY`: the business service has started with `REACHAI_REGISTRY_APP_SECRET`, SDK heartbeat is online, and API assets have synced.
- `E2E_READY`: a selected API asset or embed chat/token-broker path has completed a real invocation.

`CODE_READY` can pass while `RUNTIME_READY` or `E2E_READY` remains WARN. Treat that as "code/config looks ready but runtime has not proven itself yet", not as an automatic integration failure.

## Gateway Route And Token Broker

SDK onboarding is not complete if the business gateway is untouched and the business front end has no safe token path.

Before changing gateway or front-end code, read the manifest's `embed` section:
- Call `agentProvisioning.provisionAgentUrl` from the AI coding tool, local shell, or server-side integration step when present. It creates or reuses the project `PAGE_COPILOT` Agent and default Workflow binding.
- Use the response `agent.keySlug` as the front-end `agentId`. Store only that key slug in business front-end configuration after provisioning succeeds.
- If the response includes `defaultWorkflow.id`, use Workflow AI Coding to draw or adjust the default Workflow, save a valid draft, then call `POST /api/workflows/{workflowId}/ai-coding/publish` once with `X-ReachAI-AiCoding-Key` to create the initial ACTIVE version before declaring embed chat ready.
- Do not call `agentProvisioning.provisionAgentUrl` from browser runtime code, and never put `aiCodingKey` in browser configuration or bundles.
- If provisioning is unavailable, prefer `agentProvisioning.defaultKeySlug`, then `agentWorkflow.globalAgentKeySlug`, then `embed.defaultAgentKeySlug`.
- Use `embed.defaultAgentId` only when no key slug is available.
- Treat `embed.allowedAgents` as the platform-approved list. Do not invent a new `agentId` in the business repo.
- Do not ask the business user to manually create, choose, or configure the page copilot Agent during SDK onboarding.

Inspect the repository for the real gateway boundary:
- Spring Cloud Gateway `application.yml` / `bootstrap.yml` routes.
- Nginx or ingress configuration.
- Backend-for-frontend routes.
- Vite, Angular, or Webpack dev proxy used by the business UI.

Add the smallest route changes required for the current architecture:
- ReachAI invocation traffic must reach the business service endpoint that exposes SDK capabilities.
- Preserve `X-ReachAI-Invocation-Token`, `X-ReachAI-Trace-Id`, `X-ReachAI-Run-Id`, and the business authentication headers required by the service.
- Do not trust ordinary context headers such as `X-ReachAI-*` as the only authorization signal for business APIs.
- If no gateway module exists in the current repository, report this as a required external change instead of inventing a browser-side workaround.

Add the gateway authentication whitelist required by embed chat:
- Keep the token broker path, for example `/api/reachai/embed-token`, behind the business login token because it maps the current user to `principal.externalUserId`.
- Route `/api/reachai/embed/**` to ReachAI `/api/embed/**` as an anonymous proxy or with a dedicated security chain. Browser calls to this path use `Authorization: Bearer <embedToken>`, and business OAuth/JWT filters must not parse or reject that token.
- If the gateway has a global authentication filter, add an explicit skip for `/api/reachai/embed/**` while preserving the `Authorization`, `Origin`, and `Content-Type` headers.
- Inspect any existing whitelist or anonymous-path filter that removes JWT headers, including names such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or code that calls `mutate().header("Authorization", "")`. Do not let those filters clear `Authorization` on `/api/reachai/embed/**`; the path is anonymous only with respect to business login validation, not with respect to ReachAI embed-token forwarding.
- In Spring Security WebFlux / OAuth2 Resource Server, do not rely only on `.pathMatchers("/api/reachai/embed/**").permitAll()`. Resource Server authentication can still consume the `Authorization: Bearer <embedToken>` header before authorization rules and reject it as an invalid business JWT. Add a higher-priority security chain dedicated to the proxy path, and do not enable business `oauth2ResourceServer()` on that chain, for example:

```java
@Bean
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
SecurityWebFilterChain reachAiEmbedProxySecurity(ServerHttpSecurity http) {
    return http.securityMatcher(ServerWebExchangeMatchers.pathMatchers("/api/reachai/embed/**"))
            .authorizeExchange()
            .anyExchange().permitAll()
            .and()
            .csrf().disable()
            .build();
}
```

- In Spring Cloud Gateway this usually means both a route rewrite and a gateway authentication whitelist/security matcher. If both the gateway and ReachAI add CORS response headers, add route-level dedupe such as `DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST` so the browser does not hide the real 401/500 response as `status 0 Unknown Error`.
- In Nginx or a BFF, apply the same auth bypass and CORS de-duplication rule at the real authentication/proxy boundary.

### Gateway checklist

For Spring Cloud Gateway + WebFlux + OAuth2 Resource Server, verify these five items before marking the embed gateway work complete:

1. The token broker path, usually `/api/reachai/embed-token`, stays behind the business login and exchanges the current user for a short-lived ReachAI embed token.
2. `/api/reachai/embed/**` or the chosen proxy path rewrites to ReachAI `/api/embed/**` and forwards `Authorization: Bearer <embedToken>` intact.
3. Header-cleaning filters such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, or `RemoveRequestHeader=Authorization` do not clear `Authorization` on the embed proxy path.
4. A high-priority WebFlux security chain matches the embed proxy path and does not enable the business `oauth2ResourceServer()` parser on that chain.
5. Gateway and ReachAI CORS headers are de-duplicated so the browser exposes the real 401/500 response body.

### Local dev topology

Common local topology:

```text
front end :9200 -> business gateway :8080 (token broker + business APIs) -> ReachAI :18603 (embed APIs)
```

`gatewayBaseUrl` is the business gateway origin used by the business UI. `api.v2.baseUrl` or Chat `apiBase` may still point directly to ReachAI, for example `http://localhost:18603`, when the browser is allowed to call ReachAI `/api/embed/**` directly. Do not assume these two URLs are always the same.

Expose a server-side embed token broker for the business UI, for example:

```http
GET /api/reachai/embed-token?projectCode=demo-service&agentId=<provisionedAgentKeySlug>&pageKey=teamArchive.list&pageInstanceId=page-001&route=/teams&origin=http://localhost:5173
```

The broker must:
- Read the current business login state.
- Map the current user to ReachAI `principal`, with `principal.externalUserId` required.
- Use the project `appKey/appSecret` server-side to sign a call to ReachAI `POST /api/embed/token/exchange`.
- Forward `pageKey`, `pageInstanceId`, `route`, and `origin` into the token exchange body.
- Parse the wrapped ReachAI `ApiResult` response and read `data.token` / `data.expiresIn`; a fallback for top-level `token` / `expiresIn` is fine, but do not only read top-level fields.
- Add a mock test or local assertion for `{"code":200,"message":"success","data":{"token":"jwt","expiresIn":600}}` and verify the broker returns that token to the browser.
- Cache only short-lived embed tokens when appropriate.
- Return only the issued embed token and expiry metadata to the browser.

Never place `appSecret`, registry signatures, or project-level private keys in browser code.
Never use the business login token as the chat session token. The business login token is only for calling the broker; `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs must receive the broker-returned ReachAI embed token.
Chat message calls must use `POST /api/embed/chat/sessions/{sessionId}/messages` or the `/messages/stream` variant with body `{ "message": "..." }`.
Do not send ReachAI chat requests as { "content": "..." }, { "text": "..." }, or { "question": "..." }; map any business UI field to `message` at the ReachAI API boundary.
Chat responses are wrapped ApiResult objects. Top-level `code`/`message` describe transport status only; never render top-level `message: "success"` as the assistant reply. Render `data.answer` first, with old-shape fallback only under `data.reply`, `data.message`, or `data.content`.
Treat `data.metadata.pageActionQueue` as the preferred UI/Page Action queue. Treat `data.uiRequest` and `data.uiRequest.extension.pageActionRequest` as compatible single-action instructions. Execute them through the current page bridge and report each request id back to `/api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result`; do not only render `data.answer`.

Page Action result posted to Embed API: platform DTO accepts `status` string and `error` string. Bridge handlers may use richer internal statuses (`FAILED`, `CANCELLED`, ...); map to `SUCCESS` or an appropriate string `status`/`error` at the API boundary. See `references/platform-apis.md` for Embed vs bare JSON response shapes.

## Front-End Embed Integration

Add the ReachAI chat/embed entry in the real business front end when that front end is in scope. Do not stop at backend annotations if a UI module is present.

Use a token provider that calls the business gateway token broker. Before adding runtime front-end code, call Agent provisioning from the AI coding tool, local shell, or server-side integration step; use `agent.keySlug` from the bare JSON response (not `data.agent.keySlug`) as browser configuration:

```ts
// Set this from the provisioning response's top-level agent.keySlug.
// Do not call the provisioning API from browser runtime code or expose aiCodingKey.
const provisionedAgentKeySlug = '<provisioned-agent-key-slug>'

const tokenProvider = async () => {
  const query = new URLSearchParams({
    projectCode: 'demo-service',
    agentId: provisionedAgentKeySlug,
    pageKey: 'teamArchive.list',
    pageInstanceId,
    route: window.location.pathname,
    origin: window.location.origin,
  })
  const response = await fetch('/api/reachai/embed-token?' + query)
  const payload = await response.json()
  if (payload.code && payload.code !== 200 && payload.code !== 0) {
    throw new Error(payload.message || 'embed token broker failed')
  }
  const token = payload.data?.token || payload.token
  if (!token) throw new Error('ReachAI embed token missing')
  return token
}
```

The front end should pass `pageKey`, `pageInstanceId`, `route`, and `origin` so ReachAI can bind chat sessions, audit events, and page actions to the exact page instance. Use the same values in token exchange and session create.

When creating the global chat entry, pass the same page descriptor to the SDK so the chat session can resolve the page Workflow:

```ts
createEafChat({
  mount: '#reachai-chat',
  apiBase: 'http://localhost:18603',
  agentId: provisionedAgentKeySlug,
  tokenProvider,
  page: {
    pageKey: 'teamArchive.list',
    routePattern: window.location.pathname,
  },
})
```

`apiBase` must be the ReachAI platform origin (or a gateway host that exposes **`/api/embed/**`**). The SDK requests `${apiBase}/api/embed/chat/sessions`. **Do not** set `apiBase: '/api/reachai/embed'`; that becomes `/api/reachai/embed/api/embed/...`. If the gateway only proxies `/api/reachai/embed/**`, add a `/api/embed/**` route or keep `apiBase` as the ReachAI origin for direct browser access.

Do not create one chat button per Workflow. Keep one global embedded AI button and let ReachAI resolve the page/action/intent Workflow from `pageKey` and `ai_agent_workflow_binding`.

When caching embed tokens in the front end:
- Compute the cache expiry from `expiresIn` and expire slightly early.
- Do not impose a minimum cache time that can outlive a short token TTL.
- If creating a session or sending a message returns `embed token is expired`, clear the cached token, call the broker again, and retry once.
- Surface the real platform error body when a retry still fails; do not collapse every failure into a generic `Unknown Error`.

## Access Session Progress Reporting

When the ReachAI prompt provides `/api/ai-coding/projects/{projectId}/access-sessions/...`, report progress back to the platform instead of only summarizing in chat.

Recommended reporting points:
- `project-manifest`: manifest fetched and parsed.
- `backend-sdk`: Maven dependencies added to the correct modules.
- `reachai-config`: `reachai.registry`, `reachai.project`, and `reachai.capability` configured with narrow package scans.
- `capability-scan`: sample capabilities annotated or MVC scanning configured.
- `gateway-route`: capability invocation route configured.
- `embed-token-broker`: server-side token broker implemented.
- `gateway-whitelist`: `/api/reachai/embed/**` bypasses business OAuth/JWT filters and forwards the embed token.
- `frontend-embed`: real business front-end page or shell uses the broker-backed token provider.
- `connectivity-check`: compile/build and ReachAI self-check attempted.
- `handoff-summary`: final changed-file list, verification results, and remaining manual secrets/config.

Example:

```bash
curl -X POST "$REPORT_URL" \
  -H "Content-Type: application/json" \
  -d '{"status":"PASS","message":"Gateway whitelist added.","files":["gateway/src/main/java/SecurityConfiguration.java"],"evidence":{"command":"mvn -DskipTests compile","exitCode":0},"reportedBy":"Cursor"}'
```
