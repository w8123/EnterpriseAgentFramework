# ReachAI Public Route Contracts

> Date: 2026-07-01
> Scope: first physical split with one shared MySQL database

This document is the short source of truth for public route lifecycle decisions. The detailed route-by-route owner map remains in `docs/architecture/physical-split-route-ownership.md`; service-to-service-only endpoints remain in `docs/architecture/internal-api-contracts.md`.

## Principles

- `reachai-control-service` is the public `/api/**`, `/embed/**`, `/gateway/**`, `/mcp/**`, and `/a2a/**` entry for the management UI, browser embeds, SDK registry clients, and external protocol clients.
- The management UI must not call `reachai-runtime-service` or `reachai-capability-service` internal ports directly.
- `reachai-knowledge-service` keeps the remaining `/ai/**` Knowledge APIs and `reachai-model-service` keeps `/model/**` in this phase. Business Index is the explicit exception: anonymous `/ai/biz-index/**` is retired and its console/project ingress terminates at Control.
- Public request methods and request/response JSON shapes are not changed during this route-contract cleanup.
- A compatibility route can stay only when it delegates to the owning service implementation. A compatibility route must not proxy to the retired `ai-agent-service`, return a legacy disabled response, or become the frontend main path.

## Route Lifecycle Classes

| Lifecycle | Meaning | Rule |
| --- | --- | --- |
| Main public path | Stable path that frontend or external callers may use going forward. | Keep documented in the route owner map and guarded by route-contract scripts. |
| Frozen compatibility alias | Historical public path preserved only to avoid breaking existing callers. | Backend may keep it, but frontend must use the main public path. New features must not add new aliases in this class. |
| Retired route | Historical path or fallback that no longer represents a supported product contract. | Delete it or keep it absent; do not return migration-time placeholder errors. |
| Internal API | Service-to-service HTTP contract under `/internal/**`. | Frontend must never call it; every endpoint must be listed in `internal-api-contracts.md`. |

## Frontend Main Paths

| Product area | Frontend-facing route family | Public entry | Owning implementation |
| --- | --- | --- | --- |
| Agent catalog, Supervisor config versions and Workflow-as-Tool | `/api/agents/**` | Control | Runtime |
| Agent execution | `/api/runtime/agents/execute/**` | Control | Runtime |
| Runtime evals | `/api/runtime/evals/**` | Control | Runtime |
| Human approvals and runtime interactions | `/api/runtime/interactions/**` | Control | Runtime |
| Workflow definitions, Studio, versions, AI coding | `/api/workflows/**` | Control | Runtime |
| Workflow credentials | `/api/workflows/credentials/**` | Control | Runtime |
| Runtime debug sessions and Tool execution | `/api/runtime/**` | Control | Runtime |
| Traces and RunOps | `/api/traces/**`, `/api/runops/**` | Control | Runtime |
| SDK registry and capability sync | `/api/registry/**` | Control | Capability, except Control-owned page registration and Runtime-owned agent graph sync |
| Business page workbench | `/api/registry/projects/{projectCode}/page-workbench/**` | Control | Control, with Runtime published-data query |
| AI Coding task console, handoff activation and task protocol | `/api/ai-coding-console/tasks/**`, `/api/ai-coding/handoffs/**`, `/api/ai-coding/tasks/{taskId}/**` | Control | Control |
| Capability, Tool, Composition, API graph | `/api/capabilities/**`, `/api/tools/**`, `/api/compositions/**`, `/api/api-graph/**` | Control | Capability |
| Capability mining | `/api/capability-mining/**` | Control | Capability |
| Scan projects and semantic documents | `/api/scan-projects/**`, `/api/scan-modules/**`, `/api/semantic-docs/**` | Control | Capability |
| Embed and page actions | `/api/embed/**` and `/embed/**` | Control | Control with Runtime/Capability checks when needed |
| Gateway, MCP, A2A, market, context governance | `/gateway/**`, `/api/v1/agents/**`, `/mcp/**`, `/a2a/**`, `/api/market/**`, `/api/context/**` | Control | Control, with Runtime/Capability calls by subdomain |
| Business Index console | `/api/knowledge/biz-index/**` | Control | Control session/RBAC, then exact-byte HMAC to Knowledge |
| Business Index project sync | `/api/knowledge-ingress/projects/{projectCode}/biz-index/**` | Control | Capability verifies body-bound project credential; Control forwards the verified project to Knowledge |
| Other Knowledge and retrieval | `/ai/**` except retired `/ai/biz-index/**` | Knowledge | Knowledge |
| Model gateway | `/model/**` | Model | Model |

## Frozen Compatibility Aliases

These routes may remain on backend services while external callers migrate. They are not frontend main paths.

| Compatibility route | Main path | Owner requirement |
| --- | --- | --- |
| `/api/skill-mining/**` | `/api/capability-mining/**` | Must delegate to Capability-owned mining implementation. |

## Retired Or Frontend-Banned Routes

These routes must not appear in frontend source or product-facing guidance:

- `/api/agent/studio/**`
- `/api/agent/workflow-credentials/**`
- `/api/skill-mining/**`
- `/api/api-assets/**`
- `/api/platform/embed/pages/catalog`
- `/api/ai-coding/projects/{projectId}/access-sessions/**`
- `/api/ai-coding/projects/{projectId}/page-workbench/tasks/{taskId}/**`
- `/internal/runtime/**`
- `/internal/capability/**`
- `/internal/control/**`
- `/ai/biz-index/**`

`scripts/check-frontend-public-api-routes.mjs`, `scripts/check-internal-api-contracts.mjs`, and `scripts/check-physical-service-route-contracts.mjs` guard these boundaries. When adding a new route, update this document only if the route changes the lifecycle contract, then update the detailed owner map or internal API contract as appropriate.
