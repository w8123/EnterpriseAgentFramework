# Phase 3.0 Agent Studio v0 — 落地验收清单

> 本清单与 Phase 2.0 验收清单对齐，专注"可视化 Agent 编排 + 版本治理 + 发布端点 + 最小护栏"四件事。
> 任何一条未完成都不得对外宣称 "v0 已交付"。

---

## 0. 目标回顾（与产品路线图 §3.1 对齐）

- 让**非 Java 开发角色**（运营、解决方案、业务 SE）能完成：
  - 画布化拖拽编排 Agent（System Prompt + Tools + Skills + Knowledge）。
  - 在同一页面调试 Agent（发布端点 + Trace 时间线）。
  - 发布带版本号 / 灰度百分比的线上版本，一键回滚。
  - 从历史 trace 抽取潜在 Skill，沉淀到 Skill 挖掘审批流。
- 建立最基础的"副作用护栏"：`IRREVERSIBLE` tool 必须显式白名单。

---

## 1. 后端验收（M1）

### 1.1 数据库
- [x] 新建 `agent_definition` 表（含 `key_slug` / `canvas_json` / `allow_irreversible`）。
- [x] 新建 `agent_version` 表（`snapshot_json` / `rollout_percent` / `status`）。
- [x] `sql/agent_studio_phase3_0.sql` 幂等脚本（`add_col_if_absent` / `add_idx_if_absent`）。
- [x] `sql/init.sql` 补齐新表与注释，冷启动 / 升级一致。

### 1.2 领域模型
- [x] `AgentDefinition` 领域对象新增 `keySlug` / `canvasJson` / `allowIrreversible`。
- [x] `AgentDefinitionEntity` + `AgentDefinitionMapper` 建立 DB 映射。
- [x] `AgentVersionEntity` + `AgentVersionMapper` 建立版本映射。

### 1.3 服务层
- [x] `AgentDefinitionService` 重写为 DB 持久化；首次启动自动从旧 JSON 文件迁移。
- [x] `AgentDefinitionService` 新增 `findByKeySlug`，供网关端点使用。
- [x] `AgentVersionService` 实现：`publish` / `rollback` / `listVersions` / `resolveActiveSnapshot`。
- [x] `resolveActiveSnapshot` 支持灰度：按 `userId` hash 落进 rollout 百分比。
- [x] 快照反序列化容错：`FAIL_ON_UNKNOWN_PROPERTIES=false`，允许 schema 演进。
- [x] 单元测试：`AgentVersionServiceTest` 覆盖发布 / 回滚 / 重复版本 / 非法灰度 / hash 落桶。

### 1.4 运行时闸口
- [x] `ToolExecutionContext` 增加 `allowIrreversible`，默认 `false`。
- [x] `AgentRouter.route` / `executeByDefinition` 将 `allowIrreversible` 注入上下文。
- [x] `AiToolAgentAdapter.checkSideEffectGate`：当 `sideEffect=IRREVERSIBLE` 且上下文不允许时抛错。
- [x] `AgentFactory` 注入 `sideEffect`（从 `ToolDefinitionEntity`），把闸口闭合。

### 1.5 对外接口
- [x] `AgentGatewayController` `POST /api/v1/agents/{key}/chat` 通过 `key_slug` 对接业务。
- [x] `AgentVersionController`：
  - `GET /api/agents/{agentId}/versions`
  - `POST /api/agents/{agentId}/versions`
  - `POST /api/agents/{agentId}/versions/{versionId}/rollback`
- [x] `SkillMiningController.POST /api/skill-mining/drafts/from-trace`：Trace → Skill 抽取。

---

## 2. 前端验收（M2 + M3）

### 2.1 画布编排
- [x] 引入 `@vue-flow/core` + `background` / `controls` / `minimap`。
- [x] `AgentStudio.vue` 三栏：节点调色板 / 画布 / 属性面板。
- [x] 支持 `start` / `end` / `skill` / `tool` / `knowledge` 五类节点。
- [x] 拖拽调色板 → 画布投放（`screenToFlowCoordinate`）。
- [x] 选中节点后属性面板可编辑：label / ref / groupId / description。
- [x] `Delete` 键 / 按钮删除节点（start/end 禁用删除）。
- [x] 未选中时属性面板回落到 Agent 元数据（name / keySlug / prompt / allowIrreversible）。
- [x] 画布 JSON 与 `AgentDefinition` 互转：`utils/studio.ts`（`canvasToDefinition` / `definitionToCanvas`）。

### 2.2 与传统表单视图共存
- [x] `AgentEdit.vue` 兼容新字段（`keySlug` / `allowIrreversible`）。
- [x] `AgentList.vue` 行内按钮增加 "Studio" / "版本"。
- [x] `router/index.ts` 新增 `agent/:id/studio` 与 `agent/:id/versions` 路由。
- [x] Studio 头部 "表单视图" 二次确认切换，避免误丢画布。

### 2.3 调试抽屉（M3）
- [x] Studio 头部 "调试" 打开抽屉。
- [x] 通过 `gatewayChat(keySlug)` 调用发布端点（模拟线上链路）。
- [x] 展示 `answer` / `toolCalls` / `metadata`。
- [x] 拿到 `metadata.traceId` 后拉取 `/api/trace/{id}` 渲染 `TraceTimeline`。
- [x] Trace 工具条：多选 tool 名 → "抽取为 Skill 草稿"（`POST /drafts/from-trace`）。

### 2.4 发布 / 回滚（M3）
- [x] Studio 头部 "发布 / 灰度" 对话框：版本号 / 百分比 / note / publishedBy。
- [x] 发布前自动 `handleSave` 持久化画布。
- [x] `AgentVersions.vue` 版本列表：状态 / 灰度 / 发布人 / 查看快照 / 回滚。
- [x] 回滚调用 `POST /versions/{id}/rollback`，成功后自动刷新。

### 2.5 类型与 API
- [x] `types/agent.ts` 增加 `keySlug` / `canvasJson` / `allowIrreversible` / `AgentVersion` / `PublishVersionRequest`。
- [x] `api/agent.ts` 新增 `listAgentVersions` / `publishAgentVersion` / `rollbackAgentVersion` / `gatewayChat`。
- [x] `types/studio.ts` 定义 `CanvasNodeKind` / `CanvasNode` / `CanvasEdge` / `CanvasSnapshot`。
- [x] `api/skillMining.ts` 增加 `extractDraftFromTrace`。

---

## 3. 联调验收（端到端用例）

### U1：新 Agent → 画布 → 发布 → 调用
1. 表单视图新建 Agent（填写 `keySlug = demo_finance`）。
2. 进入 Studio：画布拖入 `tool: search_kb` + `skill: contract_summarize`。
3. 保存草稿 → 发布 `v1.0.0`（灰度 100%）。
4. 调试抽屉消息 "帮我查最近 3 个合同"；预期：
   - `gatewayChat(demo_finance)` 正常返回。
   - 右上 `toolCalls` 展示 search_kb → contract_summarize。
   - `TraceTimeline` 展示命中节点与耗时。

### U2：灰度发布 + 回滚
1. Agent 发布 `v1.0.0` 100%，再发布 `v1.1.0` 20%。
2. 用两个不同 `userId` 多次请求，观察是否按 hash 稳定落进 v1.0.0 / v1.1.0。
3. 触发 `v1.0.0` 回滚；`v1.1.0` 自动被置为 `RETIRED`，线上 100% 指向 v1.0.0。

### U3：IRREVERSIBLE 护栏
1. 新建 tool `delete_contract`（`sideEffect=IRREVERSIBLE`）。
2. Agent A：`allowIrreversible=false`，挂该 tool → 调用 → 预期直接报错并记 trace。
3. Agent B：`allowIrreversible=true`，调用 → 正常执行。

### U4：Trace → Skill
1. 执行一次跨 3+ tool 的调试，取 `traceId`。
2. 在调试抽屉选择其中 2–3 个 tool → "抽取为 Skill 草稿"。
3. 跳到 Skill 草稿审批页（Phase 2.1），确认草稿已落库（status=DRAFT，source=trace）。

---

## 4. 文档 & 指标

- [x] 本清单 `docs/Phase3.0-AgentStudio-落地验收清单.md`。
- [x] 路线图 §3.1 状态切换为 "进行中"，里程碑 M1/M2/M3 标完成。
- [x] 路线图 §3.2 新增中期 Backlog（P0/P1/P2/P3）。
- [ ] **待补**：Studio 操作录屏（30s × 3 用例）放入运营培训包。
- [ ] **待补**：Skill-评估指标口径 新增 "trace-origin drafts" 维度。

## 5. 已知不足（滚入 Backlog）

| 项 | 现状 | Backlog ID |
|---|---|---|
| 画布只支持节点基础属性，没有条件边 / 并行 / 循环 | 以 list/DAG 简化展示 | P1-canvas-control-flow |
| ~~Skill / Tool 可用性与权限 ACL 尚未校验~~ ✅ **2026-04-24 Phase 3.1 已交付**：`tool_acl` 表 + `ToolAclService` + `AgentFactory.createToolkit` 装配过滤 + `ChatRequest.roles` 全链路 + 管理端 CRUD / 批量 / 诊断页面 + 11 个单测 | 详见 `docs/Phase3.1-ToolACL-落地验收清单.md` | ~~P0-tool-acl~~ ✅ |
| 没有 HITL（Human-in-the-Loop）审批阻断 | 仅日志提醒 | P1-hitl |
| 灰度策略只有 user-hash，缺 A/B & 租户维度 | 当前足以替代停机发布 | P2-rollout-strategy |
| TraceTimeline 尚无时间轴缩放 / 跨 trace 对比 | 足以定位单次 bug | P2-trace-advanced |
| Agent 的 prompt 无版本 diff 视图 | 通过 `snapshot_json` 手工比对 | P1-prompt-diff |

---
*文档版本：v0.1 （Phase 3.0 开工时同步创建）*
