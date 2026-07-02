# ReachAI Decisions

本文只记录会影响后续代码判断的长期决策。当前代码、SQL、接口和启动配置永远优先于本文。

## 五服务物理拓扑

后端主路径已经进入物理服务拆分后的旧结构退场阶段：

- `reachai-control-service`: Platform Control / public API BFF，保持 `/api/**`、`/embed/**` 和 SDK 注册兼容入口。
- `reachai-runtime-service`: Runtime Host，承接 Agent、Workflow、GraphSpec、Trace、RunOps、调试和运行时内部 API。
- `reachai-capability-service`: Capability Catalog，承接 SDK 注册、能力快照、diff/review/apply、扫描目录和能力资产 API。
- `reachai-knowledge-service`: Knowledge / Retrieval，承接知识库、文件、chunk、RAG、业务索引、向量检索和历史扫描器实现。
- `reachai-model-service`: Model Gateway，承接模型实例中心、Chat、Embedding、Rerank 和 OpenAI 兼容代理。

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
- Agent 入口通过 binding 解析到 Workflow 活跃版本。
- Runtime 执行主线在 `reachai-runtime-service`，LangGraph4j、AgentScope 和未来运行时通过 `AgentRuntimeAdapter` 解耦。

新增 Workflow Studio 节点、AI 编辑能力或 Runtime 行为时，必须把可执行语义写入 Workflow `GraphSpec`。

## Capability 与 Skill 命名

产品和文档默认使用 `Capability / 能力`。

`Skill` 多为历史命名或兼容存储名。V2 新库基线已把历史 SQL 表 `skill_draft`、`skill_eval_snapshot`、`skill_interaction` 收敛为 `capability_draft`、`capability_eval_snapshot`、`runtime_skill_interaction`。`skill_name`、`skill_kind` 等字段如仍承载业务语义，不做无关改名。

`reachai-knowledge-service` 是 Knowledge / Retrieval 部署单元，不再称为“技能服务”。

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
