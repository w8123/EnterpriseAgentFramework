# Agent Workflow Decoupling Follow-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the Agent/Workflow split after phase 1 by moving Studio, publishing, runtime, page-action diagnostics, and old `AgentDefinition` responsibilities onto first-class Workflow APIs.

**Architecture:** Phase 1 already created the new tables, workflow package, CRUD APIs, resolver, embed runtime bridge, SDK graph sync to `ai_workflow`, page-assistant workflow binding, and SDK global-button page routing. This follow-up plan removes the remaining coupling: Studio still edits `AgentDefinition.graphSpec`, publishing still centers on `agent_version`, runtime still adapts through `AgentDefinition`, and page-action references still scan old Agent graph JSON. The plan keeps old APIs available only as temporary compatibility surfaces until each new path has tests and UI coverage.

**Tech Stack:** Java 17, Spring Boot, MyBatis Plus, JUnit 5, Mockito, Vue 3, Element Plus, TypeScript, Vite, MySQL baseline `sql/init2.sql`.

---

## Current Completed Baseline

These items are already done and should not be reimplemented:

- New SQL baseline: `sql/init2.sql`.
- New backend package: `com.enterprise.ai.agent.workflow`.
- New entities/mappers/services/controllers for `ai_agent`, `ai_workflow`, `ai_workflow_version`, and `ai_agent_workflow_binding`.
- Resolver for Agent to Workflow binding.
- SDK graph sync writes `ai_workflow`.
- Embed chat can resolve a runnable Workflow from new Agent/Workflow binding before falling back to old `AgentDefinition`.
- Page assistant register/catalog sync creates or reuses global embedded Agent, page Workflow, and page binding.
- SDK frontend sends `pageKey` via `createEafChat({ page })`.
- `SdkAccessWizard.vue` and packaged onboarding docs describe one global AI button routed by `pageKey`.

## Target End State

- `Agent` is only the entry/governance object.
- `Workflow` owns GraphSpec, canvas, Studio editing, validation, debug, publishing, and rollback.
- `LangGraph4j` executes Workflow GraphSpec directly.
- Embed chat resolves `agentId + pageKey + route + action/intent` to a Workflow and records the resolved workflow/binding in session/event metadata.
- Page-action reference diagnostics query Workflow graph JSON and bindings, not `AgentDefinitionEntity.graphSpecJson`.
- New frontend routes use `/api/agents`, `/api/workflows`, `/api/workflows/{workflowId}/versions`, and `/api/agents/{agentId}/workflow-bindings`.
- Old `/api/agent/definitions` remains only as a deprecated compatibility surface until the new UI is fully cut over.

## File Structure

### Backend New Or Refactored Files

- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowReleaseValidationService.java`
  - Owns GraphSpec validation for Workflow publishing and debug preflight.
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeRequest.java`
  - Carries Agent entry context, Workflow definition/version, session context, message, page context, and metadata.
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeService.java`
  - Public service for resolving active Workflow version and executing Workflow runtime.
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/LangGraphWorkflowRuntimeAdapter.java`
  - Thin Workflow-facing adapter. It can initially delegate to `LangGraph4jRuntimeAdapter` using a temporary `AgentDefinition` shell, then later become direct.
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowStudioService.java`
  - Save/load Studio graph/canvas for `ai_workflow`.
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceService.java`
  - Finds page/action references from Workflow GraphSpec and binding context.
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowDefinitionController.java`
  - Add Studio save/load, node types, validation, draft generation/edit endpoints.
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowVersionController.java`
  - Add validate/publish/rollback endpoints.
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/EmbedChatController.java`
  - Replace temporary shell execution path with `WorkflowRuntimeService`.
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/PlatformEmbedOpsController.java`
  - Switch page-action reference diagnostics from old Agent graph scan to Workflow references.
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/agent/AgentManageController.java`
  - Deprecate graph/studio/version endpoints after the new Workflow endpoints are live.

### Frontend New Or Refactored Files

- Modify: `ai-admin-front/src/types/workflow.ts`
  - Add Studio, version, validation, debug, and binding preview types.
- Modify: `ai-admin-front/src/api/workflow.ts`
  - Add Workflow Studio, validation, generation/edit, version publish/rollback APIs.
- Create: `ai-admin-front/src/api/agentWorkflowBinding.ts`
  - Typed wrapper around `/api/agents/{agentId}/workflow-bindings`.
- Create: `ai-admin-front/src/views/workflow/WorkflowList.vue`
  - Workflow catalog by project/type/status.
- Create: `ai-admin-front/src/views/workflow/WorkflowStudio.vue`
  - Workflow-first replacement for `AgentStudio.vue`.
- Create: `ai-admin-front/src/views/workflow/WorkflowVersions.vue`
  - Workflow publishing/rollback page.
- Create: `ai-admin-front/src/views/agent/AgentWorkflowBindings.vue`
  - Entry Agent binding management and resolve-preview UI.
- Modify: `ai-admin-front/src/views/agent/AgentEdit.vue`
  - Remove direct GraphSpec/canvas editing responsibilities.
- Modify: `ai-admin-front/src/router/index.ts`
  - Add `/workflows`, `/workflows/:workflowId/studio`, `/workflows/:workflowId/versions`, `/agents/:agentId/bindings`.
- Modify: `ai-admin-front/src/views/registry/RegistryProjectDetail.vue`
  - Add entry points for Workflows and Agent bindings.
- Modify: `ai-admin-front/src/views/settings/EmbedOpsMonitor.vue`
  - Show page-action references from Workflow diagnostics.

### Tests

- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowReleaseValidationServiceTest.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeServiceTest.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowStudioServiceTest.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceServiceTest.java`
- Modify: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/EmbedChatControllerAuditTest.java`
- Add TypeScript contract tests under `ai-admin-front/src/types/*.test-d.ts` where useful.

---

## Phase 2: Workflow Studio From Agent Studio

### Task 2.1: Backend Workflow Studio Save/Load API

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowStudioService.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowDefinitionController.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowStudioServiceTest.java`

- [ ] **Step 1: Write failing tests**

Add tests that prove Studio updates only `ai_workflow.graph_spec_json` and `ai_workflow.canvas_json`, not `agent_definition`.

Test cases:

```java
@Test
void saveStudioDraftUpdatesWorkflowGraphAndCanvas() {
    WorkflowDefinitionService workflowDefinitionService = mock(WorkflowDefinitionService.class);
    WorkflowStudioService service = new WorkflowStudioService(workflowDefinitionService, new ObjectMapper());
    WorkflowDefinitionEntity current = new WorkflowDefinitionEntity();
    current.setId("wf-1");
    current.setKeySlug("orders-page");
    current.setName("Orders Page");
    current.setGraphSpecJson("{\"nodes\":[]}");
    current.setCanvasJson("{\"nodes\":[]}");
    when(workflowDefinitionService.findById("wf-1")).thenReturn(Optional.of(current));
    when(workflowDefinitionService.update(eq("wf-1"), any(WorkflowDefinitionEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(1));

    WorkflowDefinitionEntity saved = service.saveStudioDraft("wf-1", new WorkflowStudioService.WorkflowStudioSaveRequest(
            "{\"nodes\":[{\"id\":\"start\"}]}",
            "{\"nodes\":[{\"id\":\"start\",\"x\":0,\"y\":0}]}",
            null));

    assertTrue(saved.getGraphSpecJson().contains("start"));
    assertTrue(saved.getCanvasJson().contains("\"x\":0"));
    verify(workflowDefinitionService).update(eq("wf-1"), any(WorkflowDefinitionEntity.class));
}
```

- [ ] **Step 2: Run RED**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowStudioServiceTest" test
```

Expected: compilation fails because `WorkflowStudioService` does not exist.

- [ ] **Step 3: Implement service**

Implement a small service that:

- Requires `workflowId`.
- Loads `WorkflowDefinitionEntity`.
- Validates graph JSON is non-empty.
- Writes `graphSpecJson`, `canvasJson`, and optional `extraJson`.
- Delegates persistence to `WorkflowDefinitionService.update`.

- [ ] **Step 4: Add controller endpoints**

Add to `WorkflowDefinitionController`:

```text
GET  /api/workflows/{id}/studio
PUT  /api/workflows/{id}/studio
GET  /api/workflows/graph-node-types
POST /api/workflows/runtime-validation
```

`GET /studio` returns workflow id, graphSpecJson, canvasJson, status, runtimeType, and managedBy.

- [ ] **Step 5: Run GREEN**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowStudioServiceTest" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: tests and compile pass.

### Task 2.2: Frontend Workflow Types And API

**Files:**
- Modify: `ai-admin-front/src/types/workflow.ts`
- Modify: `ai-admin-front/src/api/workflow.ts`
- Test: `ai-admin-front/src/types/workflow.test-d.ts`

- [ ] **Step 1: Add TypeScript contract test**

Create a type-level test that imports `WorkflowDefinition`, `WorkflowStudioState`, `WorkflowVersion`, and `WorkflowValidationResult`.

Expected shape:

```ts
const studio: WorkflowStudioState = {
  workflowId: 'wf-1',
  graphSpecJson: '{"nodes":[]}',
  canvasJson: '{"nodes":[]}',
  runtimeType: 'LANGGRAPH4J',
  status: 'DRAFT',
  managedBy: 'MANUAL',
}
```

- [ ] **Step 2: Run RED**

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
```

Expected: missing type errors.

- [ ] **Step 3: Implement types and API methods**

Add:

```ts
export interface WorkflowStudioState {
  workflowId: string
  graphSpecJson: string
  canvasJson?: string | null
  runtimeType: WorkflowRuntimeType
  status: WorkflowStatus
  managedBy: WorkflowManagedBy
}
```

Add API methods:

```ts
export function getWorkflowStudio(id: string)
export function saveWorkflowStudio(id: string, data: WorkflowStudioSaveRequest)
export function getWorkflowGraphNodeTypes()
export function validateWorkflowRuntime(data: WorkflowRuntimeValidationRequest)
```

- [ ] **Step 4: Run GREEN**

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
```

Expected: pass.

### Task 2.3: Create `WorkflowStudio.vue`

**Files:**
- Create: `ai-admin-front/src/views/workflow/WorkflowStudio.vue`
- Modify: `ai-admin-front/src/router/index.ts`
- Reuse carefully from: `ai-admin-front/src/views/agent/AgentStudio.vue`

- [ ] **Step 1: Copy only as a starting point**

Copy the layout and canvas logic from `AgentStudio.vue`, then immediately replace:

- Route param: `agentId` -> `workflowId`.
- Load API: `getAgent` -> `getWorkflowStudio`.
- Save API: `updateAgent` -> `saveWorkflowStudio`.
- Publish link: `/agent/:id/versions` -> `/workflows/:workflowId/versions`.
- Page text: "Agent Studio" -> "Workflow Studio".

- [ ] **Step 2: Remove Agent identity editing from Studio**

Workflow Studio should not edit:

- Agent name.
- Agent kind.
- Agent visibility.
- Agent allowed roles.
- Agent embed policy.

It should edit:

- Workflow name/description when needed.
- GraphSpec.
- Canvas JSON.
- Workflow type.
- Runtime type.
- Default model/resource config.

- [ ] **Step 3: Add route**

Add:

```ts
{
  path: '/workflows/:workflowId/studio',
  name: 'WorkflowStudio',
  component: () => import('@/views/workflow/WorkflowStudio.vue'),
}
```

- [ ] **Step 4: Verify frontend build**

```powershell
cd ai-admin-front
npm run build
```

Expected: build passes.

### Task 2.4: Keep `AgentStudio.vue` As Compatibility Redirect

**Files:**
- Modify: `ai-admin-front/src/views/agent/AgentStudio.vue`
- Modify or create helper: `ai-admin-front/src/utils/workflowCompatibility.ts`

- [ ] **Step 1: Add a compatibility lookup**

When old route opens `/agent/:agentId/studio`, fetch bindings for the Agent:

```ts
GET /api/agents/{agentId}/workflow-bindings
```

If exactly one enabled default/page binding exists, redirect to:

```text
/workflows/{workflowId}/studio
```

If none or multiple bindings exist, show a compatibility notice with links to Agent bindings and Workflow list.

- [ ] **Step 2: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: old Studio no longer silently edits `AgentDefinition.graphSpec`.

---

## Phase 3: Workflow Versions And Publishing

### Task 3.1: Workflow Release Validation Service

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowReleaseValidationService.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowReleaseValidationServiceTest.java`
- Reuse from: `ai-agent-service/src/main/java/com/enterprise/ai/agent/agent/AgentReleaseValidationService.java`

- [ ] **Step 1: Write failing tests**

Test that:

- Missing GraphSpec fails.
- Missing entry node fails.
- `PAGE_ACTION` with missing `pageKey` or `actionKey` fails.
- `PAGE_ACTION` validates against `eaf_page_action_registry`.
- Validation does not require an `AgentDefinition`.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowReleaseValidationServiceTest" test
```

Expected: compilation fails.

- [ ] **Step 3: Extract validator**

Move the GraphSpec-specific validation from `AgentReleaseValidationService` into `WorkflowReleaseValidationService`.

Keep `AgentReleaseValidationService` temporarily as a wrapper that converts old `AgentDefinition` to a validation input, so old tests remain useful during transition.

- [ ] **Step 4: Run validation tests**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowReleaseValidationServiceTest,AgentReleaseValidationServiceTest" test
```

Expected: both old and new validation tests pass.

### Task 3.2: Workflow Version Publish/Rollback

**Files:**
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowVersionService.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/WorkflowVersionController.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowVersionServiceTest.java`

- [ ] **Step 1: Add tests**

Test that publishing:

- Validates the Workflow.
- Creates a new `ai_workflow_version` row.
- Stores `graph_spec_snapshot_json`.
- Stores `canvas_snapshot_json`.
- Sets published version status to `ACTIVE`.
- Retires previous active version for the same workflow.
- Updates `ai_workflow.status` to `ACTIVE`.

- [ ] **Step 2: Implement publish**

Add:

```java
public WorkflowVersionEntity publish(String workflowId, WorkflowPublishRequest request)
```

`WorkflowPublishRequest` fields:

- `publishedBy`
- `note`
- `rolloutPercent`

- [ ] **Step 3: Implement rollback**

Add:

```java
public WorkflowVersionEntity rollback(String workflowId, String versionId, String operator)
```

Rollback sets selected version `ACTIVE`, retires others, and restores Workflow graph/canvas snapshots.

- [ ] **Step 4: Add API**

```text
POST /api/workflows/{workflowId}/versions/publish
POST /api/workflows/{workflowId}/versions/{versionId}/rollback
POST /api/workflows/{workflowId}/versions/validate
```

- [ ] **Step 5: Verify**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowVersionServiceTest,WorkflowReleaseValidationServiceTest" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: pass.

### Task 3.3: Frontend Workflow Versions Page

**Files:**
- Create: `ai-admin-front/src/views/workflow/WorkflowVersions.vue`
- Modify: `ai-admin-front/src/api/workflow.ts`
- Modify: `ai-admin-front/src/router/index.ts`

- [ ] **Step 1: Add API methods**

```ts
export function listWorkflowVersions(workflowId: string)
export function publishWorkflowVersion(workflowId: string, data: WorkflowPublishRequest)
export function rollbackWorkflowVersion(workflowId: string, versionId: string, operator?: string)
export function validateWorkflowVersion(workflowId: string)
```

- [ ] **Step 2: Build page**

Page must show:

- Current workflow name/key/type/status.
- Version table.
- Active version tag.
- Publish button.
- Rollback button.
- Validation result drawer or panel.

- [ ] **Step 3: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: pass.

---

## Phase 4: Workflow Runtime

### Task 4.1: Workflow Runtime Request And Service

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeRequest.java`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeService.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowRuntimeServiceTest.java`

- [ ] **Step 1: Write failing tests**

Test that `WorkflowRuntimeService.execute`:

- Requires Agent entry.
- Requires Workflow definition.
- Uses active Workflow version when present.
- Falls back to draft Workflow graph only when explicitly allowed for debug.
- Passes page context and session metadata into runtime request.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowRuntimeServiceTest" test
```

Expected: compilation fails.

- [ ] **Step 3: Implement request model**

Fields:

```java
String traceId;
String sessionId;
String message;
AgentEntryEntity agent;
WorkflowDefinitionEntity workflow;
WorkflowVersionEntity activeVersion;
Map<String, Object> principal;
Map<String, Object> pageContext;
Map<String, Object> metadata;
```

- [ ] **Step 4: Implement service**

Initial service can delegate to existing `LangGraph4jRuntimeAdapter` through a temporary `AgentDefinition` shell. The shell must be created in one narrow method, for example:

```java
private AgentDefinition toExecutionShell(AgentEntryEntity agent, WorkflowDefinitionEntity workflow, WorkflowVersionEntity version)
```

This method is transitional and should be marked package-private for tests.

- [ ] **Step 5: Run GREEN**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowRuntimeServiceTest" test
```

Expected: pass.

### Task 4.2: Replace Embed Temporary Runtime Bridge

**Files:**
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/EmbedChatController.java`
- Modify or deprecate: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/EmbedWorkflowRuntimeService.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/controller/EmbedChatControllerAuditTest.java`

- [ ] **Step 1: Update tests**

Add assertions that message execution records metadata containing:

- `resolvedWorkflowId`
- `workflowKeySlug`
- `bindingId`
- `pageKey`

- [ ] **Step 2: Implement controller integration**

Replace:

```text
resolveRunnableDefinition(session)
```

with:

```text
resolveAgentEntry(session.agentId)
resolveBinding(agent, pageKey, route, actionKey, intentType)
execute WorkflowRuntimeService
```

Keep old `AgentDefinition` fallback only behind a clearly named method:

```java
private Optional<AgentDefinition> resolveLegacyAgentDefinition(...)
```

- [ ] **Step 3: Verify**

```powershell
mvn -pl ai-agent-service "-Dtest=EmbedChatControllerAuditTest,WorkflowRuntimeServiceTest,EmbedWorkflowRuntimeServiceTest" test
```

Expected: pass.

### Task 4.3: Direct LangGraph Workflow Adapter

**Files:**
- Create or evolve: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/LangGraphWorkflowRuntimeAdapter.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/LangGraph4jRuntimeAdapter.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/LangGraphWorkflowRuntimeAdapterTest.java`

- [ ] **Step 1: Add adapter tests**

Test direct execution path reads `WorkflowDefinitionEntity.graphSpecJson` or active version snapshot without needing `AgentDefinition.graphSpec`.

- [ ] **Step 2: Extract GraphSpec execution core**

Move code that only depends on GraphSpec/node execution from `LangGraph4jRuntimeAdapter` into shared methods usable by Workflow adapter.

Do not rewrite all node execution in one pass. Extract only enough to remove `AgentDefinition` from the top-level Workflow adapter.

- [ ] **Step 3: Verify runtime tests**

```powershell
mvn -pl ai-agent-service "-Dtest=LangGraphWorkflowRuntimeAdapterTest,EmbedChatControllerAuditTest" test
```

Expected: pass.

---

## Phase 5: Page Action References And Diagnostics

### Task 5.1: Workflow Action Reference Service

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceService.java`
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/PlatformEmbedOpsController.java`
- Test: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowActionReferenceServiceTest.java`

- [ ] **Step 1: Write tests**

Test that reference lookup finds:

- Workflow id/key/name.
- Node id/name.
- Page key.
- Action key.
- Binding id.
- Entry Agent id/key.
- Whether binding is enabled.

- [ ] **Step 2: Run RED**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowActionReferenceServiceTest" test
```

Expected: compilation fails.

- [ ] **Step 3: Implement GraphSpec scan**

Scan `ai_workflow.graph_spec_json` and active `ai_workflow_version.graph_spec_snapshot_json` for nodes:

```json
{
  "type": "PAGE_ACTION",
  "config": {
    "pageKey": "...",
    "actionKey": "..."
  }
}
```

Join against `ai_agent_workflow_binding` by `workflow_id`.

- [ ] **Step 4: Replace old controller logic**

`PlatformEmbedOpsController` should no longer scan `AgentDefinitionEntity.graphSpecJson` for page-action references. It should call `WorkflowActionReferenceService`.

- [ ] **Step 5: Verify**

```powershell
mvn -pl ai-agent-service "-Dtest=WorkflowActionReferenceServiceTest" test
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: pass.

### Task 5.2: Frontend Embed Ops Monitor Uses Workflow References

**Files:**
- Modify: `ai-admin-front/src/api/embedOps.ts`
- Modify: `ai-admin-front/src/views/settings/EmbedOpsMonitor.vue`

- [ ] **Step 1: Add response type**

Response should include:

```ts
interface PageActionWorkflowReference {
  workflowId: string
  workflowKeySlug?: string | null
  workflowName?: string | null
  nodeId?: string | null
  nodeName?: string | null
  agentId?: string | null
  agentKeySlug?: string | null
  bindingId?: number | null
  bindingType?: string | null
  enabled: boolean
}
```

- [ ] **Step 2: Update UI**

In the page-action detail drawer, show:

- Consuming Workflows.
- Entry Agent.
- Binding type.
- Enabled/disabled state.
- Node id/name.

- [ ] **Step 3: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: pass.

---

## Phase 6: Agent UI And Binding Management

### Task 6.1: Agent Edit Becomes Entry-Agent Only

**Files:**
- Modify: `ai-admin-front/src/views/agent/AgentEdit.vue`
- Modify: `ai-admin-front/src/types/agent.ts`
- Modify: `ai-admin-front/src/api/agent.ts` or create `ai-admin-front/src/api/agentEntry.ts`

- [ ] **Step 1: Remove Workflow fields from Agent form**

Remove or hide:

- `runtimeType`
- `agentMode=WORKFLOW`
- `graphSpec`
- `canvasJson`
- Workflow node type selectors.

Keep:

- name/keySlug.
- project ownership.
- `agentKind`.
- visibility.
- enabled.
- system prompt.
- model default if still used as entry default.
- roles/embed policy.

- [ ] **Step 2: Route create/edit to `/api/agents`**

New Agent UI should use `AgentEntryController`, not `/api/agent/definitions`.

- [ ] **Step 3: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: pass.

### Task 6.2: Binding Management UI

**Files:**
- Create: `ai-admin-front/src/views/agent/AgentWorkflowBindings.vue`
- Create: `ai-admin-front/src/api/agentWorkflowBinding.ts`
- Modify: `ai-admin-front/src/router/index.ts`

- [ ] **Step 1: Add API wrapper**

Methods:

```ts
export function listAgentWorkflowBindings(agentId: string)
export function createAgentWorkflowBinding(agentId: string, data: WorkflowBindingForm)
export function resolveAgentWorkflowBinding(agentId: string, data: WorkflowBindingResolveRequest)
```

- [ ] **Step 2: Build binding table**

Columns:

- binding type.
- workflow.
- pageKey.
- routePattern.
- actionKey.
- intentType.
- priority.
- enabled.

- [ ] **Step 3: Build resolve-preview panel**

Inputs:

- pageKey.
- route.
- actionKey.
- intentType.

Output:

- selected workflow id/key/name.
- binding id.
- match rank.
- priority.

- [ ] **Step 4: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: pass.

### Task 6.3: Project Detail Closed-Loop Entrypoints

**Files:**
- Modify: `ai-admin-front/src/views/registry/RegistryProjectDetail.vue`

- [ ] **Step 1: Add Workflows entry**

Add project-scoped entry to:

```text
/workflows?projectCode={projectCode}
```

- [ ] **Step 2: Add Agent bindings entry**

When project has global embedded Agent, link to:

```text
/agents/{agentId}/bindings
```

If global Agent is not yet created, link to AI quick access / create Agent flow.

- [ ] **Step 3: Verify**

```powershell
cd ai-admin-front
npm run build
```

Expected: pass.

---

## Phase 7: Retire Old AgentDefinition Workflow Responsibilities

### Task 7.1: Mark Old APIs Deprecated

**Files:**
- Modify: `ai-agent-service/src/main/java/com/enterprise/ai/agent/controller/AgentManageController.java`
- Modify docs under `docs/`

- [ ] **Step 1: Add response warnings**

For old graph/studio/version endpoints, add response metadata or headers:

```text
X-ReachAI-Deprecated: AgentDefinition workflow APIs are deprecated. Use /api/workflows.
```

- [ ] **Step 2: Keep old CRUD only for autonomous/legacy Agent use**

Old CRUD should not be referenced by new UI paths.

- [ ] **Step 3: Verify**

```powershell
mvn -pl ai-agent-service "-Dtest=*AgentManageController*,*Workflow*" test
```

Expected: old tests pass or are intentionally updated to new API expectations.

### Task 7.2: Remove Old Studio Routes From Navigation

**Files:**
- Modify route/menu components.
- Modify any links in `ai-admin-front/src/views/**`.

- [ ] **Step 1: Search old links**

```powershell
rg -n "/agent/.*/studio|AgentStudio|AgentVersions|publishAgentVersion|validateAgentRelease|graphSpec" ai-admin-front/src
```

- [ ] **Step 2: Replace links**

Use:

```text
/workflows/{workflowId}/studio
/workflows/{workflowId}/versions
```

- [ ] **Step 3: Verify no new UI path calls old workflow APIs**

```powershell
rg -n "publishAgentVersion|validateAgentRelease|updateAgent\\(" ai-admin-front/src/views ai-admin-front/src/api
```

Expected: remaining matches are only compatibility code or old pages marked deprecated.

### Task 7.3: Remove `AgentDefinition.graphSpec` From New Runtime Path

**Files:**
- Modify runtime classes under `ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime/`
- Modify workflow classes under `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/`

- [ ] **Step 1: Search remaining runtime coupling**

```powershell
rg -n "getAgentDefinition\\(|AgentDefinition|graphSpecJson" ai-agent-service/src/main/java/com/enterprise/ai/agent/runtime ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow
```

- [ ] **Step 2: Move each required dependency to Workflow request/context**

Examples:

- model instance -> workflow default model or agent entry default.
- graph spec -> workflow graph spec or active version snapshot.
- allowed roles -> agent entry.
- tool credentials -> workflow resource config.

- [ ] **Step 3: Verify**

```powershell
mvn -pl ai-agent-service "-Dtest=*WorkflowRuntime*,*EmbedChat*,*LangGraph*" test
```

Expected: pass.

---

## Phase 8: Final Verification And Cutover

### Task 8.1: Backend Full Targeted Verification

**Files:** all backend files touched in follow-up phases.

- [ ] **Step 1: Run targeted workflow suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn -pl ai-agent-service "-Dtest=*Workflow*,*EmbedChat*,*AiAssist*,*AiRegistry*,*AgentReleaseValidation*" test
```

Expected: all targeted tests pass.

- [ ] **Step 2: Compile**

```powershell
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: compile succeeds.

### Task 8.2: Frontend Full Verification

**Files:** all frontend files touched in follow-up phases.

- [ ] **Step 1: Type and production build**

```powershell
cd ai-admin-front
npx vue-tsc --noEmit
npm run build
npm run build:sdk
```

Expected: all pass. Existing Vite large chunk warnings are acceptable unless new warnings identify the changed files.

### Task 8.3: SQL And Documentation Checks

**Files:**
- `sql/init2.sql`
- `sql/README.md`
- `docs/superpowers/specs/2026-06-14-agent-workflow-decoupling-refactor-design.md`
- This plan file.

- [ ] **Step 1: Check schema references**

```powershell
rg -n "ai_agent|ai_workflow|ai_workflow_version|ai_agent_workflow_binding" sql/init2.sql sql/README.md docs/superpowers/specs/2026-06-14-agent-workflow-decoupling-refactor-design.md
```

Expected: all core new tables are documented and present.

- [ ] **Step 2: Check no accidental whitespace errors**

```powershell
git diff --check
```

Expected: pass. CRLF conversion warnings are acceptable.

### Task 8.4: Manual Product Acceptance

**Acceptance scenarios:**

- [ ] A project has one global embedded Agent.
- [ ] The Agent has at least two page Workflow bindings with different `pageKey`.
- [ ] Business frontend mounts one global AI button.
- [ ] Page A sends `pageKey=A` and resolves Workflow A.
- [ ] Page B sends `pageKey=B` and resolves Workflow B.
- [ ] A page action dispatch is delivered only to the current `pageInstanceId`.
- [ ] Workflow Studio edits a Workflow by `workflowId`.
- [ ] Workflow publish creates `ai_workflow_version`.
- [ ] Page-action reference diagnostics show consuming Workflows and entry Agents.
- [ ] New UI no longer needs business frontend to know `workflowId`.

---

## Recommended Execution Order

1. Phase 2: Workflow Studio backend and frontend.
2. Phase 3: Workflow versions and publishing.
3. Phase 4: Workflow Runtime direct path.
4. Phase 5: Page-action reference diagnostics.
5. Phase 6: Agent UI and binding management.
6. Phase 7: Old AgentDefinition retirement.
7. Phase 8: final verification and manual acceptance.

The highest-risk areas are Phase 2 and Phase 4. Do them with small TDD slices and keep old UI/API paths working until the replacement has passed verification.
