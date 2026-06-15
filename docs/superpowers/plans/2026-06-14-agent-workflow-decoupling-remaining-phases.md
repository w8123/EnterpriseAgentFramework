# Agent Workflow Decoupling Remaining Phases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Agent/Workflow decoupling after the initial model and partial Workflow surfaces are in place, so Agent becomes the project entry/governance object and Workflow becomes the only owner of GraphSpec, Studio, publishing, runtime, and page-action references.

**Architecture:** Keep using the new `ai_agent`, `ai_workflow`, `ai_workflow_version`, and `ai_agent_workflow_binding` model as the target. Do not add new Workflow semantics back into `AgentDefinition`. Old AgentDefinition-based APIs and pages may remain temporarily as compatibility shells, but each phase must remove one concrete dependency from the new product path.

**Tech Stack:** Java 17, Spring Boot, MyBatis Plus, JUnit 5, Mockito, Vue 3, Element Plus, TypeScript, Vite, MySQL `sql/init2.sql`.

---

## Current Baseline To Preserve

These pieces are already part of the new direction and should be reused instead of rebuilt:

- `sql/init2.sql` contains the new Agent/Workflow split tables.
- Backend `com.enterprise.ai.agent.workflow` contains `AgentEntry*`, `WorkflowDefinition*`, `WorkflowVersion*`, `AgentWorkflowBinding*`, resolver, Studio service, release validation, and runtime request/service work.
- `EmbedChatController` has started resolving a runnable Workflow path through `EmbedWorkflowRuntimeService` and `WorkflowRuntimeService`, with legacy Agent fallback still present.
- `PageAssistantWorkflowBindingService` and `AiAssistController` can create/reuse global embedded Agent, page Workflow, and binding from page-assistant registration.
- `SdkAccessWizard.vue` and the packaged onboarding assets describe one global AI button routed by `pageKey`.
- Frontend has initial `/workflows`, `/workflows/:workflowId/studio`, `/workflows/:workflowId/versions`, and `/agents/:agentId/bindings` routes.

## Remaining Product End State

- Business systems render one global embedded AI entry per project.
- The browser and token broker pass `entryAgentKeySlug/agentId + pageKey + route + pageInstanceId`.
- ReachAI resolves the active Workflow from `ai_agent_workflow_binding`.
- Workflow Studio edits `ai_workflow.graph_spec_json` and `ai_workflow.canvas_json`.
- Workflow publishing writes `ai_workflow_version`.
- LangGraph executes Workflow GraphSpec directly, not `AgentDefinition.graphSpec`.
- Page-action diagnostics scan Workflow GraphSpec and binding context, not `agent_definition.graph_spec_json`.
- New UI no longer sends users through AgentDefinition Studio or AgentDefinition version pages for Workflow work.

---

## Phase R1: Finish And Verify Embed Workflow Runtime Cutover

**Purpose:** Close the currently active runtime integration slice before larger cleanup.

**Files:**
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/EmbedChatController.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/EmbedWorkflowRuntimeService.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeService.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/EmbedChatControllerAuditTest.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeServiceTest.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/EmbedWorkflowRuntimeServiceTest.java`

- [x] **Step 1: Make the Workflow path the asserted primary path**

Update `EmbedChatControllerAuditTest` so the Workflow-bound session verifies:

```text
EmbedWorkflowRuntimeService.resolveRunnableWorkflowContext(session, intentType)
WorkflowRuntimeService.execute(WorkflowRuntimeRequest)
```

and verifies the legacy `AgentRouter.executeByDefinition(...)` fallback is not called for a resolved Workflow.

- [x] **Step 2: Assert runtime metadata**

The response and/or recorded event metadata must contain:

```text
entryAgentId
entryAgentKeySlug
resolvedWorkflowId
workflowKeySlug
workflowVersionId
bindingId
bindingType
pageKey
route
```

This is important because RunOps, diagnostics, and support screens need to answer "which Workflow actually ran?"

- [x] **Step 3: Keep legacy fallback narrowly named**

Any fallback that still reads `AgentDefinition` should stay behind methods named like:

```java
resolveLegacyAgentDefinition(...)
```

No new Workflow behavior should call this path.

- [x] **Step 4: Verify**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent-service "-Dtest=EmbedChatControllerAuditTest,WorkflowRuntimeServiceTest,EmbedWorkflowRuntimeServiceTest" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: all tests pass and compile succeeds.

---

## Phase R2: Move Studio AI Generate/Edit And Debug APIs To Workflow

**Purpose:** Workflow Studio must not depend on `/api/agent/studio/*`.

**Files:**
- Create or modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowStudioDraftController.java`
- Create or modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowStudioDebugController.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/AgentStudioDraftController.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/AgentStudioDebugController.java`
- Reuse: `ai-agent-service/src/main/java/com/enterprise/ai/agent/studio/LlmWorkflowDraftGenerator.java`
- Reuse: `ai-agent-service/src/main/java/com/enterprise/ai/agent/studio/WorkflowDraftEditService.java`
- Modify: `ai-admin-front/src/api/workflow.ts`
- Modify: `ai-admin-front/src/types/workflow.ts`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/WorkflowStudioDraftControllerTest.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/WorkflowStudioDebugControllerTest.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/AgentStudioCompatibilityControllerTest.java`

- [x] **Step 1: Add Workflow-owned endpoints**

Add these endpoints on the Workflow side:

```text
POST /api/workflows/studio/generate-draft
POST /api/workflows/studio/edit-draft
POST /api/workflows/studio/debug-node
POST /api/workflows/studio/debug-run
```

The request should carry `workflowId` when editing/debugging an existing Workflow. For generation, `projectId/projectCode`, `workflowType`, `pageActions`, and user prompt are enough.

- [x] **Step 2: Convert request contracts away from AgentDefinition**

Debug requests should accept Workflow context:

```text
workflowId
graphSpecJson or graphSpec
canvasJson
nodeId
input
principal/page metadata when needed
```

They should not require `AgentDefinition`.

- [x] **Step 3: Keep old Agent Studio endpoints as compatibility wrappers**

Existing `/api/agent/studio/generate-draft`, `/edit-draft`, `/debug-node`, and `/debug-run` can remain temporarily, but each should either:

- delegate to the Workflow implementation, or
- be marked deprecated and used only by old `AgentStudio.vue`.

Do not duplicate business logic between Agent and Workflow controllers.

- [x] **Step 4: Verify**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=*WorkflowStudio*,*WorkflowDraft*,*AgentStudio*" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: Workflow endpoints work and old endpoint tests either pass or are explicitly updated as compatibility expectations.

---

## Phase R3: Full Frontend Studio Migration To Workflow

**Purpose:** The user-facing Studio should be Workflow-first, not a trimmed JSON editor plus a hidden old Agent Studio.

**Files:**
- Modify heavily: `ai-admin-front/src/views/workflow/WorkflowStudio.vue`
- Modify: `ai-admin-front/src/api/workflow.ts`
- Modify: `ai-admin-front/src/types/workflow.ts`
- Modify or replace: `ai-admin-front/src/views/agent/AgentStudioCompatibility.vue`
- Modify: `ai-admin-front/src/views/agent/AgentStudio.vue`
- Modify: `ai-admin-front/src/router/index.ts`
- Modify: `ai-admin-front/src/views/layout/MainLayout.vue`

- [ ] **Step 1: Port the real canvas/editor capabilities**

Move the useful Studio behavior from `AgentStudio.vue` into `WorkflowStudio.vue`:

```text
canvas editing
node config panels
GraphSpec/canvas conversion
AI generate draft preview/apply
AI edit draft preview/apply
node debug
whole graph debug
publish validation entry
version entry
```

While porting, replace all route and API assumptions:

```text
agentId -> workflowId
getAgent/updateAgent -> getWorkflowStudio/saveWorkflowStudio
publishAgentVersion -> publishWorkflowVersion
validateAgentRelease -> validateWorkflowVersion or validateWorkflowRuntime
/api/agent/studio/* -> /api/workflows/studio/*
```

- [x] 2026-06-14 progress: `WorkflowStudio.vue` now has Workflow-owned AI draft generation/edit preview/apply and node/whole-run debug controls backed by `ai-admin-front/src/api/workflow.ts`.
- [x] 2026-06-14 progress: `WorkflowStudio.vue` now renders a Vue Flow visual canvas through `ai-admin-front/src/utils/workflowStudio.ts`, supports adding/connecting nodes, basic node label/description/output alias editing, and saves by converting canvas back into Workflow GraphSpec/canvas JSON.
- [x] 2026-06-14 progress: `WorkflowStudio.vue` now directly reuses the mature old Agent Studio `NodeConfigPanel.vue` stack, including LLM, user input, interaction, page action, tool, condition and related per-node config panels. This is the preferred migration style: copy or reuse polished Studio implementation, then cut route/API/domain semantics from Agent to Workflow.
- [x] 2026-06-14 progress: reused config panels now load model, knowledge, tool, capability, credential, and API Graph parameter-hint resources; Workflow Studio AI draft/edit also receives tool/capability/knowledge context from the same resource lists.
- [x] 2026-06-14 progress: Workflow Studio now restores old Studio-style undo/redo, copy/paste, protected delete shortcuts, and selection-aware AI edit scope display; AI edit requests send selected node/edge ids instead of silently editing only the whole draft.
- [x] 2026-06-14 progress: Workflow Studio now restores old Studio-style canvas search, focus, auto-layout, and node collapse/expand operations.
- [x] 2026-06-14 progress: Workflow Studio now restores richer debug session playback with run summary, replayable step list, selected-step payload details, and canvas node run-status highlighting.

- [ ] **Step 2: Remove Agent identity editing from Workflow Studio**

Workflow Studio may edit Workflow name/description/type/runtime/default resource config, but it must not edit:

```text
Agent visibility
Agent allowed roles
Agent embed policy
Agent system prompt as entry identity
Agent graphSpec/canvasJson
```

- [ ] **Step 3: Turn old Agent Studio into compatibility guidance**

`/agent/:agentId/studio` should no longer silently edit `AgentDefinition.graphSpec`.

Behavior:

- if the Agent has exactly one relevant enabled binding, redirect to `/workflows/:workflowId/studio`;
- otherwise show links to `/agents/:agentId/bindings` and `/workflows`.

- [x] **Step 4: Update layout route detection**

`MainLayout.vue` currently treats `route.name === 'AgentStudio'` as the Studio page. Update this so Workflow Studio receives the same layout affordances.

- [x] 2026-06-14 progress: `MainLayout.vue` now treats `WorkflowStudio` as a full Studio page and exposes global Workflow access under the `智能体与编排` menu group; project detail also links into `/workflows?projectCode=...`.
- [x] 2026-06-14 progress: `WorkflowList.vue` now provides a `New Workflow` entry; project-scoped lists prefill the current `projectCode`, global lists allow manual `projectCode`, and successful creation opens Workflow Studio.

- [ ] **Step 5: Verify**

Run:

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
npm run build
```

Expected: typecheck and build pass. Remaining references to old Agent Studio must be only compatibility code.

---

## Phase R4: Move Page-Action Reference Diagnostics To Workflow

**Purpose:** Page-action reference lookup is one of the clearest remaining old-model leaks.

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceService.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/PlatformEmbedOpsController.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceServiceTest.java`
- Test: add or update `PlatformEmbedOpsController` audit tests if present.
- Modify: `ai-admin-front/src/api/embedOps.ts`
- Modify: `ai-admin-front/src/views/settings/EmbedOpsMonitor.vue`

- [x] **Step 1: Add Workflow reference scanner**

Scan:

```text
ai_workflow.graph_spec_json
active ai_workflow_version.graph_spec_snapshot_json
```

for `PAGE_ACTION` nodes with:

```text
config.pageKey
config.actionKey
```

The result should include:

```text
workflowId
workflowKeySlug
workflowName
workflowStatus
workflowVersionId
nodeId
nodeName
pageKey
actionKey
entryAgentId
entryAgentKeySlug
bindingId
bindingType
bindingEnabled
```

- [x] **Step 2: Join binding context**

For each referenced Workflow, join `ai_agent_workflow_binding` to show which entry Agents can route to it. This is what makes diagnostics answer the product question, not just the graph question.

- [x] **Step 3: Replace controller old scan**

`PlatformEmbedOpsController` must stop depending on:

```java
AgentDefinitionEntity
AgentDefinitionMapper
AgentDefinitionEntity::getGraphSpecJson
```

for page-action references.

- [x] **Step 4: Update frontend monitor**

The page-action detail drawer should display "consuming Workflows" instead of "referencing Agents":

```text
Workflow
Entry Agent
Binding type
Binding enabled/disabled
Node id/name
Version/source
```

- [x] **Step 5: Verify**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowActionReferenceServiceTest,*PlatformEmbedOps*" test
mvn -pl ai-agent-service "-DskipTests" compile
cd ai-admin-front
npm run build
```

Expected: no new page-action diagnostic path scans `agent_definition.graph_spec_json`.

---

## Phase R5: Direct LangGraph Workflow Runtime Adapter

**Purpose:** Remove the transitional "Workflow -> temporary AgentDefinition shell -> AgentRouter" execution model from the primary Workflow path.

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/LangGraphWorkflowRuntimeAdapter.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeService.java`
- Modify carefully: `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/LangGraph4jRuntimeAdapter.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/LangGraphWorkflowRuntimeAdapterTest.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeServiceTest.java`

- [ ] **Step 1: Extract a GraphSpec execution core**

Only extract the part of `LangGraph4jRuntimeAdapter` that truly executes GraphSpec nodes. Keep the extraction narrow:

```text
GraphSpec
message/input
session id
user/principal
roles
metadata
model/tool context
```

Do not redesign every node in this phase.

- [ ] **Step 2: Add Workflow adapter**

`LangGraphWorkflowRuntimeAdapter` should accept Workflow runtime context and read GraphSpec from:

```text
active WorkflowVersion.graphSpecSnapshotJson first
WorkflowDefinition.graphSpecJson for debug/draft fallback only when allowed
```

- [ ] **Step 3: Keep AgentScope separate**

Do not force `AgentScopeRuntimeAdapter` through Workflow. AgentScope remains an Agent/autonomous runtime until a separate product decision says otherwise.

- [ ] **Step 4: Remove shell from primary execution**

`WorkflowRuntimeService.execute(...)` should call the Workflow adapter directly. A `toExecutionShell(...)` helper may remain only for old tests or fallback wrappers, and should be marked transitional.

- [ ] **Step 5: Verify**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=LangGraphWorkflowRuntimeAdapterTest,WorkflowRuntimeServiceTest,*LangGraph*,*EmbedChat*" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: new Workflow runtime path no longer needs `AgentDefinition.graphSpec`.

---

## Phase R6: Agent UI Becomes Entry-Agent Only

**Purpose:** Remove Workflow editing responsibilities from Agent management screens.

**Files:**
- Modify: `ai-admin-front/src/views/agent/AgentEdit.vue`
- Modify: `ai-admin-front/src/views/agent/AgentList.vue`
- Modify: `ai-admin-front/src/api/agent.ts` or create `ai-admin-front/src/api/agentEntry.ts`
- Modify: `ai-admin-front/src/types/agent.ts`
- Modify: `ai-admin-front/src/views/agent/AgentWorkflowBindings.vue`
- Modify: `ai-admin-front/src/views/registry/RegistryProjectDetail.vue`

- [ ] **Step 1: Agent form fields**

Keep:

```text
project ownership
keySlug/name/description
agentKind
visibility
enabled
systemPrompt as entry persona
default model if needed
allowed roles
embed/entry config
```

Remove from the new Agent UI:

```text
runtimeType=LANGGRAPH4J
agentMode=WORKFLOW
graphSpec
canvasJson
node type selectors
workflow publish controls
```

- [ ] **Step 2: Switch new Agent UI to `/api/agents`**

The new Agent list/edit flow should call `AgentEntryController`, not `/api/agent/definitions`.

- [ ] **Step 3: Binding management becomes the place to explain page routing**

`AgentWorkflowBindings.vue` should show:

```text
binding type
workflow
pageKey
routePattern
actionKey
intentType
priority
enabled
resolve preview
```

- [ ] **Step 4: Project detail closed-loop entries**

`RegistryProjectDetail.vue` should make the intended loop obvious:

```text
AI 快速接入 -> one global entry Agent
创建页面助手 -> page actions + page Workflow
页面能力挂载/绑定 -> AgentWorkflowBindings
Workflow Studio -> selected Workflow
```

- [ ] **Step 5: Verify**

Run:

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
npm run build
```

Expected: new navigation does not require AgentDefinition graph editing.

---

## Phase R7: SDK And Page Assistant Acceptance Hardening

**Purpose:** Make sure the onboarding loop the user sees matches the new model end to end.

**Files:**
- Modify if needed: `ai-admin-front/src/views/registry/SdkAccessWizard.vue`
- Modify if needed: `ai-admin-front/src/views/registry/PageAssistantWizard.vue`
- Modify if needed: `ai-admin-front/src/views/registry/pageAssistantOnboardingPrompt.ts`
- Modify if needed: `ai-agent-service/src/main/resources/ai-assist/skills/reachai-onboarding/**`
- Modify if needed: `ai-agent-service/src/main/resources/ai-assist/skills/reachai-page-assistant-onboarding/**`
- Test: existing `AiAssistControllerTest`, `AiAccessSessionServiceTest`, and prompt/script smoke checks.

- [ ] **Step 1: SDK quick access wording**

The SDK "使用 AI 快速接入" path must consistently say:

```text
one global AI button
one entry Agent
pageKey routes to Workflow
business frontend does not select workflowId
page assistants add page capabilities and bindings
```

- [ ] **Step 2: Page assistant wording**

The page-assistant path must consistently say:

```text
inspect current page
register page and page actions
create or select PAGE_ASSISTANT Workflow
bind Workflow to global entry Agent by pageKey/action/intent
verify page action loop on current pageInstanceId
```

- [ ] **Step 3: Manifest contract check**

Manifests should expose:

```text
agentWorkflow.globalAgentKeySlug
agentWorkflow.endpoints.agentsUrl
agentWorkflow.endpoints.workflowsUrl
agentWorkflow.endpoints.globalAgentBindingsUrl
agentWorkflow.endpoints.resolvePreviewUrl
```

They should not encourage business code to choose `workflowId`.

- [ ] **Step 4: Verify**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=AiAssistControllerTest,AiAccessSessionServiceTest,PlatformAuthInterceptorAuditTest" test
cd ai-admin-front
npm run build
node scripts/check-page-assistant-prompt.mjs
git diff --check
```

Expected: prompt, manifest, backend responses, and frontend build agree on the global Agent plus page-routed Workflow model.

---

## Phase R8: Retire Old AgentDefinition Workflow Responsibilities

**Purpose:** Make old AgentDefinition Workflow behavior impossible to accidentally use from new product paths.

**Files:**
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/AgentManageController.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/AgentVersionController.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/agent/AgentDefinitionService.java`
- Modify or delete when safe: `ai-admin-front/src/views/agent/AgentStudio.vue`
- Modify or delete when safe: `ai-admin-front/src/views/agent/AgentVersions.vue`
- Modify docs under `docs/`

- [ ] **Step 1: Search old responsibilities**

Run:

```powershell
rg -n "/api/agent/studio|AgentStudio|AgentVersions|publishAgentVersion|validateAgentRelease|AgentDefinitionEntity|getGraphSpecJson|agent_definition\\.graph_spec_json" ai-agent-service/src/main/java ai-admin-front/src docs sql/init2.sql
```

Classify every match as one of:

```text
legacy compatibility
autonomous Agent path
must migrate
must delete
```

- [ ] **Step 2: Deprecate or remove old workflow APIs**

Old AgentDefinition APIs should not be the main path for:

```text
Workflow Studio
Workflow publish/rollback
Workflow validation
Workflow runtime
page-action reference diagnostics
SDK graph sync
page assistant binding
```

- [ ] **Step 3: Update docs**

Docs that still describe `AgentDefinition.graphSpec` as the normal Workflow owner should be updated to the new model. Keep legacy notes explicit and short.

- [ ] **Step 4: Verify no new UI imports old workflow actions**

Run:

```powershell
rg -n "publishAgentVersion|validateAgentRelease|generateWorkflowDraft|editWorkflowDraft|debugAgentNode|debugAgentRun" ai-admin-front/src/views ai-admin-front/src/api
```

Expected: matches are either gone or only inside deprecated compatibility files.

- [ ] **Step 5: Final targeted verification**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent-service "-Dtest=*Workflow*,*EmbedChat*,*AiAssist*,*AiRegistry*,*AgentReleaseValidation*" test
mvn -pl ai-agent-service "-DskipTests" compile
cd ai-admin-front
npx vue-tsc --noEmit
npm run build
git diff --check
```

Expected: all targeted tests, compile, typecheck, build, and diff hygiene pass.

---

## Recommended Execution Order

1. R1 first, because runtime metadata and tests make the rest observable.
2. R4 before deep cleanup, because page-action diagnostics are a clear old-model leak and small enough to isolate.
3. R2 and R3 together, because backend Workflow Studio APIs and frontend Workflow Studio must line up.
4. R6 after R3, because Agent UI can only become entry-only once Workflow UI is genuinely useful.
5. R5 when the shell adapter becomes the main remaining runtime debt.
6. R7 near the end, after the actual product path is stable enough for prompt/manifest wording to be final.
7. R8 last, when searches prove new UI/runtime/docs no longer rely on old AgentDefinition Workflow behavior.

## Practical Checkpoint Definition

After each phase, capture:

```text
what changed
which old dependency was removed
which compatibility path remains
which tests/builds passed
which search command proves the coupling moved down
```

Do not declare the whole decoupling complete until:

- `/api/workflows/**` owns Studio, generation/editing, validation, publishing, and runtime.
- `PlatformEmbedOpsController` no longer scans `AgentDefinitionEntity.graphSpecJson`.
- new frontend routes do not require `AgentStudio.vue` or `AgentVersions.vue` for Workflow work.
- LangGraph Workflow execution has a direct Workflow adapter or a clearly isolated transitional shell with no new callers.
- SDK quick access and page assistant both describe the same global Agent plus page-routed Workflow loop.
