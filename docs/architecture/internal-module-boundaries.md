# 服务内模块边界

五个物理服务保持稳定部署边界；架构优化优先在服务内部建立单向模块依赖，而不是继续拆服务或拆库。当前第一批治理聚焦体量和依赖最复杂的 `reachai-control-service` 与 `reachai-runtime-service`。

## 规则

- 顶层 Java package 是服务内模块声明。新增顶层 package 必须先登记到 `scripts/internal-module-boundaries.json`，说明其职责和依赖方向。
- 模块可以依赖另一个模块公开的 application port、只读 contract 或 client facade，不得直接引用另一模块的 `persistence` package、Mapper 或 Entity。
- 新增任意长度的循环依赖均失败，包括在已有强连通分量内增加一条边。`allowedCycleEdges` 逐边记录当前债务；移除依赖后必须删除对应 allowance，禁止回流。
- 检查范围包括显式 import、通配 import、静态 import 和完整类名引用。持久化类型根据持久化包、`@TableName`/`@Mapper` 等注解、`BaseMapper` 等父类型及其继承关系识别；不再依赖目录名是否包含 `persistence`。检查器忽略注释与字符串示例；动态反射和 SQL 表所有权仍需独立检查。
- 产品模块需要接收基础设施事件时，由基础设施 owning module 定义 port 和不可变事件 contract，产品模块实现该 port；基础设施模块不得反向导入产品 application service。
- 服务间仍遵守 [Internal API Contracts](./internal-api-contracts.md) 与 [Service Table Ownership](./service-table-ownership.md)，服务内模块化不改变表 owning service 和公共路由。

## 当前债务基线

Workflow 定义服务消费 [删除引用契约](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDeletionReferences.java)，Agent 读取自己的绑定表并实现契约，保留草稿、停用和历史配置中的全部留存引用。这个范围与 Agent 的已发布绑定证据分开：已发布查询为空不能作为允许删除的依据。能力引用和页面发布汇总位于内部接口组合层；挂接协调实现 Workflow 自有挂接接口，并消费 Workflow 管理视图。Agent 继续单向消费 Workflow 发布目录和执行契约。原 Agent/Workflow 环边豁免已删除，禁止 `workflow -> agent` 回流。源码依赖图零循环不等于全部 Spring Bean 或分布式调用已验收。


`scripts/internal-module-boundaries.json` 是唯一机读基线。第一批已移除 Control 的 `managed -> aicoding` 反向依赖：Managed Execution Inbox 只负责可靠接收、幂等、租约和重试，通过 `ControlManagedExecutionInboxProjector` 交给 AI Coding 投影；AI Coding 继续单向依赖 Managed Execution contract。

Control 的 Page Workbench 通过自有 `PageWorkbenchObservationPort` 读取 Platform 会话、页面操作、真实对话和 Trace 的不可变观测快照；Platform 负责 Mapper/Entity 查询和适配。Project、Model、Runtime 的远程访问同样经 Page Workbench 自有 port 进入，由 `config.pageworkbench` 组合根绑定具体 HTTP client。工作台专用 Feign 接口显式注册，保留类型化响应及 270 秒读取超时。共享 `client` 不再依赖工作台 DTO 或应用端口；`pageworkbench -> platform/client/config` 与 `client -> pageworkbench/config` 均禁止回流。

AI Assist 的 AI Coding access 请求契约已归属 Capability client，不再由 client 反向引用 Controller DTO。`RuntimeTrustedAgentExecutionGateway` 则从 `client` 迁回 `runtime`：它负责可信信封、流式转发和个人记忆观测编排，底层内部 HTTP client 仍留在 `client`。因此 `client -> aiassist/context/runtime` 均已禁止回流。

Control 的 `model` 模块仅承载 [模型实例配置 BFF](../../reachai-control-service/src/main/java/com/enterprise/ai/control/model/ControlModelInstanceController.java)：为已发布 Agent 的正常模型配置提供列表、详情和创建入口。它单向消费 `identity` 的平台会话/项目授权与 `client.model` 的 Model owner API，不读写 Model 表、不解密连接凭据、不改变 Model owner 或扩大为完整模型平台；模块登记不增加循环或持久化引用豁免。

Runtime 的 `execution` 保留 GraphSpec 执行、会话清理、业务记忆 hydration、运行生命周期端口、审批恢复和交互过期规则。Agent 执行编排与 `SupervisorRuntimeAdapter` 归属 `supervisor`，不再让通用执行模块依赖 Agent 配置视图。A2A 通过 `RuntimePublishedAgentExecutionPort` 调用固定版本，事件回调使用执行模块的 `RuntimeAgentExecutionEventSink`；审批授权值归属 `RuntimeSupervisorApprovalPort`。A2A 持久化 binding 在进入 Supervisor 前转换为不可变快照。共享 `USER_INPUT` 语义位于 `graph`，Workflow 发布校验和执行处理器不再互相引用。AgentScope 模型桥接、流式诊断和 answer phase 归属独立的 `agentscope` 基础模块。`execution -> agent/memory/runops/supervisor/workflow`、`a2a -> supervisor`、`workflow -> supervisor` 均禁止回流。

前端 Workflow Studio 保留画布编排 shell，评测、源码编辑、项目 API 查询模板、发布门禁和会话式调试台归属 `views/workflow/studio-overlays/`。状态与业务调用继续由既有 composable 管理，覆盖层只接收显式状态并上抛业务事件；调试台仍保留会话恢复、人工交互、节点轨迹、Trace 回放和 ACTIVE 发布版本对照。`scripts/check-workflow-studio-structure.mjs` 限制 shell 体量并禁止这些实现重新内联回 `WorkflowStudio.vue`。

本轮同时退役了三类已确认无调用价值的实现：RunOps 详情页中被常量永久隐藏的旧诊断视图、可信 Agent 流式网关中无调用方的签名辅助方法，以及 Control/Runtime 中只用于把旧英文 Page Copilot 默认值改写为中文的历史数据迁移分支。当前创建、编辑、发布、Workflow-as-Tool 附加、RunOps 调查工作台和公开 API 路由均保留；公共入口中的 Compatibility Controller 是五服务拆分后的正式 Control facade，不属于可删除旧路由。

截至 2026-09-07，增强检查下的当前结果：

| 服务 | 强连通分量 | 分量内依赖边 | 直接双向依赖 | 持久化引用例外 |
| --- | --- | --- | --- | --- |
| Control | 0 | 0 | 0 | 0 |
| Runtime | 0 | 0 | 0 | 0 |

这里统计的是服务内模块依赖，不能等同于跨服务直接访问表。旧检查只识别显式 import 中的双向边与 `persistence` 包，曾报告 Control 0 个双向环、4 个持久化例外，Runtime 9 个双向环、0 个持久化例外；旧数字漏计，不能据此声称边界已清理完毕。

新发现的历史引用按“具体文件 + 具体类型”纳入机读债务基线，原有 `forbiddenEdges` 全部保留。Control 已删除增强扫描时登记的 35 条环边、5 组双向依赖和 7 个持久化引用例外；跨 Controller 的异常响应绑定归属 `config.web`，项目 AI Coding 密钥检查归属 Identity，健康探针 Feign 配置归属 HTTP client。原有路由、错误响应范围和认证规则保留。这里的零循环是源码模块依赖图结果，不等同于所有 Spring Bean 或分布式调用均已全面验证。

Runtime 的 `configuration` 模块只负责共享配置值与绑定，保留 `reachai.runtime.context-engineering` 的配置键、默认开关和限制；记忆存储不再依赖 Supervisor 组件注册配置。`identity` 负责不可由业务参数构造的可信执行身份，`execution.policy` 负责受控评测运行策略。Capability 的身份清洗、评测策略写入和精确字节签名调用编排放在 `execution.capability`，HTTP client 保留传输和签名基础设施。新增禁止边约束这些方向，移除 19 条不再处于循环中的边豁免。

Trace 的日志和 Span 查询由 [RuntimeTraceQueryService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceQueryService.java) 统一维护，通过 [RuntimeTraceRecordQuery](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceRecordQuery.java) 返回不可变值。RunOps 查询不再持有 Trace 的 Mapper/Entity；最近运行列表查询归属 RunOps，兼容 Trace 接口的分页规则与通用 RunOps 分页规则各自保留。Trace 因此退出循环组，新增 `trace->runops` 禁止边；[持久化与组装回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/api/RuntimeTraceRunOpsQueryPersistenceTest.java) 覆盖真实 MyBatis 查询、顺序、筛选和视图隔离。

[RuntimeRunSnapshots](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeRunSnapshots.java) 定义写入 RunOps 的执行事实，生命周期服务不再接收外部模块的配置、Workflow 版本和 Skill 绑定实体。版本身份、调用目录和审计字段在调用方选定后冻结；RunOps 继续负责身份裁剪、内容脱敏与运行状态持久化。

远端 Agent 绑定归属本地 Agent 配置，其表名及 owning service 保持，配置版本复制、保存和删除由同一模块维护。[RuntimeAgentRemoteBindingReader](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentRemoteBindingReader.java) 负责配置归属与固定绑定校验，A2A 负责协议白名单、媒体类型、超时和 Control 传输。[数据库回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/agent/RuntimeAgentRemoteBindingReaderTest.java) 覆盖跨 Agent、跨版本、DRAFT/ARCHIVED、禁用绑定、排序和返回值隔离。A2A 因此退出循环组；禁止 `agent->a2a` 返回。

页面工作台通过 [RuntimeAgentPublishedWorkflowToolQuery](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentPublishedWorkflowToolQuery.java) 读取经归属、活动版本与启用状态校验的 Agent 绑定，通过 [RuntimeWorkflowRunMetricsQuery](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeWorkflowRunMetricsQuery.java) 读取运行统计。Workflow 保有自己的页面绑定与发布信息，移除 6 个外部持久化类型引用。统计实现是 RunOps 的只读查询模型，有意联合 Runtime 所有的根运行与 Trace Span 表；持久化引用例外数只衡量类型引用，不表示跨模块 SQL 已全部消除。[归属与筛选回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/agent/RuntimeAgentPublishedWorkflowToolReaderTest.java) 和 [统计回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/runops/RuntimeWorkflowRunMetricsReaderTest.java) 使用真实 MyBatis/H2，覆盖错误配置归属、30 天窗口、状态归一和嵌套调用。

工作台执行验收通过 [RuntimeRunQuery](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeRunQuery.java) 读取根运行事实，通过现有 Trace 接口的 `findSpans` 读取不可变 Span；不再直接持有 RunOps/Trace 的 Mapper 或 Entity，也不为验收读取 Tool 调用内容。Workflow 负责版本、页面绑定与节点证据归属判定：只有精确版本的成功 Workflow 调用及其版本身份一致的直接子节点可作证据。[验收回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/workflow/RuntimePageWorkbenchExecutionReadinessServiceTest.java) 覆盖跨调用、失败和孤立调用、节点版本矛盾及损坏元数据；[查询回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/api/RuntimeTraceRunOpsQueryPersistenceTest.java) 验证真实 SQL 的身份筛选与视图隔离。

RunOps 的 [诊断计算器](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeRunOpsDiagnosticsCalculator.java) 只消费已有运行视图，负责失败聚类、版本归因和统计；数据库查询、参数筛选及视图组装保留在查询服务。[失败判定策略](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeRunOpsFailurePolicy.java) 同时用于详情和诊断，区分根运行生命周期与 Span 状态，支持现有生产者的等待审批、业务终态和超时值。版本统计的单位仍是包含该版本的根运行，同一根运行多次调用相同版本只计一次；耗时和 Token 使用根运行中已有的遥测，缺失样本不填造值。诊断与单条详情复用视图组装，候选根运行只筛选一次，子证据按候选 Trace 批量读取。[独立计算回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/runops/RuntimeRunOpsDiagnosticsCalculatorTest.java) 验证版本归属、去重与缺失样本口径。

RunOps 诊断最多选择 100 条根运行，通过 Trace 的 `findRecordsByTraceIds` 一次查询 Span、一次查询 Tool Call，再由 [Guard Mapper](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeGuardDecisionLogMapper.java) 批量读取每条 Trace 最早的 500 条 Guard 记录。非空候选集合固定为 4 次 SQL 查询，空集合只有 1 次根查询。Guard 的限制放在各个分支内部，合并后再按 Trace、创建时间和 ID 排序；采用 [MySQL 5.7 的 UNION 排序与 LIMIT 规则](https://docs.oracle.com/cd/E17952_01/mysql-5.7-en/union.html)，避免把原有的每条 Trace 上限错误改成整批上限。这里减少的是数据库查询轮次，尚未证明 MySQL 查询计划或生产耗时。[真实 SQL 回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/api/RuntimeTraceRunOpsQueryPersistenceTest.java) 在 H2 中记录 JDBC 查询数与返回行数，覆盖 1/3/100 条运行、跨 Trace 隔离、单条/批量一致性、空集合与单条 Guard 记录过多的情况。

Automation 使用 Agent 所属的 [固定发布配置查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentPublishedConfigQuery.java) 和 Workflow 所属的 [执行目标查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowExecutionQuery.java)，共移除 16 个外部 Mapper/Entity 引用。所属模块负责启用状态、精确版本归属和可执行发布状态；Automation 保留项目边界、目标元数据指纹和错误码映射，并在实际执行前再次校验。Agent 发布执行共用 ACTIVE/ARCHIVED 状态规则，空值和未知值不能绕过；评测仍可使用 DRAFT。[真实持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/api/RuntimePublishedTargetQueryPersistenceTest.java) 验证历史版本固定、停用目标、错误归属、空版本及不可变元数据。这里是执行前校验，不提供执行中停用的实时取消保证。

Automation 的交互终止改为事务编排：复用 execution 所属 [会话服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowInteractionSessionService.java) 的条件取消命令，由 Trace 所属 [等待 Span 终止服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceSpanTerminationService.java) 关闭等待证据。会话修订号在条件更新中原子递增，只有成功更新才写事件；两个所属模块命令加入 Automation 的同一事务，任一步失败均回滚。终止原因仍归 Automation，不把其产品错误码或身份策略下沉为通用模块常量。移除 6 个外部持久化引用；真实 Spring/MyBatis/H2 回归验证幂等、隔离、失败回滚，以及另一连接推进修订号或抢占恢复后的条件更新。

AI Coding 模块内部由 [交付服务](../../reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/application/AiCodingTaskDeliveryService.java) 承载 Artifact 和验收命令，任务门面负责公开响应；[状态写入组件](../../reachai-control-service/src/main/java/com/enterprise/ai/control/aicoding/application/AiCodingTaskStateChanges.java) 统一调用既有状态机、乐观锁、审计和终态问题关闭，并要求已有调用方事务。目标描述与 JSON 规范化单独复用，内部返回值仍留在本模块，不新增跨模块持久化契约。验收必须检查同一必需 key 的全部结果，不能依赖返回顺序覆盖失败。[真实事务回归](../../reachai-control-service/src/test/java/com/enterprise/ai/control/aicoding/application/AiCodingTaskDeliveryPersistenceTest.java) 验证保存点与外层事务的不同回滚范围；HTTP 远端副作用不在本地保存点保证内。

Runtime 剩余 61 个例外主要集中在 Agent 配置、API 响应、运行生命周期、Automation 和评测持久化模型的交接。它们是后续应替换的实现，不能作为新增代码的范例。基线按具体边和具体引用递减，禁止用另一处新例外抵消已消除的例外。

本轮已收口的接口：

- `PlatformPrincipal`：认证会话和 Bearer 身份解析只返回 ID、用户名、显示名的不可变副本；角色及权限列表也防御性复制，持久化用户与凭据字段留在 Identity。
- `AiCodingReusableTaskQuery`：AI Coding 负责可复用状态和按主目标、任务类型、执行器筛选，通过一次关联查询确定任务 ID；RunOps 只使用应用接口，不再逐目标查询另一模块的任务表。
- `McpPublishedReferenceQuery`、`A2aPublishedReferenceQuery`：各自模块读取发布修订并返回不可变引用证据；能力治理不接触对应 Mapper/Entity。MCP 保留可达的历史修订，A2A 按当前修订 ID 批量读取。
- `RuntimeAgentWorkflowUsageQuery`：Agent 模块负责 Workflow 绑定与已发布配置查询；Workflow 删除检查批量判定引用，能力影响分析只读取活动配置或外部明确固定的历史配置。
- `RuntimeWorkflowExecutionQuery`：统一批量读取并检查 Workflow 与固定发布版本，Agent 与 Supervisor 共用；执行视图不随源 Entity 修改而变化。
- `RuntimeWorkflowToolCatalogQuery`：Workflow 模块拥有 Agent 配置设计期的当前发布版本与固定契约读取，Agent 保存、发布和目录展示只接收不可变视图。版本必须归属对应 Workflow，缺失固定版本不能回退到当前工作草稿；发布同时验证 Workflow 当前可用。批量查询保留已有版本选择顺序，每次 SQL 最多 500 个目标 ID。
- `RuntimeAgentRemoteBindingQuery`：Agent 配置模块拥有远端 Agent 绑定，执行上下文与 A2A 委派只接收不可变视图；委派前在配置所属模块校验 Agent 归属、固定 ACTIVE 配置版本和启用状态。评测快照 V2 保留每个绑定的发布配置，按 Workflow、版本、工具名共同匹配重放目标。
- `GraphSpecToolContract`：纯图语义层负责 TOOL 引用解析与发布契约固定值检查。目录实时状态与发布接纳仍由 Workflow 的 `RuntimeCapabilityContractPins` 负责，执行层不反向依赖 Workflow 管理。
- `SupervisorPageQueryPolicy`：独立承接只读页面查询的意图限制、分页与枚举参数解析、必填参数和参数映射检查；Supervisor 保留调用编排和运行状态。
- `AiCodingTaskProtocolGuideFactory`：只接收执行器标识、结果契约和脱敏器，生成客户端协议说明与示例；不读取任务表、不迁移任务状态。

Knowledge 的 `DefaultKnowledgeRetrievalEngine` 承接召回、合并、重排、结果补充和命中记录，管理端检索测试与 `KnowledgeRetrievalCore` 共用该实现；`KnowledgeServiceImpl` 保留目录、文件、Chunk 和运营管理。知识库查询及配置校验分别归属 `KnowledgeBaseLookup`、`KnowledgeBaseSettings`。历史调用方仍可通过管理服务查询知识库，不能据此声称所有旧检索入口已统一。

Capability 的 `CapabilityCatalogStateCodec` 明确评审前状态中可回滚的字段。数据库锁、目录写入、SDK 来源绑定和更新时间仍由 Registry 接纳命令维护；来源身份不能从快照恢复。快照序列化失败直接中止操作，避免因空快照跳过评审前状态检查。

Studio 的 `useWorkflowStudioTraceProjection` 只读调试结果、RunOps 和旧 Trace，生成节点状态、回放摘要、已走过的连线和最近路由。显示类型、状态归一化及载荷格式化归属 `workflowStudioTrace`，画布、调试命令、抽屉和评测共用；展示代码不再为了格式化载荷依赖调试请求 composable。挂起状态在两类结果中一致显示等待，重复节点采用最近一次路由。保存和撤销历史使用 `serializeWorkflowEdge`，仅保留 `CanvasEdge` 声明字段及稳定样式；Vue Flow 的节点引用、测量坐标、选择状态和运行高亮只参与显示。读取或清除回放不会改变草稿快照。

Workflow 自有 `runtime_workflow_capability_reference` 反向索引。草稿保存、版本发布和删除与索引维护同事务；回滚保留历史版本索引，更新草稿修订对应的索引。查询按能力引用键读取索引并关联名称、版本状态，不再逐请求加载全库 GraphSpec。覆盖标记区分已索引空图、缺失或过期索引、无法解析的图；启动时分批锁定并重建缺失索引。部署前执行 [引用索引升级](../../sql/upgrade-20260905-workflow-reference-index.sql)，新库直接使用 `initV2.sql`。

能力影响查询仍有明确容量限制：Workflow 索引命中与聚合后的 Workflow/Agent 使用证据各最多 10,000 条；MCP 最多读取 10,000 个发布与 10,000 个修订，Agent 最多读取 10,000 个活动/外部固定配置及相关绑定。超限、索引缺失或过期、缺少所有者或图无法解析时返回 `PARTIAL`，依赖不可用时由聚合端返回 `UNKNOWN`。这些状态不能解释成“没有引用”。覆盖检查仍遍历窄元数据，MCP 与 Agent 仍有有界集合读取；尚未做生产规模延迟、吞吐量或 MySQL 查询计划验证。

Workflow 草稿的任务投递由 `RuntimeWorkflowDraftSubmissionService` 统一管理，Page Workbench 与 RunOps Trace 候选只负责各自请求转换和来源校验。Workflow 自有 `runtime_workflow_draft_submission` 保存请求摘要和成功应用修订；重复内容只读，新内容通过行锁与明确的 `baseRevision` 保护其他入口的编辑。事务使用 READ_COMMITTED，Workflow 行锁查询清理 MyBatis 缓存，避免等待期间提交的新修订被后续缓存读取覆盖。投递凭据和草稿一起提交或回滚，不使用可编辑的 `extraJson` 充当幂等依据。新增表按 [升级说明](../../sql/upgrade-20260906-workflow-draft-submissions.sql) 部署；不改变服务或模块所有权，不新增跨模块持久化引用。

Trace 的 [RuntimeTraceRootService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceRootService.java) 拥有根 Span 的创建、恢复与完成规则，Supervisor、Workflow 调试和 Automation 通过不可变请求与句柄调用，不向外暴露根 Entity。摘要脱敏工具迁入 Trace，等待过期与取消复用 Trace 自有终止服务。Automation 的两个 Trace Mapper/Entity 引用例外已删除；后续子记录整理进一步收回 Supervisor 与调试的 Trace 持久化引用。RunOps 继续拥有 Run 恢复的状态条件与空值清理，不引入 Trace 对 RunOps 的反向依赖。

RunOps 完成投影在 `RuntimeRunLifecycleService` 内共用开放状态筛选和明确的 SQL null 写入；Agent、Workflow、MCP 各自追加入口所需的身份、统计与摘要字段。Workflow/MCP 匹配读取时的状态，所有三类完成均不覆盖终态。Skill 版本快照改为字段级更新，避免旧 Entity 恢复并发取消前的生命周期。此批没有新增服务、公共路由或持久化引用例外，也未改变 Managed Execution 的独立投影职责。

Managed 的 RunOps 投影已移除两个跨模块持久化类型引用：Managed 只锁定并读取自有执行记录，构造不可变投影；RunOps 自有 `RuntimeManagedRunProjectionWriter` 校验冻结身份并维护 Run 表。两层均要求调用方事务，现有事件和 outbox 写入失败时连同投影回滚。当前数据库记录替代调用方旧 Entity，修复事件序号落后一条及旧快照覆盖终态的问题；Run 的完成后清理信息仍按 Managed 最新事实同步。边界与并发证据详见 [Managed Executor 持久化归属](./managed-executor.md#8-持久化归属)。

Managed 审批另移除四个跨模块持久化类型引用：execution 自有 `RuntimeManagedApprovalInteractionStore` 维护带完整归属条件的会话状态与事件，Managed 维护自有执行记录、审批决定和待办绑定。变更遵循执行记录到会话的加锁顺序，状态条件失败中止事务。批准必须先有 Runtime 决定，幂等回执保留原始请求、决定和命令序号；公共服务重放不重复写 outbox。该批完成时剩余 49 个持久化引用例外、8 条循环边。

Trace 子记录进一步移除六个引用例外：`RuntimeTraceEvidenceWriter` 接收不可变子 Span 和 Tool Call 事实，拥有 SQL 插入；Supervisor 与 Workflow 调试保留各自上下文和摘要转换。Skill 快照和完成元数据在 Trace 自有事务中锁定、合并并按字段更新，Workflow 调试续跑复用根状态规则。真实基线回归发现的业务终态列宽问题同步修正到 `sql/initV2.sql` 与当次升级脚本。该批完成时剩余 43 个持久化引用例外、8 条循环边，未新增模块、循环边或引用例外。状态、并发与升级证据见 [Trace 契约](./agent-supervisor-runtime.md)。

Supervisor 审批再移除六个引用例外：Supervisor 组装动作与卡片，execution 的 `RuntimeSupervisorApprovalService` 拥有会话和事件；交互列表通过不可变公开视图读取，不再依赖 Supervisor 或审批 Mapper/Entity。固定配置、租户与可信主体、状态事务、到期提交、决定归一和结果回放在同一 owner 内约束；具体契约见 [Supervisor 审批恢复](./agent-supervisor-runtime.md#supervisor-审批恢复)。新增禁止 `interaction -> supervisor` 的依赖边，该批完成时仍有 37 个持久化引用例外、8 条循环边。

MCP 的两个 Trace 引用也已收回：根记录通过 Trace 的 MCP 专用入口复用状态和字段更新约束，子记录使用现有不可变写入器。严格写入、可信作用域、脱敏、MCP 错误分类和节点上限保留；终态与并发元数据不再被旧对象整行覆盖。该批完成时剩余 35 个持久化引用例外、8 条循环边，MCP 的 Workflow 版本查询另行审查，详细行为见 [Trace 契约](./agent-supervisor-runtime.md)。

Supervisor Guard 审计移除两个持久化引用例外：Supervisor 策略服务组装决策证据，RunOps 的 `RuntimeGuardDecisionWriter` 拥有 SQL 写入，Trace 不再承担 Guard 日志。Agent 项目与可信租户决定审计归属，A2A 有独立目标类型，长决策值由 SQL 基线和当次升级支持。该批完成时剩余 33 个持久化引用例外、8 条循环边；具体行为与部署边界见 [Guard 审计契约](./agent-supervisor-runtime.md)。

Agent 自动附加移除三个持久化引用例外：Workflow 只编排自有校验与不可变请求/结果；Agent 命令拥有目标查找、默认创建、模型选择和配置发布。配置写入从 ACTIVE 建立独立快照，保留用户 DRAFT、其他固定版本与停用绑定，身份更新与发布共用 Agent 行锁并分别更新自有字段。并发首次创建、重复/不同附加以及 SQL 失败回滚由真实事务回归覆盖；具体行为与首发约束见 [自动附加发布边界](./agent-supervisor-runtime.md#自动附加-workflow-的发布边界)。该批完成时剩余 30 个持久化引用例外、8 条循环边。

### 路由评估的日志统计

路由统计已移除两个跨模块持久化引用，该批完成时剩余 28 个持久化引用例外、8 条循环边。

`RuntimeRouteEvaluationService` 只保留试点阈值和建议文案，经 Trace 的 [统计查询接口](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeToolUsageStatisticsQuery.java) 获取不可变计数。Trace 自有 Reader/Mapper 不再把日志 Entity、调用入参、结果摘要或用户身份字段交给路由模块；召回字段在 SQL 内转换为是否含非空白字符的布尔值，不返回其 JSON 内容。

统计保留既有口径：窗口裁剪为 1–90 天，按 `create_time >= from` 读取，未来时间记录仍包含在内；日志条数包含空白 Trace，Trace 去重则排除空白 ID。标签和 Trace 的大小写、重音与前后空格不归一化，使用 Java 键语义；召回判定沿用 `StringUtils.hasText`，SQL 使用相同的 `Character.isWhitespace` 字符集合，不把任意非空白字符串重新解释成有效检索命中。意图试点阈值仍为至少 1,000 条不同 Trace、2 类意图；领域试点为至少 500 条不同 Trace、3 个 Agent 名称，且召回 Trace 数大于 `traceCount / 3` 的整数结果。

读取采用 REPEATABLE_READ 只读事务，先固定当前最大日志 ID，再按创建时间与 ID 续页，每页最多 500 条；没有 OFFSET 或样本截断。换页清除 MyBatis 已处理的结果缓存，后续页异常直接失败，不返回完整样式的部分计数。现有 `idx_create_time` 与主键已在 SQL 基线中，本批没有新增索引或迁移。分页限制日志结果集的单批大小；精确去重仍需保存不同 Trace 键，内存随不同 Trace 和标签数量增长，不能声称总内存恒定。

[真实持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/route/RuntimeRouteEvaluationPersistenceTest.java) 覆盖 1/500/501/1000/1500 条分页边界、乱序 ID 与相同时间、跨页重复、全部 Java 空白字符、非空白 Unicode、较大召回字段、敏感列排除、事务缓存释放、分页期间独立提交的新增/删除/修改与后页失败。验证使用 MyBatis、H2 和真实 Spring 事务代理；MySQL 的执行计划、锁/隔离实现与生产规模延迟尚未验证。

### MCP 的固定 Workflow 快照

MCP 通过 Workflow 的 [固定版本查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimePublishedWorkflowSnapshotQuery.java) 取得不可变发布快照，不再读取版本 Entity/Mapper。Workflow 负责精确版本归属、发布状态与快照校验；MCP 负责发布项目授权、执行编排和协议错误码。当前定义不参与此查询，保留既有固定版本语义；要求当前 Workflow ACTIVE 的 Agent 执行查询仍为独立契约。25 项真实数据库行为记录在整理前后均通过，撤销旧例外的策略反例则先准确拒绝两处 import。该批完成时剩余 26 个持久化引用例外、8 条循环边。

### Agent 的执行快照

Agent 的 [执行上下文](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentExecutionContext.java) 返回不可变配置、Workflow 绑定与标准 Skill 绑定；执行、Supervisor、Skill 准备和评测不再导入三种 Entity。目录列表复制和独立的短只读快照事务共同保护一次解析，固定版本与评测草稿规则保持。18 个历史引用例外已删除，该批完成时剩余 8 个持久化引用例外、8 条循环边。当时的例外集中在两个 Workflow 公共 Controller、评测的 Agent 身份读取、SDK 图同步的 Workflow 读取和 Supervisor 的交互持久化类型。事务、H2 SNAPSHOT 夹具与连接池边界见 [执行上下文契约](./agent-supervisor-runtime.md#agent-执行上下文)。

### Agent 的稳定身份查询

Eval Dataset/Experiment 的四个持久化引用已收回。Agent 的 [身份查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentIdentityQuery.java) 返回不可变身份视图，执行与管理端共用 ID 优先、别名兜底的一条有界 SQL。查找和字段归属由 Agent 维护；评测维护创建存在性、已删除目标的历史过滤与目录规则，轻量读取不调用展示配置或发布查询。真实身份冲突、项目归属、过滤、参数绑定和回滚证据见 [身份查询契约](./agent-supervisor-runtime.md#agent-身份与引用查找)。该批完成时剩余 4 个持久化引用例外与 8 条循环边：两个 Workflow 公共 Controller、SDK 图同步的 Workflow 读取、Supervisor 的交互持久化类型。

### Workflow 等待会话返回

Supervisor 已删除对 execution 的 RuntimeInteractionSessionEntity 引用，只接收 [会话服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowInteractionSessionService.java) 的不可变 WaitingSession。创建与连续等待使用同一个内部写入实现，UI 从保存结果生成；会话归属和恢复检查以可信身份为准，独立于聊天记忆开关。实际事务与身份反例见 [Workflow 等待契约](./agent-supervisor-runtime.md#workflow-等待会话的归属与返回)。该批完成时剩余 3 个持久化引用例外与 8 条循环边，引用集中在两个 Workflow 公共 Controller 和 SDK 图同步。恢复状态提交与期限已由后续批次处理。

Registry 的 SDK 图同步已删除 WorkflowDefinitionEntity 引用。Registry 只解析项目、冻结协议文档并映射回执，Workflow 的 [SDK 同步所有者](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeSdkWorkflowSyncService.java) 维护来源、草稿及引用索引的批次事务，既有定义在锁定后重新校验来源，发布版本不参与修改。预览、冲突、状态保护、元数据和并发验证边界见 [SDK 图同步契约](./workflow-semantic-contract.md#21-sdk-图同步的定义归属)。该批结束时剩余两个 Workflow 公共 Controller 引用，已在下一批收回。

两个公开 Workflow Controller 现在只接收所属模块的编辑命令、返回不可变管理视图，分页 records 也不再间接携带 Entity。普通更新与 Studio 共用来源约束和锁定后的修订检查，候选校验归属 Workflow；版本查询、发布和回滚映射完整管理历史，保留原可信身份与事务。行为、协议变化和验证边界见 [公开编辑契约](./workflow-semantic-contract.md#22-公开编辑与管理视图)。增强检查下 Control 与 Runtime 的服务内跨模块持久化引用例外均为零；Runtime 仍有一个循环组、八条环边和两组直接双向依赖，不能据此声称所有职责和调用链已整理完毕。

### Workflow 恢复的事务所有者

[恢复编排](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeInteractionResumeService.java) 不再持有 Mapper，认领、事件、终态和连续等待都交给现有交互会话所有者；短事务用状态与修订保护写入，外部执行暂停调用方事务。会话重读、版本化提交/结果和根运行重放保护共同覆盖重复请求，不引入另一套会话写入路径。实际双线程、事务失败、完整重放与边界见 [恢复契约](./agent-supervisor-runtime.md#workflow-恢复的状态提交与重放)。跨模块例外仍为 3 个，循环边仍为 8 条；崩溃后的已认领任务恢复需要继续处理。

运行记录归属进一步收口：RunOps 只接收 execution 所有的 `AgentTarget` 五项目标事实，不再依赖 Agent 管理视图；新增 `runops->agent` 禁止边，Runtime 现有七条循环边、零持久化引用例外。根运行项目取自目标，租户与业务用户取自显式可信身份；Trace 子记录沿用创建或恢复时的根归属，完成回调不再清空或重写原用户。13 项数据库反例先失败，修正后新增 20 项及相关 92 项通过，其中实际持久化 54 项；完整 Runtime 1,379 项无失败、1 项既有跳过。首次全量中的四项旧用例只从请求体提供租户，已改为可信身份并保留伪造输入与原业务断言。边界与验证见 [运行记录归属契约](./runtime-audit-attribution.md)。

入口身份绑定归属 identity：Supervisor 使用 `WorkflowExecutionIdentity.forAgentExecution`，通过既有工厂保留来源、租户与主体规则，不再把 Automation／MCP 或无用户 Agent 降级成丢失租户的普通身份。执行项目仍取已解析 Agent，全局目标与 Debug／Composition 的空归属保留；服务主体不能因此获得业务用户 ACL。入口测试移除复制的转换实现，原 4 项扩展到 20 项，其中 19 项实际经过 Runtime、AgentScope、GraphSpec 和 MyBatis/H2 审计写入；8 项业务反例先失败，相关 151 项通过，完整 Runtime 1,395 项无失败、1 项既有跳过。模块图仍为七条循环边、零持久化引用例外；验证边界见 [入口身份契约](./agent-supervisor-runtime.md#入口身份与执行身份)。

### Workflow 调试定义与运行记录

Control 的 `RuntimeDebugSessionGateway` 拥有六个调试会话接口的项目授权和请求签名，公共 Controller 只委托该入口。Runtime 的 `RuntimeDebugSessionOwner` 接收已验证的平台租户与用户，Store 先检查原所有者，再恢复回执或改变状态；异步执行显式传递该值，不能依赖请求线程。前端会话 composable 拥有账号与 Workflow 存储范围，调试命令 composable 通过同一范围和代次丢弃迟到结果并销毁旧传输实例。所有者验证不赋予 Debug 执行业务用户凭据。

Workflow 调试先从保存定义捕获目标和候选图；Studio 会话保存该不可变快照，交互续跑不重新读取可变草稿。单节点调试复用相同目标解析，未知 ID 不能凭候选图绕过；执行继续使用不可信 Debug 身份。RunOps 只接收目标与实际执行图的事实快照，引擎规则回到 Workflow 调试和 Automation 调用方。`runops->workflow` 已移除并禁止回流，该批完成时 Runtime 剩余六条循环边、一组直接双向依赖和零持久化引用例外。

14 项新增回归经过真实定义查询、GraphSpec、Studio 会话及五张基线表；10 项初始用例中 8 项业务失败，修正后相关 146 项通过。后续将新建和会话恢复分成明确入口：公开 debug-run 使用服务端新标识，持久化会话通过类型化事实恢复，执行 options 不再决定生命周期。再增加 5 项数据库反例，修正后相关 90 项通过，原有会话 CAS、取消、SSE 与 Trace 终态保护保持。边界见 [调试快照契约](./runtime-audit-attribution.md#workflow-调试快照)。

## 验证

Agent 编排随后归入现有 `supervisor` 模块：[执行应用服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/RuntimeAgentExecutionService.java) 与 [Supervisor 契约](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorRuntimeAdapter.java) 不再属于通用执行模块。RunOps 通过自有 `RuntimeRunReplayExecutionPort` 请求固定版本重放，由 `SupervisorRunReplayExecutor` 调用同一应用服务；输入替代、原运行关联和版本固定规则保留。`execution->agent` 与 `runops->supervisor` 加入禁止边，未增加模块或持久化引用例外。四条边退出循环组，剩余 `agent->workflow` 与 `workflow->agent` 仍需梳理；`runops->execution` 等正常单向端口依赖仍存在，不能把循环边减少误报为这些依赖全部消失。

调试服务的状态持久化已归属 [RuntimeDebugSessionStore](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/debug/RuntimeDebugSessionStore.java)，编排服务不再持有 Mapper。短事务负责接纳、按原检查点认领、保存回执及投影，外部 Workflow 执行位于事务之外；完成回执的恢复不重新执行流程。Store 同时拥有每次执行期限，后台扫描只交回候选标识，由同一存储 owner 在行锁内收敛无回执状态，不能自行重跑 Workflow 或写 Trace／RunOps 的表。执行结果未知与独立审计证据的关系见 [会话提交契约](./runtime-audit-attribution.md#调试会话的提交与恢复)。该整理未增加顶层模块、循环边或持久化引用例外。

```powershell
node scripts/check-internal-module-boundaries.test.mjs
node scripts/check-internal-module-boundaries.mjs
node scripts/check-runtime-kernel-structure.test.mjs
node scripts/check-runtime-kernel-structure.mjs
node scripts/check-workflow-studio-structure.mjs
node scripts/verify-architecture.mjs
```

完整 Maven、前端、SQL、运行服务和浏览器验证仍按对应变更范围分别执行；架构脚本通过不能替代业务验收。
