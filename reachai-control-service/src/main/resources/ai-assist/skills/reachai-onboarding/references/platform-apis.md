# ReachAI Platform APIs

The onboarding manifest is the primary contract for AI coding tools.

## Platform Response Shapes

Do not assume every ReachAI endpoint uses the same JSON wrapper.

| API family | Wrapped in `ApiResult`? | Read business fields from |
| --- | --- | --- |
| Embed 对外 API：`POST /api/embed/token/exchange`、`/api/embed/chat/sessions`、messages、page-actions、page-bridge rebind | **Yes** | `data.token`, `data.sessionId`, `data.answer`, ... |
| `POST /api/ai-coding/projects/{projectId}/agents/provision` | **No** | `agent.keySlug` (not `data.agent.keySlug`) |
| AI Coding handoff activation and task protocol | **No** | top-level `taskToken`, `taskRoot`, task/event/question/artifact fields |
| `POST /api/scan-projects/{projectId}/sdk-access-check` | **No** | top-level `overallStatus`, `checks` |
| `GET /api/ai-coding/projects/{projectId}/onboarding-manifest` | **No** | top-level `project`, `embed`, `agentProvisioning`, ... |

Embed success example:

```json
{ "code": 200, "message": "success", "data": { "token": "jwt", "expiresIn": 600 } }
```

Top-level `message: "success"` is transport status only. Never render it as the assistant reply; read `data.answer` for chat messages.

## Chat SDK apiBase

The browser SDK (`@reachai/embed-chat`, implemented in `eafChat.ts`) normalizes `apiBase + embedPathPrefix` into an Embed API root, then calls `{embedApiRoot}/chat/sessions` and `{embedApiRoot}/chat/sessions/{sessionId}/messages...`. The default `embedPathPrefix` is `/api/embed`.

- **Direct to ReachAI**: `apiBase = <ReachAI platform origin>`, e.g. `http://localhost:18603` -> requests hit `<origin>/api/embed/**`.
- **Via business gateway `/api/embed/**`**: set `apiBase` to the gateway origin, or to the accessible `/api/embed` root.
- **Via business gateway `/api/reachai/embed/**`**: set `apiBase` to the gateway origin and `embedPathPrefix` to `/api/reachai/embed`, or set `apiBase` directly to `/api/reachai/embed` for a relative proxy.

The SDK recognizes `/api/embed` and `/api/reachai/embed` as Embed API roots to avoid producing `/api/reachai/embed/api/embed/...`.

The token broker path (e.g. `/api/reachai/embed-token`) is separate from Chat `apiBase`.

Keep the same `pageKey`, `pageInstanceId`, `route`, and `origin` across token exchange, session create, and page actions.

For a platform-requested SPA cross-route Page Action, the target page obtains a
fresh token for its own Page Bridge identity, then the official SDK calls:

```http
POST /api/embed/chat/sessions/{sessionId}/page-bridge/rebind
Authorization: Bearer <target-page-embedToken>
Content-Type: application/json

{
  "navigationRequestId": "SDK-managed",
  "pageKey": "orders.audit",
  "pageInstanceId": "page-target-001",
  "route": "/orders/audit/42",
  "bridgeActions": ["orders.audit.search"],
  "sdkVersion": "1.0.0-SNAPSHOT"
}
```

Do not call this endpoint directly from business code. Use
`createEafPageBridge({ onNavigate })` and `chat.rebindPage(...)`; the SDK
owns the pending navigation ID and validates the handoff timing. The platform
only accepts a rebind that matches a pending `NAVIGATE` event and the exact
target token/project/Agent/business-user binding.

## Manifest

`GET /api/ai-coding/projects/{projectId}/onboarding-manifest`

AI coding tools should use the URL from the platform prompt without adding `aiCodingKey` to the query string. Send the project key as:

```http
X-ReachAI-AiCoding-Key: {key}
```

Do not use platform Bearer login for external tool calls. Platform console users may still use console-owned `/api/ai-assist/projects/**` endpoints with their normal login token.

The response does not echo the raw key in `aiCodingAccess.accessKey` or `agentProvisioning.provisionAgentUrl`. Reuse the same header when calling returned platform URLs. Do not add `aiCodingKey` query parameters to returned URLs.

Important fields:
- `project.id`: ReachAI project id.
- `project.projectCode`: stable business project code.
- `project.baseUrl`: business service base URL as known by the ReachAI server. It must be reachable from ReachAI; loopback addresses are valid only when both sides share the same host or network namespace.
- `project.registryAppKey`: app key for signed registration.
- `project.registryCredentialConfigured`: whether ReachAI has a saved credential.
- `aiCodingAccess.enabled`: whether external AI coding access is enabled.
- `aiCodingAccess.accessKey`: the project-level key used only to fetch this manifest; it is not the registry app secret. It is `null` for header-auth manifests.
- `sdk.dependencies`: Maven coordinates to add.
- `sdkArtifacts`: machine-readable Java and browser SDK coordinates, source policies, exact versioned download URLs, integrity hashes, and local install commands. Java entries expose both JAR and standalone consumer POM URLs/hashes.
- `responseShapes`: machine-readable map that tells tools whether each API family is `ApiResult` wrapped or bare JSON.
- `sdk.config.appSecretEnv`: environment variable name for the app secret.
- `security.secretSetupScriptWithinSkill` / `security.secretSetupCommandTemplate`: prompt-only Windows helper that stores the secret without printing it. User-scoped setup is inherited only by newly started terminals/processes.
- `endpoints.skillPackageUrl`: zip URL for this skill.
- `endpoints.sdkAccessCheckUrl`: platform self-check endpoint.
- `embed.tokenPath`: the business gateway token broker path the front end should call.
- `embed.defaultAgentKeySlug`: preferred stable Agent identifier for front-end `agentId`.
- `embed.defaultAgentId`: internal Agent id fallback when no key slug is available.
- `embed.allowedAgents`: Agent ids/key slugs exposed by the project's embed policy or project ownership.
- `agentProvisioning.model`: Agent provisioning contract model, normally `agent-provisioning.v2`.
- `agentProvisioning.provisionAgentUrl`: idempotent API for AI coding tools to create or reuse the project page copilot Agent.
- `agentProvisioning.defaultKeySlug`: predictable page copilot Agent key slug if provisioning has not been called yet.
- `agentProvisioning.createsSupervisorConfig` / `activatesSupervisorConfig`: provisioning creates an AgentScope config and publishes it as ACTIVE.
- `agentProvisioning.modelSelection`: model-selection policy, normally `REQUESTED_OR_FIRST_ACTIVE_LLM`.
- `agentSupervisor.model`: Supervisor/Workflow-as-Tool manifest model, normally `agent-supervisor.workflow-tools.v1`.
- `agentSupervisor.globalAgentKeySlug`: stable page copilot Agent entry.
- `agentSupervisor.runtimeType`: Supervisor runtime, normally `AGENTSCOPE`.
- `agentSupervisor.workflowToolCatalog`: the published Workflow allow-list stored in the Agent config version.
- `agentSupervisor.endpoints`: APIs for Agent config versions, Workflow Tool attachment, and execution.
- `agentSupervisor.workflowAiCoding`: project-key protected Workflow AI Coding endpoints used to create, validate, and publish business Workflows before attachment.

The manifest does not include `appSecret`.

Read `sdkArtifacts` from the manifest for Java and browser package coordinates, source policy, declared download URLs/hashes, and local install commands. Java SDK links are versioned platform artifact APIs and are intentionally usable without a ReachAI source checkout; they are not Maven repository roots. Do not derive other artifact URLs from the platform base URL. In particular, do not request `/repository/**`, `/maven/**`, `/repository/maven/**`, `/api/embed/sdk`, or `/npm/**`. If a declared artifact URL/hash is unavailable, report the platform contract defect instead of guessing a replacement.

Before front-end embed work, call `agentProvisioning.provisionAgentUrl` when present. Use the response `agent.keySlug` as the business front-end `agentId`. The call is idempotent, so it is safe for Cursor or another AI coding tool to retry. Do not ask the business user to choose an Agent id. If provisioning is unavailable, fall back to `agentProvisioning.defaultKeySlug`, `agentSupervisor.globalAgentKeySlug`, `embed.defaultAgentKeySlug`, or `embed.defaultAgentId`.

Send `X-ReachAI-AiCoding-Key` on the provisioning request.

## Agent Provisioning

AI coding tools can create or reuse the project page copilot Agent without requiring manual platform configuration:

```http
POST /api/ai-coding/projects/{projectId}/agents/provision
X-ReachAI-AiCoding-Key: {key}
Content-Type: application/json
```

Do not add `aiCodingKey` query parameters to provisioning URLs generated from the current platform prompt.

Request body:

```json
{
  "modelInstanceId": "optional-active-llm-model-id",
  "requestedBy": "Cursor"
}
```

Response body:

```json
{
  "schema": "agent-provisioning.v2",
  "agent": {
    "id": "agent-001",
    "keySlug": "demo-service-page-copilot"
  },
  "supervisorConfig": {
    "id": "config-001",
    "runtimeType": "AGENTSCOPE",
    "status": "ACTIVE",
    "modelInstanceId": "active-llm-model-id",
    "tools": []
  },
  "workflowTools": [],
  "createdAgent": true,
  "createdSupervisorConfig": true,
  "runtimeType": "AGENTSCOPE"
}
```

Use `agent.keySlug` everywhere the business gateway token broker or front-end embed SDK asks for `agentId`.

## Workflow First Publish And Supervisor Attachment

Agent provisioning deliberately creates no placeholder Workflow. After creating or updating a business-meaningful Workflow through Workflow AI Coding:

1. Save the GraphSpec with `POST /api/workflows/{workflowId}/ai-coding/patch` and `dryRun=false`.
2. Confirm release validation with `GET /api/workflows/{workflowId}/ai-coding/versions` or `POST /api/workflows/{workflowId}/ai-coding/validate`.
3. If `releaseValidation.valid=true`, call the manifest's `agentSupervisor.workflowAiCoding.publishUrlTemplate`, normally:

```http
POST /api/workflows/{workflowId}/ai-coding/publish
X-ReachAI-AiCoding-Key: {key}
Content-Type: application/json
```

Request body:

```json
{
  "version": "v1.0.0",
  "note": "initial AI Coding publish",
  "publishedBy": "Cursor"
}
```

If the version already exists, read `/versions` and choose the next semantic version. Add the ACTIVE Workflow to the Supervisor catalog through `agentSupervisor.endpoints.workflowToolAttachUrlTemplate`, normally `POST /api/ai-coding/projects/{projectId}/agent-supervisor/workflow-tools/attach`, with `workflowId`, optional `modelInstanceId`, and `publishedBy`. The attach operation publishes a new ACTIVE Agent config version.

Attachment is additive when `replaceWorkflowId` is omitted. A page may have multiple independent Workflows, so name, pageKey, route, or list position must never imply replacement. When the new Workflow intentionally supersedes one currently attached predecessor, read the current catalog first and send that predecessor's exact id as `replaceWorkflowId`. ReachAI validates project, page binding, and current attachment, replaces only that entry, preserves every other tool, and returns `WORKFLOW_REPLACEMENT_INVALID` without publishing when the target is invalid.

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

Successful token exchange responses are wrapped in ReachAI `ApiResult`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "token": "jwt",
    "expiresIn": 600,
    "sessionHint": {
      "agentId": "<provisionedAgentKeySlug>",
      "pageInstanceId": "page-001"
    }
  }
}
```

The token broker must read `data.token` and `data.expiresIn`. It may keep a backward-compatible fallback for top-level `token` / `expiresIn`, but must not only read top-level fields. If helper methods accept a single path, do not pass `("token", "data", "token")`; that means `token.data.token`, not "token or data.token". Add a mock assertion with the wrapped response shape above before marking the broker complete.

Business front-end flow:

1. Let `@reachai/embed-chat` generate or reuse the current Page Bridge identity. Read `pageKey`, `pageInstanceId`, `route`, and `origin` from its `tokenProvider` context.
2. Determine the stable current page key, for example `teamArchive.list`.
3. Call the business gateway token broker, for example `/api/reachai/embed-token`, with the normal business login token and forward `agentId=<provisionedAgentKeySlug>`, `pageKey`, `pageInstanceId`, `route`, and `origin` unchanged.
4. Use the returned embed token as `Authorization: Bearer <token>` for ReachAI chat session and message APIs.
5. Create the chat session with the same `pageKey`. The ReachAI SDK does this when `createEafChat({ page: { pageKey, routePattern } })` is configured.
6. Never send `appSecret` or project signing material to the browser.

Never generate a replacement `pageInstanceId` in the browser token provider or business broker. The token exchange, Chat Session, and Page Bridge must use the SDK-owned identity; otherwise the first exchange may succeed while session creation or Page Action routing fails.

Token boundary note: the business login token and the ReachAI embed token are different credentials. The business login token belongs only on the token broker request. `/api/reachai/embed/**`, `/api/embed/chat/sessions`, and message APIs must use the short-lived embed token returned by the broker.

Gateway authentication note: `/api/reachai/embed-token` should use the business login token, but `/api/reachai/embed/**` must forward the ReachAI embed token unchanged. Do not let business OAuth/JWT filters validate `/api/reachai/embed/**` as a business login request. Also check whitelist filters that remove login JWTs, such as `IgnoreUrlsRemoveJwtFilter`, `RemoveJwtFilter`, `RemoveRequestHeader=Authorization`, or code that calls `mutate().header("Authorization", "")`; those filters must not clear the embed-token `Authorization` header on `/api/reachai/embed/**`.

Spring Security WebFlux note: `.pathMatchers("/api/reachai/embed/**").permitAll()` alone may still fail when OAuth2 Resource Server is enabled, because the authentication layer can parse `Authorization: Bearer <embedToken>` before authorization and return 401 for an invalid business JWT. Add a higher-priority security matcher / `SecurityWebFilterChain` for `/api/reachai/embed/**` that permits all and does not enable business `oauth2ResourceServer()` for that path.

Gateway CORS note: when Spring Cloud Gateway proxies `/api/reachai/embed/**` to ReachAI `/api/embed/**`, both the gateway and ReachAI may add CORS headers. Duplicate `Access-Control-Allow-Origin` or `Access-Control-Allow-Credentials` values can make browsers hide the real response as `status 0 Unknown Error`. Add a route-level dedupe filter such as:

```yaml
filters:
  - RewritePath=/api/reachai/embed/(?<segment>.*), /api/embed/${segment}
  - DedupeResponseHeader=Access-Control-Allow-Origin Access-Control-Allow-Credentials, RETAIN_FIRST
```

ReachAI Control's application-level `/api/embed/**` CORS is controlled by `reachai.embed.cors.enabled` and defaults to `false`. Keep it disabled behind a business gateway that owns CORS headers; enable and restrict `reachai.embed.cors.allowed-origin-patterns` only when browsers call ReachAI directly. If both layers must write CORS, dedupe at the proxy boundary.

For browser Chat SDK traffic, prefer declaring the actual gateway prefix through `embedPathPrefix`. Current `@reachai/embed-chat` also treats `/api/embed` and `/api/reachai/embed` as full Embed API roots, so it will not append `/api/embed` twice.

Embed SSE ends with `message.completed`; there is no `done` event.

Embed token cache note: front ends may cache the broker-returned embed token, but must expire it before `expiresIn`. If a chat session or message request returns `embed token is expired`, clear the cached embed token, call the broker again, and retry once.

## Embed Chat Page Actions

For non-streaming and streaming chat responses, `data.answer` is only the assistant text. If the response contains `data.metadata.pageActionQueue`, the browser must execute each queued item on the current page through the registered page bridge or the official SDK bridge, then post the result to:

```text
POST /api/embed/chat/sessions/{sessionId}/page-actions/{requestId}/result
```

Use `data.uiRequest.extension.pageActionRequest` only as a compatibility fallback when `pageActionQueue` is absent. Do not mark an embedded page query as complete just because `data.answer` says the system is querying; the page action result POSTs and the visible page state are part of the contract.

The skill package includes:
- `references/page-action-result.schema.json`: JSON Schema for the Embed Page Action result DTO. `error` is a string at the Embed API boundary.
- `references/page-action-mock.html`: runnable browser mock that consumes `data.metadata.pageActionQueue`, invokes `window.__REACHAI_PAGE_BRIDGE__`, and posts each result back.

## Self-Check

`POST /api/scan-projects/{projectId}/sdk-access-check`

No request body is required:

```http
POST /api/scan-projects/{projectId}/sdk-access-check
```

Run this only after the business service compiles and has a reachable local or test instance.

The response summarizes platform-observed `CODE_READY`, `RUNTIME_READY`, and `SDK_CALLBACK_READY` facts. It does not invoke a project API and does not prove the Embed conversation path. AI Coding task readiness `E2E_READY` is a separate Control-side gate based on an authorized SDK/doctor session, user message and assistant reply observed after the current task started. Doctor automation must use business-supplied test Authorization/Cookie from environment variables and does not replace final visible browser acceptance.

## AI Coding Task Protocol

A ReachAI task prompt contains a one-time activation URL and activation code. Activate it once:

```http
POST /api/ai-coding/handoffs/{handoffId}/activate
Content-Type: application/json
```

```json
{
  "schema": "reachai.ai-coding.activation.v1",
  "activationCode": "<one-time-code>",
  "client": {
    "provider": "CURSOR",
    "sessionRef": "optional-current-session-reference"
  }
}
```

The bare JSON response contains a short-lived `taskToken` and `taskRoot`. Keep the token only in the current process and send it on every task request:

```http
Authorization: Bearer <taskToken>
```

Read `GET <taskRoot>/context` before scanning or editing. The response freezes task targets, scope and domain context, while readiness can reflect current ReachAI facts. Use:

- `POST <taskRoot>/events` for `STARTED`, real `PROGRESS`, `RESUMED`, or `FAILED`.
- `POST <taskRoot>/questions` for blocking ambiguity; poll `GET <taskRoot>/questions` for the answer.
- `POST <taskRoot>/heartbeat` while the current AI coding session remains active.
- `POST <taskRoot>/verifications/SDK_SYNC` once, after Starter registration and heartbeat pass, to request the task's project-scoped signed SDK callback. No request body is required.
- `POST <taskRoot>/artifacts` exactly once per logical result, following the contract key, version and JSON Schema returned by context.

Follow the machine-readable `protocolGuide.eventStateRules` returned by context. `PROGRESS` is valid in both `RUNNING` and `WAITING_USER`; in `WAITING_USER` it records independent progress while preserving the status and every open question. Only `RESUMED`, after all answers have been read, returns the task to `RUNNING`.

Every event and artifact uses a caller-generated idempotency key. Retrying the same key with different content is a conflict. Verification operations are explicit, task-token scoped, valid only while the onboarding task is `RUNNING`, and generate a server-side task event. Client completion first means `RESULT_SUBMITTED`; only ReachAI validation and domain application can produce `RESULT_APPLIED`, `ACCEPTANCE_READY`, or `COMPLETED`.

ReachAI cannot wake an inactive local Cursor/Codex session without installing a local component. Do not claim otherwise and do not install a Runner, resident process, global package, or system software.

## Tool Reconcile

`POST /api/scan-projects/{projectId}/tools/reconcile`

Use after task-scoped or API Management SDK sync when the platform has received capability snapshots and the user wants API catalog rows reconciled. Task `SDK_SYNC` does not auto-reconcile or publish Tools.

## Explicit SDK Sync Callback

Task-scoped `SDK_SYNC` verification and API Management manual SDK sync use the same two-hop server-side flow:

1. ReachAI sends signed `POST {project.baseUrl}{project.contextPath}/reachai/registry/capabilities/sync` to the latest online SDK instance.
2. The Starter scans the configured business packages and sends the capability snapshot back to ReachAI `POST /api/registry/projects/{projectCode}/capabilities/sync`.

The inbound business-system callback must bypass ordinary business login/JWT authentication and CSRF, but it is not anonymous: the Starter validates `X-ReachAI-App-Key`, `X-ReachAI-Timestamp`, `X-ReachAI-Nonce`, and `X-ReachAI-Signature`. If the registered base URL is a gateway, route `/reachai/registry/**` to the Starter service and preserve those headers. CORS is not involved because both hops are server-to-server.

The SDK access check may report the computed callback target, but only an actual signed sync and received `SDK_CALLBACK` snapshot proves reachability and security-chain compatibility. `SDK_CALLBACK_READY` and authorized-conversation `E2E_READY` remain independent acceptance gates. Diagnose failures by status/signature: connection refused or timeout means address/network/firewall, 401/403 usually means business security or credential mismatch, and 404/405 usually means gateway route or context-path mismatch.

## Future Extension Points

Do not assume these exist unless the manifest exposes them:
- MCP tool endpoints.
- One-time secret token endpoints.
- Deployment or production rollout endpoints.
