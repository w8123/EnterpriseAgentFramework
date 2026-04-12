

# 🏢 Enterprise Agent Framework

**让每一个 Java 企业系统，都能拥有自己的 AI Agent**

[Java](https://openjdk.org/)
[Spring Boot](https://spring.io/projects/spring-boot)
[Spring AI](https://spring.io/projects/spring-ai)
[Vue](https://vuejs.org/)
[License](LICENSE)

[English](README_EN.md) | **中文**

*一套开箱即用的企业级 AI Agent 基础设施平台 — 统一智能体编排、RAG 知识引擎、模型网关，帮助 Java 企业在不改动一行历史代码的前提下，快速落地 AI Agent 项目。*



---

## 🔥 为什么需要这个项目？

在 AI Agent 的浪潮中，Python 生态（LangChain、AutoGen 等）占据了绝大多数开源方案。但现实是：

> **中国 80% 以上的企业核心系统是 Java 技术栈。**

这意味着：

- 🏗️ 存量系统庞大 — 数百万行 Spring Boot / SSM / Dubbo 代码不可能推倒重来
- 🔌 接入成本极高 — Python Agent 框架与 Java 业务系统之间存在巨大的技术鸿沟
- 👥 团队技能不匹配 — Java 团队被迫学习 Python 生态，效率大打折扣
- 🔌 python在web生态较差 — Python在web生态、企业级场景下相比Java较差

**Enterprise Agent Framework 就是为解决这个问题而生的。** 它是一套**纯 Java 技术栈**的企业级 AI Agent 完整解决方案，让 Java 团队用最熟悉的技术，最低的成本，最快的速度落地智能体项目。

---

## ✨ 核心特性

### 🤖 智能体编排引擎

- 基于 **AgentScope + Spring AI** 的 ReAct Agent，支持意图识别、多步推理、Tool 自动调用
- **AgentDefinition 驱动路由** — 全配置化的 Agent 调度，新增 Agent 无需改代码，管理后台即可完成
- 内置 AgentRouter 意图路由，支持单 Agent / Pipeline 多 Agent 协作
- 支持多触发方式（对话 / API / 事件驱动），为 AI 能力中台奠定基础
- 完整的会话记忆管理（Redis），支持多轮对话上下文

### 📚 RAG 知识引擎

- 文档全生命周期管理：上传 → 解析 → 分块 → Embedding → 向量检索
- 支持 PDF、Word、Excel、TXT 等多种文档格式
- 基于 **Milvus** 的高性能向量检索，支持语义搜索与查重
- 业务索引能力，支持结构化数据的语义搜索

### 🔗 统一模型网关

- 多模型 Provider 路由（通义千问 / DashScope / OpenAI 兼容接口）
- 统一的 Chat & Embedding API，一次对接，随时切换模型
- 内置 OpenAI 兼容代理，第三方工具可直接对接
- 流式 SSE 响应，实时输出

### 🛠️ 尽量不改动接入历史系统

- **独创的 Skill SDK 体系** — 老系统无需改动或极少改动，通过 Feign 桥接即可将业务 API 变为 Agent 可调用的 Tool
- 标准化的 `AiTool` 接口契约，实现即注册，开箱即用
- 支持 Swagger/OpenAPI 扫描 → 自动生成 Tool 定义（规划中）
- 配合 Cursor AI 辅助生成 Skill Service，效率翻倍

### 🖥️ 可视化管理后台

- 基于 **Vue 3 + Element Plus** 的现代化管理界面
- Agent 全生命周期管理：配置、调试、启停，支持 **AI 能力中台配置**（知识库组、Prompt 模板、输出 Schema、触发方式）
- 意图类型支持预置 + 自定义扩展，列表多维筛选
- 知识库管理、模型调试、Tool 管理一站式搞定
- 开箱即用，无需额外开发管理工具

### 🚀 生产级部署方案

- Docker Compose 一键启动全套基础设施
- 提供 Kubernetes 部署清单，支持云原生部署
- Nacos 服务注册与配置中心，微服务架构开箱即用

---

## 🏗️ 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                     ai-admin-front (Vue 3)                      │
│              统一管理后台 · Agent 调试 · 知识库 · 模型            │
└────────────────────────────┬────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        ▼                    ▼                    ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────────┐
│ ai-agent     │   │ ai-text      │   │ ai-model         │
│ -service     │   │ -service     │   │ -service         │
│              │   │              │   │                  │
│ 智能体编排    │──▶│ RAG 引擎     │   │ 模型网关          │
│ 意图路由      │   │ 知识库管理    │   │ 多 Provider 路由  │
│ Tool 调用     │   │ 向量检索      │   │ Chat / Embedding │
│ 会话记忆      │   │ 文档 Pipeline │   │ SSE 流式         │
└──────┬───────┘   └──────────────┘   └──────────────────┘
       │
       ▼
┌──────────────────────────────────────┐
│         ai-skill-sdk + services       │
│                                      │
│  AiTool 接口 · ToolRegistry          │
│  DatabaseQueryTool · BusinessApiTool  │
│  ──── Feign 桥接 ────                │
└──────────────┬───────────────────────┘
               │ HTTP
               ▼
┌──────────────────────────────────────┐
│        历史业务系统 (JDK 1.8+)        │
│   CRM · ERP · OA · 任意 Java 系统    │
│        无需改动任何代码或微改动               │
└──────────────────────────────────────┘
```

---

## 📦 模块说明


| 模块                    | 说明                                                  | 端口   |
| --------------------- | --------------------------------------------------- | ---- |
| **ai-common**         | 公共库 — DTO、异常定义、通用配置                                 | -    |
| **ai-skill-sdk**      | Skill 开发 SDK — AiTool 接口、ToolParameter、ToolRegistry | -    |
| **ai-skill-services** | 内置业务工具 — DatabaseQuery、BusinessApi、UserProfile 等    | -    |
| **ai-model-service**  | 模型网关 — LLM Chat / Embedding，多 Provider 路由           | 8090 |
| **ai-text-service**   | RAG 引擎 — 知识库、文档 Pipeline、向量检索                       | 8080 |
| **ai-agent-service**  | 智能体编排 — AgentScope、意图识别、Tool 调用、会话记忆                | 8081 |
| **ai-admin-front**    | 管理前端 — Vue 3 + Vite + Element Plus + TypeScript     | 3000 |
| **deploy**            | 部署配置 — Docker Compose / Kubernetes                  | -    |


---

## 🚀 快速开始

### 环境要求

- **JDK 17+**
- **Maven 3.8+**
- **Node.js 18+**（前端）
- **Docker & Docker Compose**（基础设施）

### 1. 克隆项目

```bash
git clone https://github.com/your-username/EnterpriseAgentFramework.git
cd EnterpriseAgentFramework
```

### 2. 启动基础设施

一键拉起 MySQL、Redis、Milvus、Nacos：

```bash
docker compose -f deploy/docker-compose.infra.yml up -d
```

### 3. 初始化数据库

```bash
mysql -h localhost -u root -proot ai_text_service < sql/init.sql
```

### 4. 构建全部 Java 模块

```bash
mvn clean install -DskipTests
```

### 5. 按顺序启动服务

```bash
# 1) 模型网关（其他服务依赖它）
cd ai-model-service && mvn spring-boot:run

# 2) RAG 引擎
cd ai-text-service && mvn spring-boot:run

# 3) 智能体编排
cd ai-agent-service && mvn spring-boot:run
```

### 6. 启动管理前端

```bash
cd ai-admin-front && npm install && npm run dev
```

访问 **[http://localhost:3000](http://localhost:3000)** 即可进入管理后台。

---

## 🔧 如何接入你的历史系统

Enterprise Agent Framework 的核心价值在于：**让老系统的业务能力成为 AI Agent 可调用的 Tool，且老系统零改动或微改动。**

只需 3 步：

### Step 1：实现 AiTool 接口

```java
@Component
public class QueryCustomerTool implements AiTool {

    @Autowired
    private CrmFeignClient crmClient;

    @Override
    public String name() { return "query_customer"; }

    @Override
    public String description() {
        return "查询客户信息，支持按姓名、手机号、客户编号检索";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
            ToolParameter.required("keyword", "string", "搜索关键词")
        );
    }

    @Override
    public Object execute(Map<String, Object> args) {
        return crmClient.searchCustomer((String) args.get("keyword"));
    }
}
```

### Step 2：定义 Feign Client 桥接老系统

```java
@FeignClient(name = "legacy-crm", url = "${legacy.crm.url}")
public interface CrmFeignClient {
    @GetMapping("/api/customer/search")
    Object searchCustomer(@RequestParam String keyword);
}
```

### Step 3：打包为 jar，Agent 自动发现

将 Skill Service 作为 Maven 依赖引入 `ai-agent-service`，Spring Boot AutoConfiguration 自动注册，Agent 即刻获得调用该业务能力的能力。

**就是这么简单。** 你的 CRM / ERP / OA 系统不需要改动任何代码，Agent 就能通过自然语言调用它们的业务接口。

---

## 🛠️ 技术栈


| 层级       | 技术                                                               |
| -------- | ---------------------------------------------------------------- |
| **语言**   | Java 17                                                          |
| **框架**   | Spring Boot 3.4 · Spring Cloud 2024.0 · Spring Cloud Alibaba     |
| **AI**   | Spring AI 1.0 · Spring AI Alibaba (DashScope) · AgentScope 1.0.9 |
| **数据**   | MySQL 8 · Redis 7 · Milvus 2.4                                   |
| **注册中心** | Nacos 3.0                                                        |
| **ORM**  | MyBatis-Plus 3.5                                                 |
| **文档解析** | Apache POI 5.2 · PDFBox 2.0                                      |
| **前端**   | Vue 3 · Vite 6 · Element Plus · TypeScript · Pinia               |
| **部署**   | Docker · Kubernetes                                              |


---

## 🗺️ 路线图

### 已完成

- ✅ **AI Agent 编排引擎** — AgentScope + ReAct Agent + 意图路由
- ✅ **RAG 知识引擎** — 文档 Pipeline + Milvus 向量检索
- ✅ **统一模型网关** — 多 Provider 路由 + SSE 流式
- ✅ **Skill SDK 体系** — AiTool 接口 + ToolRegistry + AutoConfiguration
- ✅ **管理后台** — Agent / 知识库 / 模型 / Tool 管理
- ✅ **Docker / K8s 部署方案**
- ✅ **AgentDefinition 驱动路由** — 全配置化 Agent 调度，消除硬编码，管理后台可视化配置

### 进行中

- 🔨 **AI 能力 API Gateway** — 标准化 REST API 输出 AI 能力（生成、审查、提取、检索、摘要、问答、数据查询）
- 🔨 **Prompt 模板管理** — 可视化 Prompt 模板编辑、变量注入、版本管理
- 🔨 **多知识库协同检索** — 跨知识库组检索与结果融合
- 🔨 **结构化输出** — Agent 返回 JSON Schema 约束的结构化数据

### 规划中

- **Swagger/OpenAPI 自动扫描** — 扫描老项目接口，自动生成 Tool 定义
- **Controller 注解扫描** — 基于 JavaParser 静态分析
- **Freemarker 代码生成器** — 一键生成 Skill Service 脚手架
- **Cursor AI Skill** — AI 辅助理解业务语义，智能生成 Skill
- **Python Tool Protocol** — 跨语言 Tool 支持（MCP 兼容）
- **Tool 权限、限流、审计** — 企业级治理能力
- **调用链追踪与可观测性** — 全链路监控
- **WorkflowEngine / 状态机** — 多步骤、人机协同的复杂业务流程编排

---

## 🤝 适用场景


| 场景               | 说明                                   |
| ---------------- | ------------------------------------ |
| **传统企业 AI 转型**   | 有大量 Java 存量系统，想快速落地 AI Agent，但不想重写系统 |
| **智能客服 / 智能助手**  | 基于 RAG + Agent 构建企业知识问答与业务操作助手       |
| **内部效率工具**       | 让员工通过自然语言查数据、下单、审批，告别复杂的系统操作         |
| **AI 中台建设**      | 统一管理企业内多个 AI Agent、知识库、模型，避免烟囱式建设    |
| **Java 团队学习 AI** | 完整的 Spring AI + Agent 实战项目，最佳学习参考    |


---

## 💡 设计理念

1. **老系统零改动** — 历史项目保持独立运行（原 JDK 版本、原部署方式），框架通过 HTTP 桥接调用
2. **Java 原生 + Python辅助** — 不是 Python 的附庸，而是 Java 生态的一等公民方案
3. **SDK 化、可插拔** — Tool 体系高度解耦，实现 AiTool 接口即可注册，无框架绑定
4. **生产可用** — 不是 Demo，而是面向生产环境设计的完整基础设施
5. **渐进式接入** — 可以从一个 Tool 开始，逐步扩展，无需一步到位

---

## 📄 项目结构

```
EnterpriseAgentFramework/
├── ai-common/              公共库（DTO、异常、通用配置）
├── ai-skill-sdk/           Skill 开发 SDK（AiTool 接口、ToolParameter、ToolRegistry）
├── ai-skill-services/      内置业务工具（DatabaseQuery、BusinessApi、UserProfile）
├── ai-model-service/       模型网关（LLM Chat / Embedding，多 Provider 路由）
├── ai-text-service/        RAG 引擎（知识库、文档 Pipeline、向量检索）
├── ai-agent-service/       智能体编排（AgentScope、意图识别、Tool 调用、会话记忆）
├── ai-admin-front/         管理前端（Vue 3 + Element Plus + TypeScript）
├── deploy/                 部署配置（Docker Compose / Kubernetes）
├── sql/                    数据库初始化脚本
└── docs/                   架构设计文档
```

---

## 🌟 Star History

如果这个项目对你有帮助，请给一个 ⭐ Star，这是对作者最大的鼓励！

---

## 📬 联系与交流

- 如果你也在做 Java + AI 的事情，或者企业面临AI转型，欢迎交流探讨
- QQ群 1073839193
---



**Enterprise Agent Framework** — 让 Java 企业拥抱 AI Agent 时代

*Built with ❤️ by Java developers, for Java developers.*

