# Workflow 语义契约

状态：Accepted
日期：2026-07-23

## 1. 适用原则

Workflow Studio、Runtime、AI Coding、SDK 图同步和数据库只使用本契约定义的当前语义。旧字段、旧枚举、旧路由和旧 JSON 文档不再归一化或双写；已有旧 Workflow 数据通过破坏性升级删除，不做转换。

GraphSpec 是唯一运行语义事实源，`canvas_json` 只保存布局。任何执行节点、边、条件、能力引用或变量映射都不得从 Canvas 恢复。

项目尚未正式发布，Workflow Studio 的产品、架构和变更说明不使用数字代际表达产品版本，只描述“当前契约”与“历史语义”。`schemaVersion`、`layoutVersion` 等数字仅标识 JSON 文档格式，不代表产品版本。

## 2. Workflow 身份与分类

Workflow 的业务形态、执行引擎、定义权威和创建入口是四个独立维度：

| 维度 | 字段 | 允许值 |
| --- | --- | --- |
| 业务形态 | `workflowKind` | `GENERAL`、`PAGE_ASSISTANT` |
| 执行引擎 | `executionEngine` | `GRAPH_SPEC` |
| 定义权威 | `definitionAuthority` | `USER`、`SDK`、`SYSTEM` |
| 创建入口 | `creationChannel` | `STUDIO`、`AI_CODING`、`SDK_SYNC`、`AI_QUICK_ACCESS`、`SYSTEM_SEED` |

约束：

- `GENERAL` 是通用 Workflow；是否通过对话触发不属于 Workflow 类型。
- `PAGE_ASSISTANT` 是带页面上下文和 Page Action 约束的 Workflow。
- `PAGE_ACTION` 是 GraphSpec 节点类型，不是 Workflow 类型。
- `definitionAuthority=SDK` 的定义由 SDK 同步事实源维护，Studio 不得静默覆盖。
- `LANGGRAPH4J` 是实现名称，不是公共执行引擎值；Agent 的 `AGENTSCOPE` 也不属于 Workflow 执行引擎。

数据库字段固定为 `workflow_kind / execution_engine / definition_authority / creation_channel`。API 和前端不得再使用 `workflowType / runtimeType / managedBy` 表达 Workflow 元数据。

## 3. GraphSpec 当前契约

GraphSpec 顶层只允许：

- `schemaVersion=2`
- `inputSchema`
- `stateSchema`
- `nodes`
- `edges`
- `entryNodeId`
- `exitNodeIds`

`entryNodeId` 和 `exitNodeIds` 是唯一流程边界。`START`、`END` 仅是 Studio 内存画布的虚拟展示元素，不得写入 GraphSpec 节点或边。

`exitNodeIds` 中的节点就是运行终点：执行器完成该节点后立即结束；终点节点不得再声明出边。分支 Workflow 必须把每个可能结束的真实节点都列入 `exitNodeIds`。

当前正式节点类型为：`LLM`、`USER_INPUT`、`INTERACTION`、`PAGE_ACTION`、`TOOL`、`IF_ELSE`、`VARIABLE_ASSIGN`、`TEMPLATE`、`ANSWER`、`CODE`、`INTENT_CLASSIFIER`、`VARIABLE_AGGREGATOR`、`HUMAN_APPROVAL`、`LOOP`、`KNOWLEDGE_WRITE`、`DOCUMENT_EXTRACT`、`MCP_CALL`、`PARAMETER_EXTRACT`、`HTTP_REQUEST`、`KNOWLEDGE_RETRIEVAL`。

节点类型必须使用上述规范值。目录中的 `canvasKind` 只用于 Studio 内存投影，不是 GraphSpec 输入别名。

### 3.1 Knowledge 证据策略

`KNOWLEDGE_RETRIEVAL.config.evidencePolicy` 只允许：

- `REQUIRED`：事实回答必须有检索证据。节点通过 `route:evidence` 与 `route:no_evidence` 两条显式边分流；两条边缺一不可，也不得同时保留 `always` / `success` 回退边。`no_evidence` 是成功的业务结果，不是系统异常；该边必须直接连接固定 `ANSWER`，明确告知未找到证据，不得继续调用 LLM 凭空补全。
- `OPTIONAL`：业务明确允许使用通用模型知识回退时使用，保留普通线性边行为。

Studio 新建节点和 AI Proposal 默认使用 `REQUIRED`。历史 GraphSpec 缺少该字段时，Runtime 暂按 `OPTIONAL` 保持既有执行语义，发布校验会给出迁移警告；Studio 重新保存后必须显式写入所选策略。

节点结构化输出固定包含 `query / hits / hitCount / outcome / empty`，其中 `outcome=HIT|NO_EVIDENCE`。`diagnostics` 只允许候选数、过滤数、截断数、内容预算和阶段耗时等脱敏数值或布尔值，不得包含查询、命中文本、文件名或用户身份。Workflow 节点只负责返回检索结果，Knowledge 管理侧的 `directReturn*` 配置不得进入 GraphSpec。

## 4. Canvas 布局文档

`canvas_json` 固定为 `schemaVersion=1 / layoutVersion=1` 的布局文档：

- 顶层只允许 `schemaVersion / layoutVersion / viewport / layout / nodes / edges`。
- 节点布局只允许 `id / position / width / height / collapsed`。
- 边布局只允许 `id / label / style`。
- Canvas 不得包含节点 `type / data / config / ref`，也不得包含边 `source / target / condition`。

读取 Studio 时必须先从 GraphSpec 投影语义画布，再叠加 Canvas 布局。缺失或非法 Canvas 不能替代 GraphSpec，也不能被静默吞掉。

## 5. 编辑、调试与发布生命周期

| 概念 | 含义 |
| --- | --- |
| `WorkflowWorkingCopy` | `runtime_workflow` 中可编辑的当前工作副本 |
| `WorkflowProposal` | AI 生成或编辑后、尚未保存的内存候选 |
| `WorkflowVersion` | 已发布且不可变的执行快照 |
| `WorkflowRun` | 一次执行实例 |
| `WorkflowResumeCheckpoint` | Runtime 内部恢复同一次暂停执行所需的版本化断点；当前为 `WorkflowCheckpointV1` |
| `WorkflowStateSnapshot` | 调试或观察界面展示的状态快照，不承诺可恢复 |

公共 API 只提供：

- Working Copy：`GET/PUT /api/workflows/{workflowId}/working-copy`
- Proposal：`POST /api/workflows/studio/proposals/generate`、`POST /api/workflows/studio/proposals/edit`
- 发布：`POST /api/workflows/{workflowId}/versions/publish`
- Workflow Credential：`/api/workflows/credentials`

旧 `/studio`、`generate-draft`、`edit-draft`、`POST /versions` 和 `/api/agent/workflow-credentials` 路由不再提供。

请求与响应只使用 `workingCopyDefinition`、`stateSnapshot` 和 `*_WORKING_COPY`。`draftDefinition`、`finalState`、`*_DRAFT` 与 `legacyCode` 不再读取或返回。Working Copy 冲突只返回 `WORKFLOW_WORKING_COPY_CONFLICT`。

## 6. 运行状态

| 边界 | 状态 | 附加原因 |
| --- | --- | --- |
| Workflow 执行与 Runtime Run | `RUNNING / SUSPENDED / COMPLETED / FAILED / CANCELLED / TIMED_OUT` | `suspensionReason=USER_INPUT / APPROVAL` |
| Debug Session | `RUNNING / SUSPENDED / RESUMING / COMPLETED / FAILED / CANCELLED / EXPIRED` | 当前交互节点与 `uiRequest` |

`runtime_interaction_session.status=WAITING_USER` 是 Interaction 会话自身状态，不是 Runtime Run 状态。Trace Span 也有独立的观测状态。

新暂停点使用 `checkpointSchemaVersion=1 / executionEngineVersion=RUNTIME_KERNEL_V2`，并绑定暂停时 GraphSpec SHA-256、节点、payload SHA-256、默认 256 KiB 配置上限和 1 MiB 硬上限。可信身份和 Eval typed context 不进入断点；恢复身份从已验证的会话归属重建。历史 Map 只走 `LEGACY` 兼容路由，未知 schema/engine、摘要篡改或 GraphSpec 漂移均失败关闭。详细契约见 [runtime-execution-kernel-v2.md](./runtime-execution-kernel-v2.md)。

在实现确定性版本路由前，Workflow 发布只允许 `rolloutPercent=100`，任意时刻最多一个 ACTIVE 版本。

## 7. 数据库切换

`sql/initV2.sql` 是唯一新库基线。项目不提供旧 Workflow 数据转换脚本；旧 Workflow 行、旧版本快照、旧调试会话和旧 GraphSpec 不保证可执行。

历史 `upgrade-20260722-workflow-semantics.sql` 已并入 GitHub 基线并从当前树清理。早于 `ae9e1ce6` 的开发/测试库必须先备份，再从对应 Git tag 获取这份破坏性迁移或按当前 `initV2.sql` 重建；当前合并升级不重复承载旧 Workflow 数据转换。

不得通过在 Runtime 中增加旧字段读取、别名路由或隐式转换来延长旧数据生命周期。
