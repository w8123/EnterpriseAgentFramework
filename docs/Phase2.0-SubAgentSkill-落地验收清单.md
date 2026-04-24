# Phase 2.0 SubAgentSkill — 落地交付 & 验收清单

> 本文档配合 `产品演进路线-Skill-AgentStudio-护栏.md` 使用。
> 作用有三：① 记录 Phase 2.0 实际交付了哪些东西；② 说明规划里暂未落地、推迟到 2.1/2.2/3/4 的项；③ 给运维/QA 一份可直接执行的发布 + 验收流程。
>
> 适用版本：本次 Phase 2.0 SubAgentSkill MVP 提交及其后续 hotfix。

---

## 一、本次交付概览

### 1.1 一句话总结

**把"多步业务流程"从 ReAct + 原子 Tool 的不稳定决策里摘出来，封成一个子 Agent 暴露给上层 Agent 调用。** 对 LLM 透明（就是一个粒度更粗的 Tool），对运营可管理（独立的 Skill 管理页），对框架可审计（复用 `tool_call_log` 和 `trace_id`）。

### 1.2 架构决策（与规划文档的差异说明）

产品演进路线文档 §1.3 / §1.4 曾经假设 Skill 会用独立的 `skill_definition` 表、`CapabilityRegistry` 和 `skill.` 前缀名字空间。在落地时做了以下实用化收敛，理由是"用最小改动把能力原子跑通"：

| 规划文档 | 实际落地 | 取舍理由 |
| --- | --- | --- |
| 独立 `skill_definition` 表 | **复用** `tool_definition` + `kind` 列（TOOL/SKILL） | 避免两套 CRUD / 两套向量池 / 两套查询路径；检索层天然混合召回 |
| `CapabilityRegistry` 新类 | **保留** `ToolRegistry` 名字，扩展 `isSkill(name)` / `remove(name)` | 外部调用方无需 rename，迁移成本 0 |
| `skill.<name>` 命名空间 | 直接用原名，`tool_call_log.tool_name` 记裸名 | Skill 名在 `tool_definition.name` 上已有唯一键；加前缀反而污染召回语料 |
| `AiSkill.dependsOnSkills()` | **仅保留** `dependsOnTools()` | 2.0 明确禁止 Skill 嵌套 Skill（见下），没必要建 API |

这些决策在 Phase 2.1 Skill Mining 上线后可以平滑演进；如果后续数据量级证明需要拆表，也只是一次物理迁移，不改契约。

---

## 二、已完成项（逐条对应代码/文件）

### 2.1 契约层 —— `ai-skill-sdk`

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| 新建 `AiSkill extends AiTool` | `ai-skill-sdk/.../AiSkill.java` | Skill 统一契约；对 LLM 视角完全等价于 Tool |
| 新建 `SkillKind` 枚举 | `ai-skill-sdk/.../SkillKind.java` | `SUB_AGENT` / `WORKFLOW` / `AUGMENTED_TOOL`（后两者 2.0 不实现但占位） |
| 新建 `SkillMetadata` record | `ai-skill-sdk/.../SkillMetadata.java` | `version / sideEffect / hitl / timeoutMs / retryLimit / tags` |
| 新建 `SideEffectLevel` / `HitlPolicy` 枚举 | `ai-skill-sdk/.../SideEffectLevel.java`、`HitlPolicy.java` | 给 Phase 4 护栏预留语义 |
| `ToolRegistry` 新增 `isSkill(name)` / `remove(name)` | `ai-skill-sdk/.../ToolRegistry.java` | 支持运行时摘除 Skill 和区分形态 |

### 2.2 持久化层 —— SQL & Entity

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| 新建幂等 SQL 迁移脚本 | `ai-agent-service/sql/skill_phase2_0.sql` | 给 `tool_definition` 添 4 列 + 1 组合索引 |
| `ToolDefinitionEntity` 扩字段 | `ai-agent-service/.../ToolDefinitionEntity.java` | `kind / specJson / sideEffect / skillKind` |
| `ToolDefinitionUpsertRequest` 扩字段 + `skill()` 工厂 | `ai-agent-service/.../ToolDefinitionUpsertRequest.java` | 兼容旧 15 参构造，新 19 参支持 Skill |

字段约定：

- `kind`：`TOOL`（默认）/ `SKILL`；启动 `syncCodeTools` 把存量代码 Tool 回填为 `TOOL`
- `spec_json`：`kind=SKILL` 时填，内容是 `SubAgentSpec` JSON
- `skill_kind`：`SUB_AGENT` / `WORKFLOW` / `AUGMENTED_TOOL`
- `side_effect`：`NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE`，默认 `WRITE`
- 新索引 `idx_kind_enabled_visible(kind, enabled, agent_visible)` 给 SkillController 列表加速

### 2.3 Skill 执行链路 —— `ai-agent-service/.../skill/`

| 改动 | 文件 | 核心逻辑 |
| --- | --- | --- |
| `SubAgentSpec` record | `.../skill/SubAgentSpec.java` | 子 Agent 的 systemPrompt / toolWhitelist / llm / maxSteps |
| `ToolExecutionContextHolder` | `.../skill/ToolExecutionContextHolder.java` | ThreadLocal 传递父 Agent 的 `ToolExecutionContext`，让子 Agent 继承 `trace_id` |
| `SubAgentSkill` | `.../skill/SubAgentSkill.java` | `AiSkill` 实现，`execute()` 委托给 Executor |
| `SubAgentSkillExecutor` | `.../skill/SubAgentSkillExecutor.java` | ① depth ThreadLocal 限制最多 3 层嵌套；② `composeChildMessage` 把父 Agent 传的结构化参数拼成子 Agent 能消化的 Msg；③ `buildChildContext` 继承 parent 的 `traceId / sessionId / userId`；④ 通过 `ObjectProvider<AgentFactory>` 懒加载破循环依赖 |
| `SubAgentSkillFactory` | `.../skill/SubAgentSkillFactory.java` | 把 `ToolDefinitionEntity` 反序列化为可执行的 `SubAgentSkill`；集中校验 `systemPrompt` 非空、`toolWhitelist` 非空、仅支持 `SUB_AGENT` |
| `AiToolAgentAdapter` 集成 `Holder` | `.../agentscope/adapter/AiToolAgentAdapter.java` | 在 `invoke` 调用前 set/after 调用后 restore，保证嵌套 Skill 也能拿到上下文 |

### 2.4 注册、检索与装配

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| `ToolDefinitionService` 按 `kind` 分发 | `.../tools/definition/ToolDefinitionService.java` | ① `syncCodeTools` 跳过 AiSkill、回填 `kind=TOOL/sideEffect=READ_ONLY`；② `registerIfNeeded` 见 `kind=SKILL` 走 `SubAgentSkillFactory.build`；③ `toggle(false)` / `delete` 时主动 `toolRegistry.remove`；④ `applyRequest` / `validateRequest` 分 TOOL / SKILL 两条路径；⑤ 暴露 `listSkills / pageSkills / isSkill` 给 Controller |
| `ToolEmbeddingService` schema + 自动迁移 | `.../tool/retrieval/ToolEmbeddingService.java` | ① 新字段 `kind VarChar(16)`；② `ensureCollection` 用 `describeCollection` 判断老 collection 缺 `kind` 则 drop，启动日志会明确提示再跑一次 rebuild；③ `insert` 写入 `normalizeKind(entity.getKind())` |
| `RetrievalScope` 支持 `kinds` | `.../tool/retrieval/RetrievalScope.java` | 新字段 + 向后兼容构造 + `onlyTools()` / `onlySkills()` 便捷方法 |
| `ToolRetrievalService.buildExpression` | `.../tool/retrieval/ToolRetrievalService.java` | 组出 `kind in ["TOOL"]` / `kind in ["SKILL"]` 表达式 |
| `AgentFactory.createToolkit` 按形态装配 | `.../agentscope/AgentFactory.java` | ① 打 `装配 TOOL` / `装配 SKILL` 日志；② 检测 `SubAgentSkillExecutor.currentDepth() > 0` → **拒绝**在子 Skill toolkit 中再挂 Skill（Skill 嵌套防护第二道闸） |

### 2.5 扫描期副作用推断

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| 新建 `SideEffectInferrer` | `.../scan/SideEffectInferrer.java` | 基于 HTTP method + path 关键词的保守默认值推断 |
| `ScanProjectToolService.promoteToGlobalTool` 注入 | `.../scan/ScanProjectToolService.java` | 扫描期"接口晋升为全局 Tool"时自动写 `side_effect` 初值，给 Phase 4 HITL 埋线索 |

规则摘要（详见 `SideEffectInferrer` Javadoc）：

```
DELETE / path 含 delete|drop|purge|remove|refund|cancel|void  → IRREVERSIBLE
GET / HEAD / path 以 query|search|list|get|fetch 开头         → READ_ONLY
PUT / path 含 upsert|idempotent                               → IDEMPOTENT_WRITE
POST / PATCH                                                  → WRITE
其它                                                          → WRITE（保守兜底）
```

### 2.6 后端 API

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| 新建 `SkillController` | `.../controller/SkillController.java` | `GET /api/skills`（分页）、`GET/POST/PUT/DELETE /api/skills/{name}`、`PUT /toggle`、`POST /test` |
| `ToolController` 隐藏 Skill | `.../controller/ToolController.java` | `GET /api/tools` 默认过滤 `kind=SKILL`；DTO 新增 `kind / sideEffect` 字段 |

### 2.7 前端（`ai-admin-front`）

| 改动 | 文件 | 作用 |
| --- | --- | --- |
| 新建参数通用组件 | `src/components/ParameterTable.vue` | 抽 ToolList 原有参数编辑 UI，Tool/Skill 共用 |
| 新建 Skill 类型 / API | `src/types/skill.ts`、`src/api/skill.ts` | SubAgentSpec / SkillInfo / CRUD API，`SIDE_EFFECT_OPTIONS` 常量 |
| 新建 Skill 管理页 | `src/views/skill/SkillList.vue` | 列表 + 新建/编辑弹窗（含 systemPrompt、工具白名单多选、maxSteps、LLM 覆盖、sideEffect）+ 测试对话框 |
| ToolList 切换到通用组件 | `src/views/tool/ToolList.vue` | 去除本地 `addParameter/removeParameter/parameterLocations`，换用 `<ParameterTable>` |
| 菜单 + 路由 | `src/views/layout/MainLayout.vue`、`src/router/index.ts` | Tool 管理子菜单下新增「Skill 管理」，路由 `/skill` |

### 2.8 单元测试（全部绿）

| 测试类 | 用例数 | 覆盖点 |
| --- | ---: | --- |
| `SideEffectInferrerTest` | 8 | GET/POST/DELETE/PUT 规则、refund 关键词、search 前缀 POST、空 method 兜底、`inferAsString` |
| `SubAgentSkillFactoryTest` | 7 | 非 SKILL 拒绝 / 缺 spec 拒绝 / systemPrompt 空拒绝 / toolWhitelist 空拒绝 / 不支持 `WORKFLOW` / 合法构造 / spec JSON 序列化往返 |
| `SubAgentSkillExecutorTest` | 6 | `composeChildMessage` 结构化参数 / 空参数兜底 / `buildChildContext` 继承 traceId / 生成新 traceId / `MAX_DEPTH` 超限抛错 / `currentDepth` 读值 |
| `ToolRetrievalServiceExpressionTest` | 7 | null scope / 启用可见标记 / whitelist+project+module 组合 / `kind in` 过滤 / `onlyTools()` / 空 kinds 忽略 / 向后兼容构造 |

合计 **28 用例，Failures: 0，Errors: 0**。执行命令：

```bash
mvn -pl ai-agent-service test
```

### 2.9 交付文档

- `docs/产品演进路线-Skill-AgentStudio-护栏.md`（规划文档，已有）
- **`docs/Phase2.0-SubAgentSkill-落地验收清单.md`（本文，覆盖上一版）**

---

## 三、未完成项（Phase 2.0 范围内 / 或被拆到后续阶段）

> 说明：以下列表区分"Phase 2.0 本来就不做（已规划在后续阶段）"和"Phase 2.0 内可以但这次没做"两类，方便后续迭代时对账。

### 3.1 Phase 2.0 本次主动省略（等后续阶段接手）

| 项 | 拆到哪 | 现状 / 下一步 |
| --- | --- | --- |
| WorkflowSkill（固定流程 DSL） | Phase 2.2 | `SkillKind.WORKFLOW` 已占位；Factory/Executor 未实现；前端 SkillList 只放 `SUB_AGENT` 一个可选项 |
| AugmentedTool（单 Tool 装饰器） | Phase 2.2 | 同上，枚举占位 |
| Skill Mining（从调用链挖 SubAgent 草稿） | Phase 2.1 | `tool_call_log.retrieval_trace_json` 已有；挖掘/LLM 草稿流水线未写 |
| Agent Studio 画布 / 发布端点 | Phase 3.x | 依赖本次的 `tool_definition(kind=SKILL)` + `SubAgentSpec` 存量；空白 |
| `sideEffect` 作为执行前闸口（HITL） | Phase 4.1 | 本次只是"存下来 + 展示"；扫描期推断已做，执行前校验未做 |
| `tool_approval` 审批表 | Phase 4.1 | 未建表；HITL 执行流未实现 |
| 权限模型 `tool_acl`（role ↔ tool/skill） | Phase 4.2 | 未实现；当前所有启用 Skill 对所有用户可见 |
| 限流（Redis 令牌桶） | Phase 4.2 | 未实现 |
| `AiSkill.dependsOnSkills()` | 2.2+（支持嵌套时） | 2.0 **明确禁止** Skill 嵌套 Skill（运行时有 depth 3 + AgentFactory 双闸），因此不开这个 API |
| `SkillMetadata.timeoutMs / retryLimit / tags` **运行时生效** | 2.2（和 Workflow 一起做） | 字段已存，但 Executor 目前没有 timeout / retry 包装 |
| `tool_name` 前缀 `skill.` | 放弃（改计划） | 决策：裸名 + `kind` 字段足够区分，前缀反而污染向量召回文本 |

### 3.2 Phase 2.0 内本可加但这次没做（技术债，留给下一轮优化）

| 项 | 说明 | 风险级 |
| --- | --- | --- |
| SkillController 缺少 OpenAPI 注解 | `/api/skills` 新接口还没写 springdoc 注解，Swagger UI 上是裸的 | 低，内部 API |
| Skill "克隆 / 导出 JSON" 前端按钮 | 目前只能手工一项项抄 spec_json，不利于跨环境搬运 | 中，运营期可能被问 |
| `AgentFactory.createToolkit` 嵌套拦截的单元测试 | 有运行时防护但没独立 UT（目前依赖 `SubAgentSkillExecutorTest` 间接覆盖） | 低 |
| Skill 并发/超时测试 | 子 Agent 内网调用没做限流/超时压测；生产环境下 `useMultiAgentModel=true` 时的 token 消耗也没有 benchmark | 中，上生产前建议补 |
| Skill 级 `tool_call_log` 分层展示 | 当前 SkillCall + 子 Tool 调用都挂在同一个 `traceId` 下，前端 Agent 调试台暂无"按 parent skill 折叠"的 UI | 低，后端数据已齐 |
| Milvus collection schema 迁移脚本（生产级） | 目前是启动期自动 drop；生产更稳的做法是 `alter_collection add_field`，现阶段 Milvus 2.4 原生不支持，保留自动 drop + rebuild 作为妥协 | 中，文档已注明 |
| 已存量 Tool 批量回填 `side_effect` | `SideEffectInferrer` 只在扫描期 `promoteToGlobalTool` 触发；存量 `tool_definition` 仍保持默认 `WRITE`，需要一次一次性脚本批量回填 | 中，Phase 4.0 之前补上即可 |
| `SubAgentSpec.useMultiAgentModel` 前端开关 | 后端已支持，前端编辑器没暴露（默认 false） | 低 |

---

## 四、发布步骤（顺序不可调换）

1. **执行 SQL 迁移**（幂等，可重复跑）

    ```bash
    mysql -uxxx -pxxx ai_text_service < ai-agent-service/sql/skill_phase2_0.sql
    ```

    给 `tool_definition` 增加 4 列 + 1 个组合索引：`kind / spec_json / side_effect / skill_kind` + `idx_kind_enabled_visible`。

2. **重启 `ai-agent-service`**
    - 启动期 `ToolEmbeddingService.ensureCollection()` 会检测旧 Milvus collection 是否缺 `kind` 字段，如缺则自动 drop 并按新 schema 重建。
    - 启动日志会明确提示：`[ToolEmbedding] 旧 collection xxx 缺失 kind 字段，drop 重建；请调用 /api/tools/retrieval/rebuild 重灌数据`。
    - **启动完成后必须调用一次** `POST /api/tools/retrieval/rebuild`，否则 Tool Retrieval 会一直空返回。

3. **刷新 `ai-admin-front`**
    - 重新执行 `npm run build` 并发布 dist，浏览器硬刷新。
    - 左侧 Tool 管理子菜单下会新增「Skill 管理」入口。

---

## 五、手工验收清单（6 项）

| # | 场景 | 操作 | 预期结果 |
| - | ---- | ---- | -------- |
| 1 | Skill 增改查 | 进入 Skill 管理页，点击"新建 Skill"，填写 systemPrompt + 至少 1 个 toolWhitelist，保存；再编辑；最后删除 | 列表出现新 Skill，能再次打开编辑；删除能成功 |
| 2 | Skill 参数必填校验 | 保存 Skill 时把 systemPrompt 或 toolWhitelist 清空 | 前端提示 / 后端返回 400，不允许保存 |
| 3 | Skill 启动期自动注册 | 重启 `ai-agent-service`；把这个 Skill 名加到某个 Agent 的 tools 列表里 | 启动日志 `[ToolDefinitionService] 注册 Skill: name=xxx, kind=SUB_AGENT`；Agent 调试台能把该 Skill 当普通 Tool 选中 |
| 4 | Skill 运行 & trace 继承 | Agent 调试台调用一次该 Skill，或在 Skill 管理页点"测试" | 子 Agent 跑完返回文本；查 `tool_call_log`，同一 `trace_id` 下出现父 agent 的 Skill 调用行 + 子 agent 的各 Tool 调用行 |
| 5 | 递归防护 | 构造 A Skill 的 toolWhitelist 写了 B Skill 的名字，再通过父 Agent 调用 A | Agent 装配阶段日志：`子 Skill 内部不允许嵌套 Skill，跳过: skill=B`；调用不会递归失控；深度超过 3 层时抛 `IllegalStateException` |
| 6 | Tool 检索召回 Skill | Agent 未显式声明 tools 白名单，问一个与某 Skill 语义强匹配的问题 | Tool 召回 top-K 中能看到该 Skill（`kind=SKILL`）；Agent 选中后能直接调用 |

---

## 六、关键观测点

### 6.1 日志关键字

- `[ToolDefinitionService] 注册 Skill: name=xxx, kind=SUB_AGENT` — 启动期注册成功
- `[SubAgentSkill] 触发子 Agent: skill=xxx, depth=1, args.size=N, traceId=xxx` — 子 Agent 被调用
- `[AgentFactory] 装配 SKILL: xxx` / `装配 TOOL: xxx` — 上游能区分能力形态
- `[AgentFactory] 子 Skill 内部不允许嵌套 Skill，跳过: skill=xxx` — 嵌套拦截
- `[ToolEmbedding] 旧 collection xxx 缺失 kind 字段，drop 重建` — schema 迁移触发
- `SubAgent 嵌套层级超过 3` — 递归硬上限触发（抛异常）

### 6.2 数据库查询

```sql
-- 确认字段已落地
DESCRIBE tool_definition;

-- 查看所有 Skill
SELECT id, name, kind, skill_kind, side_effect, enabled, agent_visible
FROM tool_definition WHERE kind='SKILL';

-- 统计最近一天 Skill 调用命中
SELECT tool_name, COUNT(*) AS cnt, AVG(cost_ms) AS avg_ms
FROM tool_call_log
WHERE created_time >= NOW() - INTERVAL 1 DAY
  AND tool_name IN (SELECT name FROM tool_definition WHERE kind='SKILL')
GROUP BY tool_name ORDER BY cnt DESC;

-- 追踪一次嵌套调用（父 Skill + 子 Agent 的 Tool）
SELECT trace_id, agent_name, tool_name, success, cost_ms, created_time
FROM tool_call_log WHERE trace_id = 'xxxxx' ORDER BY id;
```

### 6.3 Milvus

新 schema 字段列表（可用 Attu / `describeCollection` 校验）：

```
tool_id (Int64, PK)   project_id (Int64)   module_id (Int64)
enabled (Bool)        agent_visible (Bool) kind (VarChar 16)   ← Phase 2.0 新增
text (VarChar 4096)   embedding (FloatVector, dim=properties.embeddingDim)
```

---

## 七、回滚策略

| 故障点 | 处置 |
| --- | --- |
| **某 Skill 运行出错** | 在 Skill 管理页把它 `enabled=false`；`ToolRegistry.remove` 会把它从运行时摘除，下次 Agent 装配 toolkit 时直接忽略；不需要重启服务 |
| **某 Skill 配置坏了（spec_json 语法错）** | 启动期日志会打 `Skill 注册失败（跳过，不阻塞启动）: name=xxx, err=...`；修好 `spec_json` 后热更新即可，不影响其他 Skill |
| **Milvus 迁移异常** | 删掉整个 collection，再调 `POST /api/tools/retrieval/rebuild` 重灌 |
| **SQL 迁移想整体回滚** | 脚本本身幂等；回滚 SQL：`ALTER TABLE tool_definition DROP COLUMN kind, DROP COLUMN spec_json, DROP COLUMN skill_kind, DROP COLUMN side_effect, DROP INDEX idx_kind_enabled_visible;` 并重启服务；所有存量 Tool 仍可用（代码里 `kind` 为 null 视作 TOOL） |
| **Phase 2.0 代码整体回滚** | 回滚到上一版 `ai-agent-service` 镜像 + `ai-admin-front` dist 即可；`tool_definition` 新列留空不影响旧代码（旧代码读不到但也不会校验） |

---

## 八、下一步：Phase 2.0 之后的对接

1. **Phase 2.1 Skill Mining**：基于 `tool_call_log.retrieval_trace_json` + `trace_id` 聚合的调用链，跑频繁子序列挖掘，LLM 草拟 `SubAgentSpec`，管理端评审一键上架。数据接口已备齐，差一个挖掘流水线。
2. **Phase 2.2 Workflow + AugmentedTool**：复用本次的 `kind` 列和 Factory 模式，新增两个 `SkillKind` + 两个 Executor；可与 Phase 4.1 HITL 一起合并一个里程碑。
3. **Phase 3.x Agent Studio**：直接基于本次的 `tool_definition(kind)` + `SubAgentSpec` 做画布节点库 + 发布端点；本次保留了 `source=manual` + `agent_visible` 等运营语义，Studio 那边不用再定义一层。
4. **Phase 4.x 护栏**：
    - 4.0：做一次性脚本给存量 `tool_definition` 批量回填 `side_effect`（扫描器只覆盖了晋升期）；
    - 4.1：在 `AiToolAgentAdapter.invoke` 或 `ToolDefinitionService.executeTool` 前加 `hitl_policy` 闸口；复用本次的 `ToolExecutionContext.traceId` 作为审批单主键；
    - 4.2：在 `AgentFactory.createToolkit` 按 `role ↔ (tool/skill)` 求交集，把不可见能力直接从 toolkit 中剔除（不进 LLM 视野）。

---

## 九、附：核心文件清单

后端：

```
ai-skill-sdk/src/main/java/com/enterprise/ai/skill/
    AiSkill.java              (new)
    SkillKind.java            (new)
    SkillMetadata.java        (new)
    SideEffectLevel.java      (new)
    HitlPolicy.java           (new)
    ToolRegistry.java         (modified)

ai-agent-service/sql/
    skill_phase2_0.sql        (new)

ai-agent-service/src/main/java/com/enterprise/ai/agent/
    skill/SubAgentSpec.java              (new)
    skill/SubAgentSkill.java             (new)
    skill/SubAgentSkillExecutor.java     (new)
    skill/SubAgentSkillFactory.java      (new)
    skill/ToolExecutionContextHolder.java(new)
    scan/SideEffectInferrer.java         (new)
    scan/ScanProjectToolService.java     (modified)
    tool/retrieval/RetrievalScope.java   (modified)
    tool/retrieval/ToolEmbeddingService.java (modified)
    tool/retrieval/ToolRetrievalService.java (modified)
    tools/definition/ToolDefinitionEntity.java        (modified)
    tools/definition/ToolDefinitionService.java       (modified)
    tools/definition/ToolDefinitionUpsertRequest.java (modified)
    agentscope/AgentFactory.java         (modified)
    agentscope/adapter/AiToolAgentAdapter.java (modified)
    controller/ToolController.java       (modified)
    controller/SkillController.java      (new)

ai-agent-service/src/test/java/com/enterprise/ai/agent/
    scan/SideEffectInferrerTest.java                      (new)
    skill/SubAgentSkillFactoryTest.java                   (new)
    skill/SubAgentSkillExecutorTest.java                  (new)
    tool/retrieval/ToolRetrievalServiceExpressionTest.java (new)
```

前端：

```
ai-admin-front/src/
    components/ParameterTable.vue      (new)
    types/skill.ts                     (new)
    api/skill.ts                       (new)
    views/skill/SkillList.vue          (new)
    views/tool/ToolList.vue            (modified — 改用 ParameterTable)
    views/layout/MainLayout.vue        (modified — 加 Skill 菜单)
    router/index.ts                    (modified — 加 /skill 路由)
```

文档：

```
docs/
    产品演进路线-Skill-AgentStudio-护栏.md   (已有，不改)
    Phase2.0-SubAgentSkill-落地验收清单.md    (本文)
```
