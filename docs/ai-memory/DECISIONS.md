# ReachAI Durable Decisions

## Product Story

ReachAI 的稳定定位是“面向 Java 企业系统的 AI 能力中台”。不要退回成“工作流编辑器”或“扫描历史项目生成 Tool”的窄叙事。

## Registration First

新系统和核心系统优先通过 `reachai-spring-boot2-starter` 与 `reachai-capability-sdk` 主动注册。平台扫描 Controller/OpenAPI 是存量和低改造场景的补充路径。

## Capability Naming

产品、文档和 UI 默认使用 `Capability / 能力`。

`Skill` 在当前代码中仍大量存在，但它更接近历史命名或内部能力包概念，不等同于外部 Agent 生态里的标准 Skill 包。命名迁移必须看真实合同和兼容边界，不能盲目替换。

## Agent And Workflow Split

Agent 入口与 Workflow 编排已拆分：

- **Agent** 唯一主表是 `ai_agent`（代码侧 `AgentEntry` / `AgentEntryEntity`）。`agent_definition` 与 `/api/agent/definitions` 已退役，不要在新功能中继续写入或依赖。
- **Workflow** 主表是 `ai_workflow`；`GraphSpec`、`canvas_json`、版本发布均在 Workflow 上维护。
- **Workflow Studio** 是唯一画布编辑器；Agent Studio 路由与页面仅保留兼容跳转。
- Runtime 执行合同：`AgentEntry` + `ai_agent_workflow_binding` + 已发布 Workflow `GraphSpec` → `AgentRuntimeAdapter`。
- 对外 API 主线：`/api/agents`、`/api/workflows`；binding 走 `/api/agents/{agentId}/workflow-bindings`。

## GraphSpec Is Runtime Semantics

`GraphSpec` 是可执行语义，归属 `ai_workflow`；`canvas_json` 是布局。任何新增节点、边、条件、变量映射、AI 生成/编辑或 Runtime 行为，都必须维护 Workflow 上的 `GraphSpec`。

## Preview Before Apply

AI 生成和 AI 修改工作流必须走预览/应用模式。模型输出不能直接覆盖当前 Workflow Studio 画布。

## Local Edit By Selection

Workflow Studio 的 AI 局部编辑默认围绕当前选中节点、边或多选范围工作。用户说“修改这里”“在这个节点后面加审批”时，选中上下文是输入合同的一部分。

## Runtime Adapter Boundary

`AgentRuntimeAdapter` 是统一执行契约。不同 Runtime 可以有自己的能力模型和配置面板，但 Workflow Studio、发布、RunOps 不应绑定到单一 Agent 框架。

## SQL Baseline

`sql/init.sql` 是唯一基线。未来 schema 变化同时维护 `init.sql` 和 `sql/upgrade-*.sql`。

## No Old Data Compatibility By Default

项目快速迭代时，不默认为旧数据做复杂兼容迁移。需要清理旧字段、重建表或丢弃旧数据时，在 SQL 注释和变更说明中明确即可。

## ReachAI Branding And SDK Technical Identity

ReachAI 是产品品牌，也是新 JDK8 接入 SDK 的技术身份。新业务系统接入使用 `reachai.*` 配置、`X-ReachAI-*` header、`Reach*` 类名和 `reachai-*` Maven artifact。历史 `eaf.*` 配置、`X-EAF-*` header、`Eaf*` 类名属于旧兼容边界；旧 `ai-spring-boot-starter` 已退役。

## Documentation Shape

`docs/` 是当前知识库，按能力组织。旧阶段文档和临时讨论稿不应重新扩散。新增文档要回答“当前真实系统是什么、代码在哪里、边界是什么”。

## Frontend Theme Direction

前端主题应继续基于共享 CSS 变量和 Element Plus 覆盖层演进。不要在新页面中引入散落的硬编码颜色。
