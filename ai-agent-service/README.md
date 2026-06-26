# ai-agent-service

`ai-agent-service` 是 ReachAI 当前平台核心部署单元。它暂时仍承载多个长期逻辑域，但新增代码和文档不应继续把这些职责都归入模糊的 Agent 概念。

当前内部边界：

| 逻辑域 | 当前职责 | 典型代码 |
| --- | --- | --- |
| Capability Catalog | 项目注册、实例心跳、能力快照、字段级 diff、评审 apply/ignore、扫描目录、语义文档、Tool/Capability 资产 | `registry/`、`scan/`、`semantic/`、`tools/definition/`、`capability/` |
| Runtime Host | Agent 入口、Workflow、GraphSpec 执行、调试、人工交互、Runtime Adapter | `workflow/`、`runtime/`、`agentscope/`、`skill/interactive/`、`debug/` |
| Platform Control | 平台身份、RBAC、ACL、Guard、Gateway、MCP、A2A、市场、运行治理入口 | `identity/`、`platform/`、`acl/`、`governance/`、`mcp/`、`a2a/`、`market/` |
| Observability | Trace、RunOps、Tool 调用日志、Guard 决策日志 | `trace/`、`runops/`、相关 mapper/service |
| Context Governance | 项目治理记忆与 Runtime 用户记忆候选治理 | `context/` |

## 当前依赖关系

```text
ai-agent-service
├── ai-runtime-contract  内部 Tool / Skill 运行时契约
├── ai-skills-service    Knowledge / Retrieval deployment unit（/ai）
├── ai-model-service     Model Gateway（/model）
└── ai-admin-front       管理端通过 /api、/embed 等路径调用本服务
```

`Skill` 在这里仍可能作为运行时契约、legacy SQL/API 或内部历史类名存在。产品和文档主语应使用 `Capability / 能力`。

## 关键链路

### 1. Capability 接入与资产沉淀

```text
业务系统 SDK / Starter 或低侵入扫描
  -> /api/registry/projects/register 或 /api/scan-projects/*
  -> 能力快照 / scan_project_tool / semantic_doc
  -> diff review / apply / ignore
  -> tool_definition、tool_asset、composition_definition、interaction_definition
  -> Capability Catalog
```

### 2. Agent 与 Workflow 执行

```text
AgentEntry（ai_agent）
  -> ai_agent_workflow_binding
  -> 已发布 Workflow（ai_workflow + ai_workflow_version）
  -> GraphSpec
  -> AgentRuntimeAdapter / LangGraph4jRuntimeAdapter
  -> Trace / RunOps / Guard / ACL
```

Agent 是入口和策略，Workflow 是 GraphSpec 编排和版本，Runtime Host 负责执行。

### 3. 开放协议与平台治理

```text
Platform Auth / RBAC
  -> ACL / Guard
  -> Gateway / MCP / A2A / Embed Chat
  -> RunOps / Trace / audit
```

这部分属于 Platform Control，不应和 Workflow Runtime 或 Knowledge/Retrieval 混写。

## 边界规则

- 新增能力资产、扫描、语义、注册中心代码，先归入 Capability Catalog。
- 新增 GraphSpec 执行、调试、人工交互、Runtime Adapter 代码，先归入 Runtime Host。
- 新增身份、ACL、Guard、Gateway、MCP、A2A、市场代码，先归入 Platform Control。
- 新增知识库、RAG、业务索引、文档入库代码，优先放在 `ai-skills-service` 的 Knowledge / Retrieval 边界。
- 不因本轮命名重塑改动 SQL 表名、API 路径、Maven artifactId 或端口。

## 配置要点

- `services.model-service.url`：Model Gateway 地址，默认 `http://localhost:18601`。
- `services.skills-service.url`：Knowledge / Retrieval 地址，默认 `http://localhost:18602`。
- `agent.workflow-credential-secret`：Workflow 凭证加密密钥。
- `ai.agent-runtime.*`：Runtime Host 可用运行时与执行位置策略。
- `ai.tool-retrieval.*`：Tool 语义召回配置，当前仍在本服务内使用 Milvus 和模型 embedding。

详细架构说明见 `docs/16-后端逻辑边界与命名重塑.md`。
