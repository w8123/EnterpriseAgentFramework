# Agent Supervisor Runtime

本文是 ReachAI Agent 执行主路径、版本模型、Workflow-as-Tool 和 Page Bridge 跨路由协议的架构事实源。产品层说明见 [`../03-Workflow-Studio与Runtime.md`](../03-Workflow-Studio与Runtime.md)。

## Runtime 主路径

Agent 执行入口的应用编排由 `supervisor.RuntimeAgentExecutionService` 负责，配置解析仍属于 Agent，图执行和交互持久化仍属于 execution。`SupervisorRuntimeAdapter` 位于 supervisor，承载规划运行时的替换边界。A2A 仅依赖 execution 的固定版本执行端口及事件、取消契约；RunOps 通过自有重放端口调用。审批授权值由 `RuntimeSupervisorApprovalPort.PolicyApprovalGrant` 表达，字段和持久化 JSON 语义保持不变。这些接口不引入新的执行流程，也不改变身份信任规则。

```text
runtime_agent
  -> ACTIVE runtime_agent_config_version
  -> enabled runtime_agent_workflow_tool rows (each pins workflow_version_id)
  -> AgentScope Java 2.0.0 GA ReAct Supervisor
  -> pinned runtime_workflow_version GraphSpec snapshots
  -> answer + runtime_run + Trace/Tool/Guard child events
```

`RuntimeAgentExecutionService` 的新执行以 Agent 当前 ACTIVE 配置版本及其 Workflow-as-Tool 目录作为可执行工具集。审批恢复沿用审批时保存的配置版本；显式历史回放与评测也通过固定版本入口读取，均不重新选择当前版本。

### 委派工具的协议适配

[`SupervisorDelegationTools`](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorDelegationTools.java) 负责将固定 A2A 绑定和 Managed Executor 工具名转换为 AgentScope 工具说明、参数 Schema 和异步调用适配。A2A 的精确 Skill ID、输出媒体类型与续跑标识，以及托管执行的创建／查询参数在这里统一组装；系统提示中的绑定列表复用同一个解析方法。

该组件不选择可用工具、不读取持久化实体，也不调用远程服务。Supervisor 仅为已允许的工具注册执行回调，回调仍经过现有 RunState 的计划、可信身份、权限、审批、取消、评测和追踪流程。异步结果和错误原样交回 AgentScope，不在协议适配层订阅、重试或转成成功结果。

### 入口身份与执行身份

管理端通过 HttpOnly Cookie 发起 `/api/runtime/agents/execute`、`/detailed` 和 `/stream` 时，可选会话拦截器复用控制台的会话与 CSRF 校验。显式 Bearer 优先；提交无效凭证时拒绝执行，无凭证的兼容调用仍保持非可信身份。Control 从服务端已验证会话解析个人记忆所使用的同一主体，再通过 HMAC 内部入口执行；同步、异步流和会话清理共用该主体解析。客户端注入的信任字段仍被剥除。实际 MVC 注册与 13 项传输用例见 [Cookie 身份回归](../../reachai-control-service/src/test/java/com/enterprise/ai/control/runtime/ControlRuntimeAgentCookieAuthenticationTest.java)。

Supervisor 通过 identity 模块的 `WorkflowExecutionIdentity.forAgentExecution` 绑定执行项目，不再自行枚举和降级身份来源。项目取自已经解析的 Agent；来源、租户、主体标识沿用显式传入的服务端身份，并由既有身份工厂恢复对应信任规则。全局 Agent 的项目保持为空，不能从调用者项目或业务输入补齐。此绑定操作不负责授予 Agent 访问权限。

未传身份时仍是只有目标项目的 Agent 身份；显式 Debug／Composition 仍不具有可信项目或用户。Agent／Embed 的可信用户保持；Automation／MCP／A2A 主体以及无用户 Agent 不具有业务用户 ACL，即使正文、metadata 或模型工具参数包含 userId。请求项目不匹配的既有策略拒绝继续保留。

来源必须传到实际 GraphSpec 执行器：Automation 遇到阻塞交互返回 `AUTOMATION_INTERACTION_REQUIRED`，MCP 身份返回 `MCP_WORKFLOW_INTERACTION_UNSUPPORTED`，均在产生等待结果之前拒绝；只展示结果的交互仍可执行。Supervisor 原有“必须有可信业务用户才能创建生产等待会话”的检查也保留。保留 MCP 身份不意味着增加新的 MCP Agent 对外入口。

[入口回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/identity/WorkflowTrustedIdentityEntryTest.java) 使用实际 Runtime 入口、AgentScope、GraphSpec、RunOps／Trace 与 MyBatis/H2，检查来源、租户、主体、目标项目、知识检索用户参数和交互拒绝码。它替换了原测试中复制的 Supervisor 身份转换函数。目标解析、Guard 写入、等待会话端口与模型／知识服务使用夹具；认证签名、Agent 授权、真实 MySQL 和跨服务访问仍须分别验收。

### Agent 身份与引用查找

Agent 的 [身份查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentIdentityQuery.java) 返回既有不可变身份视图，不加载配置、绑定或管理端展示数据，也不要求目标启用或已经发布。ID 与另一行的 key_slug 同时匹配时，稳定 ID 优先；共用 Mapper 使用绑定参数、明确的优先排序和 `LIMIT 1`，一次查询最多返回一个身份。执行解析、评测引用与管理端的 ID/别名入口遵循同一规则；管理端仍按原逻辑补充展示配置。

评测自己保留使用规则：创建数据集必须有真实目标，targetId/projectCode 取 Agent 事实；列表查询允许目标已删除，查询不到身份时仍按 trim 后的原目标值过滤历史记录。空白过滤值不查询 Agent；数据集的归档过滤、实验的历史状态和租户过滤继续归评测模块。此查询不替代执行时的停用检查或固定配置校验。

[19 项真实数据库用例](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/eval/RuntimeEvalAgentIdentityPersistenceTest.java) 覆盖 ID/别名冲突的五类入口、停用/未发布目标、项目归属、历史数据、改名、空白过滤、绑定参数、单条查询、身份值脱离持久化对象及数据集事务回滚。原流程在创建、两个列表和执行解析四个入口均复现错误身份选择；修复与既有管理端优先级对齐。验证使用 MyBatis/H2 和 Spring 事务代理，配置/执行与 RunOps 依赖按夹具范围替换；真实 MySQL 排序规则、执行计划和跨服务调用未验收。

### Agent 执行上下文

[上下文解析器](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentExecutionContextResolver.java) 的新执行、固定发布版本和评测入口均开启独立的只读 REPEATABLE_READ 事务，读取已提交的 Agent 身份、配置、启用的 Workflow/标准 Skill/远程 Agent 绑定与固定 Workflow 目标。评测已有写事务时暂时挂起该事务，读取完成后恢复；同一外层事务尚未提交的配置编辑不会被捕获。独立读事务会额外占用一条连接，连接池容量应覆盖并发评测与其外层事务。执行、Skill 下载和远程调用在该短事务结束后发生。

配置、Workflow 绑定和标准 Skill 绑定分别使用 Agent 所有的不可变快照，[执行上下文](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentExecutionContext.java) 与 Supervisor 请求对目录列表做防御复制。执行、Supervisor 策略/审批/Trace、Skill 准备和评测恢复不再接收这些表的 Entity；评测继续保留现有 schemaVersion、规范化字段顺序和指纹规则。固定发布入口仍接受 ACTIVE/ARCHIVED，DRAFT 只通过评测入口解析；当前 Agent 的 enabled 信息保留供执行入口判断，固定 Workflow 目标仍要求当前 Workflow ACTIVE。

[18 项数据库回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/agent/RuntimeAgentExecutionContextPersistenceTest.java) 覆盖发布期间读取、评测草稿跨表一致性、外层事务恢复、ORM 对象别名、目录不可修改、版本归属/停用/排序和 SQL 失败。H2 普通 REPEATABLE_READ 未通过其中五项跨表场景；测试夹具只将显式只读 REPEATABLE_READ 请求映射为 H2 SNAPSHOT，并保留未经过事务代理时仍复现混读的对照。该处理参考 [H2 隔离说明](https://www.h2database.com/html/advanced.html#transaction_isolation) 与 [MySQL 一致性读取](https://dev.mysql.com/doc/refman/8.0/en/innodb-consistent-read.html)，不代表真实 MySQL 并发或连接池压力已验收。

### MCP 固定 Workflow 版本

MCP 通过 Workflow 自有的 [固定快照查询](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimePublishedWorkflowSnapshotQuery.java) 读取请求指定的 Workflow ID 与版本 ID，获得已经校验的不可变 `RuntimePublishedWorkflowSnapshot`。版本归属严格匹配；只有 ACTIVE/RETIRED 版本且发布身份、GraphSpec 引用固定值及默认模型字段有效时才可返回。读取不回退到当前版本，也不依赖当前 Workflow 编辑行或 ACTIVE 状态；用于 Agent 工具解析的 `RuntimeWorkflowExecutionQuery` 仍要求当前 Workflow ACTIVE，两种查询不可互换。

MCP 自己保留版本参数检查、基于发布快照的项目授权、执行/超时、Trace 与 RunOps 生命周期和协议错误映射：无效版本参数为 `MCP_WORKFLOW_VERSION_REQUIRED`，缺失或属于其他 Workflow 为 `MCP_WORKFLOW_VERSION_NOT_FOUND`，发布状态或快照无效为 `MCP_WORKFLOW_SNAPSHOT_INVALID`，无权访问快照项目为 `MCP_WORKFLOW_PROJECT_SCOPE_DENIED`。数据库读取异常仍为技术执行失败，不能伪装成“版本不存在”。调用参数不能替换发布默认模型，发布值显式为 null 时也不能回退。

[版本持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/mcp/application/RuntimeMcpWorkflowVersionPersistenceTest.java) 在真实 MyBatis/H2 和 Spring 查询事务中覆盖固定历史版本、当前编辑状态与项目不同、当前定义不存在、版本/快照身份不符、状态/JSON/GraphSpec 无效、项目范围、null 默认模型和数据库失败；Trace、RunOps 与执行器在此夹具中使用替身。原有 MCP Trace 持久化及真实 GraphSpec 引擎配合模型替身的回归另外运行，尚未替代 MySQL 或真实 MCP 跨服务验收。

## 聚合与版本边界

| 对象 | 责任 |
| --- | --- |
| `runtime_agent` | 稳定身份、接入形态、项目归属、可见性和启用状态 |
| `runtime_agent_config_version` | Supervisor runtime、模型、提示词、执行限制、超时和策略的版本快照 |
| `runtime_agent_workflow_tool` | 某个配置版本可选择的 Workflow 工具白名单、契约覆盖和固定 `workflow_version_id` |
| `runtime_workflow` / `runtime_workflow_version` | Workflow 定义和已发布 `GraphSpec` 快照 |

发布新 Agent 配置版本时，旧 ACTIVE 版本退为历史状态；新执行解析当前 ACTIVE Agent 配置版本，既有审批恢复保留原配置。发布动作必须为每个启用的 Workflow-as-Tool 解析并写入当时选定的 `runtime_workflow_version.id`。执行时直接读取这个固定版本，不能再次解析 Workflow 当前 ACTIVE 版本。工具行随 Agent 配置版本复制和发布，确保一次 Agent 执行的工具集合与每个 Workflow 的 `GraphSpec` 都是不可变快照；Workflow 发布新版本后，必须发布新的 Agent 配置版本才能使用。

Agent 身份 API 与 Supervisor 配置 API 是两条独立写路径：`POST/PUT /api/agents` 只维护 `runtime_agent` 的稳定身份字段，不接收提示词、模型或扩展配置；这些运行设置只能通过配置草稿 API 写入。项目接入自动 provisioning 也必须先创建/复用 Agent，再单独创建并发布配置版本，禁止一次请求同时写两个聚合边界。

### Workflow 等待会话的归属与返回

Supervisor 通过 [交互会话服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowInteractionSessionService.java) 创建持久化等待，只接收不可变 `WaitingSession`：交互 ID 和已保存的 UI JSON。数据库 Entity、内部断点和续跑正文留在 execution。初次等待与连续等待复用同一内部创建实现；会话和 CREATED/REQUESTED 事件在既有事务内写入，失败时回滚。重复创建相同 ID 继续报唯一键冲突，不覆盖原快照或追加事件。Supervisor 在创建成功后使用返回的 UI，写入失败不会发布等待卡片。

等待所属项目来自 Agent，租户和业务用户来自 `WorkflowExecutionIdentity`，会话 ID 来自已校验的当前聊天会话。关闭聊天记忆持久化不会把已认证的等待用户改为 `anonymous`。没有可信业务用户的调用，包括调试身份、无用户 Agent 和 A2A 远程主体，不能创建生产 Workflow 等待。续跑信息中的 appId、tenantId、sessionId 和 userId 与会话列使用同一归属来源；`originalInput` 仍是业务输入，不能作为身份凭据。

恢复入口的 `Ownership` 显式携带可信身份，用户和租户不从提交正文、metadata 或 operatorId 提取。提交仍须匹配持久化的项目、聊天会话、用户和租户；空白租户统一按 `default` 比较，不作为租户通配条件。通过检查后，执行身份由已验证的会话归属构造，断点中的身份伪造字段仍由 checkpoint codec 排除。固定 Workflow/Agent 配置版本、GraphSpec 和 continuation 沿用暂停时的快照。

[持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowWaitingPersistenceTest.java) 使用基线派生的 H2 表、真实 MyBatis 与 Spring 事务代理，覆盖可信归属、内存会话、跨租户/用户拒绝、中文回读、不可变返回、重复创建、事件失败和连续等待回滚。模型和 Workflow 执行使用替身，真实 MySQL 与外部 E2E 未验收。

### Workflow 恢复的状态提交与重放

[恢复服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeInteractionResumeService.java) 负责身份校验、解析提交和运行固定快照，不直接访问 Mapper 或写事件。公开入口暂停调用方事务；会话读取、恢复认领、终态提交、校验后重新等待及连续等待，由交互会话所有者执行独立短事务。认领和 SUBMITTED 事件提交后才运行 Workflow，外部执行不处于数据库事务中。会话重读使用独立只读事务，避免在认领失败后沿用旧查询上下文。

所有恢复写入都约束预期状态和 revision。认领检查 WAITING_USER、有效期及 Workflow 所属来源，并保存独立的恢复期限；后续写入只接受同一修订且期限未过的 RESUMING。终态及对应事件一起提交；校验失败时，新的断点、UI、WAITING_USER 和 REQUESTED 一起提交，同时清除本次幂等键、结果和恢复期限，允许修改值后再提交。连续等待在一个事务内完成前驱和创建后继，后继不继承已消耗的恢复期限。迟到的执行不能覆盖新修订或产生孤立后继；写入失败也不会返回尚未保存的成功或等待结果。

Workflow 提交保存为版本化 `submissionSchemaVersion/action/values`，动作归一为小写，显式空 values 仍为空，不混入 Trace 或传输 metadata。相同幂等键必须匹配动作和值；老的 values-only 记录按默认 submit 读取。结果保存为 `resumeResultSchemaVersion/response`，保留当次恢复的成功/失败、步骤、metadata、UI 和后继交互 ID。完成、失败及取消的重放不受原等待期限影响；RESUMING 的重复提交明确返回处理中。

重放只返回 Workflow 当次保存的结果，不再次发放 Supervisor continuation；Agent 执行入口也不因重放再次续跑或写根运行终态，响应 metadata 标明 `idempotentReplay`。这避免重复请求提前结束仍在续跑的 Agent。首次执行的续跑编排继续使用固定 Agent 配置；Workflow 收据不是 Agent 最终回答的持久化恢复协议。

执行器在期限内抛出运行异常时，恢复服务提交 FAILED 和失败事件，公开错误不包含底层异常正文。[事务与重放回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowResumePersistenceTest.java) 使用真实 MyBatis/H2、事务代理和双线程认领；GraphSpec 执行及根运行端口使用替身。外部执行与数据库提交之间仍有故障窗口，恢复期限处理见下文。

### Workflow 恢复期限与结果未知

`resume_deadline_at` 是本次恢复提交结果的期限，与等待用户的 `expires_at` 分开。认领和 SUBMITTED 事件提交时一起保存期限，默认 900 秒，通过 `RUNTIME_INTERACTION_RESUME_TIMEOUT_SECONDS` 设置正整数；配置变化只影响之后的认领。提交、校验返回等待和连续等待的条件更新均检查期限，调度任务尚未运行时也会拒绝过期结果。

现有交互清理任务同时查询到期 WAITING_USER 和到期 RESUMING，每类 SQL 最多取 500 条，再按各自期限和 ID 合并成至多 500 条的批次。Workflow 恢复协议只处理 WORKFLOW、COMPOSITION、DEBUG；Supervisor 的 SUPERVISOR_POLICY／CONFIRM_ACTION 由独立审批所有者处理，共用期限列和批次扫描，不进入 Workflow 认领或结果协议。Managed Executor 维持独立协议。

到期 RESUMING 通过状态、revision、来源与期限条件更新为 EXPIRED，并保存版本化的完整收据：`code=RUNTIME_INTERACTION_RESUME_TIMEOUT`、`outcome=UNKNOWN`、`retryable=false`、`reconciliationRequired=true`。收据明确要求核对业务系统，保留交互 ID、Run／Trace ID 和固定 Workflow 版本；不包含 continuation。EXPIRED 事件以及同一 Trace 下仍在等待的 Span、SUSPENDED RunOps 在同一个独立事务中收尾；任一步持久化失败全部回滚。已恢复或已结束的运行证据保留。

可信调用方再次提交时也会检查到期认领，无需等待下一次调度。已保存的未知收据在更换幂等键或未提供键时仍返回；相同键但动作或值不同继续拒绝。迟到的原执行结果同样返回该收据，不能覆盖终态、创建后继或重新发放 Supervisor 续跑。Agent 响应保留 UNKNOWN 与核对标志，将本次平台运行显示为 TIMED_OUT，不因收据重放再次结束根运行。

这是结果未获确认后的收尾，不是业务对账，也不是可续租的工作者租约。期限经过不证明原进程已经停止，正在进行的外部调用仍可能完成；当前不强制中止 GraphSpec，不自动重跑、补偿或推断业务成败。持续数据库故障要待数据库恢复后才能提交收据；Agent 已完成 Workflow 后的最终回答恢复仍是后续工作。

[恢复故障回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/RuntimeWorkflowResumeRecoveryPersistenceTest.java) 使用真实 Spring 事务、MyBatis 和 SQL 基线生成的 H2 四张表，验证失去工作者的认领、完成回执失败、各类迟到结果、事件／Trace／RunOps 回滚、并发清理、来源隔离和有界查询；GraphSpec 和外部系统使用替身。新库基线新增期限列和索引，已有库按 [恢复期限升级脚本](../../sql/upgrade-20260906-workflow-resume-deadline.sql) 停旧实例、补列、回填后再部署。真实 MySQL 升级、锁和连接池行为、主机时钟差异及跨服务故障恢复尚未验收。

### Supervisor 确认续跑期限与结果未知

Supervisor 审批认领在同一事务内保存 `resume_deadline_at`、提交事件和幂等身份，默认期限也是 900 秒。原等待用户的有效期不限制同次结果回放。确认续跑超过期限时，交互所有者按状态、修订和期限条件更新为 EXPIRED，并一起提交结果收据、EXPIRED 事件以及尚未结束的 Run／Trace 超时状态；已结束的业务证据保留。

同一用户、租户、会话和幂等提交再次访问时，只回放 `SUPERVISOR_APPROVAL_RESUME_TIMEOUT`，其 `outcome=UNKNOWN`、`retryable=false`、`reconciliationRequired=true`，不再发放执行许可。过晚返回的成功结果也只能收到该已保存收据，不能覆盖超时终态。结果未知不证明外部操作未执行或已停止；需要核对业务记录，系统不会自动重跑。旧版本遗留的无期限确认续跑需人工核对，升级 SQL 不猜测结果。

[持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/RuntimeSupervisorApprovalPersistenceTest.java) 覆盖身份及幂等冲突、重复回放、过晚成功、过期候选竞争，以及交互／事件／运行／轨迹写入后的事务回滚。真实 AgentScope 确认续跑中进程退出的部署验收仍须单独完成，不能用这组 MyBatis/H2 测试替代。

### 自动附加 Workflow 的发布边界

Workflow AI Coding 入口负责项目、Workflow 类型/发布状态和替换目标的校验；[Agent 自有命令](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/agent/RuntimeAgentWorkflowAttachmentService.java) 查找或创建目标 Agent、选择可用模型，再调用配置服务完成发布。入口不直接访问 Agent Entity、Mapper 或配置持久化实体，公共请求和结果格式保持。

自动附加、替换或刷新 Workflow 版本，均从当前 ACTIVE 配置创建独立发布快照。用户已有 DRAFT 的 ID、内容、绑定和状态原样保留，不会参与自动发布。只有指定 Workflow 的绑定/版本及请求选定的模型可变化，其他 Workflow 固定版本、停用条目、标准 Skill 与远程 Agent 绑定快照保留；手工发布草稿仍沿用原有全目录版本校验和固定流程。目标绑定、模型和当前 Workflow 版本没有变化时直接复用 ACTIVE。

首次创建页面副驾驶，或该项目的默认页面副驾驶尚无 ACTIVE 时，使用服务自有中文默认配置建立首个发布版本，同时保留已经存在的用户草稿。其他自定义 Agent 需要先明确发布初始配置，再自动附加 Workflow。替换目标必须存在于已发布目录，仅在未发布草稿中存在的条目不能作为自动替换目标。

自动附加使用 READ_COMMITTED 事务，Agent 行锁覆盖目标重读、模型选择、配置和绑定复制、旧 ACTIVE 归档及活动指针切换；草稿写入、手工发布、身份修改和删除也遵循先锁 Agent 的顺序。身份编辑只更新身份字段，发布只更新活动配置指针及时间。首次自动创建的并发请求在唯一键冲突后读取同项目胜出记录，继续向同一个 Agent 附加。数据库异常会回滚当前事务创建的 Agent、版本和绑定，不提供跨服务原子发布。

[持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/agent/RuntimeAgentWorkflowAttachmentPersistenceTest.java) 使用真实 Spring 事务代理、MyBatis 和基线派生的 H2 表，覆盖已有草稿隔离、其他固定版本及停用快照、并发首次创建/重复附加/不同附加、身份修改与发布的锁等待、最终 SQL 失败回滚和中文回读。Workflow 与模型目录使用替身；MySQL 隔离与锁行为、真实跨服务调用尚未验收。此入口的 `publishedBy` 仍是现有客户端传入标签（缺省 `Cursor`），本批未将其升级为服务端认证主体。

配置版本生命周期为 `DRAFT -> ACTIVE -> ARCHIVED`。ACTIVE 和 ARCHIVED 都是不可变快照；`POST /api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft` 会把历史快照及其完整可调用 Workflow 列表复制为新的单一 DRAFT，再由用户编辑和发布。

Workflow-as-Tool 的目录读取由 Workflow 模块的 [RuntimeWorkflowToolCatalogQuery](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowToolCatalogQuery.java) 提供不可变视图，Agent 配置不直接读取 Workflow Mapper/Entity。保存目录和发布配置按批获取当前发布版本，保留原有 rollout percent、版本 ID 的选择顺序；发布时还必须确认 Workflow 当前为 ACTIVE 且发布图非空，避免将已停用目标发布为可执行目录。

历史目录按固定的版本 ID 与 Workflow ID 共同读取契约，仅接受 ACTIVE/RETIRED 发布版本。版本缺失、未固定、来源不匹配或未发布时返回空的版本/契约字段，不读取当前工作草稿；已发布版本本身未声明 Schema 时也保留空值。Agent 配置的显式 Schema 覆盖仍可生效。查询每批最多 500 个 ID；[真实 MyBatis/H2 回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowToolCatalogPersistenceTest.java) 验证 1,001 个目标的当前版本与固定版本读取各执行 6 次 JDBC 查询，未代表实际 MySQL 查询计划或生产性能验收。

## Supervisor 审批恢复

[SupervisorApprovalInteractionService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorApprovalInteractionService.java) 组装待批准动作、固定配置与参数、脱敏卡片和公开续跑摘要；[RuntimeSupervisorApprovalService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/execution/RuntimeSupervisorApprovalService.java) 拥有会话、事件、状态推进及幂等回执，实现 execution 自有的恢复端口。Supervisor 与待审批列表不直接读写会话 Mapper/Entity。创建、抢占与完成各自在事务中写入状态和对应事件。

创建保存可信身份的租户与用户，以及 Agent 的项目和配置版本。恢复必须提供可信项目和用户身份，并匹配原租户、项目、会话和用户；提交内容不能替换原工具、调用参数或配置版本。确认后通过 `resolvePublished` 读取批准时的 Agent 配置及其工具目录，配置缺失或不属于该 Agent 时失败，不回退当前版本。审批恢复仍检查 Agent 当前是否启用，不享有显式历史回放的停用豁免。

同一会话的决定读取使用行锁；更新附带来源、交互类型、Agent、Trace、节点、租户、用户、会话及状态和修订条件。确认动作和布尔值归一为批准或拒绝后再比较幂等载荷。相同决定在 RESUMING 阶段返回处理中，在 COMPLETED 阶段返回已保存结果；不同尝试或冲突决定被拒绝，不再次发放授权。到期的 WAITING_USER 会话与 EXPIRED 事件一起提交，再返回明确的过期异常；事件写入失败仍回滚整次事务。

待审批查询保持现有公开路由、Control 平台身份和项目权限检查，以及卡片 DTO；最多返回 200 条未过期的 WAITING_USER。查询不读取 `resume_checkpoint_json`，公开 `state` 仅包含 Agent、配置版本、工具名称及类型、权限键、风险和恢复入口等限定字段。原输入、执行参数与内部恢复状态不进入列表返回值。

[审批持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/execution/RuntimeSupervisorApprovalPersistenceTest.java) 使用真实 Spring 事务代理、MyBatis 和来自 SQL 基线的 H2 表；外部执行与配置目录使用替身。审批事务在执行外部工具前提交，当前契约不提供进程崩溃后的自动重试，也不承诺外部副作用与完成回执原子提交。现有 `sql/initV2.sql` 字段满足本批修改，无新增 DDL；缺少租户或固定版本的旧审批记录不补猜归属。真实 MySQL 并发和模型、工具端到端执行未验证。

## 产品与 API 语义

- 产品对象统一称为 **Agent / 智能体**；项目中不存在第二套“入口实体”模型。
- Agent 是通用 Supervisor 聚合根，不再按接入形态分类；页面副驾驶只是由项目接入流程按稳定 keySlug 自动创建或复用的 Agent。
- Agent 执行请求只使用 `agentId`（允许值为 Agent id 或 keySlug），不再接受 `agentDefinitionId` 别名。
- Agent 与 Workflow 不存在静态 binding、强制路由或 binding 故障降级。唯一关系是已发布配置版本中的 Workflow-as-Tool 白名单。
- Workflow 仍是独立的可发布业务能力；Agent 是理解、规划、工具选择和回答聚合的运行主体。

## 管理端交互

- Agent 列表显示接入形态、Supervisor 配置状态/版本和已发布 Workflow 工具数，并提供编辑、调试、评测和 RunOps 操作。
- Agent Supervisor 工作台分为“Agent 身份与接入”和“Supervisor 运行配置”；可调用 Workflow 列表支持调用名称、风险、权限键、Schema 覆盖、启停和排序。底层继续使用 Workflow-as-Tool 契约。
- 配置版本抽屉可查看 DRAFT/ACTIVE/ARCHIVED 历史并把不可变快照复制为草稿。
- Agent 调试只有 Supervisor Agent 执行，不再提供 Lightweight Chat 或独立“流式对话”模式。调试请求统一走 `/api/runtime/agents/execute/stream`，实时呈现 `supervisor.step`，并在完成时保留完整结果、Trace、UI 请求和会话 ID；RunOps 单独呈现 PLAN、REPLAN、WORKFLOW_TOOL 和配置版本指标。
- Agent Eval 通过 `RuntimeAgentExecutionService` 执行已发布配置，断言 zero/single/multi Workflow、有限重规划、页面动作、策略决策、UI 请求、Trace 和最终回答，不再评测一份脱离发布状态的 GraphSpec 副本。
- “业务页面工作台”统一提供页面上下文、AI Coding 任务、已发布 PAGE_ASSISTANT Workflow、浏览器验收和 RunOps 追踪入口。

## RunOps 运行事实边界

`runtime_run` 是每次用户或外部入口执行的根事实。Agent 即使直接回答、没有调用任何 Workflow 或 Tool，也必须先创建一行 Run；独立 Workflow 执行同样创建 Run。RunOps 列表、KPI、趋势、故障诊断、版本对比和回放都从该表开始，不能再通过 Tool 日志或 Span 反推“是否发生过一次运行”。

`runtime_trace_span`、`runtime_tool_call_log` 和 `runtime_guard_decision_log` 都是通过 `trace_id` 归属 Run 的子事件：

- `runtime_trace_span` 表达 Supervisor、PLAN、REPLAN、Workflow-as-Tool、Workflow Graph 节点和 LLM 等父子执行链路。
- `runtime_tool_call_log` 表达底层 Tool / Capability 的实际调用审计。
- `runtime_guard_decision_log` 表达 ALLOW、DENY、REQUIRE_CONFIRMATION 等策略决策。

Agent 与独立 Workflow 的根运行类型分别为 `AGENT`、`WORKFLOW`；MCP 入口与 Managed Execution 投影另外使用 `MCP`、`MANAGED_EXECUTION`。持久化状态以 `RuntimeRunStatus` 为准：`RUNNING`、`SUSPENDED`、`COMPLETED`、`FAILED`、`CANCELLED`、`TIMED_OUT`；暂停的具体原因由 `suspension_reason` 表达。Trace Span 保留自己的 `SUCCESS`、`WAITING_USER`、`WAITING_APPROVAL`、`TIMEOUT` 等状态，由 RunOps 映射为根运行生命周期。Tool 不是根运行类型。

创建 Run 时就固定 Agent 配置版本、Workflow 发布版本、身份与入口上下文，并把审计快照写入 `snapshot_json`。回放创建新的 `entry_type=REPLAY` Run，通过 `replay_of_trace_id` 指向来源，严格使用来源 Run 的历史 Agent 配置和所有固定 Workflow 版本；历史配置或固定版本不可解析时应明确失败，不能静默切换到当前 ACTIVE 配置。

Supervisor、Workflow 调试和 Automation 的根 Span 由 Trace 所属的 [RuntimeTraceRootService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceRootService.java) 统一创建和结束。完成写入同时匹配数据库 ID、Trace ID、Span ID、根节点身份及开放状态；已成功、失败、取消或超时的根记录不会被迟到回调覆盖。Supervisor 与 Workflow 调试复用按根类型限定的恢复规则，仅接受运行中或等待中的根记录；等待状态通过条件更新恢复，已结束或被并发过期处理抢占时明确报冲突，调试入口在执行节点前拒绝重开终态 Trace。

恢复时显式清空结束时间、耗时与错误信息，进入等待时也清空旧终态字段，避免 MyBatis 忽略实体的 null 值。RunOps 的 Agent 恢复单独匹配 AGENT 类型及读取时的 SUSPENDED 状态，并显式清空暂停原因与终态字段。等待过期通过 Trace 自有终止服务更新；[根 Span 持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/trace/RuntimeTraceRootLifecyclePersistenceTest.java) 和 [Run 恢复回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/runops/RuntimeRunResumePersistenceTest.java) 验证真实 MyBatis/H2 的空值清理、终态保护与另一连接提交过期后的竞争，尚未验证 MySQL 并发。

[WorkflowTraceSanitizer](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/WorkflowTraceSanitizer.java) 同属 Trace，统一处理输入、输出和错误摘要。Supervisor、Workflow 调试和 MCP 通过 [RuntimeTraceEvidenceWriter](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/trace/RuntimeTraceEvidenceWriter.java) 的不可变 `ChildSpan` / `ToolCall` 请求追加子事件与调用日志，不再引用 Trace 的 Entity/Mapper。生产者继续负责各协议上下文、状态、父子关系和摘要转换；Trace 写入器只接受合法子类型和非空父节点，根生命周期使用独立端口。子 Span 不从请求参数推断审计用户，工具调用日志继续使用可信执行身份。

MCP 的根记录通过 Trace 自有 `startMcp` / `finishMcp` 维护，限定 `MCP_TOOLS_CALL`，与 Workflow/Supervisor 完成入口相互隔离。开始写入必须返回一条记录和数据库 ID，失败时不启动 Run 或外部调用。完成只更新开放根的生命周期字段，保留并发元数据与已提交终态，成功明确清空旧错误；MCP 继续使用 SUCCESS/ERROR/TIMED_OUT 和安全拒绝摘要。子节点最多写入 500 条，保留父子关系、可信租户/项目和原有状态映射。调用结果仍来自执行本身，Trace 保留已提交的终态；根、子节点、RunOps 和外部副作用没有新增跨调用原子性。[MCP 持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/mcp/application/RuntimeMcpTracePersistenceTest.java) 通过真实 MyBatis/H2 和 Spring 事务代理验证终态保护、元数据、类型隔离、错误清理、数量上限、写入失败及 SQL 后异常回滚；MySQL 和真实外部执行未验收。

Supervisor 的 Skill 绑定快照由根 Span 服务锁定当前记录后合并，只更新 `metadata_json`；数据库 ID、Trace ID、Span ID 和 SUPERVISOR 根身份必须一致。迟到快照可以附加到已经结束的正确根记录，但不会重写其生命周期。带完成元数据的更新在同一 Trace 事务内读取并合并，保留配置版本、策略与 Skill 证据，不再整块替换这些字段；锁定查询清理 MyBatis 缓存。[元数据持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/trace/RuntimeTraceMetadataPersistenceTest.java) 使用真实 Spring 事务代理与 MyBatis/H2，验证完成与快照写入的行锁等待、字段保留、身份冲突和 SQL 写入后的异常回滚。

Supervisor 与调试保留日志写入失败时尽力继续的既有行为，Automation 保留写入异常向上传播的行为；根记录明确拒绝更新时不再推进对应 RunOps 完成投影。[子事件持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/trace/RuntimeTraceEvidencePersistenceTest.java) 覆盖 Workflow 成功、失败、等待和业务终态、节点父子关系、可信用户、中文回读、调试续跑、脱敏及写入失败时继续调用审计。Span、Tool 日志与 RunOps 仍不是跨调用原子提交，Guard 日志由 RunOps 独立维护，其他持久化引用继续按所属模块整理；真实 MySQL 和外部执行尚未验收。

`BUSINESS_TERMINAL` 长 17 字符，旧 `runtime_trace_span.status VARCHAR(16)` 会使对应子记录写入失败。新库基线已扩展为 `VARCHAR(32)`；已有开发/测试库需先执行 [Trace 状态列升级](../../sql/upgrade-20260906-trace-span-status-width.sql)，保留已有数据且不缩短更宽的列。H2 已验证实际业务终态写入，MySQL 升级和列定义回读尚未执行。

Supervisor 的 Guard 决策由策略服务投影，再交给 [RuntimeGuardDecisionWriter](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeGuardDecisionWriter.java) 写入 RunOps 自有表；Trace 服务不再承担该职责。目标项目 ID/code 来自 Agent，租户仅取独立可信身份，缺少可信身份时不写入请求声称的租户。公开 metadata 的归属字段与行字段一致，保留参数和原因脱敏；Workflow 与 A2A 分别记录 WORKFLOW_TOOL、A2A_REMOTE_AGENT。策略判断与日志失败时继续返回原决定的行为保持原有规则。[Guard 持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/supervisor/SupervisorGuardDecisionPersistenceTest.java) 覆盖两个协议的允许、拒绝、确认、评测阻断、身份来源和实际 SQL 写入。

Guard 的 REQUIRE_CONFIRMATION / EVAL_SIDE_EFFECT_BLOCKED 分别长 20/24 字符，旧 decision VARCHAR(16) 会使这些日志消失。新库基线已改为 VARCHAR(32)，已有库需按 [Guard 列宽升级](../../sql/upgrade-20260906-guard-decision-width.sql) 先扩列再部署 Runtime；脚本保留数据并提供列定义回读。H2 已验证两类长决策写入，真实 MySQL 升级和 DDL 锁影响尚未验收。

RunOps 的 [RuntimeRunLifecycleService](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/runops/RuntimeRunLifecycleService.java) 对 Agent、Workflow 和 MCP 完成复用同一组状态写入规则：只更新 RUNNING/SUSPENDED 根记录，成功清空旧错误，等待清空结束时间与耗时，终态不接受迟到完成。Workflow 与 MCP 还匹配数据库 ID、对应 Run 类型和读取时的状态，另一连接已提交的终态不会被旧对象整行回写。执行入口现有 `finishAgent` 同时承接独立 Workflow 的交互恢复结果，因此允许 AGENT/WORKFLOW 两类，排除 MCP 和 Managed Execution；MCP 的摘要字段仍采用允许列表。

Agent Skill 版本快照只更新 `snapshot_json` 与更新时间，不再通过完整 Entity 回写生命周期字段。[完成持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/runops/RuntimeRunCompletionPersistenceTest.java) 覆盖三类入口的成功、失败与终态保护、不同 Run 类型隔离、独立 Workflow 恢复、空值清理、并发超时和快照补写遇到取消的情况。此规则保护已结束的 Run，不提供同一 Trace 多次继续执行的 attempt/revision 隔离；Managed Execution 使用独立的[事务投影链路](./managed-executor.md#8-持久化归属)，真实 MySQL 并发与跨服务执行尚未验收。

## 规划与策略边界

每次运行的计划接纳、顺序预约、完成推进、失败标记与续跑快照由 [SupervisorExecutionPlanState](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorExecutionPlanState.java) 统一维护。允许调用的发布工具名称在运行开始时固定；并发提交初始计划只能接纳一个。计划校验和状态提交在同一临界区完成，任何执行仍持有预约时不能更换计划；预约时再次检查失败、完成和禁止重试状态，避免仅依赖调用前的检查。

RunState 保留实际执行、策略调用、计数、阶段编排和 Trace 编排。等待续跑与最终 metadata 的计划次数、执行列表、游标和完成列表取自同一不可变快照，已返回的计划内容不随输入集合或后续执行变化。Trace 和公开事件仍在状态接纳后写入，沿用原有故障语义，不承诺内存状态与审计存储的原子提交。验证范围与并发反例见 [计划状态整理验收](../../output/tasks/architecture-audit-20260905/supervisor-plan-state-notes.md)。

单次 Workflow 调用的取消信号、执行锁等待、超时包装和计划占用释放由 [SupervisorWorkflowExecutionScope](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorWorkflowExecutionScope.java) 维护。局部超时向 GraphSpec 执行器传递取消，执行器返回后先检查该调用是否已取消，再处理结果、展示和交互。失败处理完成后、结果交回 AgentScope 前只释放一次占用，旧执行迟到退出不会释放后续重试的占用。请求级取消仍终止整个 Agent；局部超时允许符合既有规则的只读重规划。超时仍覆盖取得执行锁后的整个工作流 callable，不承诺回滚超时前已经开始的持久化或外部副作用。反例、测试及当前应用联调限制见 [调用生命周期整理记录](../../output/tasks/architecture-audit-20260905/supervisor-workflow-timeout-notes.md)。

AgentScope Supervisor 必须在首次 Workflow 调用前记录 PLAN。Workflow 失败后必须先记录 REPLAN 才能继续调用。默认上限为 6 个计划步骤、4 次 Workflow 调用和 2 次重规划；默认总超时 300 秒、Workflow 超时 180 秒、Page Bridge 动作执行超时 30 秒。需要用户确认的页面动作另有 90 秒确认窗口，确认后再计算动作执行预算；非只读 Workflow 失败后禁止自动重试同一工具。

只读工具允许并行，写工具串行。所有工具调用先进入 `SupervisorToolPolicyService`，决策顺序为：ACTIVE 配置工具白名单与启停状态 -> project -> tenant -> Agent allowed roles -> permissionKey 角色映射 -> risk level。

- `READ`：校验通过后自动执行。
- `PAGE_ACTION`：还必须从本轮用户原始问题中识别到明确的打开、跳转或页面操作意图；外部请求不能用布尔标记伪造该意图。
- `WRITE`：创建一次性确认交互，确认凭证精确绑定 interaction、permissionKey、toolName 和 args，提交后不可重放。
- `IRREVERSIBLE`：默认拒绝；只有配置 `policy.irreversibleMode=CONFIRM` 时才允许进入强确认。

`DEV_ALLOW_ALL` 只在研发期跳过 tenant allowlist 与 permission-role 映射，不跳过工具白名单、项目、Agent allowed roles、页面显式意图、写确认和不可逆默认拒绝。所有 ALLOW / DENY / REQUIRE_CONFIRMATION 决策都写入 Guard 与 Trace。

## Supervisor 阶段进度

每次运行的阶段状态、阶段序号、终止活动阶段和 `supervisor.step` 发送顺序由 [SupervisorExecutionPhases](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorExecutionPhases.java) 统一管理。状态更新和事件入队在同一临界区完成，由一个调用方按提交顺序在锁外发送。接收端缓慢时，其他线程仍能更新取消或失败状态；重入调用也只入队，避免递归发送造成事件倒序。该顺序仅覆盖阶段事件，不覆盖答案增量或 GraphSpec 节点事件。

同一阶段保留首次分配的序号。终止操作只更新 started、waiting、active 阶段；未出现过的阶段不凭空完成，沿用最终答案的既有补全例外。正常结束的 metadata 与结果共享同一次阶段快照。对外快照和事件使用独立 Map，接收端修饰字段不会改写内部状态；这不把公开 Map 改为不可修改对象，也不新增终态封闭规则。

事件接收端抛出运行异常时，不自动重发交付结果不明的事件；已入队事件继续尝试，随后由发送调用方抛出首次异常。此组件没有新增线程、持久化队列或网络回执协议。真实 AgentScope 调用链的倒序反例、慢接收端与取消并发验证，以及应用联调边界见 [阶段进度整理记录](../../output/tasks/architecture-audit-20260905/supervisor-phase-events-notes.md)。

## 交互续跑上下文

完成一个等待交互的 Workflow 后，继续剩余计划时通过 [SupervisorRequest.withInput](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorRuntimeAdapter.java) 只替换本轮输入，保留请求中固定的 Agent/config、Workflow 目标、Skill/A2A 绑定、可信身份、个人记忆、审批、取消和评测上下文。不得用会重新补默认值的短构造器重建已有请求。

续跑继续使用原计划游标和已完成工具集合，不重新执行先前的 Workflow。个人记忆仍取独立的可信参数；原始输入中的同名字段不会成为可信记忆。适配器保留内部请求已有的评测上下文，公开评测入口仍按既有规则拒绝持久化交互恢复。行为反例及验证范围见 [续跑上下文整理记录](../../output/tasks/architecture-audit-20260905/supervisor-continuation-context-notes.md)。

## 最终回答生成

显式 `begin_final_answer` 和模型结束后的自动补救共用计划状态的完成条件。除等待用户确认外，结构化执行计划尚未完成时，直接返回 `SUPERVISOR_PLAN_INCOMPLETE`，答案阶段标记失败，终止活动阶段；不再启动额外的最终回答请求或输出声称完成的正文。`finish` 保留最终结果检查，但不承担公开答案之前的唯一检查。

[SupervisorFinalAnswerGenerator](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorFinalAnswerGenerator.java) 负责最终回答提示组装、禁用工具的生成选项、流式响应及上下文超限恢复。适配器保留计划资格、阶段切换、直接回答、Trace 和结果编排；生成组件只接收本轮用户消息、内部草稿、已验证结果提示与历史，不访问私有运行状态或另建执行计划。

初次最终回答与压缩后的重试使用同一份结果提示。只有未公开正文的上下文超限才能申请当前 `RuntimeContextEngineeringService.Invocation` 已有的压缩恢复预算；已经公开的部分正文不重放，取消和模型错误沿原路径上报。此拆分不新增模型调用预算、线程或持久化状态。反例、回归及验收边界见 [最终回答整理记录](../../output/tasks/architecture-audit-20260905/supervisor-final-answer-notes.md)。

## 页面动作边界

事实查询优先 API/数据 Workflow。只有用户明确要求打开、跳转、在页面查询或操作时，Supervisor 才能选择页面动作 Workflow。

确定性页面查询选路由 [SupervisorPageQueryRouter](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/supervisor/SupervisorPageQueryRouter.java) 负责，读取本轮已经解析的发布目标，检查图结构、查询条件和 Control 动作目录；自然语言约束继续复用 `SupervisorPageQueryPolicy`。选路只返回目标和参数，计划接纳、权限审批、执行与答案仍由适配器负责。工具注册、模型提示和选路共用 `RuntimeWorkflowSchemaResolver` 的绑定覆盖与发布 Schema 优先级，主类直接使用 `RuntimeResolvedWorkflowTarget`，避免维护第二种同结构执行目标。迁移与回归证据见 [页面查询路由整理记录](../../output/tasks/architecture-audit-20260905/supervisor-page-router-notes.md)。

跨路由页面动作必须在同一 embed session 内完成 `NAVIGATE -> TARGET_READY -> PAGE_ACTION`。Control 拥有 `control_embed_session`、`control_project_page`、`control_page_action` 和 `control_page_action_event`；Runtime 只能通过 Control internal catalog / `/internal/control/page-bridge/execute` 协作，不能直接读写这些表。

## 演进约束

- 新 Agent runtime 可以实现 `SupervisorRuntimeAdapter`，但必须复用 ACTIVE Agent 配置版本与 Workflow-as-Tool 目录。
- 新策略必须复用统一 policy/trace 决策点。
- 新页面协议阶段必须保持 session、project、Agent 和 page instance 的一致性校验。
- 新 Workflow 节点语义必须进入 `GraphSpec` 和发布快照。
