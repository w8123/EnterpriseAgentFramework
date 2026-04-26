# Phase 2.x InteractiveFormSkill — 落地交付 & 验收清单

> 配合 `产品演进路线-Skill-AgentStudio-护栏.md` 与仓库内实现：确定性槽填充 + 挂起/恢复 + 前端 UI 原语协议。

---

## 一、交付概览

| 能力 | 说明 |
| --- | --- |
| `SkillKind.INTERACTIVE_FORM` | `ai-skill-sdk` 第四态枚举 |
| `skill_interaction` 表 | 挂起状态、TTL、`spec_snapshot` |
| `InteractiveFormSkill` + Executor | 预拉选项、槽抽取、表单/确认卡挂起、resume 提交 `targetTool` |
| `ChatRequest` / `ChatResponse` / `AgentResult` | `interactionId`、`uiSubmit`、`uiRequest` |
| `AgentService` / `AgentGatewayController` | resume 短路（不经主 ReAct） |
| `AiToolAgentAdapter` | 捕获 `InteractionSuspendedException` → `ToolExecutionContext.pendingUiRequest` |
| `AgentRouter` | `currentTurnMessage` 注入 + `buildResult` 回填 `uiRequest` |
| `InteractionExpirationJob` | 每 5 分钟将超时 `PENDING` 标为 `EXPIRED` |
| PoC Tool | `team_poc_queryDeptList` / `team_poc_queryUserList` / `team_poc_createTeam` |
| 种子 SQL | `sql/poc_team_interactive_seed.sql`（可选插入 `create_team_interactive`） |
| 管理端 | `AgentDebug` 渲染 `DynamicInteraction`；`SkillList` 支持 INTERACTIVE_FORM + JSON spec |

---

## 二、运维发布步骤

1. 执行 `ai-agent-service/sql/skill_interaction_phase2_x.sql`（建表）。
2. 重启 `ai-agent-service`（注册 PoC code Tool + 加载 DB Skill）。
3. （可选）执行 `sql/poc_team_interactive_seed.sql` 插入 PoC Skill。
4. 在「Agent 调试台」将目标 Agent 的 `tools` 白名单加入 `create_team_interactive`（及依赖的 PoC Tool 已由扫描/同步入库则无需手写）。
5. 管理端「Skill 管理」可新建 `skill_kind=INTERACTIVE_FORM`，`spec_json` 为 `InteractiveFormSpec` JSON。

---

## 三、验收用例（手工）

| # | 步骤 | 期望 |
| --- | --- | --- |
| 1 | `POST /api/agent/execute/detailed`，`message=帮我创建夜班A组班组，部门生产一部`，Agent 白名单含 `create_team_interactive` | 返回 `answer` 提示补充信息，且 `uiRequest.component=form`，含 `interactionId` |
| 2 | 同会话 `POST` 带 `interactionId` + `uiSubmit.action=submit` + `values` 填齐剩余字段 | 返回 `uiRequest.component=summary_card` |
| 3 | `uiSubmit.action=submit`（确认） | `answer` 含成功模板，`uiRequest` 为空，`tool_call_log` 有 `team_poc_createTeam` |
| 4 | `uiSubmit.action=cancel` | 交互状态 `CANCELLED`，回答含「取消」 |
| 5 | 等待 `expires_at` 之后 resume | 回答「已超时」 |
| 6 | `AgentDebug` Agent 执行模式 | 消息流出现表单卡片，提交后链路同上 |

---

## 四、已知限制（Backlog）

- `SkillController` 测试接口调用 InteractiveFormSkill 仍会挂起并抛异常路径依赖 Tool 上下文；建议在调试台走完整 Agent。
- 画布 / Agent Studio 尚未可视化编辑 `INTERACTIVE_FORM` spec。
- 拼音模糊匹配未单独实现，依赖 label 包含与 LLM 抽取兜底。
