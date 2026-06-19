# ReachAI Platform APIs

The onboarding manifest is the primary contract for AI coding tools.

## Manifest

`GET /api/ai-assist/projects/{projectId}/onboarding-manifest?aiCodingKey={key}`

AI coding tools normally use the `aiCodingKey` URL from the platform prompt. Platform console users may call the same endpoint with their normal login token.

Important fields:
- `project.id`: ReachAI project id.
- `project.projectCode`: stable business project code.
- `project.baseUrl`: business service base URL as known by ReachAI.
- `project.registryAppKey`: app key for signed registration.
- `project.registryCredentialConfigured`: whether ReachAI has a saved credential.
- `aiCodingAccess.enabled`: whether external AI coding access is enabled.
- `aiCodingAccess.accessKey`: the project-level key used only to fetch this manifest; it is not the registry app secret.
- `sdk.dependencies`: Maven coordinates to add.
- `sdk.config.appSecretEnv`: environment variable name for the app secret.
- `endpoints.skillPackageUrl`: zip URL for this skill.
- `endpoints.sdkAccessCheckUrl`: platform self-check endpoint.
- `embed.tokenPath`: the business gateway token broker path the front end should call.
- `embed.defaultAgentKeySlug`: preferred stable Agent identifier for front-end `agentId`.
- `embed.defaultAgentId`: internal Agent id fallback when no key slug is available.
- `embed.allowedAgents`: Agent ids/key slugs exposed by the project's embed policy or project ownership.
- `agentProvisioning.model`: Agent provisioning contract model, normally `agent-provisioning.v1`.
- `agentProvisioning.provisionAgentUrl`: idempotent API for AI coding tools to create or reuse the project page copilot Agent.
- `agentProvisioning.defaultAgentKind`: default Agent kind, normally `PAGE_COPILOT`.
- `agentProvisioning.defaultKeySlug`: predictable page copilot Agent key slug if provisioning has not been called yet.
- `agentWorkflow.model`: decoupled Agent/Workflow manifest model, normally `agent-workflow.decoupled.v1`.
- `agentWorkflow.globalAgentKeySlug`: stable page copilot Agent entry used for Agent/Workflow routing.
- `agentWorkflow.bindingStrategy`: how page/action/intent Workflows are bound to the page copilot Agent.
- `agentWorkflow.endpoints`: platform APIs for managing Agents, Workflows, bindings, and resolve preview.

The manifest does not include `appSecret`.

The manifest also does not declare a Maven repository, npm registry, or browser SDK download endpoint. Do not derive artifact URLs from the platform base URL. In particular, do not request `/repository/**`, `/maven/**`, `/repository/maven/**`, `/api/embed/sdk`, or `/npm/**` from the ReachAI platform. If SDK artifacts are not available from the business repo's existing artifact sources, report the missing artifact source instead of guessing a platform URL.

Before front-end embed work, call `agentProvisioning.provisionAgentUrl` when present. Use the response `agent.keySlug` as the business front-end `agentId`. The call is idempotent, so it is safe for Cursor or another AI coding tool to retry. Do not ask the business user to choose an Agent id. If provisioning is unavailable, fall back to `agentProvisioning.defaultKeySlug`, `agentWorkflow.globalAgentKeySlug`, `embed.defaultAgentKeySlug`, or `embed.defaultAgentId`.

## Agent Provisioning

AI coding tools can create or reuse the project page copilot Agent without requiring manual platform configuration:

```http
POST /api/ai-assist/projects/{projectId}/agents/provision?aiCodingKey={key}
Content-Type: application/json
```

Request body:

```json
{
  "agentKind": "PAGE_COPILOT",
  "ensureDefaultWorkflow": true,
  "requestedBy": "Cursor"
}
```

Response body:

```json
{
  "schema": "agent-provisioning.v1",
  "agent": {
    "id": "agent-001",
    "keySlug": "demo-service-page-copilot",
    "agentKind": "PAGE_COPILOT"
  },
  "defaultWorkflow": {
    "id": "workflow-001",
    "keySlug": "demo-service-page-copilot-default",
    "workflowType": "PAGE_COPILOT_DEFAULT"
  },
  "defaultBinding": {
    "id": 1,
    "bindingType": "DEFAULT"
  },
  "createdAgent": true,
  "createdDefaultWorkflow": true,
  "createdDefaultBinding": true
}
```

Use `agent.keySlug` everywhere the business gateway token broker or front-end embed SDK asks for `agentId`.

## Embed Token Exchange

Business front ends must not call this endpoint directly because it requires project credentials. Implement a business gateway or server-side token broker that signs this request.

ReachAI endpoint:

```http
POST /api/embed/token/exchange
```

Minimum request shape:

```json
{
  "projectCode": "demo-service",
  "agentId": "<provisionedAgentKeySlug>",
  "pageKey": "teamArchive.list",
  "pageInstanceId": "page-001",
  "route": "/teams",
  "origin": "http://localhost:5173",
  "principal": {
    "externalUserId": "user-001",
    "globalUserId": "employee-001",
    "displayName": "Demo User",
    "roles": ["TEAM_ADMIN"]
  }
}
```

The business gateway must sign the platform request with the same project credential mechanism used by SDK registration. `principal.externalUserId` is required. Return only the issued token and expiry metadata to the browser.

Business front-end flow:

1. Generate or reuse a stable page instance id for the current page.
2. Determine the stable current page key, for example `teamArchive.list`.
3. Call the business gateway token broker, for example `/api/reachai/embed-token`, with the normal business login token and include `agentId=<provisionedAgentKeySlug>`, `pageKey`, `pageInstanceId`, `route`, and `origin`.
4. Use the returned embed token as `Authorization: Bearer <token>` for ReachAI chat session and message APIs.
5. Create the chat session with the same `pageKey`. The ReachAI SDK does this when `createEafChat({ page: { pageKey, routePattern } })` is configured.
6. Never send `appSecret` or project signing material to the browser.

Token boundary note: the business login token and the ReachAI embed token are different credentials. The business login token belongs only on the token broker request. `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs must use the short-lived embed token returned by the broker.

Gateway authentication note: `/api/reachai/embed-token` should use the business login token, but `/api/reachai/embed/**` must forward the ReachAI embed token unchanged. Do not let business OAuth/JWT filters validate `/api/reachai/embed/**` as a business login request. Also check whitelist filters that remove login JWTs, such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or code that calls `mutate().header("Authorization", "")`; those filters must not clear the embed-token `Authorization` header on `/api/reachai/embed/**`.

Spring Security WebFlux note: `.pathMatchers("/api/reachai/embed/**").permitAll()` alone may still fail when OAuth2 Resource Server is enabled, because the authentication layer can parse `Authorization: Bearer <embedToken>` before authorization and return 401 for an invalid business JWT. Add a higher-priority security matcher / `SecurityWebFilterChain` for `/api/reachai/embed/**` that permits all and does not enable business `oauth2ResourceServer()` for that path.

Gateway CORS note: when Spring Cloud Gateway proxies `/api/reachai/embed/**` to ReachAI `/api/embed/**`, both the gateway and ReachAI may add CORS headers. Duplicate `Access-Control-Allow-Origin` or `Access-Control-Allow-Credentials` values can make browsers hide the real response as `status 0 Unknown Error`. Add a route-level dedupe filter such as:

```yaml
filters:
  - RewritePath=/api/reachai/embed/(?<segment>.*), /api/embed/${segment}
  - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST
```

Embed token cache note: front ends may cache the broker-returned embed token, but must expire it before `expiresIn`. If a chat session or message request returns `embed token is expired`, clear the cached embed token, call the broker again, and retry once.

## Self-Check

`POST /api/scan-projects/{projectId}/sdk-access-check`

Example body:

```json
{
  "apiAssetId": 123,
  "args": {},
  "gatewayBaseUrl": "http://localhost:8080",
  "embedTokenPath": "/api/reachai/embed-token"
}
```

Run this only after the business service compiles and has a reachable local or test instance.

Set `gatewayBaseUrl` to the real business gateway entry when available. Set `embedTokenPath` to the business gateway token broker path that the front end will call.

## AI Access Session Progress

ReachAI can show onboarding progress in the platform page when the prompt includes an access session id or URL.

Read the current session:

```http
GET /api/ai-assist/projects/{projectId}/access-sessions/latest?aiCodingKey={key}
```

Report one step:

```http
POST /api/ai-assist/projects/{projectId}/access-sessions/{sessionId}/steps/{stepKey}/report?aiCodingKey={key}
Content-Type: application/json
```

Request body:

```json
{
  "status": "PASS",
  "message": "Gateway whitelist has been configured.",
  "files": ["qmssmp-gateway/src/main/java/SecurityConfiguration.java"],
  "evidence": {
    "command": "mvn -DskipTests compile",
    "exitCode": 0
  },
  "reportedBy": "Cursor"
}
```

Valid `status` values are `TODO`, `RUNNING`, `PASS`, `WARN`, `FAIL`, and `SKIPPED`. Use `WARN` when a step needs human configuration such as an environment variable or external config center change.

Standard `stepKey` values:
- `project-manifest`
- `backend-sdk`
- `reachai-config`
- `capability-scan`
- `gateway-route`
- `embed-token-broker`
- `gateway-whitelist`
- `frontend-embed`
- `connectivity-check`
- `handoff-summary`

Run the platform self-check and write the result into the session:

```http
POST /api/ai-assist/projects/{projectId}/access-sessions/{sessionId}/checks/run
```

Use the same body as `sdkAccessCheckUrl`. This endpoint requires normal platform login when called from the console. External AI tools should use the report endpoint for step progress and call the check endpoint only when the prompt explicitly provides credentials or a reachable authenticated context.

## Tool Reconcile

`POST /api/scan-projects/{projectId}/tools/reconcile`

Use after SDK startup/sync when the platform has received capability snapshots and the user wants API catalog rows reconciled.

## Future Extension Points

Do not assume these exist unless the manifest exposes them:
- MCP tool endpoints.
- One-time secret token endpoints.
- Deployment or production rollout endpoints.
