---
name: AI Agent 架构升级规划
overview: 当前统一采用“管理端扫描历史项目 -> 动态 Tool 入库 -> 运行时注册调用”的主线；Tooling 能力已进一步收拢到 ai-skills-service / ai-agent-service。
status: P0/P1/P2 核心收口已完成；后续聚焦扫描增强与 AI 中台能力完善。
isProject: false
---

# 企业级 AI Agent 基础设施升级规划

## 一、当前状态

### 1.1 已完成的核心能力

| 能力 | 说明 | 状态 |
|------|------|------|
| 模型网关收口 | `ai-model-service` 统一 LLM / Embedding 调用 | ✅ |
| RAG 引擎拆分 | `ai-skills-service` 承担知识库、文档处理、检索 | ✅ |
| Tooling 收编 | scanner 并入 `ai-skills-service`，`ai-skill-services` 下线 | ✅ |
| 智能体编排 | `ai-agent-service` 负责意图识别、Tool 调用、会话记忆 | ✅ |
| 管理前端统一 | `ai-admin-front` 统一承载 Agent、知识库、模型、Tool、扫描项目 | ✅ |
| 扫描项目 Web 化 | `scan_project` + `tool_definition.project_id` + 前后端页面与 API | ✅ |
| 动态 Tool 运行时注册 | 扫描结果入库并通过 `DynamicHttpAiTool` 直接调用历史系统 | ✅ |
| 最小安全降级 | `AgentWorkflow` 仅保留 `KNOWLEDGE_QA` / `GENERAL_CHAT` | ✅ |

### 1.2 当前仓库结构

```text
EnterpriseAgentFramework/
├── ai-common/
├── ai-skill-sdk/
├── ai-model-service/
├── ai-skills-service/
├── ai-agent-service/
├── ai-admin-front/
└── deploy/
```

说明：

- 根 `pom.xml` 当前聚合 5 个 Java 子模块。
- `ai-admin-front` 为独立 npm 工程，不纳入 Maven 聚合。
- scanner 核心当前位于 `ai-skills-service` 的 `com.enterprise.ai.text.tooling.scanner.*`。

## 二、目标架构

```mermaid
flowchart LR
    Admin["ai-admin-front"]
    Agent["ai-agent-service\n编排 / 动态 Tool 注册 / 安全降级"]
    Text["ai-skills-service\nRAG / scanner / Tooling core"]
    Model["ai-model-service\n模型网关"]
    Legacy["历史业务系统"]

    Admin --> Agent
    Agent --> Text
    Agent --> Model
    Text --> Legacy
```

边界约束：

- `ai-agent-service` 只保留编排、注册、执行、最小安全降级。
- `ai-skills-service` 承担知识库与 scanner / Tooling 基础能力。
- `ai-skill-sdk` 继续作为最底层 Tool 契约，不动。

## 三、关键设计决策

### 3.1 历史系统接入策略

1. 历史系统保持独立部署，不强制代码改造。
2. 统一通过 HTTP 方式桥接业务能力。
3. 统一采用管理端扫描，而不是开发时生成。

### 3.2 Tool 管理策略

1. 历史项目接口统一转为 `tool_definition` 中的动态 Tool。
2. 通过 `project_id` 管理扫描项目与工具归属关系。
3. 手写 Tool 与动态 Tool 共用 `AiTool` / `ToolRegistry` 契约。

### 3.3 降级策略

1. `AgentScope` 失败后仍保留 `AgentWorkflow`。
2. `KNOWLEDGE_QA` 走安全可用的知识问答降级链路。
3. 其他历史依赖代码 Tool 的意图统一转为 `GENERAL_CHAT`。

## 四、后续重点

### 4.1 扫描能力增强

- 更复杂的 OpenAPI 契约支持
- Service / JavaDoc 深扫
- 扫描差异对比
- 增量更新与冲突提示

### 4.2 AI 中台能力

- `/api/ai/*` 标准化能力接口
- 结构化输出：`TypedAgentResult` + JSON Schema
- Prompt 模板管理
- 多知识库协同检索
- Tool 权限、限流、审计、可观测性

### 4.3 延后决策

当前暂不改动：

- `spring.application.name`
- Nacos 服务名
- Docker / 部署名称
- 数据库名 `ai_text_service`

~~等 Tooling 边界稳定后，再统一评估正式命名与迁移方案~~（已完成：`ai-text-service` → `ai-skills-service`）。

## 五、Tool Retrieval（Phase 1）与 Skill 演进路径

### 5.1 问题背景

扫描注册已经把历史接口批量转为 `tool_definition`，随着接入项目变多，Agent 面对的工具数量从十几个扩张到上百个。在 ReAct 模式下，这带来两个问题：

1. **Tool 爆炸**：全量把工具塞进 system prompt，token 成本高且命中率差；
2. **选错 Tool**：细粒度 API 之间语义重叠，LLM 难以从名字/描述里挑对。

Phase 1 聚焦「Agent 选不准」，引入 **Tool Retrieval**：基于 `ai_description` 做语义召回，按 top-K 动态注入一个 Agent 单轮可见的 toolset。

### 5.2 架构切面

```
用户输入
  │
  ▼
AgentRouter  ──┐ 生成 traceId，构造 ToolExecutionContext
               │
               ▼
AgentFactory.buildFromDefinition(def, userMessage, context)
               │
               │   whitelist = definition.tools
               │   embedding = ModelServiceClient.embed(userMessage)
               │   candidates = ToolRetrievalService.retrieve(query, scope, topK)
               │   finalTools = whitelist ∩ candidates  （空则退回 whitelist）
               │
               ▼
        AgentScope ReActAgent
               │
   每次工具调用 │
               ▼
    AiToolAgentAdapter (埋点)
               │   → ToolCallLogService 异步写 tool_call_log
               ▼
        AiTool.execute()
```

关键组件：

- `ToolEmbeddingService`：`tool_embeddings` collection 的生命周期；CRUD 触发点见 5.3；
- `ToolRetrievalService`：以 `RetrievalScope`（project / module / whitelist / enabled / agentVisible）过滤，向量库搜 top-K；
- `AgentFactory`：把召回结果与白名单求交集，回填 `ToolExecutionContext.retrievalTraceJson`；
- `ToolCallLogService`：异步写 `tool_call_log`（含 `trace_id` + `retrieval_trace_json`）。

### 5.3 索引同步触发点

| 触发动作 | 位置 | 行为 |
| --- | --- | --- |
| Tool 新建 / 编辑 / 启停 | `ToolDefinitionService.create/update/toggle` | `upsert(tool)` |
| Tool 删除 | `ToolDefinitionService.delete/deleteByProjectId` | `delete(toolId)` |
| AI 语义描述生成 | `SemanticGenerationOrchestrator.applyToolAiDescription` | `upsert(tool)` |
| 管理端手工编辑 `ai_description` | `SemanticDocController.edit` | `upsert(tool)` |
| 管理端触发全量重建 | `ToolRetrievalController.rebuild` | 异步 `rebuildAll()` |

文本选取顺序：`ai_description > description > name`；空则从索引中移除。

### 5.4 配置与降级

`application.yml`：

```yaml
milvus:
  host: ${MILVUS_HOST:localhost}
  port: ${MILVUS_PORT:19530}
ai:
  tool-retrieval:
    enabled: true
    top-k: 15
    min-score: 0.3
    fallback-on-error: true
    collection-name: tool_embeddings
    embedding-dim: 1536
  tool-call-log:
    enabled: true
    async: true
    result-max-chars: 2000
    args-max-chars: 4000
```

降级矩阵：

| 状态 | 行为 |
| --- | --- |
| `tool-retrieval.enabled=false` | 直接使用 `definition.tools` 白名单（旧行为） |
| Milvus 不可达 / collection 未就绪 | 召回返回空 → `AgentFactory` 回退白名单 |
| 召回与白名单无交集 | 回退白名单（记 debug 日志） |
| 召回抛出异常 | 捕获 + 日志，走白名单，不阻塞主链路 |
| `tool-call-log.enabled=false` | 不写日志表，但不影响主链路 |

### 5.5 Phase 2：Skill Mining 演进路径

Phase 1 的 `tool_call_log` 已经把原始语料积累起来，Phase 2 的核心是从中「挖」出组合技能：

1. **候选挖掘**：按 `trace_id` 聚合一次 Agent 执行中的 Tool 调用序列；用 PrefixSpan / N-gram 等发现高频序列模式；
2. **相似序列聚类**：结合 `intent_type` 与入参/出参特征，把语义等价的序列聚成同一 Skill 候选；
3. **人工 / LLM 审核**：管理端提供「候选技能」列表，支持命名、起草 `SkillDefinition`（Workflow / SubAgent / AugmentedTool）；
4. **上架 Skill**：审核通过后写入 Skill 注册表，作为一等公民参与检索与路由；
5. **闭环反馈**：Skill 复用率、替代旧 ReAct 路径的成功率回流到 `tool_call_log`，形成飞轮。

在数据足够前，Phase 1 的 Tool Retrieval + 审计日志已经能直接改善「Agent 选不准」，Phase 2 则是在可度量的数据上做能力层抽象，而不是凭直觉拍脑袋设计 Skill。

### 5.6 手工验收清单

1. 管理端编辑任一 Tool 的 `ai_description`，`tool_call_log` / Milvus 端可以看到该条被 `upsert`；
2. `/api/tool-retrieval/search` 输入任一业务问题，返回 top-K 与分数，且受 `enabledOnly / agentVisibleOnly` 过滤影响；
3. `/api/tool-retrieval/rebuild` 提交后返回 `taskId`，`rebuild/status` 能轮询到 `RUNNING → DONE` 进度；
4. 对已配置 `tools` 的 Agent 执行一次任务，`tool_call_log` 中出现本次 `trace_id`，`retrieval_trace_json` 有 top-K 内容；
5. 关闭 `ai.tool-retrieval.enabled` 后，Agent 只加载白名单中的 Tool，不经过 Milvus；
6. 停掉 Milvus 后再跑 Agent，日志中有 warn，但主链路仍然按白名单正常执行，不抛异常。

## 六、结论

当前架构已经完成从“开发时生成”到“运行时扫描注册”的收口，并进一步把 Tooling 核心并入 `ai-skills-service`。Phase 1 通过 Tool Retrieval 解决细粒度工具的选型压力，并沉淀 `tool_call_log` 作为 Skill Mining 的数据底座。后续重点从“把接口变成 Tool”演进为“从使用轨迹里抽出 Skill”，把 Agent 的能力结构化往上抬一层。
