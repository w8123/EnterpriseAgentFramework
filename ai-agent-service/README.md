# ai-agent-service

Enterprise Agent Framework 的智能体编排服务 — 基于 AgentScope 的意图识别、Agent 编排、Tool 调用、会话记忆管理

---

## 1 定位与职责

ai-agent-service 是平台的 **"大脑"和"调度中心"**：负责理解用户意图、编排智能体、决策和执行工具调用、管理会话记忆，但 **不直接持有模型调用能力或 RAG 检索能力**。

```
ai-agent-service = 智能体编排 + 意图路由 + Tool 执行框架 + 会话管理
```

| 该做的事 | 说明 |
|---------|------|
| 智能体编排 | AgentScope ReActAgent、Pipeline（Sequential/Fanout）、MsgHub |
| 意图识别与路由 | LLM 驱动意图分类，按意图分发到不同 Agent/Pipeline |
| Tool 执行框架 | ToolRegistry 注册中心、ToolRegistryAdapter 框架桥接 |
| 会话与记忆管理 | Redis 短期上下文窗口，自动过期 |
| Agent 定义管理 | Agent 配置持久化、CRUD REST API |
| 轻量 Tool Calling | /api/chat 路径支持知识搜索等内置工具 |
| SSE 流式输出 | model-service → agent-service → 用户 |

| 不该做的事 | 应归属的服务 |
|-----------|------------|
| 直接 LLM 调用 | ai-model-service（统一模型网关） |
| RAG 检索 + 生成 | ai-text-service（RAG 引擎） |
| Embedding 向量化 | ai-model-service |
| 具体业务 Tool 实现 | ai-skill-services（jar 包加载） |

---

## 2 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| Agent 编排 | AgentScope Java 1.0.9 | ReActAgent、Pipeline、MsgHub |
| LLM 调用 | Feign → ai-model-service | 非 Agent 路径通过 LlmService |
| Agent 模型 | AgentScope OpenAIChatModel → model-service 代理 | Agent 的 LLM 推理统一走模型网关 |
| 会话记忆 | Redis + ConversationMemoryService | 短期上下文窗口 |
| 流式输出 | WebClient + SseEmitter | 消费 model-service SSE 并转发 |
| 工具框架 | ai-skill-sdk (AiTool + ToolRegistry) | 框架无关的工具接口 |
| 业务工具 | ai-skill-services (jar 包加载) | DatabaseQueryTool 等业务工具 |
| 微服务 | Spring Cloud (Feign + Nacos + LoadBalancer) | 服务间调用 |
| Web 框架 | Spring Boot 3.4.5 | REST API |

---

## 3 架构设计

### 3.1 服务依赖关系

```
                        ┌──────────────────────────┐
                        │    ai-agent-service       │
                        │    :8081                  │
                        └──────┬───────────┬───────┘
                               │           │
              Feign            │           │          Feign
         ┌─────────────────────┘           └──────────────────┐
         ▼                                                    ▼
┌─────────────────┐                                 ┌─────────────────┐
│ ai-model-service│                                 │ ai-text-service │
│ :8090           │                                 │ :8080           │
│ 统一模型网关     │                                 │ RAG 引擎         │
└─────────────────┘                                 └─────────────────┘
         │
         ▼
   LLM Provider
   (通义/极视角/OpenAI)
```

### 3.2 分层架构

```
┌─────────────────────────────────────────────────────────────┐
│                    Controller 层（REST API）                 │
│    ChatController       AgentController                     │
│    (轻量对话+Tool+SSE)  (完整Agent编排)                      │
│    AgentManageController (Agent定义管理)                     │
├─────────────────────────────────────────────────────────────┤
│                     Service 层（业务逻辑）                    │
│    ChatService             AgentService                     │
│    LightweightToolCaller   IntentService                    │
├─────────────────────────────────────────────────────────────┤
│               AgentScope 编排层（可替换框架）                 │
│    AgentRouter          AgentFactory                        │
│    AgentOrchestrator    AgentWorkflow(降级)                  │
├─────────────────────────────────────────────────────────────┤
│                    会话记忆层                                │
│    ConversationMemoryService (Redis 短期上下文窗口)          │
├─────────────────────────────────────────────────────────────┤
│                    LLM 调用层（代理）                        │
│    AgentScope OpenAIChatModel → model-service 代理端点       │
│    LlmService → ModelServiceClient (Feign) → model-service  │
│    ModelStreamClient (WebClient) → model-service SSE         │
├─────────────────────────────────────────────────────────────┤
│               Tool 框架层（基于 ai-skill-sdk）               │
│    ToolRegistry(SDK)   ToolRegistryAdapter(AgentScope桥接)  │
│    KnowledgeSearchTool(内置)                                │
│    ... ai-skill-services(jar包加载的业务工具)               │
├─────────────────────────────────────────────────────────────┤
│                Agent 定义层                                  │
│    AgentDefinition     AgentDefinitionService               │
│    (JSON文件持久化, 内存缓存, CRUD API)                      │
├─────────────────────────────────────────────────────────────┤
│           外部服务调用层（Feign）                             │
│    ModelServiceClient (→ ai-model-service :8090)            │
│    TextServiceClient  (→ ai-text-service :8080)             │
│    JishiAgentClient   (→ 极视角, 仅业务工具)                │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 双路径 + 流式设计

```
用户请求
   │
   ├── POST /api/chat ──→ ChatService
   │     │                  ├── 加载会话记忆 (Redis)
   │     │                  ├── LLM 第一轮（含工具描述）
   │     │                  ├── 若触发 [TOOL_CALL] → LightweightToolCaller 执行
   │     │                  ├── LLM 第二轮（携带工具结果）
   │     │                  └── 保存记忆，返回（含 sessionId）
   │
   ├── POST /api/chat/stream ──→ ChatService.chatStream()
   │     │                        ├── WebClient → model-service SSE
   │     │                        ├── SseEmitter 逐 token 转发
   │     │                        └── 完成后保存记忆
   │
   └── POST /api/agent/execute ──→ AgentService
                                    │
                                    └──→ AgentOrchestrator
                                           │
                                           ├── AgentScope 路径（主路径）
                                           │    AgentRouter → AgentFactory
                                           │    → ReActAgent / Pipeline
                                           │    → ToolRegistryAdapter → ToolRegistry
                                           │
                                           └── Legacy 路径（降级路径）
                                                AgentWorkflow
                                                → LlmService + ToolRegistry
```

---

## 4 项目结构

```
src/main/java/com/enterprise/ai/agent/
├── AiAgentServiceApplication.java         # 启动类
│
├── agentscope/                            # 【AgentScope 编排层】
│   ├── AgentScopeConfig.java              #  模型配置（OpenAIChatModel → model-service 代理）
│   ├── AgentFactory.java                  #  Agent 实例工厂
│   ├── AgentRouter.java                   #  意图路由 + Pipeline 编排
│   └── adapter/
│       └── ToolRegistryAdapter.java       #  AgentScope ↔ ToolRegistry 唯一桥接点
│
├── agent/                                 # 【Agent 核心】
│   ├── AgentContext.java                  #  执行上下文
│   ├── AgentDefinition.java              #  Agent 定义模型
│   ├── AgentDefinitionService.java        #  Agent 定义 CRUD 管理
│   ├── AgentOrchestrator.java             #  编排入口
│   └── AgentWorkflow.java                 #  降级工作流
│
├── llm/                                   # 【LLM 调用层】
│   └── LlmService.java                   #  Feign → model-service（同步+流式）
│
├── memory/                                # 【会话记忆】
│   ├── ConversationMemoryService.java     #  Redis 短期上下文窗口
│   └── MemoryMessage.java                 #  消息 DTO
│
├── controller/                            # 【API 接口层】
│   ├── AgentController.java               #  /api/agent/* 复杂任务
│   ├── AgentManageController.java         #  /api/agent/definitions Agent 定义管理
│   └── ChatController.java                #  /api/chat 轻量对话 + SSE 流式
│
├── service/                               # 【业务服务层】
│   ├── AgentService.java                  #  Agent 任务入口
│   ├── ChatService.java                   #  多轮对话 + 轻量 Tool Calling + 流式
│   ├── IntentService.java                 #  意图识别
│   └── LightweightToolCaller.java         #  轻量 Tool Calling 引擎
│
├── tools/                                 # 【Tool 层（基于 ai-skill-sdk）】
│   ├── AiTool.java                        #  兼容层（继承 SDK AiTool 接口，@Deprecated）
│   ├── ToolRegistry.java                  #  Spring 管理层（继承 SDK ToolRegistry）
│   └── KnowledgeSearchTool.java           #  平台内置：知识库检索（调 text-service）
│
├── rag/                                   # 【RAG 能力】
│   ├── RagClient.java                     #  Feign → ai-text-service
│   └── RagService.java                    #  RAG 服务
│
├── client/                                # 【外部系统客户端】
│   ├── ModelServiceClient.java            #  Feign → ai-model-service（同步）
│   ├── ModelStreamClient.java             #  WebClient → ai-model-service（SSE 流式）
│   ├── TextServiceClient.java             #  Feign → ai-text-service
│   └── JishiAgentClient.java              #  极视角平台客户端（仅业务工具能力）
│
├── config/                                # 【配置】
│   ├── JishiAgentProperties.java          #  极视角平台配置
│   ├── LLMConfig.java                     #  Agent/LLM 参数 + Agent 开关
│   ├── SpringAIConfig.java                #  已废弃（Spring AI 直连已移除）
│   └── ToolConfig.java                    #  Tool 扩展配置（预留）
│
└── model/                                 # 【数据模型】
    ├── AgentResult.java                   #  Agent 执行结果（含 toolCalls/steps metadata）
    ├── ChatRequest.java                   #  对话请求
    ├── ChatResponse.java                  #  对话响应（含 sessionId）
    └── jishi/                             #  极视角平台模型
```

---

## 5 Tool 层设计

### 核心理念：工具与框架彻底解耦

```
                                 ┌────────────────────┐
                                 │ AiTool 接口 (SDK)   │
                                 │ name()             │
                                 │ description()      │
                                 │ parameters()       │
                                 │ execute(args)      │
                                 └────────┬───────────┘
                                          │
              ┌────────────────┬──────────┼───────────────────┐
              │                │          │                   │
   ai-agent-service      ai-skill-services (jar 加载)        │
   KnowledgeSearchTool   DatabaseQueryTool                   │
   (平台内置)            BusinessApiTool                未来 skill-*
                         UserProfileTool
                                          │
                                    ToolRegistry (SDK)
                                   (Spring 自动扫描注册)
                                          │
              ┌───────────────────────────┤
              │                           │
    ToolRegistryAdapter            LightweightToolCaller
    (AgentScope @Tool 注解,          (ChatService 轻量调用,
     唯一的框架耦合点)                 白名单机制)
              │
         AgentScope Toolkit
              │
         ReActAgent
```

### 模块依赖关系

```
ai-skill-sdk          ← 定义 AiTool、ToolParameter、ToolRegistry
    ↑
ai-skill-services     ← 实现 DatabaseQueryTool、BusinessApiTool 等
    ↑
ai-agent-service      ← 编排层，通过 jar 依赖加载 skill-services
```

### 新增 Tool 流程

**业务工具**（在 ai-skill-services 中）：
1. 创建新类，实现 `com.enterprise.ai.skill.AiTool` + `@Component`
2. 在 `ToolRegistryAdapter` 中添加对应的桥接方法
3. 完成（ToolRegistry 自动注册）

**平台内置工具**（在 ai-agent-service 中）：
1. 在 `tools/` 下创建，实现 AiTool + `@Component`
2. 如需轻量路径支持，在 `LightweightToolCaller.ALLOWED_TOOLS` 中添加

---

## 6 Agent 编排

### 6.1 ReActAgent 推理循环

```
用户输入
    │
    ▼
┌─────────────────────────────────┐
│         ReActAgent              │
│  1. Reasoning (LLM 推理)        │
│      │                          │
│      ▼                          │
│  需要调用工具？                   │
│   是 → 2. Acting (执行 Tool)    │
│   │      结果存入 Memory        │
│   └── 回到 1                    │
│                                 │
│   否 → 返回最终回答              │
└─────────────────────────────────┘
```

### 6.2 Agent 类型与路由

| Agent | 意图类型 | 工具 |
|-------|---------|------|
| KnowledgeQAAgent | KNOWLEDGE_QA | search_knowledge |
| QueryDataAgent | QUERY_DATA | query_database |
| AnalysisAgent | ANALYSIS | query_database, search_knowledge |
| BusinessOperationAgent | BUSINESS_OPERATION | call_business_api |
| GeneralChatAgent | GENERAL_CHAT | 无工具 |

Agent 定义通过 `AgentDefinitionService` 管理，支持 REST API 动态 CRUD。

### 6.3 Pipeline 编排

```
ANALYSIS 意图:  QueryDataAgent ──→ AnalysisAgent ──→ 最终回答
                 (查询数据)           (分析数据)

CREATIVE_TASK:  InfoGatherAgent ──→ CreativeAgent ──→ 最终回答
                 (信息采集)           (创意生成)
```

---

## 7 LLM 调用链路

所有 LLM 调用统一通过 ai-model-service，agent-service 不直接持有任何 API Key。

```
Agent 路径:
  ReActAgent → AgentScope OpenAIChatModel
    → model-service OpenAI 兼容代理端点 (/model/openai-proxy/v1)
    → model-service 转发 → DashScope

非 Agent 路径:
  LlmService → ModelServiceClient (Feign)
    → model-service /model/chat

流式路径:
  LlmService → ModelStreamClient (WebClient)
    → model-service /model/chat/stream (SSE)
```

---

## 8 API 接口

### POST /api/chat — 轻量多轮对话（支持轻量 Tool Calling）

```json
// Request
{
  "message": "我们公司差旅报销的标准是什么？",
  "userId": "user_001",
  "sessionId": "abc123"  // 可选，为空则自动生成
}

// Response
{
  "answer": "根据公司制度，差旅报销标准为...",
  "sessionId": "abc123",
  "toolCalls": ["search_knowledge"]
}
```

### POST /api/chat/stream — 流式对话（SSE）

SSE 事件类型：
- `message`：文本片段
- `done`：流结束，data 为 sessionId
- `error`：流出错

### POST /api/agent/execute — 完整 Agent 编排

```json
// Request
{
  "message": "对比分析各班组本月和上月的得分变化趋势",
  "userId": "user_001",
  "intentHint": "ANALYSIS"
}
```

### POST /api/agent/execute/detailed — 详细执行结果（含推理步骤和工具链）

### Agent 定义管理 API

```
GET    /api/agent/definitions          Agent 定义列表
GET    /api/agent/definitions/{id}     获取单个定义
POST   /api/agent/definitions          创建 Agent 定义
PUT    /api/agent/definitions/{id}     更新 Agent 定义
DELETE /api/agent/definitions/{id}     删除 Agent 定义
```

### 会话管理

```
DELETE /api/chat/session/{sessionId}   清除会话记忆
```

---

## 9 配置说明

### application.yml 关键配置

```yaml
# 服务间调用
services:
  model-service:
    url: ${MODEL_SERVICE_URL:http://localhost:8090}
  text-service:
    url: ${TEXT_SERVICE_URL:http://localhost:8080}

# AgentScope 模型（通过 model-service 代理）
agentscope:
  model:
    name: qwen-max

# Agent 配置
agent:
  max-steps: 5
  default-timeout: 60000
  definitions:
    file: ${AGENT_DEF_FILE:agent-definitions.json}
  agents:
    query-data: false
    knowledge-qa: true
    business-operation: false
    analysis: false
    creative-task: true
    general-chat: true

# 会话记忆
memory:
  max-messages: 20    # 上下文窗口最大消息数
  ttl-hours: 24       # 会话过期时间

# RAG
rag:
  top-k: 5
  score-threshold: 0.7
```

### Agent 开关

通过 `agent.agents.*` 可自由启用/关闭各意图类型的 Agent。关闭的类型不会出现在 IntentService 的候选列表中，AgentRouter 会自动降级到 GENERAL_CHAT。

---

## 10 架构原则

| 原则 | 说明 |
|------|------|
| **Agent 框架可替换** | Tool 层不依赖 AgentScope，更换框架只需替换 ToolRegistryAdapter |
| **Tool 写一次** | 所有工具实现 `com.enterprise.ai.skill.AiTool`，零框架注解 |
| **LLM 统一出口** | 全部 LLM 调用走 ai-model-service，agent-service 无 API Key |
| **业务工具外置** | 业务 Tool 在 ai-skill-services，平台只保留 KnowledgeSearchTool |
| **会话有状态** | Redis 管理短期上下文，支持多轮对话连续性 |
| **降级容错** | AgentScope 异常时自动回退到 AgentWorkflow |

---

## 11 后续扩展方向

| 方向 | 说明 | 状态 |
|------|------|------|
| ~~groupId 统一~~ | ~~`com.jishi.ai.agent` → `com.enterprise.ai.agent`~~ | ✅ 已完成 |
| 长期记忆 (MySQL) | 跨会话记忆用户偏好和历史 | P2 |
| RemoteToolProvider | Python/MCP 远程工具协议支持 | P2 |
| 执行链追踪 | AgentScope Hook System + 日志持久化 | P2 |
| Workflow 可视化编排 | 前端拖拽 + 后端 DAG 引擎 | P2 |
| MsgHub 多 Agent 辩论 | 多角度分析同一问题 | P2 |
| FanoutPipeline 并行分析 | 同时查询多数据源 | P2 |

---

## 12 参考资料

- [AgentScope Java 官网](https://java.agentscope.io/en/intro.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [架构设计文档](../docs/)
