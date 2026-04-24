

# 🏢 Enterprise Agent Framework

**让每一个 Java 企业系统，都能拥有自己的 AI Agent**

[Java](https://openjdk.org/)
[Spring Boot](https://spring.io/projects/spring-boot)
[Spring AI](https://spring.io/projects/spring-ai)
[Vue](https://vuejs.org/)
[License](LICENSE)

[English](README_EN.md) | **中文**

*一套开箱即用的企业级 AI Agent 基础设施平台 — 统一智能体编排、RAG 知识引擎、模型网关，帮助 Java 企业在不改动一行历史代码的前提下，快速落地 AI Agent 项目。*

本仓库的定位正在从「对话型 Agent 后台」演进为**企业 AI 能力中台**：一方面通过 **Skill SDK / AiTool** 把存量业务 API 变成 Agent 可调用的工具（**业务能力 → Agent**）；另一方面规划标准化 **REST 能力面**（如 `/api/ai/*`），便于业务系统在任意环节嵌入生成、审查、抽取、检索等能力（**AI 能力 → 业务系统**）。编排层、RAG、模型网关与 Tool 体系共享同一套底座。

---

## 🔥 为什么需要这个项目？

在 AI Agent 的浪潮中，Python 生态（LangChain、AutoGen 等）占据了绝大多数开源方案。但现实是：

> **中国 80% 以上的企业核心系统是 Java 技术栈。**

这意味着：

- 🏗️ 存量系统庞大 — 数百万行 Spring Boot / SSM / Dubbo 代码不可能推倒重来
- 🔌 接入成本高 — 若核心链路再叠一层异构 Agent 运行时，与现有服务、权限、运维体系对齐成本显著上升
- 👥 团队与交付 — 多数企业 Java 团队占比高，在熟悉栈内落地 AI「增强能力」迭代更快、维护更稳
- 🧩 AI 是增强能力而非孤岛 — 常要嵌入审批、检索、定时任务等流程；Python 生态在模型与脚本侧很丰富，本框架以 **Java 为编排与业务集成核心**，数据处理等辅助场景可继续用 Python，并**预留跨语言 Tool 协议**（如 MCP 思路）

**Enterprise Agent Framework 就是为解决这个问题而生的。** 它是一套以 **Java 为核心技术栈**的企业级 AI Agent 基础设施，让 Java 团队用最熟悉的技术，以较低成本落地智能体与中台化能力；不与 Python 生态对立，而是在企业集成面上优先 Java。

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

- **独创的 Skill SDK 体系** — 老系统无需改动或极少改动，通过 HTTP 桥接即可将业务 API 变为 Agent 可调用的 Tool
- 标准化的 `AiTool` 接口契约，实现即注册，开箱即用
- 已提供 **一条历史系统接入主线**
  - **运行时 Web 扫描链路** — 管理后台录入项目名 / 域名 / 磁盘路径，后端扫描 OpenAPI 或 Controller，结果直接入库并注册为动态 Tool

### 🖥️ 可视化管理后台

- 基于 **Vue 3 + Element Plus** 的现代化管理界面
- Agent 全生命周期管理：配置、调试、启停，支持 **AI 能力中台配置**（知识库组、Prompt 模板、输出 Schema、触发方式）
- 意图类型支持预置 + 自定义扩展，列表多维筛选
- 知识库管理、模型调试、Tool 管理、**扫描项目管理** 一站式搞定
- 开箱即用，无需额外开发管理工具

### 🚀 生产级部署方案

- Docker Compose 一键启动全套基础设施（含 MySQL、Redis、Milvus、Nacos 等）
- 提供 Kubernetes 部署清单，支持云原生部署
- 服务间调用以 **OpenFeign** 为主；**Nacos** 可作为注册/配置中心逐步接入（能力与共享网关仍在演进中）
- **统一 AI 入口网关**（如 Spring Cloud Gateway + 鉴权/限流）规划为独立工程，与业务微服务网关解耦

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
│ ai-agent     │   │ ai-skills    │   │ ai-model         │
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
│   ai-skill-sdk + dynamic tooling     │
│                                      │
│  AiTool 接口 · ToolRegistry          │
│  DynamicHttpAiTool · Scanner Core    │
│  ──── HTTP 桥接 / 运行时注册 ────      │
└──────────────┬───────────────────────┘
               │ HTTP
               ▼
┌──────────────────────────────────────┐
│        历史业务系统 (JDK 1.8+)        │
│   CRM · ERP · OA · 任意 Java 系统    │
│        无需改动任何代码或微改动               │
└──────────────────────────────────────┘
```

**对外 API 形态（`ai-agent-service`）**：`/api/chat` 提供轻量对话、会话记忆与轻量 Tool Calling；`/api/agent/execute` 提供完整编排（意图识别、ReAct / Pipeline）；标准化 **AI 能力 REST**（`/api/ai/*`，供业务系统直接调用）在路线图中推进，与上述入口共享同一套模型、RAG 与 Tool 底座。

---

## 📦 模块说明


| 模块                    | 说明                                                  | 端口   |
| --------------------- | --------------------------------------------------- | ---- |
| **ai-common**         | 公共库 — DTO、异常定义、通用配置                                 | -    |
| **ai-skill-sdk**      | Skill 开发 SDK — AiTool 接口、ToolParameter、ToolRegistry | -    |
| **ai-model-service**  | 模型网关 — LLM Chat / Embedding，多 Provider 路由           | 8090 |
| **ai-skills-service**   | 知识 / Tooling 基础层 — RAG、文档 Pipeline、向量检索、OpenAPI / Controller 扫描核心 | 8080 |
| **ai-agent-service**  | 智能体编排 — AgentScope、意图识别、Tool 调用、会话记忆                | 8081 |
| **ai-admin-front**    | 管理前端 — Vue 3 + Vite + Element Plus + TypeScript     | 3000 |
| **deploy**            | 部署配置 — Docker Compose / Kubernetes                  | -    |

仓库根目录 `pom.xml` 当前聚合 **5 个 Java 子模块**。扫描核心已并入 `ai-skills-service`；**`ai-admin-front`** 为同目录下的 **npm / Vite 工程**，不参与 Maven 聚合；**`deploy`** 为部署清单目录，同样不在聚合内。

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
mysql -h localhost -u root -proot ai_text_service < ai-skills-service/sql/init.sql
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
cd ai-skills-service && mvn spring-boot:run

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

### Step 3：注册到运行时 Tool 体系

将实现类放入当前运行时模块或独立依赖模块并交给 Spring 托管，`ToolRegistry` 即可发现并注册该能力。

**就是这么简单。** 你的 CRM / ERP / OA 系统不需要改动任何代码，Agent 就能通过自然语言调用它们的业务接口。

### 运行时 Web 扫描接入（已实现）

对于已经部署在服务器上的历史 Java 项目，现在优先推荐直接走管理后台的 **扫描项目** 流程：

1. 在管理后台新增扫描项目，填写**项目名称**、**项目域名**、**磁盘路径**、**扫描方式**（OpenAPI / Controller）
2. 后端由 `ai-agent-service` 通过 Feign 调用 `ai-skills-service` 暴露的扫描接口（`/ai/scanner/openapi`、`/ai/scanner/controller`），对磁盘项目进行扫描
3. 扫描结果直接写入数据库：
   - `scan_project`：保存项目元数据、扫描状态、错误信息
   - `tool_definition`：保存接口定义，并用 `project_id` 关联项目
4. 开发者在页面上编辑工具名、描述、参数、开关后，即可直接作为**动态 Tool** 被 Agent 调用

这条链路的特点是：

- **没有 YAML 中间文件**
- **没有必须生成 jar 的前置要求**
- **更适合运维 / 联调 / 快速接入历史系统**

## 🛠️ 技术栈


| 层级       | 技术                                                               |
| -------- | ---------------------------------------------------------------- |
| **语言**   | Java 17                                                          |
| **框架**   | Spring Boot 3.4 · Spring Cloud 2024.0 · Spring Cloud Alibaba     |
| **AI**   | Spring AI 1.0 · Spring AI Alibaba (DashScope) · AgentScope 1.0.9 |
| **数据**   | MySQL 8 · Redis 7 · Milvus 2.4                                   |
| **注册中心** | Nacos 3.0（可选；与统一网关能力持续完善中）                                  |
| **ORM**  | MyBatis-Plus 3.5                                                 |
| **文档解析** | Apache POI 5.2 · PDFBox 2.0                                      |
| **前端**   | Vue 3 · Vite 6 · Element Plus · TypeScript · Pinia               |
| **部署**   | Docker · Kubernetes                                              |


---

## 🗺️ 路线图

### 已完成

- ✅ **AI Agent 编排引擎** — AgentScope + ReAct Agent + 意图路由；`/api/chat` 与 `/api/agent/execute` 等入口
- ✅ **RAG 知识引擎** — 文档 Pipeline + Milvus 向量检索
- ✅ **统一模型网关** — 多 Provider 路由 + SSE 流式；OpenAI 兼容代理供 AgentScope 使用
- ✅ **调用链路** — Agent 侧 LLM / RAG 经 Feign 统一走 `ai-model-service`、`ai-skills-service`
- ✅ **Skill SDK 体系** — `AiTool`（含 `parameters()`）+ `ToolRegistry` + Spring Boot 自动注册；动态 Tool 与手写 Tool 共用同一套运行时契约
- ✅ **扫描项目 Web 化闭环** — `scan_project` + `tool_definition.project_id`、`/api/scan-projects/*`、管理端扫描项目列表 / 详情页、动态 Tool 直接入库
- ✅ **会话记忆（Redis）** — 短期上下文窗口
- ✅ **管理后台** — 知识库 / Agent / 模型 / Dashboard；**Tool 管理页**对接后端 REST
- ✅ **AgentDefinition 全配置化** — `AgentRouter` / `AgentFactory` 消除硬编码；**IntentService** 意图候选随 Agent 定义动态生成；扩展字段（触发方式、知识库组、Prompt 模板 ID、输出 Schema、多 Agent 模型等）
- ✅ **Tool 管理 REST API** — `GET/POST /api/tools`（列表、详情、测试执行）
- ✅ **Docker / K8s 部署方案**

### 进行中（高优先级 backlog）

- 🔨 **AI 能力 REST（`/api/ai/*`）** — 面向业务系统的标准化能力面（生成、审查、抽取、检索、摘要、问答、数据查询等）
- 🔨 **结构化输出** — TypedAgentResult + JSON Schema 约束
- 🔨 **Prompt 模板管理** — 实体 + CRUD + 变量注入 + 管理端页面
- 🔨 **多知识库协同检索** — KnowledgeBaseGroup、多 Collection 与结果融合

### 规划中（能力与治理持续迭代）

- **RAG Embedding 解耦** — `ai-skills-service` 侧 Embedding 调用统一改走 `ai-model-service`
- **长期记忆** — 会话与偏好等 MySQL 持久化（当前以 Redis 短期记忆为主）
- **AI Gateway（独立工程）** — 统一入口、鉴权、限流、熔断
- **管理端深化** — 会话列表与历史、执行链可视化、登录与权限等（与后端 API 同步推进）
- **源码级扫描增强** — Service 层 / JavaDoc 扫描（三级扫描策略的最后一层）
- **扫描能力深化** — OpenAPI 更复杂契约支持、Service / JavaDoc 深扫、差异对比与增量更新
- **RemoteToolProvider / MCP 兼容** — 跨语言 Tool 接入
- **Tool 权限、限流、审计** — 企业级治理能力
- **调用链追踪与可观测性** — AgentScope Hook、日志与指标持久化
- **WorkflowEngine / 可视化编排** — 有状态流程、人机协同、DAG / 低代码编排（分阶段推进）

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

## 📚 架构与设计文档

更完整的背景、现状与演进路径见仓库内文档（与 README 同步维护）：

- [docs/背景、现状、目标.md](docs/背景、现状、目标.md) — 背景与动机、Tool 分层与调用链路、运行时 Web 扫描主线、分阶段实施与中台演进概要
- [docs/AI能力系统升级规划.md](docs/AI能力系统升级规划.md) — 单仓模块划分、各服务职责、扫描项目 Web 化与 backlog 路线图

---

## 📄 项目结构

```
EnterpriseAgentFramework/
├── ai-common/              公共库（DTO、异常、通用配置）
├── ai-skill-sdk/           Skill 开发 SDK（AiTool 接口、ToolParameter、ToolRegistry）
├── ai-model-service/       模型网关（LLM Chat / Embedding，多 Provider 路由）
├── ai-skills-service/        知识 / Tooling 基础层（知识库、文档 Pipeline、向量检索、扫描核心）
├── ai-agent-service/       智能体编排（AgentScope、意图识别、Tool 调用、会话记忆）
├── ai-admin-front/         管理前端（Vue 3 + Element Plus + TypeScript）
├── deploy/                 部署配置（Docker Compose / Kubernetes）
├── ai-skills-service/sql/    数据库初始化与升级脚本（含 tool_definition / scan_project）
└── docs/                   架构与设计文档（见上一节链接）
```

根目录 Maven 聚合与各模块关系见上文「📦 模块说明」表格下方的说明。

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

