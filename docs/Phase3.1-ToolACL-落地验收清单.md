# Phase 3.1 Tool ACL — 落地验收清单

> 与 `docs/产品演进路线-Skill-AgentStudio-护栏.md` §3.2 Backlog 的 P0 `tool-acl` 条目对齐。
> 目标：Agent Studio 开放后，按"角色 × 能力"黑白名单阻断 Tool / Skill 调用，补齐进生产必需的权限控制护栏。

---

## 0. 目标回顾

- 建立角色 × 能力的三元组授权：`role_code × target_kind × target_name → ALLOW / DENY`。
- 在 Agent 装配阶段（`AgentFactory.createToolkit`）就把被拒能力摘掉，**LLM 根本看不到不该看的 Tool**。
- 支持 `SKIPPED` 兼容语义：`roles` 为空时不拦截、仅打 warn，方便各前端 / 网关渐进接入。
- 提供管理端 CRUD / 批量授权 / 决策诊断三板斧，让运营可以自助维护 ACL 规则。

---

## 1. 数据层

- [x] `tool_acl` 表：`(role_code, target_kind ∈ {TOOL,SKILL,ALL}, target_name, permission ∈ {ALLOW,DENY}, enabled, note, created_at, updated_at)`。
- [x] `UNIQUE (role_code, target_kind, target_name)` + 常用索引 `idx_role_enabled` / `idx_target`。
- [x] `ai-agent-service/sql/tool_acl_phase3_1.sql` 幂等脚本（`add_col_if_absent_acl` / `add_idx_if_absent_acl`）。
- [x] `sql/init.sql` 同步新增 §九 ACL 节（紧跟 `agent_version` 之后）。
- [x] 种子数据：`admin` ALL/* ALLOW（管理员万能角色）、`public` TOOL/* DENY（匿名默认禁 TOOL）。仅在表空时插入，避免覆盖运营配置。

## 2. 后端决策引擎

- [x] `ToolAclEntity` + `ToolAclMapper`（MyBatis-Plus）。
- [x] `ToolAclDecision` 四态枚举：`ALLOW / DENY_EXPLICIT / DENY_NO_MATCH / SKIPPED`。
- [x] `ToolAclService.decide(roles, toolName, isSkill)` 核心判定：
  - `roles` 为空 → `SKIPPED`（走兼容旧行为）；
  - 命中任意 DENY 直接短路返回 `DENY_EXPLICIT`；
  - 命中 ALLOW 记住，继续扫完整组 roles，避免漏判 DENY；
  - 没有任何命中 → `DENY_NO_MATCH`（保守拒绝）。
- [x] kind 语义：`ALL` = TOOL ∪ SKILL；target `*` 通配。
- [x] 5 分钟本地缓存（按 `role_code` 聚合），CRUD 后自动 `evictCache()`。
- [x] CRUD / 批量授权 / 诊断 API：`create / update / delete / toggle / grantBatch / explain / page / listRoles`。
- [x] 单元测试 `ToolAclServiceTest` 11 个用例（SKIPPED / DENY 优先 / 通配 / TOOL vs SKILL / 禁用规则 / 多角色组合 / 校验路径 / 批量诊断），**全部通过**。

## 3. 全链路透传

- [x] `ToolExecutionContext.roles`（`List<String>`）。
- [x] `ChatRequest.roles` 增加字段，允许网关 / 前端直接传；未传时后端走 `SKIPPED`。
- [x] `AgentRouter.route(..., roles)` / `AgentRouter.executeByDefinition(..., roles)` 两条入口都能注入 roles，保持旧签名向后兼容。
- [x] `AgentGatewayController` 把 `ChatRequest.getRoles()` 透传给 router。
- [x] `AgentOrchestrator.orchestrate(..., roles)` + `AgentService` 内部联动。
- [x] `AgentFactory.createToolkit` 在装配阶段按 `toolAclService.decide(...)` 过滤；被拒能力打 warn 并跳过注册。
- [x] `SubAgentSkillExecutor.buildChildContext` 继承父 ctx 的 `roles` 和 `allowIrreversible`，避免子 Skill 绕过。

## 4. REST 管理端

- [x] `ToolAclController`：
  - `GET /api/tool-acl` 分页查询（roleCode / targetKind 过滤）；
  - `GET /api/tool-acl/roles` 角色去重列表；
  - `POST / PUT / DELETE /api/tool-acl[/{id}]` CRUD；
  - `POST /api/tool-acl/{id}/toggle` 启停；
  - `POST /api/tool-acl/batch` 一次给一个 role 批量 ALLOW / DENY 多个 tool/skill；
  - `POST /api/tool-acl/explain` 诊断：给 `roles + targets` 返回每个 target 的决策。
- [x] Service 层校验：kind / permission 取值、必填字段、规范化大小写。

## 5. 前端管理页面

- [x] `src/types/toolAcl.ts` + `src/api/toolAcl.ts` 封装。
- [x] `src/views/settings/ToolAclList.vue`：
  - 左栏：角色列表（含全部入口、搜索、计数）。
  - 右栏：规则表（角色 / 类型 / 能力 / 决策 / 启用 / 备注 / 操作）+ 分页。
  - 新建 / 编辑弹窗：角色 + 类型 (TOOL/SKILL/ALL) + 能力 + 决策 + 启用 + 备注。
  - 批量授权弹窗：一次输入多行 tool / skill → 一把 ALLOW 或 DENY。
  - 决策诊断弹窗：输入 roles + `kind:name` 列表 → 展示每项决策（ALLOW / DENY_EXPLICIT / DENY_NO_MATCH / SKIPPED）。
- [x] 路由 `/settings/tool-acl` + `MainLayout.vue` 新增侧边菜单「设置 / 护栏 → Tool ACL」。
- [x] `vue-tsc --noEmit` 整体通过。

---

## 6. End-to-End 用例

### E1：admin 万能放行
1. 初始化后 `admin` 角色自带 `ALL / * / ALLOW`。
2. `ChatRequest.roles=["admin"]` 发起带任意 Tool 的 Agent 调用 → 全部放行、无 WARN。

### E2：ops 白名单 + DENY 覆盖
1. 管理端给 `ops` 新建：`TOOL / * / ALLOW`（通配放行所有 TOOL）。
2. 再新建：`TOOL / delete_order / DENY`（精确拒绝）。
3. `ChatRequest.roles=["ops"]`：
   - 装配 `list_order` → `ALLOW`；
   - 装配 `delete_order` → 在 AgentFactory 打 warn + 被跳过，LLM 看不到该工具。

### E3：未配置角色 → 默认拒绝
1. 用一个未录入 ACL 的角色 `guest` 发起调用。
2. 后端 `decide` 返回 `DENY_NO_MATCH`，全部 Tool 被跳过；LLM 视野空 → 回答自然降级（或提示"没可用 Tool"）。

### E4：兼容旧行为
1. `ChatRequest.roles=null`（或空数组）。
2. AgentFactory 每个能力都打 warn "roles 为空，跳过 ACL 校验（建议网关补上）"，但全部正常装配。
3. 方便已上线客户端逐步灰度接入。

### E5：SubAgentSkill 子链继承
1. 父 Agent `roles=["ops"]` 调用一个 SubAgentSkill。
2. Skill 内部再装配 tools 时同样走 ACL（`parent.roles` 已传入 `buildChildContext`），杜绝"父 Agent 受限但子 Skill 绕过"。

### E6：诊断工具
1. 管理端 → Tool ACL → 诊断。
2. 输入 `roles=["ops","customer"]`，targets 多行：`TOOL:delete_order / TOOL:list_order / SKILL:contract_review`。
3. 结果页展示每项 ALLOW / DENY_*，用于上线前 dry-run。

---

## 7. 已知限制（滚入后续 Backlog）

| 项 | 现状 | 后续去向 |
|---|---|---|
| 多实例部署时本地缓存需各实例自行 evict | CRUD 仅 evict 当前进程；5min 自然过期 | Backlog `acl-cache-sync`（切换成 Redis pub/sub 或 Nacos 通知） |
| 目前没有按租户维度的 ACL | role 已足够，多租户可把 `{tenant}:{role}` 编码在 role_code 里 | 与 `rollout-strategy`（Backlog P2）合流一并做 |
| `listRoles` 只扫描 `tool_acl` 里出现过的 role，不对接身份系统 | 允许无规则的 role 为前端拉侧栏 | 接入企业 IdP 后改成联查 |
| 管理端的"批量授权"只做 upsert，不会删除列表外已有规则 | 避免误删 | Phase 3.2 按 role 做"精确覆盖"模式 |
| ACL 校验仅作用在 `AgentFactory.createToolkit` 装配阶段，没有 runtime 二次校验 | 足够挡住 LLM 路径；运营手动调用依然受 REST 权限层覆盖 | Phase 4 `api-gateway-authz` 统一 |

---

*文档版本：v0.1（Phase 3.1 交付日同步创建 · 2026-04-24）*
