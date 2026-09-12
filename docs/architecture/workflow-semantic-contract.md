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

### 2.1 SDK 图同步的定义归属

Registry 保留 `/api/registry/projects/{projectCode}/agent-graphs/sync` 的请求与响应协议，在数据库写事务之外解析 Capability 项目，再向 Workflow 所有的 [RuntimeSdkWorkflowSyncService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeSdkWorkflowSyncService.java) 传入不可变的序列化图命令。Registry 不读取或构造 Workflow Entity。GraphSpec 校验、模型默认值、Canvas 布局、来源与草稿字段都由 Workflow 维护。

稳定 keySlug 继续由项目编码和 SDK graphCode 组合并规范化。找到相同 keySlug 只表示有候选定义；更新必须同时匹配项目 ID／编码、SDK 定义权威、SDK_SYNC 创建渠道及 `extra.sdkGraph` 中的 source／projectCode／graphCode。缺失或损坏的来源证据、规范化后的异源碰撞和人工／系统定义都拒绝覆盖；不猜测旧记录归属，也不自动改名。相同请求内的重复或碰撞编码在写入前拒绝。

预览和实际同步共用草稿校验与来源规则；预览只返回 WOULD_CREATE／WOULD_UPDATE，不写草稿、修订或引用索引。预览不是发布校验，也不预留 keySlug。实际同步按 keySlug 固定顺序锁定已有 Workflow，再重新检查来源，响应仍按原请求顺序返回。所有草稿更新与引用索引属于同一批事务；唯一键竞争明确返回冲突，后续失败回滚整批，不返回部分成功。

SDK 维护当前工作副本，保持已有定义的生命周期状态、未由 SDK 声明的运行默认设置及已发布版本快照。更新使用锁定后读取的当前修订，不能把并发发布后的 ACTIVE 改回早先读到的 DRAFT。`extra_json` 保留当前顶层其他键，只替换 SDK 同步信息；旧 `previousExtraJson` 递归包装不再保留或继续嵌套，其中埋藏的历史键不做兼容恢复。正式发布历史仍由 Workflow Version 保存。

[SDK 同步持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/registry/RuntimeSdkWorkflowSyncPersistenceTest.java) 使用实际 MyBatis、Spring 事务及 SQL 基线生成的 H2 Workflow／Version／引用表，覆盖来源冲突、并发发布与元数据、创建竞争、批次锁序、整批回滚、预览和发布快照保留。Capability 项目查询使用替身，H2 夹具移除了 MySQL 专用的排序规则和索引前缀；真实 MySQL 锁与唯一键竞争、Control／SDK 签名和跨服务同步未在本批验收。现有 SQL 基线满足本批，无新增表、列、索引或升级脚本。

### 2.2 公开编辑与管理视图

普通创建／更新、Studio 工作副本和版本管理经 [Workflow 管理服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowManagementService.java) 进入所属模块。公开 Controller 不再接收或返回 Entity，列表、详情、分页 records 和版本历史使用不可变视图；原字段名、分页结构、版本快照、画布、发布人、发布时间和备注保留。执行专用的发布视图继续独立，不与管理历史合并。

`POST /api/workflows` 创建 USER／STUDIO／DRAFT 草稿。ID、创建／更新时间和删除资格不属于写入命令，发送这些字段不会改变服务端值。`definitionAuthority`、`creationChannel`、`status` 可回传当前值，但只能作为一致性声明，不能赋予其他来源或直接制造发布状态；普通编辑不再提供直接改生命周期状态的隐式入口。

普通 `PUT /api/workflows/{id}` 和 Studio 保存均必须传 `baseRevision`。所属事务先锁定并重读 Workflow，再检查来源和修订；SDK、SYSTEM 以及 AI_QUICK_ACCESS 定义拒绝人工编辑。USER／AI_CODING 定义可以在 Studio 继续编辑，保留原创建入口。过期修订沿用 409／`WORKFLOW_WORKING_COPY_CONFLICT` 与当前 ETag；缺失修订、只读定义或试图改写所属字段返回结构化 400。草稿与引用索引同事务提交，发布后的编辑保留生命周期状态与历史快照。

删除仍遵循草稿且无 Agent 引用的既有规则，先锁定 Workflow 后清理所属记录。发布／回滚保留原事务及可信操作人要求。项目权限和跨服务身份校验仍由原 Control／Runtime 入口承担，本次没有新增授权模型。GraphSpec 候选校验在 Workflow 内构造隔离候选；尚未保存的新候选也使用请求指定的默认模型，不修改已保存定义。

[公开管理持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowManagementPersistenceTest.java) 通过 MockMvc、实际 MyBatis/H2 与 Spring 事务验证 JSON 绑定、编辑归属、并发修订、失败回滚和历史响应。发布校验／能力固定端口使用替身，未替代真实 MySQL、Control 签名或跨服务验收。现有 SQL 基线满足本批，没有新增 DDL 或升级脚本。

### 2.3 Studio 工作副本异步结果归属

Studio 的运行校验由 [工作副本校验 composable](../../ai-admin-front/src/views/workflow/composables/useWorkflowStudioRuntimeValidation.ts) 维护。请求同时绑定当前文档实例、路由、请求序号、图、默认模型和编辑代次。重新加载同一 Workflow、切换路由或销毁页面立即使旧请求失效；切走后再切回相同 ID，也不能接收此前结果。路由已经切换但新文档尚未加载时，不得把旧图提交到新 Workflow 的校验入口。原有画布同步、服务失败提示和显式重试保留。

保存与发布的版本冲突确认属于发起操作的工作副本。用户在弹窗出现后切换 Workflow 或重新加载文档，旧确认不得重新加载并覆盖当前内容；发布冲突还校验编辑代次。覆盖操作不接受弹窗默认焦点，必须显式选择；取消或 Escape 保留本地输入。服务端修订检查仍是最终并发边界。

本批浏览器检查使用模拟的 503／409 和延迟响应，覆盖键盘重试、重复校验保护、旧响应、旧保存／发布确认及 768 像素视口。未向后端保存或发布业务数据，不能据此宣称真实跨服务发布已经验收。

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

发布与回滚命令必须携带调用方已读取的 `baseRevision`。Runtime 在同一事务内锁定 `runtime_workflow` 行，校验修订、校验发布内容、切换唯一活动版本、推进修订并追加 `runtime_workflow_release_event`。过期或缺失修订不得通过省略参数绕过；并发命令只能有一个接受同一修订。

管理端发布/回滚的操作人来自 Control 已验证平台会话，经精确请求体 HMAC 传递到 Runtime，请求体不接受手填发布人。平台操作记录为 `platform:<userId>`；页面工作台与 AI Coding 服务内交付记录其服务端工作流身份，不把请求中的显示名称当成人工审计身份。

回滚表示重新激活选定的历史发布版本，保留当前编辑草稿；原发布人、发布时间和发布快照保持原值，操作人和回滚时间另记新事件。历史版本按其发布快照中的默认模型和业务形态重新校验，不能借用当前草稿的配置通过校验。活动版本超过一个属于需修复的数据异常，运行查询不得静默选第一项。

Supervisor、MCP 和 Automation 通过 Workflow 所有的 `RuntimePublishedWorkflowSnapshot` 解析发布执行配置。节点显式模型优先，其次为发布默认模型；发布默认值明确为空时不能从调用参数补入模型。损坏或缺少执行配置的快照拒绝执行，重新发布后恢复。

发布版本的输入输出 Schema 只能从发布快照与发布 GraphSpec 解析，缺少 Schema 时使用通用输入约束，不能读取当前草稿。评测目标快照的文档 `schemaVersion=2` 保存完整发布配置，并以 Workflow ID、发布版本 ID 和工具名共同定位绑定；同一 Workflow 的不同固定版本不能相互覆盖。旧评测快照需要重新捕获，不能用当前草稿补齐历史配置。

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

`runtime_workflow_capability_reference` 是 Workflow 所有的可重建引用索引，不参与执行语义。定义保存、发布与删除须同时维护索引；发布或审计失败时索引也回滚。已有库先执行 [引用索引升级](../../sql/upgrade-20260905-workflow-reference-index.sql)，Runtime 启动时补齐缺失索引。索引缺失、草稿修订不匹配或图无法解析时，能力影响证据为 `PARTIAL`，不能作为零引用的依据；历史发布版本的固定引用继续参与查询。

历史 `upgrade-20260722-workflow-semantics.sql` 已并入 GitHub 基线并从当前树清理。早于 `ae9e1ce6` 的开发/测试库必须先备份，再从对应 Git tag 获取这份破坏性迁移或按当前 `initV2.sql` 重建；当前合并升级不重复承载旧 Workflow 数据转换。

不得通过在 Runtime 中增加旧字段读取、别名路由或隐式转换来延长旧数据生命周期。
