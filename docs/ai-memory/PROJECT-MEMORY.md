# ReachAI Project Memory

## 产品定位

ReachAI 是面向 Java 企业系统的 AI 能力中台。它不是单纯的 Workflow Builder，也不是只扫描历史项目生成 Tool 的工具；它把企业系统中的接口、领域方法、知识、模型、流程、权限和运行审计沉淀为 Agent 可理解、可编排、可治理、可开放的能力资产。

稳定表述：

- 面向 Java 企业系统的 AI 能力中台。
- Enterprise AI Capability Platform。
- AI Agent Control Plane / Enterprise Agent Runtime Platform 可作为架构侧表达。

## 主线流程

1. 业务系统引入 `reachai-spring-boot2-starter` 和 `reachai-capability-sdk`。
2. 业务方法或 Controller 使用 `@ReachCapability` 声明能力，参数或 DTO 字段使用 `@ReachParam` 补充语义，返回 DTO 字段可使用 `@ReachOutput` 声明可引用输出。
3. Starter 启动时扫描本地 Bean、注册项目并发送实例心跳；能力描述留在业务进程中，能力快照由 Project Onboarding 任务或 API 管理显式触发 SDK 同步，SDK 图也使用独立同步契约。
4. 平台形成字段级 diff、评审 apply/ignore，并沉淀正式能力资产。
5. Workflow Studio 基于能力资产编排 Workflow `GraphSpec`（表：`runtime_workflow`）。
6. Agent（表：`runtime_agent`）发布版本化 Supervisor 配置和 Workflow-as-Tool 白名单；Runtime 通过 AgentScope 理解、规划和选择一个或多个已发布 Workflow，并对失败做有限重规划。
7. RunOps、Trace、ACL、Guard、Gateway、MCP、A2A 和嵌入式对话负责生产治理与开放。

## 当前模块地图

- `ai-admin-front/`: Vue 3 + Element Plus 管理端。
- `reachai-control-service/`: Platform Control / public API BFF 主入口，保持 `/api/**`、`/embed/**` 和 SDK 注册公开入口。
- `reachai-runtime-service/`: Runtime Host，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service/`: Capability Catalog，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service/`: Knowledge / Retrieval，承接知识库、文件、chunk、文档 pipeline、RAG、业务索引、向量检索和历史扫描器实现。不要再称为“技能服务”。
- `reachai-model-service/`: Model Gateway，承接模型中心 V2（model_template + model_instance）、Chat、Embedding、Rerank；不再提供 /model/openai-proxy。
- `ai-common/`: 通用模型、响应和工具类。
- `reachai-capability-sdk/`: JDK8 兼容的业务系统能力声明 SDK 契约。
- `reachai-spring-boot2-starter/`: Spring Boot 2 接入、扫描、注册、心跳、能力同步和 SDK 图同步。
- `ai-runtime-contract/`: 中台内部业务记忆引用契约。
- `sql/`: `initV2.sql` 新库 SQL 基线与升级脚本入口。
- `docs/`: 当前权威知识库。

旧 `ai-agent-service` module 已从仓库主路径删除，不再是平台主后端、默认后端模块、历史 fallback、本地启动项或部署单元。

## 后端边界

当前五服务拓扑：

| 逻辑域 | 部署单元 | 默认端口 |
| --- | --- | ---: |
| Platform Control | `reachai-control-service` | 18603 |
| Runtime Host | `reachai-runtime-service` | 18604 |
| Capability Catalog | `reachai-capability-service` | 18605 |
| Knowledge / Retrieval | `reachai-knowledge-service` | 18602 |
| Model Gateway | `reachai-model-service` | 18601 |

第一阶段保持同一个 MySQL 库，不拆库。公开 `/api/**`、`/embed/**` 和 SDK 注册入口继续由 `reachai-control-service` 兼容收口，不要求前端直接调用 Runtime/Capability 内部服务。剩余兼容入口必须迁为 owning service 本地实现或正式删除，不能默认转发到旧 `ai-agent-service`。

同库阶段仍按服务治理表所有权。`docs/architecture/service-table-ownership.md` 是当前表 ownership 矩阵；跨服务直接读写表默认违规，服务间协作应走 owning service 的 internal API、显式 client 或服务自有 read model。守护脚本 `scripts/check-service-table-ownership.mjs` 会检查 `@TableName`、MyBatis 注解 SQL、MyBatis XML SQL、JdbcTemplate SQL，以及 `sql/initV2.sql` 中的 `CREATE TABLE` 是否都登记 owner。

## SQL 基线

`sql/initV2.sql` 是当前新库 SQL 基线入口。它覆盖注册中心、能力资产、Agent、GraphSpec、Trace、RunOps、Tool ACL、Guard、模型、知识库、业务索引、MCP、A2A、Gateway、市场资产和嵌入式对话等表。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

`sql/initV2.sql` 中每张 `CREATE TABLE` 表都必须在 service table ownership 矩阵中有唯一 owning service。代码访问是判断边界违规的事实源；当前基线面向新库重建，不要求兼容旧表名或迁移旧数据。

未来 SQL 变化必须同时维护：

- `sql/initV2.sql`
- `sql/upgrade-YYYYMMDD-short-name.sql`
- `sql/README.md` 或相关说明

不再使用 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 作为活跃迁移目录。

## Agent、Workflow Studio 与 Runtime

Agent 与 Workflow 已解耦：

- Agent（`runtime_agent`）：稳定身份、接入形态、项目范围和启用状态；项目中不存在第二套入口实体模型。
- Agent 配置版本（`runtime_agent_config_version`）：Supervisor runtime、模型、提示词、限制、超时和策略快照。
- Workflow（`runtime_workflow`）：`GraphSpec`、`canvas_json`、版本与发布。
- Workflow Studio：可视化画布、交互式节点、会话式调试台、AI 生成/局部修改、SDK 图展示、发布校验、Runtime 执行与 Trace/RunOps 复盘。
- Workflow-as-Tool（`runtime_agent_workflow_tool`）：某个 Agent 配置版本允许 Supervisor 选择的 Workflow 工具白名单和契约覆盖。
- Workflow `INTERACTION`：Runtime 已具备 GraphSpec-native 暂停/恢复、canonical uiRequest、`WAITING_USER` RunOps 语义与 Debug/Agent/Embed 提交契约；详见 `docs/architecture/workflow-interaction-runtime.md`。Registry 仅在生产 Live E2E 全部门槛通过后开放（当前仍 `publishable/studio/aiAuthoring=false`，状态 CODE_READY / E2E_PENDING）。
- Workflow 节点能力（安全范围已冻结）：`VARIABLE_ASSIGN` / `TEMPLATE` / `VARIABLE_AGGREGATOR`（STABLE）；`KNOWLEDGE_RETRIEVAL` / `HTTP_REQUEST` / `LOOP`（BETA open，Browser/Live/LOOP E2E PENDING）；`INTERACTION` 关闭。`KNOWLEDGE_RETRIEVAL` 新节点默认 `evidencePolicy=REQUIRED`，以 `route:evidence / route:no_evidence` 显式分流；无证据是成功业务结果，禁止 `always` 回退给 LLM，并只向 Trace 投影白名单计数/耗时。历史缺字段节点暂按 `OPTIONAL` 并产生发布警告。安全五项见 `SECURITY-BACKLOG.md`（不再扩张）。`LOOP` v1=有界串行 FOREACH（平面 GraphSpec + bodyNodeIds）。Control→Runtime：HMAC + `BODY_SHA256`；nonce 满容 fail-closed；审计 `userId` 仅来自 `WorkflowExecutionIdentity`。

当前 Agent 主执行链路是：`Agent` -> ACTIVE Agent 配置版本 -> AgentScope Java `2.0.0` 正式版 Supervisor -> PLAN / Workflow-as-Tool / 有限 REPLAN -> 汇总回答。执行 API 统一使用 `agentId`。事实查询优先 API/数据 Workflow；只有用户明确要求打开、跳转、在页面查询或操作时才允许页面动作。跨路由页面动作必须在同一 embed session 内完成 `NAVIGATE -> TARGET_READY -> PAGE_ACTION`。

Supervisor 策略链已实现分级执行：READ 自动执行，PAGE_ACTION 校验原始用户问题的显式页面意图，WRITE 生成绑定 interaction/permissionKey/toolName/args 的一次性确认，不可逆操作默认拒绝；project、tenant、roles、permissionKey 与 ACTIVE allowlist 在同一 Guard/Trace 决策点校验。Agent Eval 直接执行已发布 Agent 配置并持久化真实结果，管理端路由为 `/agent/:id/evals`。

当前接入入口已对齐该主线：“项目接入工作台” provisioning 只创建/复用项目页面副驾驶 Agent 并发布 ACTIVE Supervisor 配置，不创建占位 Workflow；“业务页面工作台”管理页面地图与任务交付。只有真实发布、具有一等 TARGET PAGE 绑定并加入 ACTIVE Agent 配置版本的 PAGE_ASSISTANT Workflow 才进入“已发布”列表。

项目接入与页面扫描共用 Control 的 AI Coding Task Kernel。正式客户端为 Codex、Cursor、Trae、Claude Code；Windows 交接通过当前用户 DPAPI 加密缓存跨 Shell 恢复，并由公共 helper 强制 UTF-8 JSON 回传。客户端事件和自然语言无权完成任务，只有 Artifact 校验、领域应用和显式平台门禁后的服务端 `task.executionStatus` 是完成事实源。项目接入在 `CODE_READY / RUNTIME_READY / E2E_READY` 未全部 `PASS` 时停留于 `RESULT_APPLIED`；真实 E2E 由任务启动后的 Embed Session、用户消息和助手回复证明。

`GraphSpec` 是平台可执行语义的核心中间表示，归属 Workflow 而非 Agent。新增节点、边、变量映射、条件路由或 Runtime 行为时，优先维护 Workflow `GraphSpec` 语义，不能只扩展画布 JSON。

## 命名规则

- 2026-09-15 已确认的目标是同时服务 Java 接入开发者与实施人员，分别建设“业务方法”和“API”的管理与使用路径；`Capability / 能力` 可保留为统称或技术身份。本条记录目标决定，不代表实现状态；恢复本专项先读[实施基线](../plans/业务方法与API重构实施基线.md)和[实施进度](../plans/业务方法与API重构实施进度.md)。
- ReachAI 禁止重新引入自创的 Skill 业务资产模型。Skill 仅用于标准 Agent Skill 包或外部协议字段；Capability 是业务资产，Tool 是调用协议，Workflow 是 GraphSpec 编排。
- 通用 Tool 不作为独立产品资产或菜单；当前管理端以业务方法、API、可调用 Workflow、MCP 暴露和调用权限呈现 owning object。旧能力目录和 `/tool` 重定向已删除，来源变化在所属项目处理。`capability_tool_definition` 与 `/api/tools/**` 仅保留为运行时投影和兼容契约。“业务方法”使用独立 owner、接纳修订、类型和页面，API 使用独立资产与来源关系；调用投影不能作为资产事实源。详见[现行资产架构](../architecture/business-method-api-assets.md)，本批实际验证和限制见专项实施进度。
- `reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不要再描述成“技能服务”。
- `eaf.*`、`X-EAF-*`、`Eaf*` 属于兼容敏感技术身份；品牌文案改成 ReachAI 时不要顺手替换这些标识。

## 前端事实

管理端是工作台型产品，优先信息密度、稳定布局和重复操作效率。项目范围选择器、注册中心、扫描项目、Workflow Studio 侧边栏折叠等行为与 `MainLayout.vue`、`ProjectSelector.vue`、router 和 project store 相关。改导航和布局前先检查这些共享位置。

当前前端大页已完成职责拆分：

- `WorkflowStudio.vue` 只保留主画布编排 shell；持久化、发布、调试、AI draft、history、API query template 等状态和业务逻辑位于 `views/workflow/composables/`，节点配置面板位于 `views/workflow/studio-panels/`，评测、源码、API 查询模板、发布和调试覆盖层位于 `views/workflow/studio-overlays/`。
- `PageAssistantWizard.vue` 保留兼容路由名，但产品语义是“业务页面工作台”；四个自由 Tab 的面板位于 `views/registry/components/page-workbench/`，真实 API 数据与操作集中在 `useBusinessPageWorkbench.ts`。
- `ScanProjectDetail.vue` 已收敛为组装层；header、overview、modules/tools、drawers/dialogs 拆入 `views/scan/components/scan-project/`。
- `RegistryProjectDetail.vue`、`SdkAccessWizard.vue` 已收为 composable 组装层；继续改动前先复用 registry viewModel/composables。
- `ApiGraphCanvas.vue` 已拆出 graph geometry/viewModel、data actions、drag、horizontal dock 和 styles。

开发代理：

- `/api/**` -> `reachai-control-service:18603`
- `/ai/**` -> `reachai-knowledge-service:18602`
- `/model/**` -> `reachai-model-service:18601`

前端不应直接依赖 `reachai-runtime-service:18604` 或 `reachai-capability-service:18605`。
