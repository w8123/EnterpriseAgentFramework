# Complex Agent Service

企业级复杂业务智能体微服务 —— AgentScope 编排 + Spring AI 模型调用 + 统一 Tool 层

---

## 1 项目简介

Complex Agent Service 是企业 AI 平台中的核心智能体服务，负责复杂 AI 推理和业务编排能力。

该服务提供：

- 基于 AgentScope ReActAgent 的智能体编排
- 多 Agent 协作（SequentialPipeline 顺序流水线/ FanoutPipeline 并行分发/ MsgHub 消息协作中心）
- 自动 Tool 调用决策（ReAct 模式推理循环，PE模式暂未实现）
- 多步推理与数据分析
- NL2SQL 数据查询(数据源由数据同步服务同步大数据中心数据，再进行select查询)
- RAG 知识问答(暂未实现，计划由小铁宝做权限控制，极视角提供能力)
- 业务系统 API 调用

### 系统定位

```
用户 → 小铁宝智能体门户 → Complex Agent Service → Tool/MCP服务 → 业务系统
                                    ↕
                              极视角智能体平台
                            （大模型 / RAG 能力）
```

---

## 2 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| Agent 编排 | AgentScope Java 1.0.9 | ReActAgent、Pipeline、MsgHub |
| LLM 调用 | Spring AI 1.0.0 + DashScope | 统一由 LlmService 封装 |
| Agent 模型 | AgentScope DashScopeChatModel | Agent 内部的 LLM 推理 |
| Web 框架 | Spring Boot 3.4.5 | REST API、依赖注入 |
| 构建 | Maven | 依赖管理 |
| 工具 | Lombok | 样板代码消除 |

---

## 3 架构设计

### 3.1 核心架构关系

```
AgentScope = 智能体大脑（编排 + 决策）
Spring AI  = LLM 调用 SDK（模型接口层）
Tool 层    = 框架无关的业务能力（AiTool + ToolRegistry）
```

三者是上下层关系，不是并列关系：

```
Agent 编排层（AgentScope）
  ├── Agent Router（意图路由）
  ├── Multi-Agent 协作（Pipeline / MsgHub）
  └── Tool 决策（ReAct 推理循环）
        │
        ↓ ToolRegistryAdapter（唯一桥接点）
        │
Tool 层（框架无关）
  ├── AiTool 接口
  ├── ToolRegistry（注册中心）
  ├── DatabaseQueryTool
  ├── BusinessApiTool
  ├── KnowledgeSearchTool
  └── UserProfileTool
        │
        ↓
Client 层（业务系统调用）
  ├── BusinessSystemClient
  ├── JishiAgentClient
  └── RagClient

LLM 调用层（Spring AI）
  └── LlmService → ChatClient → DashScope 模型
```

### 3.2 职责分工

| 功能 | Spring AI | AgentScope |
|------|-----------|------------|
| LLM 调用 | ✔ | ✘ |
| Prompt 管理 | ✔ | ✘ |
| Streaming | ✔ | ✘ |
| Agent 编排 | ✘ | ✔ |
| 多 Agent 协作 | ✘ | ✔ |
| Tool 调用决策 | ✘ | ✔ |
| Workflow | ✘ | ✔ |

### 3.3 分层架构图

```
┌─────────────────────────────────────────────────────────────┐
│                    Controller 层（REST API）                 │
│              ChatController    AgentController              │
├─────────────────────────────────────────────────────────────┤
│                     Service 层（业务逻辑）                     │
│         ChatService(简单对话)    AgentService(复杂任务)        │
├─────────────────────────────────────────────────────────────┤
│               AgentScope 编排层（Agent 大脑）                  │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  AgentOrchestrator → AgentRouter → AgentFactory     │   │
│  │                                                     │   │
│  │  Agents:                                            │   │
│  │   QueryDataAgent    (NL2SQL 数据查询)                 │   │
│  │   KnowledgeQAAgent  (RAG 知识问答)                    │   │
│  │   AnalysisAgent     (数据分析)                        │   │
│  │   BusinessOpAgent   (业务操作)                        │   │
│  │   GeneralChatAgent  (通用对话)                        │   │
│  │                                                     │   │
│  │  Pipelines:                                         │   │
│  │   Sequential: QueryData → Analysis                  │   │
│  └─────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│               LLM 调用层（Spring AI — 唯一出口）                │
│              LlmService → ChatClient → DashScope            │
├─────────────────────────────────────────────────────────────┤
│                 Tool 层（框架无关 — AiTool 接口）               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  AiTool 接口 ← 所有工具统一实现                          │   │
│  │  ToolRegistry ← 集中管理/自动注册                       │   │
│  │  ToolRegistryAdapter ← AgentScope 桥接（唯一耦合点）     │   │
│  │                                                      │   │
│  │  DatabaseQueryTool   BusinessApiTool                  │  │
│  │  KnowledgeSearchTool UserProfileTool                  │  │
│  └──────────────────────────────────────────────────────┘   │
├─────────────────────────────────────────────────────────────┤
│                    Client 层（外部系统调用）                    │
│           BusinessSystemClient    JishiAgentClient          │
│                    RagClient       RagService               │
└─────────────────────────────────────────────────────────────┘
```

### 3.4 架构原则

| 原则 | 说明 |
|------|------|
| **Agent 框架可替换** | Tool 层和 LLM 层不依赖 AgentScope，更换框架只需替换 Adapter |
| **Tool 写一次** | 所有工具实现 AiTool 接口，零框架注解。AgentScope 通过 ToolRegistryAdapter 桥接 |
| **LLM 单一出口** | 非 Agent 路径统一通过 LlmService 调用 Spring AI |
| **降级容错** | AgentScope 异常时自动回退到 AgentWorkflow（基于 LlmService + ToolRegistry） |

### 3.5 双路径设计

```
用户请求
   │
   ├── POST /api/chat ──→ ChatService ──→ LlmService
   │                                      （纯 LLM 对话，无 Tool Calling）
   │
   └── POST /api/agent/execute ──→ AgentService
                                     │
                                     └──→ AgentOrchestrator
                                            │
                                            ├── AgentScope 路径（主路径）
                                            │    AgentRouter → AgentFactory
                                            │    → ReActAgent.call() / Pipeline
                                            │    → ToolRegistryAdapter → ToolRegistry
                                            │
                                            └── Legacy 路径（降级路径）
                                                 AgentWorkflow
                                                 → LlmService + ToolRegistry
```

---

## 4 项目结构

```
src/main/java/com/jishi/ai/agent/
├── ComplexAgentServiceApplication.java    # 启动类
│
├── agentscope/                            # 【AgentScope 编排层】
│   ├── AgentScopeConfig.java              #  模型配置（单Agent/多Agent模型）
│   ├── AgentFactory.java                  #  Agent 实例工厂
│   ├── AgentRouter.java                   #  意图路由 + Pipeline 编排
│   └── adapter/
│       └── ToolRegistryAdapter.java       #  AgentScope ↔ ToolRegistry 唯一桥接点
│
├── agent/                                 # 【Agent 核心】
│   ├── AgentContext.java                  #  执行上下文
│   ├── AgentOrchestrator.java             #  编排入口（委托给 AgentRouter）
│   └── AgentWorkflow.java                 #  降级工作流（LlmService + ToolRegistry）
│
├── llm/                                   # 【LLM 调用层】
│   └── LlmService.java                   #  Spring AI 唯一出口
│
├── controller/                            # 【API 接口层】
│   ├── AgentController.java               #  /api/agent/* 复杂任务接口
│   └── ChatController.java                #  /api/chat 简单对话接口
│
├── service/                               # 【业务服务层】
│   ├── AgentService.java                  #  Agent 任务入口
│   ├── ChatService.java                   #  简单对话（LlmService 路径）
│   └── IntentService.java                 #  意图识别（LlmService 路径）
│
├── tools/                                 # 【统一 Tool 层（框架无关）】
│   ├── AiTool.java                        #  工具接口（零框架依赖）
│   ├── ToolRegistry.java                  #  工具注册中心
│   ├── DatabaseQueryTool.java             #  数据库查询
│   ├── BusinessApiTool.java               #  业务API调用
│   ├── KnowledgeSearchTool.java           #  知识库检索
│   └── UserProfileTool.java               #  用户身份查询（Mock）
│
├── rag/                                   # 【RAG 能力】
│   ├── RagClient.java                     #  RAG 底层客户端
│   └── RagService.java                    #  RAG 服务
│
├── client/                                # 【外部系统客户端】
│   ├── BusinessSystemClient.java          #  业务系统 REST 客户端
│   └── JishiAgentClient.java              #  极视角平台客户端
│
├── config/                                # 【配置】
│   ├── JishiAgentProperties.java          #  极视角平台多智能体配置
│   ├── LLMConfig.java                     #  Agent/LLM 参数配置 + Agent 开关
│   ├── SpringAIConfig.java                #  Spring AI ChatClient 配置
│   └── ToolConfig.java                    #  Tool 扩展配置（预留）
│
└── model/                                 # 【数据模型】
    ├── AgentResult.java                   #  Agent 执行结果
    ├── ChatRequest.java                   #  对话请求
    ├── ChatResponse.java                  #  对话响应
    └── jishi/                             #  极视角平台模型
```

---

## 5 统一 Tool 层设计

### 核心理念：Tool 不依赖任何 AI 框架

```
                                 ┌─────────────────┐
                                 │   AiTool 接口    │
                                 │  name()          │
                                 │  description()   │
                                 │  execute(args)   │
                                 └────────┬────────┘
                                          │
              ┌───────────────┬───────────┼────────────┬──────────────┐
              │               │           │            │              │
      DatabaseQueryTool  BusinessApiTool  KnowledgeSearchTool  UserProfileTool
      (@Component)       (@Component)     (@Component)         (@Component)
              │               │           │            │
              └───────────────┴───────────┴────────────┘
                                          │
                                    ToolRegistry
                                   (自动收集所有 AiTool Bean)
                                          │
              ┌───────────────────────────┤
              │                           │
    ToolRegistryAdapter            直接调用 (降级路径)
    (AgentScope @Tool 注解,          ToolRegistry.execute()
     唯一的框架耦合点)
              │
         AgentScope Toolkit
              │
         ReActAgent
```

### 新增工具流程

1. 创建新类，实现 `AiTool` 接口 + `@Component`
2. 在 `ToolRegistryAdapter` 中添加对应的桥接方法
3. 完成（ToolRegistry 自动注册，AgentScope 自动可用）

### 更换 Agent 框架

只需替换 `ToolRegistryAdapter`，所有 `AiTool` 实现零修改。

---

## 6 Agent 编排架构

### 6.1 AgentScope ReActAgent

每个 Agent 基于 ReAct（Reasoning + Acting）范式运行：

```
用户输入
    │
    ▼
┌─────────────────────────────────┐
│         ReActAgent              │
│                                 │
│  ┌────────┐    ┌────────────┐  │
│  │ Memory │    │   Toolkit   │  │
│  └───┬────┘    └──────┬─────┘  │
│      │                │        │
│      ▼                │        │
│  1. Reasoning         │        │
│  (读取Memory+LLM推理)  │        │
│      │                │        │
│      ▼                │        │
│  需要调用工具？         │        │
│   是 ──→ 2. Acting ◄──┘        │
│   │      (执行Tool)             │
│   │         │                  │
│   │    结果存入Memory           │
│   │         │                  │
│   └─── 回到 1 ─┘               │
│                                 │
│   否 ──→ 返回最终回答            │
└─────────────────────────────────┘
```

### 6.2 Agent 类型

| Agent | 用途 | 工具 |
|-------|------|------|
| QueryDataAgent | NL2SQL 数据查询 | query_database |
| KnowledgeQAAgent | RAG 知识问答 | search_knowledge |
| AnalysisAgent | 数据分析（多步推理） | query_database, search_knowledge |
| BusinessOperationAgent | 业务操作 | call_business_api |
| GeneralChatAgent | 通用对话 | 无工具 |

### 6.3 Pipeline 编排

**Sequential Pipeline（串行）：**

```
ANALYSIS 意图:  QueryDataAgent ──→ AnalysisAgent ──→ 最终回答
                 (查询数据)           (分析数据)
```

---

## 7 Agent Router 设计

```
AgentRouter.route(sessionId, userId, message, intentHint)
    │
    ├── 1. 意图识别: IntentService.recognizeIntent() (LlmService)
    │       └── 根据 agent.agents.* 开关动态生成 prompt
    │
    ├── 2. 开关校验: LLMConfig.AgentsToggle.isEnabled()
    │       └── 已关闭的 Agent 类型自动降级到 GENERAL_CHAT
    │
    ├── 3. 路由分发:
    │    ├── QUERY_DATA       → 单Agent: QueryDataAgent
    │    ├── KNOWLEDGE_QA     → 单Agent: KnowledgeQAAgent
    │    ├── BUSINESS_OPERATION → 单Agent: BusinessOperationAgent
    │    ├── ANALYSIS         → Pipeline: QueryDataAgent → AnalysisAgent
    │    └── GENERAL_CHAT     → 单Agent: GeneralChatAgent
    │
    └── 4. 结果转换: Msg → AgentResult
```

---

## 8 LLM 调用层设计

```
非 Agent 路径:
  IntentService  ──→ LlmService ──→ Spring AI ChatClient ──→ DashScope
  ChatService    ──→ LlmService ──→ Spring AI ChatClient ──→ DashScope
  AgentWorkflow  ──→ LlmService ──→ Spring AI ChatClient ──→ DashScope

Agent 路径:
  ReActAgent ──→ AgentScope DashScopeChatModel ──→ DashScope API
```

两者共享同一个 DashScope API Key，但模型可以不同：
- LlmService 默认使用 `deepseek-r1`（`spring.ai.dashscope.chat.options.model`）
- Agent 默认使用 `qwen-max`（`agentscope.model.name`，工具调用能力更强）

---

## 9 API 接口

### POST /api/chat

简单对话（LlmService 路径，纯 LLM 无 Tool Calling）

```json
{
  "message": "你好，今天天气怎么样？",
  "userId": "user_001"
}
```

### POST /api/agent/execute

复杂 Agent 任务（AgentScope 路径，支持 Tool Calling + 多步推理）

```json
{
  "message": "对比分析各班组本月和上月的得分变化趋势",
  "userId": "user_001",
  "intentHint": "ANALYSIS"
}
```

---

## 10 配置说明

### application.yml 关键配置

```yaml
agentscope:
  model:
    name: qwen-max
    enable-thinking: false

agent:
  max-steps: 5
  default-timeout: 60000
  agents:
    query-data: true
    knowledge-qa: true
    business-operation: true
    analysis: true
    general-chat: true
```

### Agent 开关

通过 `agent.agents.*` 可自由启用/关闭各意图类型的 Agent。
关闭的类型不会出现在 IntentService 的候选列表中，且 AgentRouter 会自动降级到 GENERAL_CHAT。

---

## 11 框架可替换性

本架构的核心设计原则是 **Agent 框架可替换**：

```
当前:  AgentScope ─→ ToolRegistryAdapter ─→ ToolRegistry ─→ AiTool ─→ 业务系统
未来:  LangGraph  ─→ LangGraphAdapter    ─→ ToolRegistry ─→ AiTool ─→ 业务系统
      Dify       ─→ DifyAdapter          ─→ ToolRegistry ─→ AiTool ─→ 业务系统
```

更换 Agent 框架时：
- AiTool 实现（4 个工具类）：**零修改**
- ToolRegistry：**零修改**
- LlmService：**零修改**
- 需要修改：新框架的 Adapter + AgentFactory + AgentRouter

---

## 12 后续扩展方向

| 方向 | 说明 | AgentScope 支持 |
|------|------|----------------|
| MsgHub 多 Agent 辩论 | 多角度分析同一问题 | MsgHub |
| FanoutPipeline 并行分析 | 同时查询多数据源 | FanoutPipeline |
| Hook 可观测性 | 记录每步推理和工具调用 | Hook System |
| 长期记忆 | 跨会话记忆用户偏好 | LongTermMemory |
| MCP 协议 | 连接外部 MCP 工具服务器 | MCP SDK |

---

## 13 参考资料

- [AgentScope Java 官网](https://java.agentscope.io/en/intro.html)
- [AgentScope Java GitHub](https://github.com/agentscope-ai/agentscope-java)
- [Spring AI 文档](https://docs.spring.io/spring-ai/reference/)
- [ARCHITECTURE.md](ARCHITECTURE.md) — AI Agent 开发规范
