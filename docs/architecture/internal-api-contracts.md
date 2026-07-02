# Internal API Contracts

This document is the source of truth for service-to-service internal HTTP contracts in the first physical split phase.

Internal APIs are not public frontend APIs. The frontend enters through `reachai-control-service` public `/api/**`, `/embed/**`, `/ai/**`, or `/model/**` routes only.

| Contract | Owner service | Consumers | Purpose | Frontend callable |
| --- | --- | --- | --- | --- |
| `GET /internal/control/page-actions/{projectCode}/{pageKey}/{actionKey}` | `reachai-control-service` | `reachai-runtime-service` | Runtime release validation reads Control-owned page action catalog state without direct `eaf_page_action_registry` table access. | No |
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
| `GET /internal/capability/projects/by-id/{projectId}` | `reachai-capability-service` | `reachai-runtime-service` | Runtime resolves Capability-owned project identity by project id. | No |
| `GET /internal/capability/runtime-instances` | `reachai-capability-service` | `reachai-runtime-service` | Runtime lists Capability-owned registered runtime instances when syncing runtime registry state. | No |
| `GET /internal/capability/projects/by-id/{projectId}/onboarding` | `reachai-capability-service` | `reachai-control-service` | Control reads Capability-owned onboarding state for AI Coding project access views. | No |
| `PATCH /internal/capability/projects/by-id/{projectId}/ai-coding-access` | `reachai-capability-service` | `reachai-control-service` | Control updates Capability-owned AI Coding access status while preserving public route shape. | No |
| `GET /internal/capability/embed/credentials` | `reachai-capability-service` | `reachai-control-service` | Control lists Capability-owned embed credential policies for platform management routes. | No |
| `PUT /internal/capability/embed/credentials/{id}/policy` | `reachai-capability-service` | `reachai-control-service` | Control updates Capability-owned embed credential policy state. | No |
| `POST /internal/capability/embed/token/exchange/verify` | `reachai-capability-service` | `reachai-control-service` | Control verifies embed token exchange through Capability-owned credential policy rules. | No |

## Guard

Run the contract guard after adding or changing an internal endpoint:

```powershell
node scripts/check-internal-api-contracts.mjs
```

The guard discovers Spring mapping annotations under the five service source roots, requires every `/internal/runtime/**`, `/internal/capability/**`, and `/internal/control/**` contract to be listed above, and fails if `ai-admin-front` calls an internal service API directly.
