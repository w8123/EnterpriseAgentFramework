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
- `agentWorkflow.model`: decoupled Agent/Workflow manifest model, normally `agent-workflow.decoupled.v1`.
- `agentWorkflow.globalAgentKeySlug`: preferred stable global embedded Agent entry. Use this before `embed.defaultAgentKeySlug` when present.
- `agentWorkflow.bindingStrategy`: how page/action/intent Workflows are bound to the global Agent.
- `agentWorkflow.endpoints`: platform APIs for managing Agents, Workflows, bindings, and resolve preview.

The manifest does not include `appSecret`.

If `agentWorkflow.globalAgentKeySlug`, `embed.defaultAgentKeySlug`, or `embed.defaultAgentId` is present, use that value directly. Prefer `agentWorkflow.globalAgentKeySlug`. Do not ask the business user to choose an Agent id. If all are empty, stop front-end embed work and ask the ReachAI platform owner to create or enable a project Agent first.

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
  "agentId": "<manifest.agentWorkflow.globalAgentKeySlug>",
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
3. Call the business gateway token broker, for example `/api/reachai/embed-token`, with the normal business login token and include `agentId`, `pageKey`, `pageInstanceId`, `route`, and `origin`.
4. Use the returned embed token as `Authorization: Bearer <token>` for ReachAI chat session and message APIs.
5. Create the chat session with the same `pageKey`. The ReachAI SDK does this when `createEafChat({ page: { pageKey, routePattern } })` is configured.
6. Never send `appSecret` or project signing material to the browser.

Token boundary note: the business login token and the ReachAI embed token are different credentials. The business login token belongs only on the token broker request. `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs must use the short-lived embed token returned by the broker.

Gateway authentication note: `/api/reachai/embed-token` should use the business login token, but `/api/reachai/embed/**` must forward the ReachAI embed token unchanged. Do not let business OAuth/JWT filters validate `/api/reachai/embed/**` as a business login request.

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
- Agent or page-assistant generation endpoints.
- Deployment or production rollout endpoints.
