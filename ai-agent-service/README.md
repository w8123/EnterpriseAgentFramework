# ai-agent-service

Enterprise Agent Framework 的智能体编排服务，负责意图识别、AgentScope 编排、动态 Tool 注册与最小安全降级。

## 定位

`ai-agent-service` 当前只承载这几类职责：

- 智能体编排：`AgentRouter`、`AgentFactory`、`AgentOrchestrator`
- Tool 运行时：`ToolRegistry`、`ToolRegistryAdapter`、`DynamicHttpAiTool`
- 扫描项目后端：`ScanProjectService`、`ToolDefinitionService`、`/api/scan-projects/*`
- 会话与轻量对话：`ChatService`、`LightweightToolCaller`、Redis 会话记忆
- 最小安全降级：仅保留 `KNOWLEDGE_QA` 与 `GENERAL_CHAT`

不再承载的职责：

- 业务示例 Tool 模块
- `query_database` / `call_business_api` / `query_user_profile` 这类硬编码代码 Tool
- 独立 scanner 模块实现

## 当前依赖关系

```text
ai-agent-service
├── ai-skill-sdk      Tool 契约与注册中心
├── ai-skills-service   知识检索 + scanner 核心代码
├── ai-model-service  LLM 调用网关（Feign / OpenAI 兼容代理）
└── ai-admin-front    管理端入口（通过 REST API 调用本服务）
```

## 关键链路

### 1. 历史项目扫描 -> 动态 Tool

```text
管理端创建扫描项目
  -> POST /api/scan-projects
  -> POST /api/scan-projects/{id}/scan
  -> ScanProjectService 调用 ai-skills-service 中的 scanner 核心
  -> 扫描结果写入 scan_project / tool_definition
  -> ToolDefinitionService 注册 DynamicHttpAiTool
```

### 2. Agent 执行

```text
POST /api/agent/execute
  -> AgentOrchestrator
  -> AgentScope 主路径：AgentRouter -> AgentFactory -> ReActAgent / Pipeline
  -> 失败时降级：AgentWorkflow
       - KNOWLEDGE_QA -> RAG
       - 其他意图 -> GENERAL_CHAT
```

### 3. 轻量对话

```text
POST /api/chat
  -> ChatService
  -> LightweightToolCaller
  -> 仅执行数据库中允许的轻量 Tool
```

## 模块结构

```text
src/main/java/com/enterprise/ai/agent/
├── agent/         AgentDefinition / AgentWorkflow / AgentOrchestrator
├── agentscope/    AgentRouter / AgentFactory / ToolRegistryAdapter
├── controller/    Agent / Chat / Tool / ScanProject REST API
├── scan/          扫描项目实体、Mapper、Service
├── service/       ChatService / IntentService / LightweightToolCaller
├── tools/         KnowledgeSearchTool / DynamicHttpAiTool
├── rag/           调 ai-skills-service 的知识检索封装
├── client/        调 ai-model-service / ai-skills-service / 极视角客户端
└── config/        LLM、Scanner、Tool 等配置
```

## 配置要点

- `agent.definitions.file`：Agent 定义持久化文件，默认 `agent-definitions.json`
- `services.model-service.url`：模型网关地址（也可通过环境变量 `MODEL_SERVICE_URL` 覆盖，与 `application.yml` 一致）
- `services.skills-service.url`：知识 / Tooling 基础层地址；**生产与本机统一通过环境变量 `SKILLS_SERVICE_URL` 配置**（未设置时默认 `http://localhost:8080`）
- `agent.agents.*`：意图开关；当前默认仅保留知识问答与通用对话安全可用

## 当前默认行为

- 默认 Agent 定义只保留 `KNOWLEDGE_QA` 与 `GENERAL_CHAT`
- `ToolRegistryAdapter` 默认只桥接 `search_knowledge`
- `AgentWorkflow` 不再依赖任何业务代码 Tool
- 历史项目接入统一走“扫描入库 -> 页面编辑 -> 动态注册”
