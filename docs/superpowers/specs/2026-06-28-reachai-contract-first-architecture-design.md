# ReachAI Contract-first Backend Architecture Design

> Date: 2026-06-28
> Status: Approved design, not an implementation plan
> Scope: Backend architecture target for the next stage of ReachAI Platform Core

## Context

ReachAI is an Enterprise AI Capability Platform for Java enterprise systems. The current product direction is not a narrow Workflow Builder and not only a legacy project scanner. The main flow is:

1. Business systems register projects, instances, capabilities, and SDK graphs through `reachai-spring-boot2-starter`, `reachai-capability-sdk`, `@ReachCapability`, and `@ReachParam`.
2. The platform creates capability snapshots, field-level diffs, review apply/ignore decisions, and durable capability assets.
3. Workflow Studio composes Workflow `GraphSpec`; Agent entries bind to Workflow through binding.
4. Runtime execution is decoupled through `AgentRuntimeAdapter`.
5. RunOps, Trace, Tool ACL, Guard, Gateway, MCP, A2A, embedded chat, and enterprise identity form the production governance boundary.

The current deployment topology remains:

- `ai-model-service`: Model Gateway.
- `ai-skills-service`: Knowledge / Retrieval deployment unit.
- `ai-agent-service`: Platform Core, currently containing Capability Catalog, Runtime Host, and Platform Control.

This design keeps the physical services unchanged for now. The next architecture target is a contract-first modular monolith inside `ai-agent-service`, so future Maven module or service extraction becomes a boundary movement rather than a redesign.

## Current State From Repository Checks

The current working tree is dirty and contains a large architecture refactor. The following checks were run before this design discussion:

- `git status --short`: shows many modified, deleted, and untracked files; the three local `application.yml` files remain dirty.
- `git log -5 --oneline --decorate`: current HEAD is `3ed9eb8 (HEAD -> main, origin/main, origin/HEAD) fix：优化代码架构`.
- `node scripts/check-backend-boundary-naming.mjs`: passed.
- `node scripts/check-backend-domain-dependencies.mjs`: passed.
- `node scripts/check-backend-domain-dependencies.test.mjs`: passed.
- Runtime Host controller forbidden import scan: no matches for direct imports from `workflow`, `tool.log`, `registry`, or `agentscope`.
- `platform.control` / `capability.catalog` forbidden direct import scan: no matches for direct imports from `workflow`, `agentscope`, `tool.log`, or `registry`.
- `platform.control` / `capability.catalog` Runtime Host internal import scan: no matches for `DefaultRuntime*`, `LangGraph4jRuntime*`, execution plans, coordinators, assemblers, or resolvers.

The current repository already documents the intermediate target in:

- `docs/16-后端逻辑边界与命名重塑.md`
- `docs/17-后端物理模块拆分准备度.md`
- `docs/18-runtime-host-contract-阶段性盘点.md`

## Design Decision

The approved target is **Contract-first modular monolith**.

This means:

- Keep the current physical deployments for now.
- Make the source packages, public contracts, dependency direction, and guard scripts match the future module boundaries.
- Do not immediately split Maven modules.
- Do not let architecture cleanup remove pages, workflows, API capabilities, or current production governance features.

API paths and frontend routes are allowed to change in future batches, but only if backend, frontend, menu entries, permission points, docs, and verification are updated together in the same batch. The non-negotiable requirement is that pages and product functions must not be lost.

## Phase 1: Overall Logical Architecture

`ai-agent-service` should be understood as Platform Core with three internal logical domains:

### Capability Catalog

Owns capability assets:

- Project registration.
- Project instances and heartbeat.
- Capability snapshots.
- Field-level diffs.
- Review apply/ignore state.
- Scan catalog.
- Tool / Capability definitions.
- Semantic docs and SDK graph assets.

Capability Catalog does not execute Workflows. It exposes stable executable views, directories, or ports for Runtime Host.

### Runtime Host

Owns runtime semantics and runtime facts:

- `AgentEntry`.
- Agent-Workflow binding.
- Workflow version loading.
- `GraphSpec` execution.
- Runtime adapters.
- Workflow Studio debug.
- Replay.
- Runtime registry.
- Trace and RunOps runtime facts.

`AgentEntry`, Workflow binding, and `GraphSpec` execution belong together in Runtime Host. Platform Control must not own half of this execution chain.

### Platform Control

Owns control-plane surfaces:

- Platform identity.
- Tenant and project authorization.
- RBAC and ACL.
- Gateway.
- Embed.
- MCP.
- A2A.
- Guard policy configuration.
- Governance workbench.

Platform Control initiates execution through Runtime Host public API contracts. It does not directly depend on Workflow persistence, AgentScope implementation, LangGraph4j internals, or runtime executors.

### Shared Kernel

The following remain shared kernel and should not be forced into one of the three domains for naming neatness:

- `com.enterprise.ai.agent.graph.GraphSpec`
- `com.enterprise.ai.agent.graph.AgentGraphSpec`
- `com.enterprise.ai.agent.graph.AgentGraphNodeType`
- Stable value contracts in `com.enterprise.ai.agent.runtime`

`GraphSpec` is Workflow runtime semantics. `canvas_json` is layout only.

## Phase 2: Runtime Host API / Internal Layering

Runtime Host should first be split at the source package level, not at the Maven module level.

### Target Package Shape

`runtime.host.api`

- `Runtime*Facade`
- `Runtime*Directory`
- Request records.
- Result records.
- View records.
- Public runtime exceptions.

This is the only Runtime Host surface that cross-domain consumers should import.

`runtime.host.internal`

- `DefaultRuntime*`
- `LangGraph4jRuntime*`
- Coordinators.
- Resolvers.
- Executors.
- Graph execution planner.
- Node executors.
- Workflow persistence collaboration.
- AgentScope/debug/interactive implementation collaboration.

This is Runtime Host implementation detail. Cross-domain imports should be forbidden by guard scripts.

`runtime.host.controller`

- HTTP entrypoints.
- Long-term dependency direction: controllers depend on `runtime.host.api` facade contracts.
- Existing facade-closed controllers should stay closed.
- Remaining controllers that still call services or executors directly should be migrated in batches.

### Cross-domain Rules

`platform.control`, `capability.catalog`, RunOps consumers, and controllers must not import:

- `runtime.host.internal.*`
- `DefaultRuntime*`
- `LangGraph4jRuntime*`
- `*Coordinator`
- `*Resolver`
- `*Executor`
- Workflow persistence services/entities.
- AgentScope implementation classes.
- Tool log persistence classes.
- Registry persistence classes.

Allowed imports are Runtime Host API contracts such as:

- `RuntimeAgentExecutionFacade`
- `RuntimeEmbeddedChatExecutionFacade`
- `RuntimeTraceDirectory`
- `RuntimeWorkflowDefinitionFacade`
- `RuntimeWorkflowDebugFacade`
- `RuntimeWorkflowVersionFacade`
- `RuntimeAgentEntryFacade`
- `RuntimeAgentWorkflowBindingFacade`
- Their request/result/view/exception types.

### Phase 2 Acceptance Criteria

Phase 2 is complete when:

1. New Runtime Host public contracts default to `runtime.host.api`.
2. New Runtime Host implementation classes default to `runtime.host.internal`.
3. `platform.control` and `capability.catalog` import only Runtime Host API contracts.
4. Guard scripts detect external imports of Runtime Host internal implementation.
5. Existing pages and functions remain available.

## Phase 3: Catalog Executable Views And Control Thin Entrypoints

Phase 3 stabilizes how the two surrounding domains collaborate with Runtime Host.

### Capability Catalog To Runtime Host

Capability Catalog owns assets, but Runtime Host consumes only stable executable views.

The executable view should include enough information for execution without exposing registry, scan, review, or persistence internals:

- `toolKey` / `capabilityKey`.
- Input schema.
- Output schema.
- Parameter semantics.
- Credential reference.
- Invocation endpoint.
- ACL / Guard labels.
- Version and source summary.

Runtime Host should not import catalog mappers, registry persistence entities, scan implementation, or review state-machine internals.

### Platform Control To Runtime Host

Gateway, Embed, A2A, MCP, and Guard controllers should be thin entrypoints:

1. Resolve identity, tenant, project, and protocol context.
2. Apply control-plane permission checks.
3. Build `Runtime*ExecutionRequest`.
4. Call Runtime Host API facade.
5. Assemble protocol-specific response.

They should not directly read Workflow services, AgentScope implementation, LangGraph4j internals, executor classes, or route resolvers.

### Guard Split

Guard is split by responsibility:

- Platform Control owns Guard policy configuration and governance UI.
- Runtime Host owns execution-time decision invocation and runtime fact recording.

This keeps governance visible in the control plane while keeping runtime facts in the execution chain.

## Phase 4: Future Physical Module Target And Readiness

Physical Maven module split is not part of the immediate implementation. It becomes eligible only after the contract-first monolith is stable.

Future candidate modules:

| Future Module | Owns |
| --- | --- |
| `shared-kernel` | `GraphSpec`, `AgentGraphSpec`, `AgentGraphNodeType`, runtime root value contracts |
| `runtime-host-api` | Runtime facades, directories, request/result/view records, public exceptions |
| `runtime-host-core` | Runtime implementation, GraphSpec execution, LangGraph4j, debug, replay, runtime registry, node executors |
| `capability-catalog-api/core` | Catalog ports, executable views, registration, scan, review, capability asset catalog |
| `platform-control-api/core` | Identity, RBAC, Gateway, Embed, MCP, A2A, Guard policy, governance entrypoints |
| `ai-agent-service` | Spring Boot assembly, dependency wiring, MyBatis scanning, resource loading, runtime configuration |

Physical Maven split readiness requires:

- `runtime.host.api / internal` source layering is stable.
- Runtime controllers primarily depend on API facades.
- `platform.control` has no direct dependency on runtime internals, Workflow persistence, or AgentScope implementation.
- `capability.catalog` has no direct dependency on runtime internals or registry persistence.
- Capability Catalog exposes Runtime executable views.
- Guard, Trace, and RunOps ownership is explicit.
- Boundary guard scripts pass consistently.
- Backend tests pass consistently.
- Frontend pages remain usable after any API or route changes.

## Product Continuity Rules

Architecture upgrades must preserve product behavior.

Allowed:

- Rename or reorganize API paths when there is a clear boundary reason.
- Rename or reorganize frontend routes when the frontend is updated in the same batch.
- Move source packages.
- Introduce facade, directory, port, view, or request/result contracts.
- Prepare future Maven module boundaries.

Not allowed:

- Lose existing management pages.
- Break Workflow Studio graph editing, preview/apply, release validation, debug, publish, replay, or binding flows.
- Break Agent execution through Gateway, Embed, A2A, or MCP.
- Break capability registration, heartbeat, snapshot diff, review apply/ignore, or Tool catalog retrieval.
- Treat `canvas_json` as runtime semantics.
- Move `GraphSpec`, `AgentGraphSpec`, or `AgentGraphNodeType` merely for package neatness.
- Touch local database connection edits in the three `application.yml` files as part of architecture cleanup.

When API paths or frontend routes change, the same batch must update:

- Backend controller mappings.
- Frontend request clients.
- Frontend routes and menus.
- Permission keys and guards.
- Tests.
- Documentation.

## Verification Baseline

Architecture batches should run at least:

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-backend-domain-dependencies.mjs
node scripts/check-backend-domain-dependencies.test.mjs
mvn -pl ai-agent-service test
mvn -pl ai-agent-service -am compile
git diff --check -- . ':!ai-agent-service/src/main/resources/application.yml' ':!ai-model-service/src/main/resources/application.yml' ':!ai-skills-service/src/main/resources/application.yml'
```

If frontend pages, routes, menus, permissions, or API clients change:

```powershell
cd ai-admin-front
npm run build
```

SQL is out of scope for this architecture design. If a future batch changes schema, it must update `sql/init.sql`, add `sql/upgrade-YYYYMMDD-short-name.sql`, and update SQL docs.

## Explicit Non-goals

- Do not physically split Maven modules in the immediate next implementation step.
- Do not change SQL schema as part of this design.
- Do not rename compatibility-sensitive `Skill` storage names globally.
- Do not move `GraphSpec` into Runtime Host internal packages.
- Do not treat `ai-skills-service` as a Skill service in product architecture language; it remains Knowledge / Retrieval.
- Do not make `Platform Control` responsible for Workflow execution.
- Do not let `Runtime Host` own platform identity, external protocol governance, or Guard policy configuration.

## Open Follow-up For Implementation Planning

The next implementation plan should break this design into small batches:

1. Create or simulate `runtime.host.api / internal` package boundaries and update guard scripts.
2. Migrate selected Runtime Host public contracts into the API package.
3. Move selected Runtime Host implementation classes into the internal package.
4. Close remaining high-value Runtime Host controllers behind facades.
5. Define Catalog executable views and ports.
6. Thin selected Platform Control entrypoints.
7. Update architecture docs and verification commands.

Each batch must keep existing pages and functions available.
