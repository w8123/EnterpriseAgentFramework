# Internal API Contracts

This document is the source of truth for service-to-service internal HTTP contracts in the first physical split phase.

Internal APIs are not public frontend APIs. The frontend enters through `reachai-control-service` public `/api/**`, `/embed/**`, `/ai/**`, or `/model/**` routes only.

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| `GET /internal/control/page-actions` | `reachai-control-service` | `reachai-runtime-service` | Runtime AI Coding catalog reads all allowed page actions for a first-class Workflow page binding using `projectCode`, `pageKey`, optional `status`, and `limit` query parameters. | No |
| `GET /internal/control/page-actions/lookup` | `reachai-control-service` | `reachai-runtime-service` | Runtime release validation performs an unambiguous page-action lookup using `projectCode`, `pageKey`, and `actionKey` query parameters. | No |
| `GET /internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}` | `reachai-control-service` | `reachai-runtime-service` | Runtime release validation reads the Control-owned `control_page_action` contract without direct table access. | No |
| `POST /internal/control/page-bridge/execute` | `reachai-control-service` | `reachai-runtime-service` | Runtime executes a page Workflow through the Control-owned embed session and Page Bridge protocol, including cross-route `NAVIGATE -> TARGET_READY -> PAGE_ACTION`, without direct access to Control tables. | No |
| `POST /internal/runtime/page-workbench/projects/{projectCode}/workflow-drafts` | `reachai-runtime-service` | `reachai-control-service` | Control applies a validated `WORKFLOW_ENGINEERING` task Artifact by creating an idempotent, task-scoped `PAGE_ASSISTANT` Workflow draft. Runtime owns the exact `TARGET PAGE` resource binding and returns current release-validation facts; this contract never publishes the draft. | No |
| `POST /internal/runtime/page-workbench/projects/{projectCode}/workflows/{workflowId}/deliver` | `reachai-runtime-service` | `reachai-control-service` | After Control proves the originating Workflow engineering task was explicitly accepted, Runtime validates project, task ownership, exact page binding and release readiness, then publishes the Workflow version and attaches it to the page copilot Agent in one Runtime transaction. | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/published` | `reachai-runtime-service` | `reachai-control-service` | Control lists real active PAGE_ASSISTANT Workflow versions, first-class page bindings, active Agent Workflow-as-Tool links, recent calls and success facts without direct Runtime table access. | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/release-readiness` | `reachai-runtime-service` | `reachai-control-service` | Control verifies the exact Page Workbench pre-release Artifact target through Runtime-owned Workflow, resource-binding, release-validation and version facts. Required query parameters are `pageKey`, `workflowId`, and `workflowVersion`; the result is deterministic `PASS` or `FAIL`, while transport unavailability is mapped to `PENDING` by Control. | No |
| `GET /internal/runtime/page-workbench/projects/{projectCode}/execution-readiness` | `reachai-runtime-service` | `reachai-control-service` | Control verifies that an exact post-task Embed Run (`pageKey`, `sessionId`, `pageInstanceId`, `traceId`) completed and produced a successful `WORKFLOW_TOOL` Span for the acceptance task's exact `workflowId + workflowVersionId + workflowVersion`. The response exposes identifiers and statuses only; it does not return message content or user identity and does not infer required `PAGE_ACTION` behavior. | No |
| `GET /internal/runtime/health` | `reachai-runtime-service` | `reachai-control-service` | Control aggregates Runtime health for the public service topology view. | No |
| `GET /internal/runtime/agent-tool-references` | `reachai-runtime-service` | `reachai-capability-service` | Capability scan/catalog logic reads Runtime-owned Agent-to-Tool references without direct Runtime table access. | No |
| `GET /internal/runtime/tool-call-logs/by-tool` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition metrics reads Runtime-owned tool-call history by tool. | No |
| `GET /internal/runtime/tool-call-logs/recent` | `reachai-runtime-service` | `reachai-capability-service` | Capability mining reads recent Runtime-owned tool-call history. | No |
| `GET /internal/runtime/tool-call-logs/by-trace/{traceId}` | `reachai-runtime-service` | `reachai-capability-service` | Capability mining resolves Runtime-owned trace samples by trace id. | No |
| `DELETE /internal/runtime/tool-call-logs/demo` | `reachai-runtime-service` | `reachai-capability-service` | Capability mining demo setup clears demo Runtime tool-call log rows through the owner boundary. | No |
| `POST /internal/runtime/tool-call-logs/demo` | `reachai-runtime-service` | `reachai-capability-service` | Capability mining demo setup appends demo Runtime tool-call log rows through the owner boundary. | No |
| `GET /internal/runtime/interactions/admin-test/pending` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition admin-test reads pending Runtime-owned interaction rows. | No |
| `GET /internal/runtime/interactions/{interactionId}` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition resume flow reads Runtime-owned interaction state. | No |
| `PATCH /internal/runtime/interactions/{interactionId}` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition resume flow updates Runtime-owned interaction state after user input. | No |
| `DELETE /internal/runtime/interactions/admin-test/{interactionId}` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition admin-test cancels one Runtime-owned pending interaction. | No |
| `DELETE /internal/runtime/interactions/admin-test` | `reachai-runtime-service` | `reachai-capability-service` | Capability composition admin-test cleanup cancels all matching Runtime-owned pending interactions. | No |
| `GET /internal/capability/health` | `reachai-capability-service` | `reachai-control-service` | Control aggregates Capability health for the public service topology view. | No |
| `GET /internal/capability/tools/{qualifiedName}` | `reachai-capability-service` | `reachai-control-service`, `reachai-runtime-service` | Control exposes public tool metadata routes and Runtime resolves executable Tool metadata through Capability ownership. | No |
| `POST /internal/capability/tools/{qualifiedName}/execute` | `reachai-capability-service` | `reachai-runtime-service` | Runtime executes HTTP Tools through Capability-owned Tool invocation metadata and execution boundary. | No |
| `GET /internal/capability/compositions/{qualifiedName}` | `reachai-capability-service` | `reachai-runtime-service` | Runtime loads Capability-owned Composition GraphSpec before executing composition routes. | No |
| `GET /internal/capability/projects/{projectCode}` | `reachai-capability-service` | `reachai-runtime-service` | Runtime resolves Capability-owned project identity by project code. | No |
| `GET /internal/capability/projects/by-id/{projectId}` | `reachai-capability-service` | `reachai-runtime-service`, `reachai-control-service` | Runtime and the Control page-workbench domain resolve a non-sensitive Capability-owned project summary by project id; AI Coding keys never enter task snapshots or context responses. | No |
| `GET /internal/capability/projects/by-id/{projectId}/onboarding` | `reachai-capability-service` | `reachai-control-service` | Control reads Capability-owned onboarding state for AI Coding project access views. | No |
| `PUT /internal/capability/projects/by-id/{projectId}/ai-coding-access` | `reachai-capability-service` | `reachai-control-service` | Control updates Capability-owned AI Coding access status while preserving the public `PATCH` route; internal PUT avoids the JDK Feign client's unsupported PATCH method. | No |
| `GET /internal/capability/embed/credentials` | `reachai-capability-service` | `reachai-control-service` | Control lists Capability-owned embed credential policies for platform management routes. | No |
| `PUT /internal/capability/embed/credentials/{id}/policy` | `reachai-capability-service` | `reachai-control-service` | Control updates Capability-owned embed credential policy state. | No |
| `POST /internal/capability/embed/token/exchange/verify` | `reachai-capability-service` | `reachai-control-service` | Control verifies embed token exchange through Capability-owned credential policy rules. | No |
| `GET /internal/capability/projects/by-id/{projectId}/tools` | `reachai-capability-service` | `reachai-runtime-service`, `reachai-control-service` | Runtime AI Coding context and Control diagnostics list project-scoped enabled tools without cross-service table reads. | No |
| `GET /internal/capability/projects/by-id/{projectId}/readiness-facts` | `reachai-capability-service` | `reachai-control-service` | Control aggregates CODE/RUNTIME readiness using Capability-owned instance heartbeat facts without reading Capability tables directly. | No |
| `POST /internal/runtime/projects/{projectId}/agent-supervisor/workflow-tools/attach` | `reachai-runtime-service` | `reachai-control-service` | Control AI Coding generic Workflow-as-Tool attach delegates to Runtime-owned Agent config publish logic. | No |
| `POST /internal/runtime/agents/execute` | `reachai-runtime-service` | `reachai-control-service` | Control executes Agents with HMAC-SHA256 authenticated identity + body integrity (`X-ReachAI-Internal-*`: caller, timestamp, nonce, identity source/userId, body SHA-256, signature). Canonical string (ai-common `InternalServiceHmac`): `METHOD\nPATH\nCALLER\nIDENTITY_SOURCE\nIDENTITY_USER_ID\nTIMESTAMP\nNONCE\nBODY_SHA256`. Control signs the exact serialized HTTP body bytes; Runtime verifies the same raw bytes via a size-capped cached-body wrapper. Also verifies caller allowlist, clock skew, constant-time signature, and JDBC nonce anti-replay (`runtime_internal_auth_nonce`; only expired nonces may be deleted; at capacity fail-closed). Shared secret: `REACHAI_INTERNAL_SERVICE_SECRET` (Control/Runtime must match; blank → fail-closed; readiness reports `INTERNAL_AUTH_NOT_CONFIGURED`). Path name alone is never attestation. Public `/api/runtime/agents/execute` must not accept client trust flags; body `userId` is never trusted for Knowledge ACL / credentials / RunOps audit `userId`. | No |
| `POST /internal/runtime/agents/execute/stream` | `reachai-runtime-service` | `reachai-control-service` | Same HMAC + body-digest auth as sync execute. SSE path must call `RuntimeAgentExecutionService.execute(..., WorkflowExecutionIdentity)`. Control Agent stream (Bearer) and Embed stream (claims userId) use this entry; public Runtime stream stays untrusted. Signatures/identity/body digests must never be written into logs, Trace, or downstream SSE. | No |

Model catalog (not under the three guarded prefixes, documented for Runtime AI Coding):

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| `GET /internal/model/instances` | `reachai-model-service` | `reachai-runtime-service` | Runtime AI Coding context lists ACTIVE LLM model instances without reading model tables. | No |

Knowledge retrieval (Workflow Runtime I/O; not under the three guarded prefixes, documented for service boundary):

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| `POST /internal/knowledge/retrieval/query` | `reachai-knowledge-service` | `reachai-runtime-service` | Runtime `KNOWLEDGE_RETRIEVAL` nodes retrieve structured hits without reading `knowledge_*` tables or embedding Knowledge service beans. Request: `query`, `knowledgeBaseCodes`, `userId`, `topK`, `similarityThreshold`, `searchMode`(`vector|keyword|hybrid`), `rerankEnabled`. Reuses Knowledge production retrieval (`retrievalTest` path) with request-level searchMode/rerank override and file ACL filtering. Returns `{query,hits,hitCount}` only; does not generate LLM answers. Status: CODE_READY / E2E_PENDING. | No |

## Guard

Run the contract guard after adding or changing an internal endpoint:

```powershell
node scripts/check-internal-api-contracts.mjs
```

The guard discovers Spring mapping annotations under the five service source roots, requires every `/internal/runtime/**`, `/internal/capability/**`, and `/internal/control/**` contract to be listed above, and fails if `ai-admin-front` calls an internal service API directly.
