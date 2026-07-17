# ReachAI Decisions

本文只记录会影响后续代码判断的长期决策。当前代码、SQL、接口和启动配置永远优先于本文。

## 五服务物理拓扑

后端主路径已经进入物理服务拆分后的旧结构退场阶段：

- `reachai-control-service`: Platform Control / public API BFF，保持 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service`: Runtime Host，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service`: Capability Catalog，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service`: Knowledge / Retrieval，承接知识库、文件、chunk、RAG、业务索引、向量检索和历史扫描器实现。
- `reachai-model-service`: Model Gateway，承接模型中心 V2（`model_template` + `model_instance`）、Chat、Embedding、Rerank；不再保留未使用的 `/model/openai-proxy` 入口。

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已从仓库主路径删除，不再作为 Maven、IDEA、本地启动或部署单元存在。

## Public API 边界

`reachai-control-service` 是第一阶段唯一公共 API/BFF 入口：

- 前端 `/api/**` 指向 Control。
- 前端 `/ai/**` 指向 Knowledge。
- 前端 `/model/**` 指向 Model。
- Runtime 和 Capability 内部端口不直接暴露给前端。
- 旧 agent generic fallback、legacy catch-all 和占位响应壳都不再作为当前策略。

剩余 public route 必须由 owning service 本地实现，或正式删除并同步前端/文档契约。

## Workflow 与 Runtime

`GraphSpec` 是运行语义，归属 Workflow；`canvas_json` 只是画布布局。

- DB 字段：`runtime_workflow.graph_spec_json` 和 `runtime_workflow.canvas_json`。
- Workflow Studio 负责 AI 生成、局部编辑、调试、发布校验和运行预览。
- Agent 是稳定聚合根；可执行设置进入版本化 `runtime_agent_config_version`，允许选择的 Workflow 进入同版本的 `runtime_agent_workflow_tool`。
- AgentScope Java `2.0.0` 正式版 Supervisor 负责理解、规划、选择一个或多个 Workflow 和有限重规划；单个 Workflow 仍按其已发布 `GraphSpec` 快照执行。
- “项目接入工作台”的 Agent provisioning 只创建/复用项目 Agent 并发布 ACTIVE Supervisor 配置，不创建占位 Workflow；“创建页面助手”先发布 PAGE_ASSISTANT Workflow，再把它加入 Supervisor Workflow-as-Tool 白名单并发布新版 Agent 配置。
- Agent 与 Workflow 的唯一执行关系是已发布 Agent 配置版本中的 `runtime_agent_workflow_tool` 目录。
- Agent 身份写入和 Supervisor 配置写入必须使用独立 API；`POST/PUT /api/agents` 不再隐式创建或修改配置草稿。
- 产品统一称为 Agent；Agent 是通用 Supervisor 聚合根，不再保留 `agent_kind` 接入形态字段。页面副驾驶按稳定 keySlug 创建或复用，Embed / Gateway / A2A 属于开放渠道而非 Agent 类型。
- 执行请求只接受 `agentId`，删除 `agentDefinitionId` 兼容别名。
- ACTIVE/ARCHIVED 配置不可变；历史版本只能复制为新 DRAFT 后再编辑和发布。
- 页面动作只响应用户明确的页面意图；跨路由动作保持同一 embed session，并严格执行 `NAVIGATE -> TARGET_READY -> PAGE_ACTION`。
- Supervisor 统一策略链固定为工具白名单/启停 -> project -> tenant -> Agent roles -> permissionKey roles -> risk；READ 自动、PAGE_ACTION 要求原始问题显式页面意图、WRITE 一次性精确确认、IRREVERSIBLE 默认拒绝。
- Agent Eval 必须调用 `RuntimeAgentExecutionService` 执行当前已发布配置，并断言工具、PLAN/REPLAN、策略、UI 请求、Trace 与回答；不允许再生成“runtime 未接入”的占位结果。
- Runtime 执行主线在 `reachai-runtime-service`，Supervisor 通过 `SupervisorRuntimeAdapter` 解耦，上层规划和底层 Workflow runtime 不混为一个概念。

新增 Workflow Studio 节点、AI 编辑能力或 Runtime 行为时，必须把可执行语义写入 Workflow `GraphSpec`。

## Capability 与 Skill 命名

产品和文档默认使用 `Capability / 能力`。

`Skill` 多为历史命名或兼容存储名。V2 新库基线已把历史 SQL 表 `skill_draft`、`skill_eval_snapshot`、`skill_interaction` 收敛为 `capability_draft`、`capability_eval_snapshot`、`runtime_skill_interaction`。`skill_name`、`skill_kind` 等字段如仍承载业务语义，不做无关改名。

`reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再称为“技能服务”。

## 模型中心 V2（第一阶段）

模型中心只保留两个领域对象：

- `model_template`：平台维护的只读目录/创建模板；不含真实 API Key；本阶段不提供模板写接口。
- `model_instance`：对 Agent / Workflow / Knowledge 暴露的稳定可执行资源；业务继续绑定稳定 `modelInstanceId`，实例内部配置可改以支持原地替换。

关键约束：

- 模板与实例之间不建外键，实例不保存 `template_id`；从模板创建只复制快照，模板后续变化不影响已有实例。
- 协议仅 `OPENAI_COMPATIBLE`；模型类型仅 `LLM` / `EMBEDDING` / `RERANKER`。
- `project_code` 为预留字段（NULL=全局），本期只存取，不做强制项目隔离。
- 实例启停状态（ACTIVE/DISABLED/ARCHIVED）与连通测试状态（UNKNOWN/SUCCESS/FAILED）是两套语义；删除等于归档，无物理删除。
- `connection_config_json` 必须 `aesgcm:` 加密；API 仅返回掩码敏感字段；不再兼容明文读取或 `apiKeyEnv` / `BUILT_IN`。
- `baseUrl` 为 API Root，path 为相对路径；Chat/Embedding/Rerank 受保护字段不可被 defaultOptions/options 覆盖；BaseURL authority 变更必须重输凭证。
- `project_scope_key` 由数据库生成列维护；管理端旧契约仍待第二阶段原子升级。

详见 `docs/architecture/model-center-v2.md`。

## SQL 决策

`sql/initV2.sql` 是当前新库 SQL 基线入口；旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。任何 schema、索引、种子数据、字段语义相关改动，都必须同时维护：

- `sql/initV2.sql`
- `sql/upgrade-YYYYMMDD-short-name.sql`
- `sql/README.md` 或相关说明

默认不为旧数据做复杂兼容迁移。V2 按新库重建处理，不提供旧表名兼容视图或旧数据迁移脚本；后续如需要面向存量库升级，再新增当次 upgrade SQL 并写清影响。

## 品牌与技术身份

ReachAI 是产品品牌，也是新 JDK8 接入 SDK 的技术身份。新业务系统接入使用 `reachai.*` 配置、`X-ReachAI-*` header、`Reach*` 类名和 `reachai-*` Maven artifact。

历史 `eaf.*` 配置、`X-EAF-*` header、`Eaf*` 类名属于兼容敏感边界，不要为了品牌统一顺手替换。

## 文档入口

- `README.md`: 对外入口。
- `docs/README.md`: 内部知识库入口。
- `docs/01-*` 到 `docs/05-*`: 根目录只保留当前产品主线事实源。
- `docs/architecture/`: 路由、internal API、表所有权、五服务边界和旧结构退场等架构契约。
- `docs/guides/`: 面向业务系统接入方的操作指南和样例。
- `docs/reference/`: 身份授权、嵌入式对话、AI Coding、Context Governance 等长篇参考。
- `AGENTS.md`: AI 编程工具最高优先级项目规则。
- `docs/ai-memory/`: AI 工具跨会话记忆。

文档应回答“当前真实系统是什么、代码在哪里、边界是什么”。历史计划、阶段稿和讨论稿不能覆盖当前事实。
