# 产品演进路线：Skill 层 / Agent Studio / Tool 护栏

> 本文承接 `AI能力系统升级规划.md` 第五章（Tool Retrieval / Skill Mining 演进路径），把之前对话中敲定的「未来主攻方向」沉淀为可执行的阶段规划，作为后续版本的升级蓝本。
>
> 版本约定：Phase 0 = 当前已交付；Phase 1 = Tool Retrieval + tool_call_log（已完成，见升级规划文档 5.x）；本文聚焦 Phase 2 ~ Phase 4。

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

### 1.3 契约设计（草案）

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

存储侧：
- `skill_definition` 表：`id / name / kind / spec_json / metadata_json / enabled / agent_visible / version`；
- `spec_json` 按 `kind` 解析：Workflow 用 DSL，SubAgent 用 `AgentDefinition` 子集，AugmentedTool 用 `{ toolName, preHook, postHook, policies }`。

### 1.4 与现有架构的接入点

1. `ToolRegistry` 升级为 `CapabilityRegistry`，同时索引 Tool 与 Skill；Skill 对外名字空间用 `skill.<name>`，避免和 Tool 冲突；
2. `tool_embeddings` 扩一列 `kind`（TOOL/SKILL），检索时同池召回 → LLM 天然看到的是「粗粒度能力 + 细粒度 Tool」的混合集合；
3. `AgentFactory.createToolkit` 识别 Skill → 调用 Skill 自己的 `execute`（Workflow / SubAgent / AugmentedTool 分别对应不同执行器）；
4. `tool_call_log` 兼容：Skill 执行也写日志，`tool_name` 前缀 `skill.`，`retrieval_trace_json` 仍然记录召回来源。

### 1.5 Skill 来源三路并行

Skill 不是只能写代码产出，规划三条来源通道：

| 来源 | 触发者 | 典型产物 | Phase |
| --- | --- | --- | --- |
| **代码编写** | 工程 | `WorkflowSkill` / `SubAgentSkill` Java 类 | 2.0 |
| **日志挖掘（重点）** | 系统 + LLM | 从 `tool_call_log` 的高频调用链自动生成 `SkillDraft` → 人工评审 → 上架 | 2.1 |
| **运营搭建** | 运营在 Agent Studio 画布里拖拽生成 Workflow DSL | `WorkflowSkill` JSON spec | 3.x（和 Phase 3 联动） |

### 1.6 实施顺序（重要：SubAgent 先行）

之前讨论过三态优先级，结论是 **SubAgent 先落地**，原因：

1. 代码改动最小：`SubAgentSkill` 本质是「封装一个子 `AgentDefinition`」，复用现有 `AgentFactory / ReActAgent` 基础设施；
2. 最能解决 Phase 2 的核心痛点：多步业务流程不稳 = 把 ReAct 的决策面收窄，SubAgent 天然就是「有限工具 + 有限目标」的子域 Agent；
3. 先验证 Skill 注册 / 检索 / 调用 / 日志整条链路跑通，再扩 Workflow 和 AugmentedTool 成本低。

拟分三步：

1. **2.0 SubAgent**：定义 `AiSkill` 契约、新增 `skill_definition` 表、`SubAgentSkillExecutor`、把 `CapabilityRegistry` 和检索打通；
2. **2.1 Skill Mining**：基于 `tool_call_log` + `trace_id` 聚合调用链，LLM 生成 Skill 草稿（优先 SubAgent 模板），管理端评审后一键上架；
3. **2.2 Workflow + AugmentedTool**：给「固定流程」和「单 Tool 装饰」补齐另外两态，和 Phase 4 的护栏能力（HITL / sideEffect）一起落。

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

---

## 三、Phase 4：Tool 护栏（企业治理）

> 并行于 Phase 2 / Phase 3 推进，生产上线前必须补齐。

### 3.1 四条护栏

| 护栏 | 解决的问题 | 交付形态 |
| --- | --- | --- |
| **sideEffect 标注** | Agent 不知道哪些 Tool 是危险操作 | `tool_definition.side_effect` 枚举；扫描阶段默认推断（GET=READ_ONLY / POST=WRITE），人工可覆盖 |
| **HITL（Human-in-the-loop）** | 副作用操作直接执行风险大 | Tool/Skill 级 `hitl_policy`；执行前暂停 → IM / Web 端审批 → 继续 |
| **审计日志** | 出事后无法还原现场 | 已交付：`tool_call_log`（Phase 1）+ Skill 日志扩展 |
| **权限与限流** | 所有用户能调所有 Tool / 无限流 | `tool_acl` 表（role ↔ tool）；令牌桶限流（Redis） |

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
- **命中率**：Skill 上线后被召回 / 被选中的次数；
- **替代率**：相同意图下，Skill 执行次数 / (Skill + 原子 Tool chain 执行次数)；
- **成功率差**：Skill 版本 vs 原 ReAct 多步版本的任务成功率；
- **Token 节省**：Skill 一次调用 vs 多步 ReAct 的 token 总消耗差。

指标稳定正向后才把 Skill 设为「推荐」，反之回滚。

---

## 五、总体时间线（建议）

```
Phase 1  Tool Retrieval + tool_call_log          ✅ 已交付
Phase 2  Skill 层
         ├─ 2.0 SubAgentSkill（先行）              ⇦ 下一步
         ├─ 2.1 Skill Mining（基于 Phase 1 数据）
         └─ 2.2 WorkflowSkill + AugmentedTool
Phase 3  Agent Studio
         ├─ 3.0 画布 + Skill/Tool 调色板
         ├─ 3.1 调试面板 + trace 回放
         └─ 3.2 发布端点 + 版本灰度
Phase 4  Tool 护栏（和 2/3 并行）
         ├─ 4.0 sideEffect 标注（扫描 + 人工）
         ├─ 4.1 HITL 执行流 + 审批台
         └─ 4.2 权限与限流
```

先决关系：**2.0 先于 2.1 先于 2.2**；**3.x 必须在 2.0 之后**；**4.0 尽早，4.1 不晚于 2.2，4.2 不晚于 3.2**。

---

## 六、风险与取舍

1. **不要把 Skill 做成"万能框架"**：三态已经够用，不追加第四态（比如"状态机 Skill"），超出就走 SubAgent；
2. **不要让 Agent Studio 走向低代码平台**：只围绕 Agent 编排，其他能力（ETL / 表单 / 流程）不掺和；
3. **日志挖掘不能无人值守上架**：Skill 命名和 prompt 一定要人工审核，LLM 生成只是草稿；
4. **护栏先于产品化**：Agent Studio 发布到生产前，Phase 4.0 / 4.1 必须可用，否则运营一键发布 = 风险一键放大；
5. **Tool Retrieval 的数据稀疏期**：新环境前两周 `tool_call_log` 为空，Skill Mining 会空转，提前准备「示例业务场景」喂数据，避免冷启动尴尬。

---

## 七、附：关键术语对照

| 术语 | 本项目含义 |
| --- | --- |
| Tool | 原子能力，通常对应一个 HTTP 接口或一个 Java 方法，`AiTool` 契约 |
| Skill | 粗粒度能力，对 LLM 透明，三态：Workflow / SubAgent / AugmentedTool |
| Capability | Tool + Skill 的并集，`CapabilityRegistry` 统一管理 |
| Tool Retrieval | 按用户问题向量召回 top-K 能力（覆盖 Tool + Skill）注入 Agent 可见集合 |
| Skill Mining | 从 `tool_call_log` 里挖高频调用链，LLM 生成 Skill 草稿 |
| Agent Studio | 面向运营的可视化 Agent 编辑/调试/发布平台 |
| HITL | Human-in-the-loop，副作用操作的人工审批门 |
| sideEffect | Tool/Skill 的副作用级别（NONE/READ_ONLY/IDEMPOTENT_WRITE/WRITE/IRREVERSIBLE） |
| trace_id | 一次 Agent 执行的全局追踪 ID，贯穿 log / approval / metrics |
