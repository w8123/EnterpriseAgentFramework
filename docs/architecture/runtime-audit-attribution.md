# Runtime 运行记录的归属

本契约覆盖 `RuntimeRunLifecycleService`、`SupervisorExecutionTraceService` 与 Workflow 调试入口写入的根运行、根 Span、子 Span 和 Workflow-as-Tool 审计。执行授权继续使用 `WorkflowExecutionIdentity`；审计快照本身不授予权限。

## 创建时确定归属

- Agent 根运行的项目来自已解析的 Agent。`RuntimeAgentRunLifecyclePort.AgentTarget` 只携带 ID、名称、稳定键与所属项目，RunOps 不接收完整 Agent 管理视图。全局 Agent 的项目允许为空，不能从业务输入或调用者项目补齐。
- 独立已发布 Workflow 根运行的项目来自已选定的 Workflow 快照，发布版本保持固定。
- 租户只读取显式传入且 `projectTrusted` 的执行身份；业务用户只读取 `userTrusted` 的身份。Automation、A2A、MCP 的服务主体不因此成为业务用户。没有可信身份时，租户和业务用户为空。
- 业务输入中的 `projectCode`、`tenantId`、`userId` 不能覆盖上述归属。`appId` 继续作为调用上下文标签；未提供时以所属项目编码补齐。它不构成权限凭证。
- Studio 调试提供 Workflow ID 时，先查询保存的定义，即使请求附有候选图也不能跳过。ID、稳定键、名称、项目和引擎来自保存的定义；候选图优先于保存的图。全局 Workflow 保留空项目，未知 ID 在执行前拒绝。没有 ID 的候选图仍可调试，其显式项目只作声明标签，没有已保存目标身份。普通 `inputParams` 不能覆盖归属或赋予租户、用户身份，调试执行继续使用 `untrustedDebug()`。

## Workflow 调试快照

[RuntimeWorkflowDebugService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDebugService.java) 在调试前解析 `DebugDefinition`，包含目标、项目、规范引擎、实际候选图和所选模型。只有 ID 的请求也先读取执行图，再形成 RunOps 的图摘要。单节点调试使用相同解析规则。RunOps 接收 `RuntimeRunSnapshots.Workflow` 或已发布版本事实，不再导入 Workflow 的语义规则；引擎校验由 Workflow 调试和 Automation 调用方在创建运行记录前完成。

会话创建先校验 targetType，再捕获定义；`working_copy_definition_json` 保存服务端解析的快照。交互续跑只从已保存会话恢复该快照，不再查询最新 Workflow，也不接收公开请求中的“可信快照”标志。因此之后重命名、修改或删除原 Workflow，不会更换正在调试的图或所属项目。根和子 Span 使用同一范围，RunOps 保留创建时快照。解析目标用于记录真实归属，不代表完成了目标访问授权，也不将 Debug 提升为业务用户身份。

`/api/workflows/studio/debug-run` 每次创建新的运行，runId 与 traceId 由服务端生成，请求 debugOptions 中的同名字段不能指定或复用已有运行。调用者应使用响应标识查询运行记录。该入口的 entryNodeId 仍可指定新调试的起点，但不表示恢复旧 Trace；AI Coding 也使用这个新建入口。

持久化会话通过明确的 `startSessionDebug`／`resumeSessionDebug` 进入 Workflow。创建从图入口执行；提交经过会话状态和修订认领后，由会话所有者传入 `DebugContinuation`，包含已保存的运行标识和当前节点。执行选项剔除 runId、traceId、sessionId、entryNodeId 及旧提交控制字段，不再从 options 推断新建还是恢复。Trace 原有终态恢复保护保持，缺失 Trace 的最佳努力补记仍只使用已保存会话事实。

已有会话与审计记录没有回填。本批验证新建／恢复的记录边界，未验证用户之间的会话访问授权。

## 调试会话的提交与恢复

[RuntimeDebugSessionGateway](../../reachai-control-service/src/main/java/com/enterprise/ai/control/runtime/RuntimeDebugSessionGateway.java) 统一承接会话接口的 `workflow:debug` 权限、项目授权和 Control→Runtime 签名。创建使用嵌套 `workingCopyDefinition.workflowId` 查询保存的 Workflow 项目，不能信任请求根或候选副本中的归属；没有保存 ID 的候选图按声明的项目检查授权。后续查询和命令以已保存会话快照中的项目重新检查当前授权，提交、取消及流式恢复须先通过原会话查询。

Runtime 对全部 `/api/runtime/debug-sessions` 路径验证签名、精确请求字节、方法、路径及 nonce，只接受 Control 的 `PLATFORM_SESSION` 身份来源。创建把经过验证的租户和用户保存在 `owner_tenant_id`、`owner_user_id`，异步执行显式携带不可变所有者；当前 Control 的平台租户由服务端固定为 `default`。这些字段不是执行凭据，Workflow 仍使用 `untrustedDebug()`。查询在行锁内先比较租户和用户，再投影回执或处理超时；跨用户、跨租户、未知标识和旧无归属记录均返回 404。取消、认领和结果写入再次检查原所有者，不能由请求正文认领或更换。后台内部恢复不暴露用户读取入口。

[RuntimeDebugSessionStore](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/debug/RuntimeDebugSessionStore.java) 统一维护会话创建、认领、完成回执、状态投影和取消。创建先在短事务提交 RUNNING 行及固定定义，再调用 Workflow；调试编排暂停调用方事务，外层回滚不能抹去已执行会话。提交先解析原检查点，再按同一快照的修订号和节点认领，不能用新读到的修订执行旧状态。

执行完成分两次短事务写入：先把带 `completionSchema` 和认领修订号的结果回执提交到现有 result_json，再投影为状态、消息、检查点、步骤及普通结果摘要。投影异常后，读取或再次提交会先恢复已保存回执，不调用 Workflow。读取恢复在行锁内完成，多个读者不能重复应用；已提交回执优先于后来的取消，先完成的取消也不能被迟到执行结果覆盖。完成会话不会因取消请求改为 CANCELLED。

执行入口抛出异常时不再重新开放检查点；结果不确定的执行记录为 FAILED／DEBUG_EXECUTION_OUTCOME_UNKNOWN，已取消调用保持 CANCELLED。完成记录暂未写好时，异常携带 DEBUG_SESSION_COMPLETION_PENDING 和 sessionId，后续应恢复该会话。回执本身未能持久化或进程在写入前消失时，RUNNING／RESUMING 不允许再次执行；不能凭空恢复结果。

Store 在创建和每次恢复认领时保存独立的 `execution_deadline_at`，配置 `RUNTIME_DEBUG_SESSION_EXECUTION_TIMEOUT_SECONDS` 默认为 900 秒且必须为正数。等待状态和终态投影清除活动执行期限；原 `expires_at` 不承担本次执行期限，也未在本批增加等待会话过期清理。配置只影响新的尝试，调用方的 options 不能覆盖期限。

无回执（`result_json IS NULL`）的活动尝试到期后，由读取、完成提交或有界后台扫描收敛为 EXPIRED，摘要保存 `DEBUG_SESSION_EXECUTION_TIMEOUT`、`outcome=UNKNOWN`、`retryable=false`、`reconciliationRequired=true`。缺失期限同样关闭，使用 `DEBUG_SESSION_EXECUTION_DEADLINE_MISSING`。检查点、原节点及幂等载荷保留，顶层 UI 清空；同一幂等提交只能重读该结果。前端显示结果无法确认，并使历史交互保持不可操作。

完成回执提交、取消及超时检查都先锁定当前会话，再采样时间。已及时提交的回执即使超过期限才投影也仍优先恢复；迟到的成功或等待结果不能覆盖超时。扫描默认每 5 秒读取最多 100 个候选，逐条使用同一存储服务重新判断，旧候选不能关闭较新的尝试，单条失败不阻断余下候选。关闭后台扫描不会关闭读取和写入的期限保护；损坏或非回执的旧 JSON 不会被自动覆盖，需人工核查。

这是平台确认结果的期限。它不证明模型或外部调用已停止，也不将未知业务结果推断为失败。会话到期时，[RuntimeDebugExecutionLifecycle](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/debug/RuntimeDebugExecutionLifecycle.java) 必须加入 Store 的当前事务，通过各 owner 的端口关闭同一 trace 下仍开放的 Studio Workflow Run 和 Trace：Run 为 TIMED_OUT，未结束的 RUNNING／WAITING_USER／WAITING_APPROVAL Span 为 TIMEOUT，错误码说明平台未确认结果。已结束证据保持原样；会话、Run 或 Trace 任一步持久化失败，整笔到期事务回滚。该路径不调用 Workflow、不重发业务操作，需要结合运行记录和业务系统对账。

实际本地 Runtime 在首次执行 RUNNING 和提交续跑 RESUMING 两种状态下、距持久化期限至少 43 秒时被终止；两笔已进入的只读 HTTP 调用在进程退出后完成。恢复默认配置后，两会话均为 EXPIRED／UNKNOWN，Run 与 Trace 一致关闭，四次同键重放没有新增调用，47 张表原有记录不变，见 [Studio 执行中断验收](../../output/tasks/architecture-audit-20260905/studio-active-crash-notes.md)。此证据不覆盖客户 SDK 写入、跨主机故障或外部操作取消。

流式创建在会话提交后、Workflow 执行前发送 `session.created`，数据为当时的 RUNNING `SessionView`。此前的 `turn.started` 只表示流开始，不表示执行成功；接纳写入失败不能发布会话，发送接纳事件失败则取消已保留会话且不调用执行器。前端传输层收到标识就同步给宿主，完整接纳视图经 Studio 现有会话组件立即保存标识，不等待流结束，也不新增输入内容或凭据的浏览器存储。

收到成功 SSE 响应后，读流错误、提前 EOF、结束后的 GET 错误都不能触发 REST 创建或提交。正常 EOF 丢失业务终态时，可以通过原标识 GET 并恢复已保存的完成或等待快照；RUNNING／RESUMING 只呈现“执行结果尚未确认”，不会显示完成，也不会重新开放已认领的交互。对话层的失败状态在这里表示连接与结果确认失败，服务端执行状态保留在快照 metadata，不能据此宣称 Workflow 已失败。

调试面板中的“重试”查询原会话，重新打开也使用同一恢复入口。临时网络／服务错误保留标识；查询明确返回 404／410 时才清除当前账号、当前 Workflow 的存储引用。存储键使用 `workflow-studio-debug-session:v2:<encoded-account>:<encoded-workflow>`，只存标识，旧无账号引用直接丢弃。退出登录、切换账号或 Workflow 时清空内存状态、输入和传输实例；异步恢复、事件、版本查询、节点结果及轨迹均检查原作用域和代次，迟到响应不能写入新界面、删除另一账号引用或把旧输入重新发出。同账号切走再切回也不能接受之前的响应。浏览器隔离不替代服务端授权。

只收到部分事件或中止消费也保留当前账号已经接纳的标识。创建请求发送前，传输层生成一次尝试的 `idempotencyKey`，Studio 在相同账号／Workflow 存储键下以 `:creation` 后缀保存标识，不保存输入或完整请求。接纳事件从未到达时，通过 `GET /api/runtime/debug-sessions/by-creation-key/{key}` 查询；此查询的 404 可能是接纳尚未提交，因此保留尝试标识并允许再次查询，不能自动重发创建。取得会话后转换为会话 ID 引用。显式发起新调试才生成新标识。

Runtime 以可信 owner 与创建标识确定会话 ID，并保存规范化创建请求的 SHA-256 指纹。对象键顺序不影响指纹；数组顺序和请求内容保持语义。主键竞争决定唯一接纳者；同 owner、同标识、同内容只返回原会话，不再次执行，不同内容拒绝。无标识的旧调用仍各自创建。创建键查询继续验证 Control 签名、会话 owner 和当前项目权限。接纳提交后进程中断也不自动再次执行，未知状态由原执行期限处理；该保证不等于外部副作用恰好执行一次。

[持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/debug/RuntimeDebugSessionPersistenceTest.java) 使用实际 MyBatis/H2 和 Spring 事务代理，覆盖接纳失败、外层回滚、旧检查点、执行异常、投影失败、新服务实例恢复、提交后响应丢失、多读者和取消竞争。原会话与 SSE 测试也改为真实表更新，移除手工模拟 CAS 成功的实现。[期限回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/debug/RuntimeDebugSessionExecutionDeadlineTest.java) 另覆盖无回执超时、迟到结果、时钟与锁顺序、写入回滚、重复读取及扫描竞争。新库基线增加期限列及索引，已有库的 DDL 与执行说明见 [SQL 入口](../../sql/README.md)，当前开发库迁移已备份后执行并回读，应用层 MySQL 并发仍需单独验收。

[传输与恢复回归](../../ai-admin-front/src/conversation/transports/workflowDebugRecovery.test.ts) 覆盖接纳后断流、消费者提前结束、空流、遗漏终态、查询 404 禁重复创建、共享对话状态恢复及进行中快照。[客户端存储回归](../../ai-admin-front/src/views/workflow/composables/useWorkflowStudioDebugSession.test.ts) 区分临时查询错误与明确缺失。

## 拒绝、恢复和完成

执行入口把经过验证的身份显式传给拒绝记录端口。找不到 Agent 时，项目与 Agent 标识为空；已经解析的 Agent 使用其所属项目。原错误码、响应体和输入脱敏规则保持。

`RuntimeTraceRootService.Scope` 是根 Span 的不可变项目、租户、应用标签。创建后子记录使用该快照，恢复时从原根 Span 重建；已保存的空值也必须保留，不能用新请求或新定义补齐。最佳努力的根写入失败时，当前调用仍携带已确定的标签，后续证据不会退回读取输入中的身份字段。该快照不用于凭据解析或访问控制。

根运行完成只更新状态、结果、计数、耗时和运行元数据，保留创建时的项目、租户与业务用户。完成回调缺少身份不能清空原用户，新的身份也不能重写原用户。恢复与终态保护继续使用原状态条件，重复或迟到完成不能覆盖已结束的运行。

## 验证与边界

[数据库回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/runops/RuntimeAuditAttributionPersistenceTest.java) 从 `sql/initV2.sql` 加载三个实际表并通过 MyBatis 写入、回读；覆盖输入伪造、可信／非业务用户身份、全局目标、拒绝、已发布 Workflow、Studio 标签、恢复、空归属、根写入失败和完成时用户保护。H2 的 MySQL 模式不等同于实际 MySQL 验收。

本批没有修改 SQL、网络认证或凭据授权规则，也没有回填已有审计记录。Supervisor 的入口身份绑定已由后续的 [实际执行链回归](./agent-supervisor-runtime.md#入口身份与执行身份) 补齐：可信来源和租户不会因绑定 Agent 项目而丢失，服务主体不会成为业务用户。该验证仍不替代认证或 Agent 访问授权。

[调试快照回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDebugSnapshotPersistenceTest.java) 通过实际定义查询、GraphSpec、Studio 会话和 MyBatis/H2 的五张基线表，检查候选图、ID 单独调试、全局目标、未知目标、单节点、输入伪造、删除后续跑和结构化失败。新增入口用例覆盖等待／运行／结束状态的旧 Trace、重复客户端标识、新会话入口防跳过；断言旧运行和全部 Span 原样保留，正常会话续跑保持同一运行标识。外部模型、能力和控制服务使用替身；不替代实际 MySQL、浏览器或跨服务验收。
