# 产品演进路线：Skill 层 / Agent Studio / Tool 护栏

> 本文承接 `AI能力系统升级规划.md` 第五章（Tool Retrieval / Skill Mining 演进路径），把之前对话中敲定的「未来主攻方向」沉淀为可执行的阶段规划，作为后续版本的升级蓝本。
>
> 版本约定：Phase 0 = 当前已交付；Phase 1 = Tool Retrieval + tool_call_log（已完成，见升级规划文档 5.x）；**Phase 2.0 SubAgentSkill MVP = 已交付（详见 `Phase2.0-SubAgentSkill-落地验收清单.md`）**；本文聚焦 Phase 2.1 起至 Phase 4。
>
> 进度看板（每次里程碑更新这张表，避免本文和实际实现脱节）：
>
> | 里程碑 | 状态 | 交付物 |
> | --- | :---: | --- |
> | Phase 1 Tool Retrieval + tool_call_log | ✅ 已交付 | `AI能力系统升级规划.md` §5 |
> | Phase 2.0 SubAgentSkill MVP | ✅ 已交付 | `Phase2.0-SubAgentSkill-落地验收清单.md` |
> | **Phase 2.0.1 可信度补强**（timeout/retry + sideEffect 回填 + 指标观测 + Trace 回放） | ✅ **本轮交付** | 本文 §1.6.2 |
> | **Phase 2.1 Skill Mining 骨架**（聚合 + 频繁序列挖掘 + 草稿评审 + 评估调度） | 🟡 **代码已交付，待真实 `tool_call_log` 数据验证** | 本文 §1.6.3 / §四 |
> | Phase 2.2 WorkflowSkill + AugmentedTool | ⚠️ **策略调整**：WorkflowSkill 合并入 Phase 3 Agent Studio，AugmentedTool 仍独立 | 本文 §1.6.4 |
> | **Phase 3.0 Agent Studio v0**（DB 化 `agent_definition` + 版本灰度 + 画布 + 调试抽屉 + Trace→Skill + IRREVERSIBLE 闸口） | ✅ 已交付（2026-04-24） | `Phase3.0-AgentStudio-落地验收清单.md`、本文 §二 |
> | **Phase 3.1 Tool ACL**（`tool_acl` 表 + `AgentFactory.createToolkit` 角色过滤 + 管理端 CRUD / 批量 / 诊断） | ✅ **本轮交付** | 本文 §3.2 P0 `tool-acl` 条目 |
> | Phase 4 Tool 护栏 | 🟡 `side_effect` 运行时 IRREVERSIBLE 闸口 + Tool ACL 已接通，HITL / 限流仍在 Backlog | 本文 §三 |
>
> **本轮（2026-04-24）变更摘要**（上一次迭代由 `phase_2.0.1_+_2.1_推进路径_cae36c82.plan.md` 驱动）：
>
> 1. **补齐 2.0 的四项技术债**：`SubAgentSkillExecutor` + `AiToolAgentAdapter` 真正消费 `timeoutMs / retryLimit` + 结构化 `SkillTimeoutException`；`SideEffectBackfillJob` 启动期一次性回填；`/api/skills/{name}/metrics` 给出 P50/P95 延迟 + Token + 成功率 + 趋势；`docs/Skill-评估指标口径.md` 固化 4 指标 SQL 口径。
> 2. **Trace 回放链路打通**：`GET /api/traces/{traceId}` + `GET /api/traces/recent` + 前端 `TraceTimeline.vue`（按 `agentName` 前缀 `skill:*` 折叠父子调用链）+ `AgentDebug.vue` 右侧抽屉 + `AgentList.vue` "最近 Trace" 页签。
> 3. **Skill Mining 模块整骨架**：`mining/` 目录新增 `ToolChainAggregator` / `PrefixSpanMiner`（distinct trace 支持度）/ `SkillDraftLlmWriter`（当前模板策略，已预留 LLM 入口）/ `SkillMiningService`（precheck + 幂等 generateDrafts + 幂等 publishDraft）/ `SkillEvaluationScheduler`（每日 02:00 自动跑评估 + 低阈值打 `ROLLBACK_CANDIDATE`）；新增 `skill_draft` / `skill_eval_snapshot` 两张表 + `tool_call_log` 三个索引。
> 4. **策略调整**（和之前讨论对齐）：
>    - **原 Phase 2.2 的 WorkflowSkill 不再独立立项**，直接并入 Phase 3 Agent Studio 的画布产物（避免写一遍 Java DSL、再写一遍画布 JSON）；AugmentedTool 保留在 2.2。
>    - **HITL 正式降级**（现阶段 Demo / POC 为主，审批台 ROI 低），`HitlPolicy` 字段保留、运行时暂不实现；改由 Agent Studio 发布到生产时再单独立项。
>
> **本轮增量（2026-04-24 · Phase 3.0 Agent Studio v0）**：
>
> 1. **`AgentDefinition` DB 化 + 版本灰度**：新增 `agent_definition` / `agent_version` 表与 SQL 幂等迁移脚本；`AgentDefinitionService` 首次启动自动吞并旧 JSON 文件；`AgentVersionService.publish / rollback / resolveActiveSnapshot`（按 `userId` hash 落桶的灰度路由）；反序列化 `FAIL_ON_UNKNOWN_PROPERTIES=false` 容忍后续字段演进。
> 2. **对外发布端点**：`POST /api/v1/agents/{key}/chat` 通过 `key_slug` 暴露，和内部 JSON-based `agentId` 接口并存；同请求走版本灰度解析 → `AgentRouter.executeByDefinition` 直接执行快照。
> 3. **可视化画布**：引入 `@vue-flow/core`，`AgentStudio.vue` 三栏（调色板 / 画布 / 属性面板），支持 `start / end / skill / tool / knowledge` 五类节点、拖拽投放、键盘删除；`utils/studio.ts` 承担画布 JSON ↔ `AgentDefinition` 互转；与原 `AgentEdit.vue` 表单视图并存，列表页新增 "Studio" / "版本" 入口。
> 4. **调试 + 发布 + 回滚闭环**：Studio 头部 "调试" 抽屉通过发布端点 `gatewayChat` 真实走线上链路 → 拉 Trace → 复用 `TraceTimeline.vue` 渲染；发布弹窗生成 `agent_version` 快照 + 灰度百分比；`AgentVersions.vue` 版本列表支持查看快照 JSON / 一键回滚。
> 5. **Trace → Skill 一键抽取**：调试抽屉在拿到 trace 后可多选 tool 子序列（框选雏形）调用 `POST /api/skill-mining/drafts/from-trace`，与 Phase 2.1 的 `skill_draft` 评审流合流；后端 `SkillMiningService.extractDraftFromTrace` 幂等 upsert。
> 6. **Phase 4 护栏最小闸口接上**：`ToolExecutionContext.allowIrreversible` 默认 `false`，`AiToolAgentAdapter.checkSideEffectGate` 在调用前拦截 `sideEffect=IRREVERSIBLE` 的 Tool；`AgentFactory` 注入每个 Tool 的 `sideEffect`，`AgentRouter` 把 `AgentDefinition.allowIrreversible` 注入上下文，`AgentEdit.vue` / `AgentStudio.vue` 属性面板都能勾选。
> 7. **新增文档**：`docs/Phase3.0-AgentStudio-落地验收清单.md`；本文 §3.1 追加 Phase 3.0 交付清单、§3.2 追加中期 Backlog（10 项）。
>
> **本轮增量（2026-04-24 · Phase 3.1 Tool ACL，紧跟 3.0 发布）**：
>
> 1. **数据表**：新增 `tool_acl`（`role_code × target_kind × target_name × permission`），`ai-agent-service/sql/tool_acl_phase3_1.sql` 幂等脚本 + `sql/init.sql` 同步；初始化种子 `admin` 放全部 / `public` 默认拒所有 TOOL。
> 2. **决策引擎**：`ToolAclService.decide(roles, toolName, isSkill)` 返回 `ALLOW / DENY_EXPLICIT / DENY_NO_MATCH / SKIPPED` 四态；**DENY 优先，无命中保守拒绝**；5 min 本地缓存按 `roleCode` 聚合，CRUD 后自动 evict。
> 3. **全链路透传**：`ChatRequest.roles` → `AgentRouter.route / executeByDefinition` → `ToolExecutionContext.roles` → `AgentFactory.createToolkit` 在装配阶段过滤被拒能力（LLM 视野里根本看不到）；`SubAgentSkillExecutor` 同步继承父 ctx 的 roles，避免 Skill 绕过。
> 4. **REST / 管理端**：`POST/GET/PUT/DELETE /api/tool-acl` + `POST /api/tool-acl/batch` 批量授权 + `POST /api/tool-acl/explain` 决策诊断；前端新增 `src/views/settings/ToolAclList.vue`（左侧 role 列表 + 右侧规则表 + 新建/编辑/启停/批量/诊断四弹窗）+ 路由 + 菜单条目「设置 / 护栏 → Tool ACL」。
> 5. **测试**：`ToolAclServiceTest` 共 11 个用例覆盖 SKIPPED / DENY 优先 / 通配 / kind 区分 / 禁用规则 / 多角色 / 校验路径；全部通过。
> 6. **灰度策略**：`roles` 为空时仍走兼容旧行为（SKIPPED，仅打 warn），方便各前端 / 网关渐进接入；§3.2 Backlog `tool-acl` 条目已标记为 ✅ 交付。

## 零、问题盘点（为什么还要继续做）

当前框架已经解决「把历史接口变成 Tool」这件事。往前看还有四个明确痛点，按「谁会先抱怨」排序：

| 代号 | 痛点 | 表象 | 解法方向 | 覆盖阶段 |
| --- | --- | --- | --- | --- |
| A | Agent 选不准 Tool | 工具多了以后 ReAct 挑错、把不相关接口塞进 prompt | Tool Retrieval（向量召回） | **Phase 1**（已落地） |
| B | 多步业务流程 Agent 跑不稳 | 「下单 → 核销 → 发通知」这类 3+ 步流程，LLM 偶发跳步、重试语义飘 | **Skill 层**（Workflow / SubAgent / AugmentedTool 三态） | **Phase 2** |
| C | 运营想自建场景 Agent | 每接一个业务都要工程同学写 `AgentDefinition` yaml、拉模型、配 Tool 白名单 | **Agent Studio**（可视化画布 + 发布端点） | **Phase 3** |
| D | 企业治理缺位 | 副作用型 API 没人审、权限靠信任、出问题不能追溯 | **Tool 护栏**（`sideEffect` 标注 / HITL / 审计 / 限流） | **Phase 4**（与 2/3 并行收口） |

一句话总结：**Tool 层管「有没有」，Skill 层管「稳不稳」，Agent Studio 管「能不能让非技术人员用」，Tool 护栏管「敢不敢上生产」。**

---

## 一、Phase 2：Skill 层（三态）

### 1.1 为什么不能只有 Tool

ReAct + 原子 Tool 的组合在「单步问题」上够用，但面对「多步带条件分支的业务流程」，LLM 每一步推理都有掉链子的可能。把稳定的业务流程抽成「技能」，让 LLM 面对更少、语义更粗的选择项，是 Agent 领域近两年的主流解法。

我们对 Skill 的定义：**一个对 LLM 可调用、对框架可注册、对运营可管理的"能力原子"**。对外暴露和 Tool 一样的 `AiTool` 契约，对内可以是三种形态：

### 1.2 三态设计

```
                ┌─────────────── Skill（对 LLM 是一个黑盒） ───────────────┐
                │                                                          │
user ──▶ Agent ─┼──▶ WorkflowSkill      固定编排，DAG / Sequential / Branch │
                ├──▶ SubAgentSkill       子 Agent（独立 systemPrompt + 工具）│
                └──▶ AugmentedTool       单 Tool + 前/后处理 / 校验 / 缓存 / HITL│
```

| 形态 | 本质 | 适用场景 | 复杂度 | 示例 |
| --- | --- | --- | --- | --- |
| **WorkflowSkill** | 确定性编排，代码或 DSL 描述步骤 | 业务分支固定、可描述为流程图的多步流程 | 低 | 「工单超时关单」「月结对账」 |
| **SubAgentSkill** | 独立 Agent，有自己的 prompt + tool 子集 | 需要自主推理但范围封闭的业务子域 | 中 | 「客户画像子 Agent」「单据审核子 Agent」 |
| **AugmentedTool** | 单 Tool 的装饰器 | 单一接口但需要参数清洗 / 结果整形 / 人工审核 | 低 | 「发通知（带 HITL 门）」「退款（金额校验）」 |

### 1.3 契约设计（草案 → Phase 2.0 已落地）

> Phase 2.0 落地位置：`ai-skill-sdk/src/main/java/com/enterprise/ai/skill/{AiSkill,SkillKind,SkillMetadata,SideEffectLevel,HitlPolicy}.java`。
>
> **与草案差异**：
> 1. 未开 `dependsOnSkills()` API —— 2.0 阶段明确禁止 Skill 嵌套 Skill（运行时有 `SubAgentSkillExecutor.MAX_DEPTH=3` + `AgentFactory.createToolkit` 子 toolkit 装配拦截双重闸门），等到 2.2 Workflow 真的需要嵌套时再放开；
> 2. `SkillMetadata.timeoutMs / retryLimit / tags` 字段已存下但 Executor 未消费（2.0 仅 "记录"，2.2 起生效）。

统一 `AiSkill extends AiTool`，对上层透明：

```java
public interface AiSkill extends AiTool {
    SkillKind kind();                  // WORKFLOW / SUB_AGENT / AUGMENTED_TOOL
    SkillMetadata metadata();          // 版本、维护者、SLA、sideEffect 级别、HITL 策略
    default List<String> dependsOnTools() { return List.of(); }  // 用到的底层 tool
    default List<String> dependsOnSkills() { return List.of(); } // 嵌套 skill
}

public record SkillMetadata(
    String version,
    SideEffectLevel sideEffect,        // NONE / READ_ONLY / IDEMPOTENT_WRITE / WRITE / IRREVERSIBLE
    HitlPolicy hitl,                   // NEVER / ON_WRITE / ALWAYS
    int timeoutMs,
    int retryLimit,
    Map<String, String> tags
) {}
```

存储侧（Phase 2.0 实际落地）：

- ~~`skill_definition` 独立表~~ → **复用 `tool_definition` + 新列 `kind`**（`TOOL` / `SKILL`），避免两套 CRUD、两套向量池、两套查询路径。
- `tool_definition` Phase 2.0 新增列：`kind / spec_json / skill_kind / side_effect` + 组合索引 `idx_kind_enabled_visible(kind, enabled, agent_visible)`。
- `spec_json` 按 `kind + skill_kind` 解析：
    - Phase 2.0 已实现：`skill_kind=SUB_AGENT` → `SubAgentSpec { systemPrompt, toolWhitelist, llmProvider, llmModel, maxSteps, useMultiAgentModel }`。
    - Phase 2.2 规划：`skill_kind=WORKFLOW` → DSL；`skill_kind=AUGMENTED_TOOL` → `{ toolName, preHook, postHook, policies }`。

迁移脚本：`ai-agent-service/sql/skill_phase2_0.sql`（幂等，反复跑不会出错）。

### 1.4 与现有架构的接入点（Phase 2.0 已打通 3 条，第 4 条改了方案）

| # | 原规划 | Phase 2.0 实际落地 |
| - | --- | --- |
| 1 | `ToolRegistry` → `CapabilityRegistry`，Skill 名字空间 `skill.<name>` | **保留 `ToolRegistry` 名字**，扩 `isSkill(name)` / `remove(name)`；Skill 裸名入库，`tool_definition.name` 上已有唯一键 |
| 2 | `tool_embeddings` 扩 `kind`，混合召回 | ✅ 已做。`ToolEmbeddingService` 加 `F_KIND VarChar(16)`，`RetrievalScope` 加 `kinds` 过滤，`ToolRetrievalService.buildExpression` 拼 `kind in ["SKILL"]`。启动期自动探测旧 collection 缺字段 → drop 重建，**必须手动**调一次 `POST /api/tools/retrieval/rebuild` 回灌 |
| 3 | `AgentFactory.createToolkit` 识别 Skill 走自己的 executor | ✅ 已做。Skill 对 Adapter 完全透明（都经 `AiToolAgentAdapter`），`SubAgentSkill.execute()` 内部委托给 `SubAgentSkillExecutor`；`createToolkit` 额外做两件事：① 日志打 `装配 SKILL/TOOL` 区分；② 当 `SubAgentSkillExecutor.currentDepth() > 0` 拒绝在子 Skill toolkit 中再挂 Skill |
| 4 | `tool_name` 前缀 `skill.` | **改计划**：Skill 写 `tool_call_log` 时用裸名 + 配合 `tool_definition.kind` 列区分。前缀会污染向量召回语料，且 SkillMining 侧需要频繁 strip 前缀，得不偿失 |

**trace 与审计一致性**（Phase 2.0 新加）：父 Agent 调 Skill 时，`SubAgentSkillExecutor` 通过 `ToolExecutionContextHolder`（ThreadLocal）把 `traceId / sessionId / userId` 传给子 Agent，子 Agent 的所有 Tool 调用都写同一个 `traceId`。这是 Phase 2.1 Skill Mining 能从日志里"回放整条业务流"的前提。

### 1.5 Skill 来源三路并行

Skill 不是只能写代码产出，规划三条来源通道：

| 来源 | 触发者 | 典型产物 | Phase |
| --- | --- | --- | --- |
| **代码编写** | 工程 | `WorkflowSkill` / `SubAgentSkill` Java 类 | 2.0 |
| **日志挖掘（重点）** | 系统 + LLM | 从 `tool_call_log` 的高频调用链自动生成 `SkillDraft` → 人工评审 → 上架 | 2.1 |
| **运营搭建** | 运营在 Agent Studio 画布里拖拽生成 Workflow DSL | `WorkflowSkill` JSON spec | 3.x（和 Phase 3 联动） |

### 1.6 实施顺序（SubAgent 先行：Phase 2.0 ✅ 已交付）

之前讨论过三态优先级，结论是 **SubAgent 先落地**，原因：

1. 代码改动最小：`SubAgentSkill` 本质是「封装一个子 `AgentDefinition`」，复用现有 `AgentFactory / ReActAgent` 基础设施；
2. 最能解决 Phase 2 的核心痛点：多步业务流程不稳 = 把 ReAct 的决策面收窄，SubAgent 天然就是「有限工具 + 有限目标」的子域 Agent；
3. 先验证 Skill 注册 / 检索 / 调用 / 日志整条链路跑通，再扩 Workflow 和 AugmentedTool 成本低。

分三步：

#### 2.0 SubAgent —— ✅ 已交付（对应本次变更）

> 详细交付物、验收清单、回滚方案见 `docs/Phase2.0-SubAgentSkill-落地验收清单.md`。下面只列关键清单用于快速对照。

**已完成**：

- [x] `ai-skill-sdk`：新增 `AiSkill / SkillKind / SkillMetadata / SideEffectLevel / HitlPolicy` 契约；`ToolRegistry` 扩 `isSkill / remove`
- [x] 数据层：`tool_definition` 加 `kind / spec_json / skill_kind / side_effect` + 组合索引；迁移脚本 `skill_phase2_0.sql`（幂等）
- [x] 执行层：`SubAgentSkill` + `SubAgentSkillExecutor`（递归深度 `MAX_DEPTH=3`、`ToolExecutionContextHolder` 传 trace、结构化参数注入到子 Agent 首条消息）
- [x] 工厂层：`SubAgentSkillFactory`（解析 `spec_json` + 校验 `toolWhitelist` 非空、`maxSteps ∈ [1,20]`）
- [x] 检索层：`ToolEmbeddingService` 加 `F_KIND` 字段 + 旧 collection 自动探测重建；`RetrievalScope.kinds` + `onlyTools() / onlySkills()`；`ToolRetrievalService.buildExpression` 拼 `kind in [...]`
- [x] 装配层：`AgentFactory.createToolkit` 按 `AiSkill / AiTool` 分发日志 + 子 Skill toolkit 禁止再挂 Skill
- [x] 扫描期治理埋点：`SideEffectInferrer`（HTTP 方法 + 路径关键词推断），`ScanProjectToolService.promoteToGlobalTool` 把结果写入 `side_effect`
- [x] 后端 API：`SkillController`（list / CRUD / toggle / test），`ToolController` 默认过滤掉 `kind=SKILL`
- [x] 前端：`ParameterTable.vue` 抽成复用组件；新增 `SkillList.vue`（列表 + 编辑器 + 测试对话框）+ `api/skill.ts` + `types/skill.ts` + 路由 + 菜单
- [x] 单元测试：`SubAgentSkillExecutorTest` / `SubAgentSkillFactoryTest` / `ToolRetrievalServiceExpressionTest` / `SideEffectInferrerTest`（28 用例全绿）

**Phase 2.0 明确不做（已从本阶段范围剔除，进入后续阶段）**：

| 原计划事项 | 推迟到 | 理由 |
| --- | :---: | --- |
| `ToolRegistry` 改名 `CapabilityRegistry` | - | 破坏性改动，名字不影响语义，**永久搁置** |
| Skill 强制 `skill.` 前缀 | - | 污染向量召回语料、Mining 侧要频繁 strip，**永久搁置** |
| Skill 嵌套 Skill（`dependsOnSkills`） | 2.2 | 2.0 运行时双层硬拦截，2.2 Workflow 一起开放 |
| `SkillMetadata.timeoutMs / retryLimit` 真正生效 | 2.2 / 4.x | 字段已存，Executor 未消费 |
| LLM provider/model 在 Skill spec 中覆盖 | 2.2 | 2.0 `SubAgentSpec.llmProvider/llmModel` 字段已预留，执行时暂不消费 |
| 画布化 Skill 编辑（DAG 视图） | 3.x | Agent Studio 一起做 |
| Skill 级 HITL / 权限 / 限流 | 4.x | 和 Tool 护栏统一节奏 |

**Phase 2.0 已知技术债（进入 2.0.1 已全部清掉 ✅ ）**：

1. ~~Milvus collection 字段变更"drop + 重建"策略，生产集群需要先跑 `POST /api/tools/retrieval/rebuild` 回灌向量~~ → 保留，已写入发布 runbook（Phase 2.1 上线时复用）；
2. ~~`SubAgentSkillExecutor` 没有 `timeoutMs` 兜底，极端 case 下子 Agent 长时间 hang~~ → **Phase 2.0.1 已闭合**：`Mono.timeout(timeoutMs)` + `retryWhen(retryLimit, 可重试异常白名单)`，`AiToolAgentAdapter` 侧加 `subscribeOn(Schedulers.boundedElastic())` 确保对同步 `fromCallable` 有效；
3. `tool_call_log.trace_id` 的传递：Phase 2.0.1 继续走 `ToolExecutionContextHolder`，子 Agent 内部目前都是同步执行；将来任何异步扩展（例如 WorkflowSkill）需要手动 `ToolExecutionContextHolder.set()`；
4. ~~前端 Skill 编辑器 `toolWhitelist` 字符串列表手填~~ → Phase 2.1 未覆盖到，**转入 2.2 / Agent Studio 的画布节点选择器一并解决**，单独出一张卡片不划算。

#### 2.0.2 Phase 2.0.1 —— 可信度补强（✅ 本轮已交付）

> 设计动机：Phase 2.0 把 Skill 三态中的 SubAgent 骨架搭起来了，但要让它真正可被业务依赖，必须先把"超时 / 重试 / 副作用真相 / 可观测 / 可复盘"做实。本小节对应本轮变更摘要的第 1、2 项。

**已完成**：

- [x] **执行器可靠性**：`SubAgentSkillExecutor` 接入 `SkillMetadata.timeoutMs + retryLimit` → `Mono.timeout()` + `retryWhen(Retry.backoff)`；超时统一抛 `SkillTimeoutException`（结构化 `errorCode / elapsedMs / skillName`，前端 Trace 可识别）；`containsTimeout` 加递归护栏避免 cause 链自指；
- [x] **Tool 层兜底**：`AiToolAgentAdapter.callAsync` 包一层 `Mono.fromCallable(...).subscribeOn(boundedElastic).timeout(toolTimeoutMs)`，单 Tool 也不会挂死父线程；
- [x] **`side_effect` 历史回填**：新增 `backfill_side_effect.sql`（手动批量 UPDATE）+ `SideEffectBackfillJob`（`ApplicationRunner`，开关 `ai.side-effect.backfill-on-startup`）+ `ToolDefinitionService.backfillSideEffectsForTools()` 复用 `SideEffectInferrer`，保证 Phase 2.0 之前入库的历史 Tool 都有值；
- [x] **Skill 指标 API**：`GET /api/skills/{name}/metrics?days=7` 返回 `p50/p95Latency`、`p50/p95Token`、`callCount`、`successRate` 及按日 `SkillMetricPoint[]`；`SkillList.vue` 增加"指标"按钮 + 指标 Dialog；
- [x] **Trace 回放全链路**：`GET /api/traces/{traceId}` + `GET /api/traces/recent?userId=&days=&limit=`；后端 `ToolCallLogService.listRecentTraces` 加数据库层 `LIMIT` + 内存分组容量上限，避免大表 OOM；前端 `TraceTimeline.vue` 按 `agentName` 前缀 `skill:*` 自动折叠父子层级 + `argsJson / resultSummary` 尝试 pretty-print JSON；
- [x] **入口接入**：`AgentDebug.vue` 右侧抽屉"查看 Trace"；`AgentList.vue` `el-tabs` 页签"最近 Trace"（懒加载 + userId / 天数过滤 + 复制 traceId）；
- [x] **KPI 口径文档**：`docs/Skill-评估指标口径.md` 固化 `HitRate / ReplacementRate / SuccessRateDiff / TokenSavings` 的取数 SQL；实现侧 `ToolCallLogService.computeCoverageMetrics` 严格按文档实现。

**Phase 2.0.1 相关技术决策**：

1. **Trace 父子折叠做在前端而非后端**：后端返回扁平 `TraceNode[]`，前端 `groupedNodes` computed 按 `agentName.startsWith("skill:")` 分组；这样 `/api/traces/{traceId}` 仍是通用接口，Skill Mining / 审批台等其它消费者不受影响。
2. **HitRate 定义升级**：原 KPI 文档只写 `COUNT(*)`，实现时改成"有调用日的天数 / 总天数"（覆盖率语义），更能反映 Skill 是否"持续被使用"而不是"某天暴涨"。文档已同步修正。
3. **LLM 实时指标暂不走缓存**：`getSkillMetrics` 直接查 `tool_call_log`，靠 `idx_create_time / idx_intent_create` 覆盖；Skill 数量 < 500 时这条路径足够，进入 Phase 3 Agent Studio 批量发布时再考虑物化到 `skill_eval_snapshot`。

#### 2.1 Skill Mining 骨架 —— 🟡 本轮交付代码，待真实数据验证

**已完成（代码骨架 + 评审闭环）**：

- [x] **聚合层**：`ToolChainAggregator` 按 `traceId` 聚合 `tool_call_log`，产出有序 `ToolChain` 语料；过滤掉跨 session 的噪声；
- [x] **挖掘层**：`PrefixSpanMiner`（N-gram + support threshold；`support = distinct traceId 数`，避免同 trace 多次计数虚高）；输出 `ChainPattern { tools[], traceIds, support }`；
- [x] **草稿反写层**：`SkillDraftLlmWriter`（**当前是模板策略**：systemPrompt 写死一句、`toolWhitelist = 挖出来的工具序列`、`name = 首尾工具名 ASCII 片段 + 6 位 sequence 哈希`；注释已标注"预留 LLM 反写入口"）；
- [x] **评审表**：`skill_draft`（`name / description / confidence_score / source_trace_ids / spec_json / status / review_note`），状态机 `DRAFT → APPROVED / DISCARDED / ROLLBACK_CANDIDATE / PUBLISHED`；
- [x] **服务层**：`SkillMiningService.precheck()` 做数据密度自检（日志量、trace 数、多步 trace 数、`readyForMining` 建议）+ `recommendedScenarios()` 给 5 条业务场景喂数建议；`generateDrafts` 幂等（同名草稿更新 `sourceTraceIds / confidenceScore`）；`publishDraft` 幂等（同名 Skill 已存在仅刷状态）；
- [x] **REST API**：`SkillMiningController` 5 端点 `/precheck`、`/drafts/generate`、`/drafts`、`/drafts/{id}/status`、`/drafts/{id}/publish`；
- [x] **前端评审页**：`SkillMining.vue`（数据密度卡片 + 草稿列表 + 预览抽屉复用 `TraceTimeline` 展示草稿来源 trace + 一键上架 / 丢弃）；菜单项 + 路由 `/skill/mining`；
- [x] **评估调度**：`SkillEvaluationScheduler`（`@EnableScheduling`，每日 02:00 跑）→ `skill_eval_snapshot`；`successRate < 0.70 ∥ p95TokenCost > 4000` → 打 `ROLLBACK_CANDIDATE`；`HitRate / ReplacementRate` 调 `ToolCallLogService.computeCoverageMetrics` 真算，不再是占位；
- [x] **数据库迁移**：`sql/skill_mining_phase2_1.sql` 创建 `skill_draft` + `skill_eval_snapshot`；`sql/tool_call_log_index_phase2_0_1.sql` 加 `idx_create_time / idx_user_create_time / idx_intent_create`（用 `add_idx_if_absent` 存储过程保证幂等）。

**待真实 `tool_call_log` 数据后持续完善**：

1. **`SkillDraftLlmWriter` 真正接 LLM**：目前是模板策略，Phase 2.1 首次跑通、评审同学打出第一批评分后，再用"LLM 只做 systemPrompt / description 反写、toolWhitelist 仍从挖掘结果取"的混合策略引入，避免一上来被幻觉工具名坑；
2. **support / confidence 阈值调优**：当前默认 `minSupport=3 / days=7 / limit=20`，需要在有 ≥ 500 条真实 trace 后基于评审通过率回调；
3. **草稿排重升级**：目前按 `name` 幂等，后续可改成"序列 Levenshtein 相似度 + 工具集合 Jaccard" 做语义去重；
4. **评估指标真值**：`computeCoverageMetrics` 的 `ReplacementRate` 需要 Skill 上架后持续观测 3~4 周才能看出趋势，当前指标只是对过去的回溯。

#### 2.2 AugmentedTool（WorkflowSkill 合并至 Phase 3）—— ⏳ 待启动

> **策略调整**（本轮更新）：原计划的 `WorkflowSkill`（代码 DSL）不再独立立项，直接并入 **Phase 3 Agent Studio** 的画布产出物，避免"写一次 Java DSL + 写一次画布 JSON"的双份成本。

剩下的是 AugmentedTool：单 Tool 的前后置装饰器（参数清洗、结果整形、幂等键注入、简单缓存）。此阶段不再需要"把 timeoutMs / retryLimit 接上"——**Phase 2.0.1 已经接上了**。AugmentedTool 落地时只需复用 `SubAgentSkillExecutor` 的可靠性外壳。

---

## 二、Phase 3：Agent Studio（产品化）

### 2.1 目标用户与核心价值

目标用户：**业务运营 / 解决方案工程师**，不写 Java、但懂业务流程。

核心价值：把现在 `AgentDefinition` YAML + 系统提示词 + 工具白名单 + 路由规则这套「工程师视角」转译成画布交互，让运营 30 分钟能把一个新业务场景跑起来。

### 2.2 形态

```
┌─────────────── Agent Studio ───────────────┐
│                                            │
│  [ 画布编辑器 ]                            │
│   节点类型：Skill / Tool / Branch / HITL   │
│   连线：控制流 / 数据流                    │
│                                            │
│  [ 侧栏 ]                                  │
│   - Skill/Tool 库（按标签/语义检索）      │
│   - 变量 & 参数映射                       │
│   - 测试对话框                            │
│                                            │
│  [ 发布端点 ]                              │
│   /api/v1/agents/{key}/chat                │
│   一键生成：AgentDefinition + 路由注册    │
│                                            │
└────────────────────────────────────────────┘
```

### 2.3 三个最小可用能力

1. **可视化编辑**：节点拖拽 + 连线，底层落为 `AgentDefinition + WorkflowSkill spec`；
2. **调试面板**：编辑器内直接发消息，实时看每一步 Tool/Skill 的输入输出 / trace_id / 召回 top-K；
3. **发布与版本**：每次发布生成一个不可变 `agent_version`，支持灰度（按 user_id / 流量比例）与回滚。

### 2.4 和前面两阶段的依赖

- 依赖 Phase 2 的 Skill 契约：画布节点的「能力」本质是 Skill；
- 依赖 Phase 1 的 `tool_call_log`：调试面板的执行回放全靠这张表；
- 依赖 Phase 4 的治理：发布到生产前的 sideEffect 检查 / HITL 配置，需要护栏层提供。

### 2.5 非目标（避免发散）

- **不做** 通用低代码平台（不是什么节点都能塞进来，只限「Agent 能力编排」相关）；
- **不做** 运行时 DAG 引擎（Workflow 仍然在 Java 侧执行，Studio 只是编辑器 + 持久化 + 版本管理）；
- **不做** 零代码写新 Tool（原子 Tool 仍由工程同学通过扫描 / 编码接入）。

### 3.1 Phase 3.0 Agent Studio v0 —— ✅ 本轮交付

> 详细验收清单见 `docs/Phase3.0-AgentStudio-落地验收清单.md`。本节只列关键清单。

| 类别 | 交付物 | 关键实现 |
| --- | --- | --- |
| **数据** | `agent_definition` / `agent_version` 两表 + 幂等迁移 | `sql/agent_studio_phase3_0.sql`，`sql/init.sql` 已同步 |
| **领域** | `AgentDefinition.{keySlug, canvasJson, allowIrreversible}` + `AgentVersion` 快照 | `agent/AgentDefinition.java`、`agent/persist/*Entity` |
| **服务** | `AgentDefinitionService`（DB 化 + 旧 JSON 迁移）、`AgentVersionService`（publish / rollback / 灰度 hash 路由） | `AgentVersionServiceTest` 覆盖重点分支 |
| **运行时** | `ToolExecutionContext.allowIrreversible` + `AiToolAgentAdapter.checkSideEffectGate` | `AgentFactory` / `AgentRouter` 已将 sideEffect 与 allowIrreversible 串通 |
| **对外** | `POST /api/v1/agents/{key}/chat`（`keySlug` 网关）+ `/agents/{id}/versions` REST | `AgentGatewayController` / `AgentVersionController` |
| **前端** | `AgentStudio.vue`（Vue Flow 三栏画布 + 调试抽屉 + 发布弹窗）、`AgentVersions.vue`（版本列表 + 回滚）、`AgentEdit.vue` 新字段 | `utils/studio.ts` 做画布 ↔ 定义转换 |
| **Skill 闭环** | 调试抽屉选中 trace 子序列 → `POST /api/skill-mining/drafts/from-trace` | 与 Phase 2.1 `skill_draft` 评审流合流 |

**明确不做（滚入 §3.2 Backlog）**：条件边 / 并行 / 循环节点、租户维度灰度、HITL 阻断、prompt diff 视图、Tool ACL 强制、Trace 多 trace 对比视图。

### 3.2 中期 Backlog（Phase 3.0 之后的 10 项）

按"谁会先抱怨"排序，P0 = 进生产前必须，P1 = Phase 3.1 / 3.2 候选，P2 = Phase 4 后补，P3 = 长期展望。

| 优先级 | ID | 名称 | 痛点 / 价值 | 预估规模 |
| --- | --- | --- | --- | --- |
| ✅ 已交付 | `tool-acl` | Tool / Skill 级 ACL | Studio 开放后任何运营都能挂任何 Tool，生产必须按角色黑白名单 | **2026-04-24 交付**：`tool_acl` 表 + `ToolAclService`（含 5min 本地缓存）+ `AgentFactory.createToolkit` 按 roles 过滤 + `ChatRequest.roles` 全链路透传 + 管理端 `ToolAclList.vue`（CRUD / 批量授权 / 决策诊断）+ 11 个单测 |
| **P0** | `rate-limit` | 令牌桶限流（Redis） | 不加限流，一个 prompt 错误可能把下游 HTTP 打挂 | 已有 `tool_call_log`，加 Redis counter + `AiToolAgentAdapter` 拦截，约 2 天 |
| **P0** | `studio-training` | Studio 操作录屏 + 运营手册 | 画布交付后没有培训物料，运营不会用 | 3 段 30s 视频 + 一份 PDF |
| **P1** | `canvas-control-flow` | 画布条件边 / 并行 / 循环 | 当前画布只是能力清单，复杂流程仍需工程编码 | DSL 扩展 + Vue Flow 自定义边，约 1 周 |
| **P1** | `prompt-diff` | 版本快照 diff 视图 | 回滚时运营看不到 prompt / tools 差异 | 前端 `monaco-diff` 组件 + `snapshot_json` diff，约 2 天 |
| **P1** | `hitl` | HITL 审批门（`tool_approval` 表 + IM/Web 推送） | 降级过久，生产敏感场景仍需人工审批 | 见 §3.3 设计，约 1 周（不含 IM 适配） |
| **P2** | `rollout-strategy` | 灰度策略增强（A/B、按租户、按 header） | 当前只有 user-hash 一种 | `AgentVersionService.resolveActiveSnapshot` 扩策略 SPI，约 3 天 |
| **P2** | `trace-advanced` | 跨 trace 对比 / 时间轴缩放 | 生产定位 bug 时要对比多个 trace | `TraceTimeline.vue` 重构为可嵌套，约 4 天 |
| **P2** | `skill-mining-llm` | `SkillDraftLlmWriter` 真正接 LLM 反写 name / description | 当前模板生成可读性差，依赖运营改名 | 引入 `ChatClient` + 提示工程，约 2 天 |
| **P3** | `agent-marketplace` | Agent / Skill 市场（跨项目复用） | 多项目出现同类 Skill 重复建设 | 导入导出 + 公有/私有市场，规模较大 |

> 滚动原则：每发布一次 Phase 3.x 之前，先从本表 P0 / P1 里挑选 2 ~ 3 项落地，按实际数据回灌优先级；P3 只做"不做会后悔"的前期调研。

---

## 三、Phase 4：Tool 护栏（企业治理）

> 并行于 Phase 2 / Phase 3 推进，生产上线前必须补齐。

### 3.1 四条护栏

| 护栏 | 解决的问题 | 交付形态 | 状态 |
| --- | --- | --- | --- |
| **sideEffect 标注** | Agent 不知道哪些 Tool 是危险操作 | `tool_definition.side_effect` 枚举；扫描阶段默认推断（GET=READ_ONLY / POST=WRITE），人工可覆盖；**Phase 2.0.1 起历史 Tool 已被启动期 Job 回填** | 🟡 **字段 + 扫描 + 回填已完成**（2.0 / 2.0.1），运行时拦截（根据 sideEffect 拒绝某些 Agent 调用）仍未接入，计划随 Phase 3 发布流程一起做 |
| **HITL（Human-in-the-loop）** | 副作用操作直接执行风险大 | Tool/Skill 级 `hitl_policy`；执行前暂停 → IM / Web 端审批 → 继续 | ⚠️ **已降级**：Demo / POC 阶段 ROI 低，`HitlPolicy` 枚举保留，运行时暂不实现；Phase 3 Agent Studio 发布生产前再单独立项 |
| **审计日志** | 出事后无法还原现场 | `tool_call_log`（Phase 1）+ Skill 日志 trace 贯穿（2.0）+ **Trace 回放 API + 前端时间线（2.0.1）** + **指标 API（2.0.1）** | ✅ **可观测性已闭环** |
| **权限与限流** | 所有用户能调所有 Tool / 无限流 | `tool_acl` 表（role ↔ tool）；令牌桶限流（Redis） | ⏳ 未开工，随 Phase 3 做 |

### 3.2 sideEffect 推断规则（扫描期）

```
HTTP Method × 命名线索 × 返回类型
├─ GET + 包含 list/query/get  → READ_ONLY
├─ POST + idempotency-key     → IDEMPOTENT_WRITE
├─ POST / PUT / PATCH         → WRITE
├─ DELETE                     → IRREVERSIBLE
└─ 其他（如 webhook）         → WRITE（保守）
```

扫描器给出初值，管理端可手工 override；AI 语义描述里同步记录 sideEffect 级别，让 LLM 在 prompt 中看到。

### 3.3 HITL 执行流

```
Agent ──▶ Skill/Tool 执行前检查 hitl_policy
              │
              ├─ NEVER              ──▶ 直接执行
              ├─ ON_WRITE + 非写    ──▶ 直接执行
              └─ 命中                ──▶ 挂起 + 推送审批 + 持久化 pending 态
                                           │
                                           ├─ 批准 ──▶ 恢复执行
                                           └─ 驳回 ──▶ 记录原因 + 返回给 Agent
```

需要一张 `tool_approval` 表（`trace_id / tool_name / args / status / approver / decided_at`），并和 `tool_call_log` 通过 `trace_id` 关联。

### 3.4 权限模型

最小闭环：
- `role` ↔ `tool` / `skill` 黑白名单；
- `AgentDefinition` 标注 `required_role`；Agent 执行时取当前 userId 的 role 求交集；
- 交集为空 → Tool/Skill 在 `AgentFactory.createToolkit` 阶段被过滤掉，不进 LLM 视野（彻底防 prompt 注入越权）。

---

## 四、Skill 挖掘（Phase 2.1 详化）

### 4.1 数据底座

Phase 1 已沉淀 `tool_call_log`，关键字段再强调：

- `trace_id`：一次 Agent 执行的 ID，用来聚合调用链；
- `retrieval_trace_json`：当时召回了哪些候选（包含未被 LLM 选中的），用于训练更好的选择器；
- `args_json / result_summary`：配合序列挖掘看参数/结果语义稳定性。

### 4.2 挖掘流水线

```
tool_call_log
    │
    ▼ 按 trace_id 聚合
调用序列语料（ordered tool chain）
    │
    ▼ 频繁序列挖掘（PrefixSpan / 自定义 N-gram）
高频子序列（chain pattern）
    │
    ▼ 结合 intent_type / user 意图做聚类
Skill 候选
    │
    ▼ LLM 生成草稿（命名 + description + Workflow/SubAgent spec）
SkillDraft
    │
    ▼ 管理端评审（编辑 / 合并 / 丢弃 / 上架）
SkillDefinition（上架）
    │
    ▼ 纳入 CapabilityRegistry + 向量池
LLM 召回 Skill 替代原来的多次 Tool 调用
```

### 4.3 评估指标

Skill 不是挖出来就结束，要持续度量：
- **命中率（HitRate）**：覆盖率语义 —— "有 Skill 调用日"的天数 / 统计窗口总天数；
- **替代率（ReplacementRate）**：相同意图下，Skill 执行次数 / (Skill 执行 + 同意图下多工具 trace 数)；
- **成功率差（SuccessRateDiff）**：Skill 版本 vs 原 ReAct 多步版本的任务成功率；
- **Token 节省（TokenSavings）**：Skill 一次调用 vs 多步 ReAct 的 token 总消耗差。

> 取数口径固化在 `docs/Skill-评估指标口径.md`；`ToolCallLogService.computeCoverageMetrics` 是唯一实现点，API / 调度器 / 前端都通过它取值，避免口径漂移。

指标稳定正向后才把 Skill 设为「推荐」，反之回滚。**Phase 2.0.1 起** `SkillEvaluationScheduler` 每日 02:00 自动把 `successRate < 0.70 ∥ p95TokenCost > 4000` 的 Skill 打成 `ROLLBACK_CANDIDATE`，运营在 `SkillMining.vue` 评审页可见。

---

## 五、总体时间线（建议）

```
Phase 1  Tool Retrieval + tool_call_log          ✅ 已交付
Phase 2  Skill 层
         ├─ 2.0   SubAgentSkill MVP               ✅ 已交付（详见 Phase2.0-SubAgentSkill-落地验收清单.md）
         ├─ 2.0.1 可信度补强（超时/重试/回填/    ✅ 本轮已交付
         │        指标/Trace 回放/KPI 口径）
         ├─ 2.1   Skill Mining 骨架               🟡 代码已交付，等真实 tool_call_log 数据验证
         │                                         （阈值调优 + LLM 反写接入 + 草稿语义排重）
         └─ 2.2   AugmentedTool                   ⏳ 规划中（WorkflowSkill 已合并到 Phase 3）
Phase 3  Agent Studio（含原 WorkflowSkill 画布）
         ├─ 3.0 画布 + 调试抽屉 + 发布端点 + 版本灰度 + Trace→Skill + IRREVERSIBLE 闸口   ✅ 本轮已交付
         ├─ 3.1 条件边 / 并行 / prompt diff / 操作手册                                      ⏳ 规划中（见 §3.2 Backlog P1）
         └─ 3.2 灰度策略扩展 + 跨 trace 对比 + Skill Mining LLM 反写                        ⏳ 规划中（见 §3.2 Backlog P2）
Phase 4  Tool 护栏（和 2/3 并行）
         ├─ 4.0 sideEffect 标注                   ✅ Phase 3.0 运行时 IRREVERSIBLE 闸口已接通；
         │                                         IRREVERSIBLE 运行时闸门已随 Phase 3.0 接入
         ├─ 4.1 HITL 执行流 + 审批台              ⚠️ 已降级，挂在 §3.2 Backlog P1 `hitl`
         └─ 4.2 权限与限流                        ├─ `tool-acl` ✅ 2026-04-24 交付
                                                  └─ `rate-limit` ⏳ §3.2 Backlog P0
```

先决关系（本轮已修订）：**2.0 先于 2.0.1 先于 2.1**；**3.0 Studio v0 已先于 3.1/3.2 / Phase 4 后续护栏上线**；§3.2 P0 现状为 `tool-acl` ✅ 已交付（2026-04-24）、`rate-limit` / `studio-training` 仍阻塞 3.1 —— **3.1 开工前必须先补齐限流 + 培训**，否则不对外开放发布端点；**2.2 AugmentedTool 可以并行 3.1**。

---

## 六、风险与取舍

1. **不要把 Skill 做成"万能框架"**：三态已经够用，不追加第四态（比如"状态机 Skill"），超出就走 SubAgent；
2. **不要让 Agent Studio 走向低代码平台**：只围绕 Agent 编排，其他能力（ETL / 表单 / 流程）不掺和；
3. **日志挖掘不能无人值守上架**：Skill 命名和 prompt 一定要人工审核，LLM 生成只是草稿；**Phase 2.1 虽然代码已交付，但在 `SkillMining.vue` 评审流程被业务团队跑通前，绝不允许自动发布**；
4. **~~护栏先于产品化~~ → 改为：可观测先于产品化**（本轮策略调整）：HITL 已降级，Agent Studio 发布到生产前的最低门槛是 Phase 2.0.1 的 Trace 回放 + 指标观测 + Phase 4.0 的 sideEffect 运行时拦截；审批台留到真正的生产 rollout 时再做；
5. **Tool Retrieval / Skill Mining 的数据稀疏期**：新环境前 2~3 周 `tool_call_log` 量不够，`SkillMiningService.precheck()` 会打 `readyForMining=false`，运营看到应当按提示的"示例业务场景"先手工喂数据，**不要直接点生成草稿**（会全是噪声）；
6. **模板反写的局限**：`SkillDraftLlmWriter` 目前的 name/description 是模板+哈希拼的，草稿可读性一般；依赖人工评审时改名。接真 LLM 的时机取决于第一批评审通过率——通过率 > 60% 说明骨架靠谱，可以接 LLM 提升可读性；通过率 < 30% 说明阈值或挖掘维度有问题，先调挖掘再谈 LLM。

---

## 七、附：关键术语对照

| 术语 | 本项目含义 |
| --- | --- |
| Tool | 原子能力，通常对应一个 HTTP 接口或一个 Java 方法，`AiTool` 契约 |
| Skill | 粗粒度能力，对 LLM 透明，三态：Workflow / SubAgent / AugmentedTool |
| Capability | Tool + Skill 的并集；本项目保留 `ToolRegistry` 作为实际类名，统一管理 Tool 与 Skill（Phase 2.0 决策） |
| Tool Retrieval | 按用户问题向量召回 top-K 能力（覆盖 Tool + Skill）注入 Agent 可见集合 |
| Skill Mining | 从 `tool_call_log` 里挖高频调用链，LLM 生成 Skill 草稿 |
| Agent Studio | 面向运营的可视化 Agent 编辑/调试/发布平台 |
| HITL | Human-in-the-loop，副作用操作的人工审批门 |
| sideEffect | Tool/Skill 的副作用级别（NONE/READ_ONLY/IDEMPOTENT_WRITE/WRITE/IRREVERSIBLE） |
| trace_id | 一次 Agent 执行的全局追踪 ID，贯穿 log / approval / metrics |
