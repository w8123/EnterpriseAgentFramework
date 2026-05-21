# Agent Studio Eval MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Studio-embedded evaluation loop so an AI-generated workflow draft can be tested against imported datasets before publish.

**Architecture:** Store datasets, cases, runs, per-case results, and reports in new `agent_eval_*` tables. Reuse `LangGraph4jRuntimeAdapter.debugRun(...)` as the execution engine for current Studio drafts, then score each run with deterministic rules plus optional LLM-assisted semantic judging and repair suggestions. Surface the feature as a new "评测" tab inside `AgentStudio.vue`.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis-Plus, Jackson, Apache POI, Vue 3, Element Plus, xlsx.

---

## File Structure

- Backend eval package: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/`
  - Entities and mappers for `AgentEvalDataset`, `AgentEvalCase`, `AgentEvalRun`, `AgentEvalCaseResult`.
  - Request/response records for import, run, report, and template APIs.
  - `AgentEvalService` owns dataset persistence, run orchestration, aggregation, and report creation.
  - `AgentEvalJudgeService` owns deterministic and LLM-assisted scoring.
  - `AgentEvalSuggestionService` owns failure clustering and repair suggestions.
- Backend controllers:
  - Create `AgentEvalController` at `/api/agent/evals`.
  - Keep `AgentStudioDebugController` unchanged except for any small helper extraction needed to reuse debug-run safely.
- SQL:
  - Create `ai-agent-service/sql/agent_eval_mvp.sql`.
  - Add the same DDL to root `sql/init.sql` near the Agent Studio tables.
- Frontend:
  - Create `ai-admin-front/src/api/agentEval.ts` and `ai-admin-front/src/types/agentEval.ts`.
  - Modify `ai-admin-front/src/views/agent/AgentStudio.vue` to add the "评测" tab/panel.
- Tests:
  - Create backend unit tests under `ai-agent-service/src/test/java/com/enterprise/ai/agent/eval/`.
  - Update `LangGraph4jRuntimeAdapterTest` only if the safe-run behavior requires a reusable helper.

## Task 1: SQL Schema and Domain Models

**Files:**
- Create: `ai-agent-service/sql/agent_eval_mvp.sql`
- Modify: `sql/init.sql`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalDatasetEntity.java`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalCaseEntity.java`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalRunEntity.java`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalCaseResultEntity.java`
- Create: mapper interfaces in the same package for each entity

- [ ] **Step 1: Write the SQL migration**

Use four tables:

```sql
CREATE TABLE IF NOT EXISTS `agent_eval_dataset` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `agent_id` VARCHAR(64) DEFAULT NULL COMMENT '关联 agent_definition.id；草稿评测可为空',
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `name` VARCHAR(128) NOT NULL,
  `description` VARCHAR(512) DEFAULT NULL,
  `source` VARCHAR(32) NOT NULL DEFAULT 'IMPORT',
  `case_count` INT NOT NULL DEFAULT 0,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY `idx_eval_dataset_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_dataset_name` (`name`)
);

CREATE TABLE IF NOT EXISTS `agent_eval_case` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `message` TEXT DEFAULT NULL,
  `input_params_json` MEDIUMTEXT DEFAULT NULL,
  `expected_json` MEDIUMTEXT DEFAULT NULL,
  `judge_config_json` MEDIUMTEXT DEFAULT NULL,
  `tags` VARCHAR(512) DEFAULT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_case_dataset` (`dataset_id`, `enabled`, `id`),
  UNIQUE KEY `uk_eval_case_no` (`dataset_id`, `case_no`)
);

CREATE TABLE IF NOT EXISTS `agent_eval_run` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `dataset_id` BIGINT NOT NULL,
  `agent_id` VARCHAR(64) DEFAULT NULL,
  `agent_name` VARCHAR(128) DEFAULT NULL,
  `run_name` VARCHAR(128) DEFAULT NULL,
  `repeat_count` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `canvas_snapshot_json` MEDIUMTEXT DEFAULT NULL,
  `graph_spec_json` MEDIUMTEXT DEFAULT NULL,
  `summary_json` MEDIUMTEXT DEFAULT NULL,
  `suggestion_json` MEDIUMTEXT DEFAULT NULL,
  `started_at` DATETIME DEFAULT NULL,
  `finished_at` DATETIME DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_run_dataset` (`dataset_id`, `create_time`),
  KEY `idx_eval_run_agent` (`agent_id`, `create_time`),
  KEY `idx_eval_run_status` (`status`, `create_time`)
);

CREATE TABLE IF NOT EXISTS `agent_eval_case_result` (
  `id` BIGINT PRIMARY KEY AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `dataset_id` BIGINT NOT NULL,
  `case_id` BIGINT NOT NULL,
  `case_no` VARCHAR(64) NOT NULL,
  `round_no` INT NOT NULL DEFAULT 1,
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  `runtime_success` TINYINT(1) NOT NULL DEFAULT 0,
  `assertion_passed` TINYINT(1) NOT NULL DEFAULT 0,
  `semantic_score` DOUBLE DEFAULT NULL,
  `score` DOUBLE NOT NULL DEFAULT 0,
  `elapsed_ms` INT NOT NULL DEFAULT 0,
  `answer` MEDIUMTEXT DEFAULT NULL,
  `trace_id` VARCHAR(96) DEFAULT NULL,
  `step_results_json` MEDIUMTEXT DEFAULT NULL,
  `judge_result_json` MEDIUMTEXT DEFAULT NULL,
  `error_code` VARCHAR(128) DEFAULT NULL,
  `error_message` TEXT DEFAULT NULL,
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_eval_result_run` (`run_id`, `case_id`, `round_no`),
  KEY `idx_eval_result_case` (`case_id`, `create_time`),
  KEY `idx_eval_result_status` (`run_id`, `status`)
);
```

- [ ] **Step 2: Add entities and mappers**

Entity pattern:

```java
@Data
@TableName("agent_eval_dataset")
public class AgentEvalDatasetEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentId;
    private String agentName;
    private String name;
    private String description;
    private String source;
    private Integer caseCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
```

Create mapper interfaces like:

```java
public interface AgentEvalDatasetMapper extends BaseMapper<AgentEvalDatasetEntity> {
}
```

- [ ] **Step 3: Run schema/model compile check**

Run:

```powershell
mvn -pl ai-agent-service -DskipTests compile
```

Expected: `BUILD SUCCESS`.

## Task 2: Deterministic Judge Service

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalJudgeService.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/eval/AgentEvalJudgeServiceTest.java`

- [ ] **Step 1: Write failing tests for mixed judging**

Cover:

```java
@Test
void passesWhenAnswerContainsRequiredTextAndJsonPathMatches() {
    AgentEvalJudgeService service = new AgentEvalJudgeService(null, new ObjectMapper());
    AgentEvalJudgeService.JudgeResult result = service.judge(
            "已查询到订单1001，状态为已支付",
            Map.of("answer", "已查询到订单1001，状态为已支付", "finalState", Map.of("orderId", "1001")),
            """
            {"contains":["订单1001"],"jsonPath":{"finalState.orderId":"1001"}}
            """,
            "{}");
    assertTrue(result.passed());
    assertEquals(1.0, result.score());
}

@Test
void failsWhenRegexDoesNotMatch() {
    AgentEvalJudgeService service = new AgentEvalJudgeService(null, new ObjectMapper());
    AgentEvalJudgeService.JudgeResult result = service.judge(
            "没有找到订单",
            Map.of("answer", "没有找到订单"),
            "{\"regex\":[\"订单\\\\d+\"]}",
            "{}");
    assertFalse(result.passed());
    assertTrue(result.failures().get(0).contains("regex"));
}
```

- [ ] **Step 2: Implement deterministic rules**

Support expected JSON keys:

```json
{
  "contains": ["必须出现的文本"],
  "regex": ["订单\\\\d+"],
  "jsonPath": {
    "finalState.orderId": "1001",
    "answer": "可直接匹配 answer"
  },
  "minSemanticScore": 0.75
}
```

Decision rules:
- `runtime_success=false` is handled by runner before judge scoring.
- `contains`, `regex`, and `jsonPath` are hard assertions.
- If no deterministic assertions are configured, answer presence counts as a weak pass with score `0.6`.
- LLM semantic score is optional and can only raise or explain score; it must not override failed hard assertions.

- [ ] **Step 3: Run judge tests**

Run:

```powershell
mvn -pl ai-agent-service -Dtest=AgentEvalJudgeServiceTest test
```

Expected: all tests pass.

## Task 3: Eval Runner Service

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalService.java`
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalSuggestionService.java`
- Create: `ai-agent-service/src/test/java/com/enterprise/ai/agent/eval/AgentEvalServiceTest.java`

- [ ] **Step 1: Write failing service tests**

Use a fake runner function or mock `LangGraph4jRuntimeAdapter` so tests do not call real models.

Test cases:
- `runsDatasetForConfiguredRepeatCount`: 2 cases x 3 repeats creates 6 result rows.
- `aggregatesAccuracySuccessLatencyAndBias`: report includes `accuracyRate`, `successRate`, `p50LatencyMs`, `p95LatencyMs`, `biasCount`.
- `usesSandboxByDefault`: copied `AgentDefinition.allowIrreversible` is false even if the draft has it true.
- `clustersFailedNodesInSuggestions`: failed step node IDs appear in suggestion JSON.

- [ ] **Step 2: Implement run orchestration**

`AgentEvalService.startRun(request)` should:
- Validate `datasetId`, `agentDefinition`, and `repeatCount` in `1..20`.
- Persist an `agent_eval_run` row with `RUNNING`.
- Load enabled cases by dataset.
- For each `roundNo` and case, call:

```java
LangGraph4jRuntimeAdapter.WorkflowDebugRunResult debug = langGraph4jRuntimeAdapter.debugRun(
        sandboxedDefinition(request.agentDefinition()),
        caseEntity.getMessage(),
        readMap(caseEntity.getInputParamsJson()),
        Map.of("evalMode", true, "sandboxSideEffects", true));
```

- Persist one `agent_eval_case_result` row per case per round.
- Update `agent_eval_run.summary_json`, `suggestion_json`, `status`, and timestamps.

- [ ] **Step 3: Implement aggregation**

Summary JSON shape:

```json
{
  "caseCount": 20,
  "repeatCount": 3,
  "totalExecutions": 60,
  "passedExecutions": 54,
  "runtimeSuccessRate": 0.95,
  "accuracyRate": 0.90,
  "avgScore": 0.88,
  "p50LatencyMs": 1200,
  "p95LatencyMs": 3200,
  "biasCount": 6,
  "failedNodeCounts": {"llm_1": 4}
}
```

- [ ] **Step 4: Implement repair suggestions**

Suggestion JSON shape:

```json
{
  "summary": "6 条执行未通过，主要集中在 llm_1 和 tool_order_query。",
  "items": [
    {
      "nodeId": "llm_1",
      "severity": "HIGH",
      "reason": "答案未包含期望字段 orderId",
      "recommendation": "在该节点输出 schema 中加入 orderId，并在后续 Answer 节点引用。"
    }
  ]
}
```

Use deterministic suggestions first. If a judge model is configured in request, add an optional `llmComment` field; otherwise omit it.

- [ ] **Step 5: Run runner tests**

Run:

```powershell
mvn -pl ai-agent-service -Dtest=AgentEvalServiceTest,AgentEvalJudgeServiceTest test
```

Expected: all tests pass.

## Task 4: Dataset Import and Eval APIs

**Files:**
- Create: `ai-agent-service/src/main/java/com/enterprise/ai/agent/eval/AgentEvalController.java`
- Create: request/response records in `AgentEvalService.java` or small files in the eval package
- Create: controller tests if the project has MVC test patterns; otherwise cover via service tests

- [ ] **Step 1: Add REST endpoints**

Routes:

```text
GET  /api/agent/evals/template
GET  /api/agent/evals/datasets?agentId=
POST /api/agent/evals/datasets
POST /api/agent/evals/datasets/{datasetId}/cases/import
GET  /api/agent/evals/datasets/{datasetId}/cases
POST /api/agent/evals/runs
GET  /api/agent/evals/runs/{runId}
GET  /api/agent/evals/runs/{runId}/results
```

- [ ] **Step 2: Define import contract**

Support JSON import first in the controller and Excel/CSV from the frontend in Task 5. The JSON request mirrors parsed spreadsheet rows:

```json
{
  "agentId": "agent-1",
  "name": "售后流程回归集",
  "description": "AI 自动生成工作流评测",
  "cases": [
    {
      "caseNo": "refund-001",
      "message": "查询订单1001是否可以退款",
      "inputParams": {"question": "查询订单1001是否可以退款"},
      "expected": {"contains": ["订单1001"], "jsonPath": {"finalState.orderId": "1001"}},
      "judgeConfig": {"semanticEnabled": true},
      "tags": "refund,smoke"
    }
  ]
}
```

- [ ] **Step 3: Define run request**

```json
{
  "datasetId": 1,
  "agentId": "agent-1",
  "agentName": "售后流程助手",
  "runName": "发布前评测",
  "repeatCount": 3,
  "agentDefinition": {},
  "canvasSnapshot": {}
}
```

- [ ] **Step 4: Run compile and targeted tests**

Run:

```powershell
mvn -pl ai-agent-service -Dtest=AgentEvalServiceTest,AgentEvalJudgeServiceTest test
mvn -pl ai-agent-service -DskipTests compile
```

Expected: tests pass and compile succeeds.

## Task 5: Studio Eval Panel

**Files:**
- Create: `ai-admin-front/src/api/agentEval.ts`
- Create: `ai-admin-front/src/types/agentEval.ts`
- Modify: `ai-admin-front/src/views/agent/AgentStudio.vue`

- [ ] **Step 1: Add frontend types**

Create interfaces for:

```ts
export interface AgentEvalDataset {
  id: number
  agentId?: string | null
  agentName?: string | null
  name: string
  description?: string | null
  caseCount: number
  createTime: string
  updateTime?: string
}

export interface AgentEvalRunSummary {
  caseCount: number
  repeatCount: number
  totalExecutions: number
  passedExecutions: number
  runtimeSuccessRate: number
  accuracyRate: number
  avgScore: number
  p50LatencyMs: number
  p95LatencyMs: number
  biasCount: number
  failedNodeCounts: Record<string, number>
}
```

- [ ] **Step 2: Add API wrapper**

Functions:

```ts
export function listEvalDatasets(params?: { agentId?: string }) {
  return agentRequest.get<AgentEvalDataset[]>('/api/agent/evals/datasets', { params })
}

export function createEvalDataset(payload: AgentEvalDatasetImportRequest) {
  return agentRequest.post<AgentEvalDataset>('/api/agent/evals/datasets', payload)
}

export function startEvalRun(payload: AgentEvalRunRequest) {
  return agentRequest.post<AgentEvalRun>('/api/agent/evals/runs', payload)
}
```

- [ ] **Step 3: Add Studio "评测" tab**

Place it beside the existing debug surface. The tab should include:
- Dataset selector.
- Import button accepting `.xlsx` and `.csv`.
- Repeat count input with min `1`, max `20`, default `1`.
- Run button.
- Summary cards for accuracy, runtime success, P95 latency, and bias count.
- Result table grouped by case and round.
- Suggestion panel listing node IDs and recommendations.

- [ ] **Step 4: Parse Excel/CSV in the frontend**

Use existing `xlsx` dependency. Required columns:

```text
caseNo,message,inputParams,expected,judgeConfig,tags
```

Parsing rules:
- `inputParams`, `expected`, and `judgeConfig` must parse as JSON objects when non-empty.
- `caseNo` defaults to `case-<rowIndex>` when blank.
- Invalid rows stay client-side and show row-level errors before upload.

- [ ] **Step 5: Send current draft definition**

Use the same current-form conversion already used by debug-run:

```ts
const definition = buildCurrentAgentDefinitionForDebug()
await startEvalRun({
  datasetId: selectedDatasetId.value,
  agentId: form.id,
  agentName: form.name,
  repeatCount: evalRepeatCount.value,
  agentDefinition: definition,
  canvasSnapshot: currentCanvasSnapshot(),
})
```

Use the actual local helper names in `AgentStudio.vue`; if they are private inline blocks today, extract a small helper rather than duplicating conversion logic.

- [ ] **Step 6: Validate frontend build**

Run:

```powershell
cd ai-admin-front
npm run build
```

Expected: `vue-tsc` and Vite build succeed.

## Task 6: Acceptance and Documentation

**Files:**
- Create: `docs/AgentStudio智能体评测闭环-MVP.md`
- Modify: `docs/产品演进路线-Skill-AgentStudio-护栏.md` to add one short Phase entry for "Agent Studio Eval MVP"

- [ ] **Step 1: Write operator docs**

Document:
- Spreadsheet columns.
- Example case.
- Metrics definitions: accuracy rate, runtime success rate, response time, bias count.
- Sandbox behavior and why blocked side effects are shown separately.
- How to interpret AI repair suggestions.

- [ ] **Step 2: Run final verification**

Run:

```powershell
mvn -pl ai-agent-service -Dtest=AgentEvalServiceTest,AgentEvalJudgeServiceTest test
mvn -pl ai-agent-service -DskipTests compile
cd ai-admin-front
npm run build
git diff --check
```

Expected:
- Backend tests pass.
- Backend compile succeeds.
- Frontend build succeeds.
- `git diff --check` reports no whitespace errors.

- [ ] **Step 3: Manual acceptance**

In Agent Studio:
- Generate or open a workflow draft.
- Import a two-row dataset.
- Run evaluation with repeat count `2`.
- Confirm four result rows are created.
- Confirm the summary shows accuracy, success rate, latency, and bias count.
- Confirm a failed assertion highlights a failed node or reports "final answer" when no node can be inferred.
- Confirm publish remains a separate user decision; evaluation suggestions do not mutate the canvas automatically.

## Assumptions Locked

- First version evaluates Studio drafts, not published versions.
- Entry point is inside `AgentStudio.vue`, not a standalone Eval Center.
- Excel/CSV import is the operator-facing path; backend receives normalized JSON rows.
- Repeat count means the whole dataset is run N times.
- Side effects are sandboxed by default. The run request does not expose "allow real side effects" in MVP.
- LLM judge is auxiliary. Deterministic assertions decide hard pass/fail.
- Failed evaluations produce suggestions only; they do not modify the graph.
