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
3. Starter 在启动时同步项目、实例、能力快照和 SDK 图。
4. 平台形成字段级 diff、评审 apply/ignore，并沉淀正式能力资产。
5. Workflow Studio 基于能力资产编排 Workflow `GraphSpec`（V2 表：`runtime_workflow`）。
6. Agent 入口（V2 表：`runtime_agent`）通过 binding 绑定 Workflow；Workflow 发布形成版本快照，Runtime 通过 `AgentEntry` + binding + `AgentRuntimeAdapter` 执行。
7. RunOps、Trace、ACL、Guard、Gateway、MCP、A2A 和嵌入式对话负责生产治理与开放。

## 当前模块地图

- `ai-admin-front/`: Vue 3 + Element Plus 管理端。
- `reachai-control-service/`: Platform Control / public API BFF 主入口，保持 `/api/**`、`/embed/**` 和 SDK 注册公开入口。
- `reachai-runtime-service/`: Runtime Host，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service/`: Capability Catalog，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service/`: Knowledge / Retrieval，承接知识库、文件、chunk、文档 pipeline、RAG、业务索引、向量检索和历史扫描器实现。不要再称为“技能服务”。
- `reachai-model-service/`: Model Gateway，承接模型实例中心、模型配置、Chat、Embedding、Rerank 和 OpenAI 兼容代理。
- `ai-common/`: 通用模型、响应和工具类。
- `reachai-capability-sdk/`: JDK8 兼容的业务系统能力声明 SDK 契约。
- `reachai-spring-boot2-starter/`: Spring Boot 2 接入、扫描、注册、心跳、能力同步和 SDK 图同步。
- `ai-runtime-contract/`: 中台内部 Tool / Skill 运行时契约。
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

同库阶段仍按服务治理表所有权。`docs/architecture/service-table-ownership.md` 是当前 V2 表 ownership 矩阵；跨服务直接读写表默认违规，服务间协作应走 owning service 的 internal API、显式 client 或服务自有 read model。守护脚本 `scripts/check-service-table-ownership.mjs` 会检查 `@TableName`、MyBatis 注解 SQL、MyBatis XML SQL、JdbcTemplate SQL，以及 `sql/initV2.sql` 中的 `CREATE TABLE` 是否都登记 owner。

## SQL 基线

`sql/initV2.sql` 是当前新库 SQL 基线入口。它覆盖注册中心、能力资产、Agent、GraphSpec、Trace、RunOps、Tool ACL、Guard、模型、知识库、业务索引、MCP、A2A、Gateway、市场资产和嵌入式对话等表。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

`sql/initV2.sql` 中每张 `CREATE TABLE` 表都必须在 service table ownership 矩阵中有唯一 owning service。代码访问是判断边界违规的事实源；V2 面向新库重建，不要求兼容旧表名或迁移旧数据。

未来 SQL 变化必须同时维护：

- `sql/initV2.sql`
- `sql/upgrade-YYYYMMDD-short-name.sql`
- `sql/README.md` 或相关说明

不再使用 `ai-agent-service/sql`、`ai-model-service/sql`、`ai-skills-service/sql` 作为活跃迁移目录。

## Agent、Workflow Studio 与 Runtime

Agent 与 Workflow 已解耦：

- Agent（`runtime_agent` / `AgentEntry`）：身份、入口策略、权限与 binding。
- Workflow（`runtime_workflow`）：`GraphSpec`、`canvas_json`、版本与发布。
- Workflow Studio：可视化画布、交互式节点、会话式调试台、AI 生成/局部修改、SDK 图展示、发布校验、Runtime 执行与 Trace/RunOps 复盘。
- Binding（`runtime_agent_workflow_binding`）：Runtime 通过 binding 解析 Agent -> Workflow -> 活跃版本。

`GraphSpec` 是平台可执行语义的核心中间表示，归属 Workflow 而非 Agent。新增节点、边、变量映射、条件路由或 Runtime 行为时，优先维护 Workflow `GraphSpec` 语义，不能只扩展画布 JSON。

## 命名规则

- 产品和文档默认使用 `Capability / 能力`。
- `Skill` 多为历史代码、legacy SQL 或内部旧命名，不要盲目全局替换。
- `reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不要再描述成“技能服务”。
- `eaf.*`、`X-EAF-*`、`Eaf*` 属于兼容敏感技术身份；品牌文案改成 ReachAI 时不要顺手替换这些标识。

## 前端事实

管理端是工作台型产品，优先信息密度、稳定布局和重复操作效率。项目范围选择器、注册中心、扫描项目、Workflow Studio 侧边栏折叠等行为与 `MainLayout.vue`、`ProjectSelector.vue`、router 和 project store 相关。改导航和布局前先检查这些共享位置。

当前前端大页已完成职责拆分：

- `WorkflowStudio.vue` 保留主画布编排入口；持久化、发布、调试、AI draft、history、API query template 等逻辑已拆入 `views/workflow/composables/`，节点配置面板主路径为 `views/workflow/studio-panels/`。
- `PageAssistantWizard.vue` 已收敛为步骤容器；步骤面板和 prompt 对话框拆入 `views/registry/components/page-assistant/`，wizard 状态、数据、步骤、draft 和 AI actions 拆入 registry composables。
- `ScanProjectDetail.vue` 已收敛为组装层；header、overview、modules/tools、drawers/dialogs 拆入 `views/scan/components/scan-project/`。
- `RegistryProjectDetail.vue`、`SdkAccessWizard.vue` 已收为 composable 组装层；继续改动前先复用 registry viewModel/composables。
- `ApiGraphCanvas.vue` 已拆出 graph geometry/viewModel、data actions、drag、horizontal dock 和 styles。

开发代理：

- `/api/**` -> `reachai-control-service:18603`
- `/ai/**` -> `reachai-knowledge-service:18602`
- `/model/**` -> `reachai-model-service:18601`

前端不应直接依赖 `reachai-runtime-service:18604` 或 `reachai-capability-service:18605`。
