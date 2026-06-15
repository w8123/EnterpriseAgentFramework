# Agent Workflow Decoupling Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first executable slice of the Agent/Workflow split: `sql/init2.sql`, backend persistence entities, CRUD services, REST controllers, and binding resolver.

**Architecture:** Keep old `AgentDefinition` code untouched in this phase. Add new first-class `ai_agent`, `ai_workflow`, `ai_workflow_version`, and `ai_agent_workflow_binding` tables plus Java packages under `com.enterprise.ai.agent.workflow`. Runtime execution and frontend migration are later phases.

**Tech Stack:** Java 17, Spring Boot, MyBatis Plus, JUnit 5, Mockito, MySQL SQL baseline.

---

## File Structure

- Create `sql/init2.sql`: new database baseline for Agent/Workflow split.
- Modify `sql/README.md`: document `init2.sql` as the new-database path.
- Create `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/AgentEntryEntity.java`: entry Agent persistence model.
- Create `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowDefinitionEntity.java`: Workflow persistence model.
- Create `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/WorkflowVersionEntity.java`: Workflow version persistence model.
- Create `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/AgentWorkflowBindingEntity.java`: Agent-to-Workflow binding persistence model.
- Create four mapper files in the same package.
- Create `AgentEntryService`, `WorkflowDefinitionService`, `AgentWorkflowBindingService`, and `AgentWorkflowResolver`.
- Create `AgentEntryController`, `WorkflowDefinitionController`, and `AgentWorkflowBindingController`.
- Create tests:
  - `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/AgentEntryServiceTest.java`
  - `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowDefinitionServiceTest.java`
  - `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/AgentWorkflowResolverTest.java`

## Task 1: New Model Service Tests

**Files:**
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/AgentEntryServiceTest.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/WorkflowDefinitionServiceTest.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/workflow/AgentWorkflowResolverTest.java`

- [ ] **Step 1: Write failing tests**

Create tests that expect:

```java
AgentEntryService.create fills id, defaults agentKind to PROJECT_ENTRY, visibility to PROJECT, enabled to true, and validates keySlug.
WorkflowDefinitionService.create fills id, defaults workflowType to CHAT, runtimeType to LANGGRAPH4J, status to DRAFT, managedBy to MANUAL, and stores graphSpecJson.
AgentWorkflowResolver.resolve chooses exact PAGE+ACTION binding before PAGE, INTENT, and DEFAULT bindings.
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=AgentEntryServiceTest,WorkflowDefinitionServiceTest,AgentWorkflowResolverTest" test
```

Expected: compilation fails because new workflow classes do not exist.

## Task 2: SQL Baseline

**Files:**
- Create: `sql/init2.sql`
- Modify: `sql/README.md`

- [ ] **Step 1: Add `init2.sql`**

Create a full new-database baseline with:

```sql
CREATE DATABASE IF NOT EXISTS ai_text_service DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_text_service;

CREATE TABLE IF NOT EXISTS ai_agent (...);
CREATE TABLE IF NOT EXISTS ai_workflow (...);
CREATE TABLE IF NOT EXISTS ai_workflow_version (...);
CREATE TABLE IF NOT EXISTS ai_agent_workflow_binding (...);
```

Include the supporting page/embed tables needed by the new model: `eaf_page_registry`, `eaf_page_action_registry`, `eaf_embed_session`, `eaf_page_action_event`, and `eaf_embed_chat_event`.

- [ ] **Step 2: Update README**

Document:

```text
New Agent/Workflow split database: mysql -uroot -p < sql/init2.sql
Legacy database: mysql -uroot -p < sql/init.sql
```

- [ ] **Step 3: Verify SQL text**

Run:

```powershell
rg -n "CREATE TABLE IF NOT EXISTS `ai_agent`|CREATE TABLE IF NOT EXISTS `ai_workflow`|CREATE TABLE IF NOT EXISTS `ai_agent_workflow_binding`" sql/init2.sql
```

Expected: all three table definitions are found.

## Task 3: Persistence Entities And Mappers

**Files:**
- Create entity and mapper classes under `ai-agent-service/src/main/java/com/enterprise/ai/agent/workflow/`.

- [ ] **Step 1: Implement entities**

Use MyBatis Plus annotations:

```java
@Data
@TableName("ai_agent")
public class AgentEntryEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long projectId;
    private String projectCode;
    private String keySlug;
    private String name;
    private String description;
    private String agentKind;
    private String visibility;
    private String systemPrompt;
    private String modelInstanceId;
    private String allowedRolesJson;
    private String entryConfigJson;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Repeat the same direct-field pattern for the other three tables.

- [ ] **Step 2: Implement mappers**

Each mapper extends `BaseMapper<Entity>`.

## Task 4: Services And Resolver

**Files:**
- Create: `AgentEntryService.java`
- Create: `WorkflowDefinitionService.java`
- Create: `AgentWorkflowBindingService.java`
- Create: `AgentWorkflowResolver.java`

- [ ] **Step 1: Implement create/list/find/update/delete**

Use mapper methods, UUID-based 12-character ids for string ids, defaults from the tests, and simple key slug validation with pattern `[A-Za-z0-9][A-Za-z0-9_-]{1,127}`.

- [ ] **Step 2: Implement resolver**

Ranking order:

```text
PAGE + ACTION exact
PAGE + INTENT exact
PAGE
ROUTE
INTENT
DEFAULT
```

Higher `priority` wins inside the same rank.

- [ ] **Step 3: Run tests to verify GREEN**

Run:

```powershell
mvn -pl ai-agent-service "-Dtest=AgentEntryServiceTest,WorkflowDefinitionServiceTest,AgentWorkflowResolverTest" test
```

Expected: all tests pass.

## Task 5: Controllers

**Files:**
- Create: `AgentEntryController.java`
- Create: `WorkflowDefinitionController.java`
- Create: `AgentWorkflowBindingController.java`

- [ ] **Step 1: Add REST endpoints**

Create:

```text
GET    /api/agents
POST   /api/agents
GET    /api/agents/{id}
PUT    /api/agents/{id}
DELETE /api/agents/{id}

GET    /api/workflows
POST   /api/workflows
GET    /api/workflows/{id}
PUT    /api/workflows/{id}
DELETE /api/workflows/{id}

GET    /api/agents/{agentId}/workflow-bindings
POST   /api/agents/{agentId}/workflow-bindings
POST   /api/agents/{agentId}/workflow-bindings/resolve-preview
```

Return entities directly for this phase to keep the slice small.

- [ ] **Step 2: Compile module**

Run:

```powershell
mvn -pl ai-agent-service "-DskipTests" compile
```

Expected: compile succeeds.

## Task 6: Final Verification

**Files:**
- All files touched in this phase.

- [ ] **Step 1: Run targeted tests**

```powershell
mvn -pl ai-agent-service "-Dtest=AgentEntryServiceTest,WorkflowDefinitionServiceTest,AgentWorkflowResolverTest" test
```

- [ ] **Step 2: Run compile**

```powershell
mvn -pl ai-agent-service "-DskipTests" compile
```

- [ ] **Step 3: Run document and whitespace checks**

```powershell
rg -n "init2.sql|ai_agent|ai_workflow|ai_agent_workflow_binding" docs/superpowers/specs/2026-06-14-agent-workflow-decoupling-refactor-design.md sql/README.md sql/init2.sql
git diff --check
```
