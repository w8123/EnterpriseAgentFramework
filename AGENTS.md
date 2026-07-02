# ReachAI Agent Rules

本文件是给 Codex、Cursor、Claude Code、Copilot CLI 等 AI 编程工具看的项目级规则。进入本仓库后，智能体必须先读本文件，再执行代码、SQL 或文档修改。

## 工作原则

- 以当前仓库为事实源。先查真实代码、SQL、接口、前端页面和文档，再下结论。
- 可以修改前端、后端、SQL、文档和构建配置，但改动必须聚焦当前任务。
- 不要为了兼容旧数据而牺牲当前正确设计。本项目默认处于快速迭代阶段，schema 和种子数据可以按当前目标直接演进。
- 不要回滚、格式化或重写与任务无关的用户改动。
- Windows PowerShell 是常见执行环境；中文文件统一按 UTF-8 读写，避免 GBK/mojibake。
- 使用 `rg` / `rg --files` 优先查找文件和文本。

## 项目定位

ReachAI 是面向 Java 企业系统的 AI 能力中台，不只是 Workflow Builder，也不只是扫描历史项目生成 Tool。

当前主线是：

1. 业务系统通过 `reachai-spring-boot2-starter`、`reachai-capability-sdk`、`@ReachCapability`、`@ReachParam` 主动注册项目、实例、能力和 SDK 图。
2. 平台侧形成能力快照、字段级 diff、评审 apply/ignore，并沉淀到能力资产目录。
3. Workflow Studio 使用 `GraphSpec` 编排 Workflow（V2 表：`runtime_workflow`）；Agent 入口（V2 表：`runtime_agent`）通过 binding 绑定 Workflow。AI 生成、局部修改、调试、发布和回放均在 Workflow Studio 闭环。
4. Runtime 通过 `AgentRuntimeAdapter` 解耦 AgentScope、LangGraph4j 和未来运行时。
5. RunOps、Trace、Tool ACL、Guard、Gateway、MCP、A2A、嵌入式对话和企业身份共同组成生产治理边界。

当前后端重塑已进入物理服务拆分后的旧结构退场阶段。目标拓扑是 `reachai-control-service`、`reachai-runtime-service`、`reachai-capability-service`、`reachai-knowledge-service`、`reachai-model-service`。第一阶段保持同一个 MySQL 库，不拆库；公共入口由 `reachai-control-service` 保持 `/api/**`、`/embed/**` 和 SDK 注册入口兼容。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。任何新增代码必须遵守服务表所有权和服务间 API 边界，不允许为了快速编译跨服务直接写对方表。

## 关键目录

- `ai-admin-front/`: Vue 3 + Element Plus 管理端。
- `reachai-control-service/`: 当前 Platform Control / public API BFF 主入口，保持 `/api/**`、`/embed/**` 和 SDK 注册公开入口。
- `reachai-runtime-service/`: 当前 Runtime Host 部署单元，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service/`: 当前 Capability Catalog 部署单元，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service/`: 当前 Knowledge / Retrieval 部署单元；包括知识库、文件、chunk、RAG、业务索引、向量检索和历史扫描器实现。不要再把它描述成“技能服务”。
- `reachai-model-service/`: 当前 Model Gateway 部署单元；包括模型实例中心、Chat、Embedding、Rerank 和 OpenAI 兼容代理。
- `reachai-capability-sdk/`: JDK8 兼容的业务能力声明 SDK 契约。
- `reachai-spring-boot2-starter/`: Spring Boot 2 业务系统接入、扫描、注册和 SDK 图同步。
- `ai-runtime-contract/`: 中台内部 Tool / Skill 运行时契约。
- `sql/initV2.sql`: 当前新库 SQL 基线入口；第一阶段仍是同一个 MySQL 库，不拆库。
- `sql/upgrade-*.sql`: 仅用于当次数据库变更升级已有开发/测试库；当前 V2 新库基线不要求兼容旧数据。
- `docs/`: 当前权威知识库。
- `docs/ai-memory/`: 给 AI 编程工具看的项目记忆。

## SQL 规则

- 任何 schema、索引、种子数据、字段语义相关改动，都必须检查 `sql/initV2.sql`。
- 如果改动需要数据库变化，必须至少修改 `sql/initV2.sql`，让全新环境直接可用。
- 如果该数据库变化还需要落到已有开发/测试库，必须新增一份 `sql/upgrade-YYYYMMDD-short-name.sql`，写清升级影响，并更新 `sql/README.md` 或相关文档中的执行说明。
- 不再使用 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 作为活跃迁移目录。
- 默认不为旧数据做复杂兼容迁移；如果必须清理、重建、重命名或丢弃旧字段，直接在升级 SQL 和变更说明里写清楚。
- MySQL 5.7/8 兼容性要看 `sql/initV2.sql` 已有写法，优先沿用现有 `information_schema` 判空和幂等模式。
- 同库阶段仍必须维护 `docs/architecture/service-table-ownership.md`；`sql/initV2.sql` 中每张 `CREATE TABLE` 表都必须有唯一 owning service。
- 跨服务直接读写表默认违规，扫描范围包括 `@TableName`、MyBatis 注解 SQL、MyBatis XML SQL 和 JdbcTemplate SQL。确有历史兼容需要时，只能作为临时例外写入 `Additional direct access`，并说明访问服务和原因。
- 服务协作优先通过 owning service 的 internal API、显式 client 或服务自有 read model，不允许为了快速编译跨服务复用对方 Mapper、Entity 或直接 SQL。
- V2 表名按 owner service / domain 前缀收口，例如 `runtime_workflow`、`runtime_agent`、`capability_draft`、`runtime_skill_interaction`、`control_page_registry`、`runtime_tool_call_log`、`knowledge_base`。本项目当前按新库重建，不要求兼容旧表名或旧数据迁移。

## GraphSpec 与 Runtime

- `GraphSpec` 是运行语义，归属 Workflow；`canvas_json` 只是画布布局。
- 后端类型以当前主路径服务中的 `com.enterprise.ai.agent.graph.GraphSpec` 为准；`reachai-runtime-service` 是 Runtime 执行主路径，后续再评估 shared-kernel 抽取。
- 前端 Workflow 图语义类型以 `ai-admin-front/src/types/workflow.ts` 为准（Studio 状态、Workflow 定义等；共享节点/边结构仍可见于 `agent.ts` 的 `AgentGraphSpec`）。
- DB 字段：`runtime_workflow.graph_spec_json`（运行语义）、`runtime_workflow.canvas_json`（画布布局）。
- 发布校验由 `WorkflowReleaseValidationService` 负责；Runtime 执行主线在 `LangGraph4jRuntimeAdapter`（经 binding 解析 Workflow）。
- 新增 Workflow Studio 节点、AI 编辑能力或 Runtime 行为时，必须把可执行语义写入 Workflow `GraphSpec`，不能只改前端画布表现。
- AI 生成走 `/api/workflows/studio/generate-draft` 和 `LlmWorkflowDraftGenerator`；AI 局部编辑走 `/api/workflows/studio/edit-draft` 和 `WorkflowDraftEditService`。

## 命名规则

- 产品和文档默认使用 `Capability / 能力`。
- `Skill` 在本项目中多为历史命名或代码遗留，不能简单全局替换。
- V2 新库基线中历史 `skill_draft`、`skill_eval_snapshot`、`skill_interaction` 已收敛为 `capability_draft`、`capability_eval_snapshot`、`runtime_skill_interaction`；`skill_name`、`skill_kind` 等字段名如仍承载业务语义，不做无关改名。
- `eaf.*`、`X-EAF-*`、`Eaf*` 类名、Maven artifactId、运行时路径属于技术身份，品牌文案改成 ReachAI 时不要顺手改这些兼容敏感标识。

## 前端规则

- 管理端是工作台型产品，优先信息密度、扫描效率和可重复操作体验。
- 主题色、暗色/亮色适配优先使用现有 CSS 变量和主题文件，不要在页面里散落硬编码颜色。
- 涉及页面布局时，先确认路由、`MainLayout.vue`、共享组件和状态管理，不要在单页里做难以复用的局部 hack。
- Workflow Studio 变更要验证画布、配置面板、预览/应用、发布校验、Agent binding 和调试链路是否仍然一致。

## 验证规则

- 后端优先跑相关 Maven 模块测试；小改动至少跑对应模块编译或目标测试。
- 前端改动优先在 `ai-admin-front` 下跑 `npm run build`，必要时先跑 `npx vue-tsc --noEmit`。
- 文档和规则改动至少跑 `git diff --check`，并检查链接/路径是否指向真实文件。
- SQL 改动至少检查 `sql/initV2.sql`；如果当前任务新增 upgrade SQL，还要确认 upgrade SQL 包含目标表/列/索引。有 MySQL 环境时再执行验证。
- 如果用户报告具体错误，必须复现或对照同一错误签名后再声明修复完成。

## AI 记忆入口

更长的项目记忆在 `docs/ai-memory/`：

- `README.md`: 记忆区说明。
- `PROJECT-MEMORY.md`: 产品定位、模块地图和当前事实。
- `WORKING-RULES.md`: 开发、SQL、验证和协作规则。
- `DECISIONS.md`: 已形成的架构和命名决策。
- `KNOWN-PITFALLS.md`: 以前踩过的坑和诊断顺序。
- `VERIFICATION.md`: 常用验证命令。
- `AI-TOOLS.md`: Playwright 浏览器调试和 DBHub MySQL 只读查询约定。
