# 业务页面工作台架构

AI Coding 任务、交接、连接状态、任务状态和任务级鉴权统一遵循 [AI Coding Task Protocol v1](./ai-coding-task-protocol-v1.md)；本文只保留业务页面工作台的领域模型和领域规则。

## 1. 定位与边界

业务页面工作台是 `reachai-control-service` 中面向项目的设计与交付域，不是新的部署服务，也不是 Workflow Runtime 的替代品。

- Codex / Cursor：读取业务仓库、理解页面代码、实施修改、执行测试与真实浏览器验证。
- 业务系统：拥有真实数据、业务规则、事务、权限和领域正确性。
- ReachAI Control：拥有页面上下文、允许访问范围、AI Coding 任务、问题、报告、验收和审计。
- ReachAI Runtime：拥有 Workflow `GraphSpec`、发布版本、Agent Workflow-as-Tool、运行、Trace 和 RunOps。
- ReachAI Capability：拥有项目、业务方法与 API 源资产及接纳契约；Control 只通过 internal API / client 读取。

当前迭代不兼容旧页面助手数据。页面工作台使用全新领域表；成熟的 Workflow、Agent、RunOps、嵌入式会话和 AI Coding 鉴权能力继续复用。

## 2. 不变量

1. 一个项目的一个 `page_key` 只有一份稳定页面定义，不再按 `origin` 复制页面。
   多来源写入按 `MANUAL > SDK > AI_SCAN` 处理：低权威来源只能补空白页面信息，不能覆盖高权威描述或动作契约；完整动作同步只清理当前来源拥有的动作。
2. `page_instance_id` 是浏览器运行会话状态，只存在于 `control_embed_session`，不进入稳定页面定义。
3. 页面操作是页面下的一等契约；运行时仍通过 Control internal API 查询，不跨服务读表。
4. 页面地图扫描只建立模块、页面、资源和已有操作，不生成业务价值建议。
5. 单页面只读分析一次最多返回 3 条结果；事实、技术推断和待确认事项必须分开。
6. AI Coding 任务状态由后端状态机维护，不使用前端计时器、模拟进度或虚假百分比。
7. 领域 Console API 与公共 AI Coding Task API 调用同一 Task Kernel 和页面领域 Provider；区别只在认证和接口视图。
8. 交接包由公共协议签发，前端只负责单一复制动作；一次性激活码不保存明文，丢失或过期时必须重新签发交接包。
9. Workflow 与页面的关系使用 `runtime_workflow_resource_binding`，不从 `extra_json` 解析关键业务语义；PAGE_ASSISTANT 必须恰好有一个 `TARGET PAGE`，可另有 `RELATED PAGE`。资源绑定只允许在首次发布前的 `DRAFT` 状态修改，并使用 Workflow `updated_at` 做乐观并发控制；首次发布后绑定冻结，避免版本、权限范围和验收对象漂移。
10. “已发布”是 Runtime 真实数据的查询模型，不创建 Control 侧发布副本。
11. 任务快照与外部 context 只读取 Capability 的无敏感项目摘要；页面任务使用任务级 Token，项目级 `aiCodingKey` 只保留给 Gateway discovery 和 Workflow AI Coding 等项目级工程 API。
12. 同一页面允许挂载多个职责不同的 Workflow。接入默认只新增；替换必须显式携带当前已挂载前序 Workflow 的精确 `replaceWorkflowId`，并同时校验项目、页面绑定和 Agent 当前目录。替换只移除该目标，保留全部其他工具；不得根据页面、名称或顺序猜测替换关系。

## 3. 领域模型

### Control 所有

- `control_project_page`：稳定页面定义。
- `control_project_page_resource`：路由、组件、API、配置、权限、Store、测试等代码资源。
- `control_page_action`：页面可执行操作契约。
- `control_page_analysis_finding`：页面只读分析结果及人工处理状态。
- `control_ai_coding_task`：公共 AI Coding 任务根对象。
- `control_ai_coding_task_target`：PROJECT、PAGE、WORKFLOW 等任务目标。
- `control_ai_coding_task_handoff`：一次性交接与任务连接事实。
- `control_ai_coding_task_event`：公共不可变任务事件。
- `control_ai_coding_task_question`：AI Coding 工具提出、用户回答的问题。
- `control_ai_coding_task_artifact`：公共 Artifact envelope 和页面领域报告。

### Runtime 所有

- `runtime_workflow_resource_binding`：Workflow 与 PAGE 等外部资源的稳定绑定。
- 继续复用 `runtime_workflow`、`runtime_workflow_version`、`runtime_agent_workflow_tool`、`runtime_agent`、`runtime_run`。

JSON 只用于结构化 schema、快照、证据列表和可扩展元数据。任务状态、页面键、风险、权限、发布绑定等查询和约束语义必须使用稳定列。

## 4. AI Coding 任务

页面地图扫描、页面只读分析、Workflow 工程、代码实施、浏览器验收和发布前检查都是公共 Task Kernel 的 `taskKind`，由页面领域 Provider 构建上下文、校验报告并应用结果。

连接状态、任务执行状态和环境就绪状态统一遵循 [AI Coding Task Protocol v1](./ai-coding-task-protocol-v1.md)，本文不再复制状态机。AI Coding 客户端提交页面报告后首先进入 `RESULT_SUBMITTED`，页面显示“AI Coding 已提交”；只有页面领域 Provider 校验并应用成功后才能进入 `RESULT_APPLIED`，页面显示“AI Coding 已反馈，待平台验证”。全部服务端门禁通过后才显示“待人工验收”。普通事件或客户端自然语言不能把平台任务推进为完成。

`WORKFLOW_ENGINEERING` 只允许选择当前主页面已登记且有效的 Page Action。任务 context 同时提供该页面当前已挂载的 `existingPageWorkflows`；版本化 Artifact 可选声明 `replaceWorkflowId`，但平台只把它当作建议。Artifact 经 Control 校验后，由 Runtime 创建带唯一 `TARGET PAGE` 绑定的 `PAGE_ASSISTANT` 草稿；该动作不会发布 Workflow，也不会修改 Agent 配置。只有任务完成人工验收后，用户才能在发布确认框明确选择“新增并保留全部”或“替换指定旧 Workflow”。Runtime 再次验证精确目标，并在同一事务中完成发布与 Workflow-as-Tool 目录变更，失败时不得留下半发布或半接入状态。

Workflow 草稿的跨服务重试由 Runtime 的 [投递服务](../../reachai-runtime-service/src/main/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDraftSubmissionService.java) 管理。`runtime_workflow_draft_submission` 保存任务作用域、规范化请求摘要及成功应用修订；相同请求重放只读取当前草稿，旧请求不会覆盖后来的自动修正或人工编辑。新内容只可替换上次自动应用后未经其他编辑的 DRAFT，否则返回修订冲突。Workflow ID 由来源、项目、任务与目标页面确定，修改请求中的展示键不会创建另一份草稿。已删除、移出项目或已发布的草稿不能被自动重建或替换。

投递记录与草稿写入在同一个 Runtime 事务内完成，不能将 Control 的本地事务回滚解释为远端草稿也已回滚。已有环境部署前执行 [草稿投递表升级](../../sql/upgrade-20260906-workflow-draft-submissions.sql)；不根据旧草稿猜测历史请求摘要，旧任务遇到冲突时使用新任务或在 Studio 显式编辑。RunOps Trace 候选草稿共用该规则，仍保留自己的来源校验。[持久化回归](../../reachai-runtime-service/src/test/java/com/enterprise/ai/runtime/workflow/RuntimeWorkflowDraftSubmissionPersistenceTest.java) 使用 Spring/MyBatis/H2；真实 MySQL 与跨服务故障恢复尚未验收。

页面地图中的“接入检查”是只读诊断，不是当前首轮接入的强制发布门禁。它只使用服务端已经观察到的页面信息、有效操作目录、带 `pageInstanceId`/SDK 版本的 Embed Session、Bridge 操作快照和真实 Page Action 终态结果；客户端自报或 HTTP 200 不能生成 PASS。

发布前检查的客户端报告只负责声明精确 `workflowId`、`workflowVersion` 和交付材料。ReachAI Runtime 必须独立验证该 Workflow 的项目、`PAGE_ASSISTANT` 类型、唯一 `TARGET PAGE`、当前发布校验和目标版本状态；验证结果以 `PRE_RELEASE_READY` readiness 返回。Runtime 暂不可用时保持 `PENDING`，不得把客户端自报通过展示为平台已验证。

页面目录中的 `businessPageUrl` 是浏览器可直接打开的绝对 HTTP(S) 页面地址。SDK 页面登记优先从真实 `origin + route` 同步，也可由 AI Coding 页面地图报告或页面编辑表单维护。项目 `baseUrl` 只表示业务后端/网关 API 地址，工作台不得用它拼接页面路由；缺少 `businessPageUrl` 时隐藏“查看业务页面”入口并引导补充，不能生成看似可用但实际错误的链接。

浏览器验收的截图、场景和观察结论仍作为交付材料保存，但不能单独放行。`PAGE_BROWSER_E2E_READY` 只根据当前任务启动后、当前稳定 `pageKey` 的 Control 观测事实计算：业务页面必须真实创建带 SDK 版本的 Embed Session，并产生用户消息和助手回复。其他页面的会话不能代替当前页面；未观测到时保持 `RESULT_APPLIED`，允许用户完成真实链路后重新验证。Control 自身的会话或 Trace 证据查询暂时不可用时，相关 readiness 必须降级为带安全依赖证据的 `PENDING`，不得让任务详情返回 500，也不得因观测失败误判为通过。

从“已发布”列表发起验收时，页面同时提交精确的 Workflow 与版本快照。Control 在创建任务时通过 Runtime 已发布查询复核目标，拒绝已下线、页面不匹配或版本漂移的对象。运行后，`PAGE_WORKFLOW_TRACE_READY` 只接受当前任务启动后的当前页面 Trace，并要求 Runtime Run 已完成且成功 `WORKFLOW_TOOL` Span 精确匹配该 Workflow 版本。AI Coding 回传的 `traceId` 必须精确命中；没有回传时仅允许唯一符合条件的 Trace 自动关联，多条候选继续等待明确选择。

`PAGE_WORKFLOW_TRACE_READY` 通过后，Control 再按该精确已发布版本的 GraphSpec 判断是否声明了 `TOOL` 与 `PAGE_ACTION` 节点。声明的能力节点只有在同一精确 Trace 中观察到相应成功 `WORKFLOW_NODE` 后，`CAPABILITY_TOOL_E2E_READY` 才能通过；声明的页面动作只有在观察到相应节点的 `SUCCESS` 或可解释的 `BUSINESS_TERMINAL` 后，`PAGE_ACTION_E2E_READY` 才能通过。未声明的类别明确显示为 `NOT_REQUIRED`，图中存在节点本身绝不等于已执行。

节点证据还必须属于目标 Workflow 的成功调用：`parentSpanId` 指向精确匹配任务版本、状态为 `SUCCESS` 或 `BUSINESS_TERMINAL` 的 `WORKFLOW_TOOL` Span，节点元数据中的 `workflowId + workflowVersionId + workflowVersion` 也必须一致。同一 Trace 中其他 Workflow、其他版本、失败调用或未关联父调用的节点均不能补足目标的证据；缺失或损坏的元数据按未观测处理。发布图声明 `INTERACTION/PRESENT_OUTPUT` 时，只有符合上述归属条件且执行成功的展示节点才放行。

如果页面动作会写入业务数据，`WRITE_ACTION_E2E_READY` 仍只接受真实 `SUCCESS`；`NO_DATA`、`PRECONDITION_FAILED` 与 `USER_CANCELLED` 证明桥接和业务终态已被正确传回，但不会被误标为“写操作成功”。入口可见性、业务正确性和视觉体验继续由结构化浏览器材料与人工验收负责，不能被“Workflow 执行过”替代。

## 5. API 边界

Console API：

```text
/api/registry/projects/{projectCode}/page-workbench/**
```

页面接入中心使用只读聚合契约：

```text
GET /api/registry/projects/{projectCode}/page-workbench/access-center
```

该契约将页面、分析目标、公共 Task Kernel、Runtime 发布版本和精确版本验收投影为页面级 `currentStep`、`completedSteps` 与 `nextAction`。它不维护第二套状态机，也不直接执行状态迁移。Runtime 不可用时必须返回显式降级状态，不能将空发布结果解释为“未发布”。

公共外部 AI Coding Task API：

```text
/api/ai-coding/tasks/{taskId}/**
```

外部任务 API 使用一次性激活后换取的任务级 Token，只提供当前任务范围内的上下文和回写端点。报告使用显式版本：

- `reachai.page-map-report.v1`
- `reachai.page-analysis-report.v1`
- `reachai.code-implementation-report.v1`
- `reachai.browser-acceptance-report.v1`
- `reachai.pre-release-report.v1`

页面地图报告按 `page_key` 幂等应用。增量扫描不删除未出现页面；全量扫描才可将本次未出现的页面标记为 `ARCHIVED`。报告重复提交时，`artifact_key + content_hash` 必须一致。

外部任务事件、问题与报告分别使用稳定的 `clientEventId`、`questionId` 和 `artifactKey` 做幂等键。问题由 AI Coding 客户端生成 `questionId`，服务端不在重试时猜测原问题身份。

Runtime owning service 通过以下内部只读契约提供发布事实和精确发布就绪检查，前端不得直接调用：

```text
GET /internal/runtime/page-workbench/projects/{projectCode}/published
GET /internal/runtime/page-workbench/projects/{projectCode}/release-readiness
```

## 6. 前端

现有路由 path 与 route name 保留。页面接入中心包含三个面向业务用户的一级工作区：

```text
页面 / 活动记录 / 在线能力
```

“页面”按服务端计算的下一步排序，并将每个稳定页面定义投影为“选择目标 / AI 实施 / 确认上线 / 真实验收”四个用户可理解的里程碑。`currentStep` 表示当前所处阶段，`completedSteps` 表示已完成阶段，两者不得混用。该投影不是新的线性状态机；异常恢复、问题回答、重新实施和重复验收仍由公共 Task Kernel 承载。

页面详情不是临时抽屉或一次性向导，而是一级路由中的长期工作台。现有 `registry/projects/:projectCode/page-assistant` path 与 `PageAssistantWizard` route name 保持不变，以 `pageId` 查询参数恢复当前页面；进入详情不得写入全局项目选择。工作台按“页面基础 / 接入目标 / AI 实施 / 接入诊断 / 调试验证”组织为可自由切换的五个工作区，而不是把它们表现成必须顺序通过的线性步骤：

- “页面基础”位于首位，内部再按“页面资源 / 可调用操作”分组；资源是扫描或人工登记的实现依据，操作是经过契约化登记后可供 AI 安全调用的能力。
- “接入目标”负责选择或查看本次接入目标；“AI 实施”负责实施进度、问题答复、重新实施，以及后续上线确认和真实验收。两个工作区按当前服务端阶段给出合适的默认入口，但用户可随时切换查看。
- 没有任何页面资源时，进入详情默认落到“页面基础”，提示先扫描或补充依据；已经存在页面基础时，目标选择阶段默认落到“接入目标”，实施阶段默认落到“AI 实施”，同时允许用户随时返回核对依据。
- “接入诊断 / 调试验证”分别读取真实 readiness 和真实页面操作调试结果，不参与目标生成，也不伪造执行状态。

工作台还提供以下按需子界面：

- AI Coding 只读推荐说明与真实任务交接。
- 页面信息、资源目录、页面操作契约、真实页面调试和服务端接入诊断。
- 当前页面的活动历程、开放问题及已经存在的实施、浏览器和 Trace 材料。
- 页面助手 GraphSpec 草稿的人工上线确认，以及锁定 Workflow 精确版本的真实验收准备。
- 只根据核心加载错误、活动加载错误、Runtime 降级和 readiness 非 PASS 项生成的异常恢复动作。

这些子界面只投影已有页面、任务、Artifact、诊断、发布版本与 RunOps 数据。尚未形成的测试报告、浏览器证据或 Trace 必须显示为“尚未形成”，不得由前端静态构造、计时器推进或用 AI 自报替代。

普通接入流程不要求用户选择 `taskKind`。用户确认页面目标后，前端按受约束 Recipe 创建对应任务并展示一次性交接包；问题、结果和人工验收回到原活动。“分析结果”保留在小型“高级功能”入口；项目扫描、页面分析、工作流建设、代码实施、浏览器验收和发布前检查等完整任务历史统一命名为“AI 任务记录”，通过标题栏独立入口查看，不承担通用任务创建职责。

“在线能力”中的浏览器验收入口创建锁定当前页面、Workflow 与精确版本的 `BROWSER_ACCEPTANCE` 任务；最近运行入口只进入对应 RunOps Trace。两者不得混用或把普通运行记录命名为验收结果。

前端必须区分项目不存在、核心页面数据加载失败、真实空页面地图和局部依赖不可用。核心页面数据失败时展示可重试的阻断状态，不能降级成普通空地图；AI Coding 任务列表单独失败时保留页面资料的只读浏览，但活动记录显示不可用，且状态恢复前禁止新建任务，避免在未知状态下产生重复交接。Runtime 发布查询失败时页面级发布状态显示“暂不可用”，不得退化为等待接入。跨项目异步列表只允许最后一次请求更新当前页面。

## 7. 删除与复用

删除页面工作台对以下旧语义的依赖：

- 七步向导状态和前端步骤门禁。
- `PAGE_ASSISTANT` 固定接入会话步骤。
- 前端拼接大段 AI Coding 提示词。
- 从 `runtime_workflow.extra_json` 推断页面绑定。

保留并复用：

- 项目级 AI Coding Key 鉴权，仅用于 Gateway discovery、Workflow AI Coding 等项目级工程 API。
- 公共 AI Coding Task Kernel、一次性交接、任务级 Token、事件、问题和 Artifact。
- Capability 项目与能力上下文 client。
- 嵌入式会话、Page Bridge 与页面动作执行协议。
- Workflow AI Coding、GraphSpec 校验、发布与版本。
- Agent Workflow-as-Tool、Trace、RunOps 和真实浏览器验收证据。

## 8. 能力守恒清单

旧页面助手的数据和步骤语义不兼容，但以下有效能力必须在新主路径中有唯一归宿：

| 有效能力 | 新主路径 |
| --- | --- |
| 项目内页面发现与人工补录 | 页面地图的 `PAGE_MAP_SCAN` 任务与 `POST .../pages` |
| 路由、组件、API、权限等页面信息 | `control_project_page_resource` |
| SDK 注册与人工声明页面动作 | 统一 `control_page_action`；手工编辑只维护 `MANUAL` 来源，不覆盖 SDK/扫描结果 |
| 针对单页发现潜在交互 | 显式 `PAGE_READONLY_ANALYSIS` 任务；一次最多 3 条 finding |
| Codex / Cursor 提示词交接 | 公共协议签发短启动交接包；一次复制并激活，任务 API 持续回写 |
| AI Coding 提问、进度、结果与报告 | task event / question / artifact 真实契约 |
| Workflow 创建、编辑、校验、调试与发布 | `WORKFLOW_ENGINEERING` Artifact 只创建草稿；人工验收后由 Runtime 事务化发布并接入 Agent |
| 页面范围和 Workflow 关系 | `runtime_workflow_resource_binding`；首次发布后冻结 |
| Agent Workflow-as-Tool 接入 | 复用已发布 Agent 配置版本与白名单 |
| 页面动作真实执行 | 复用 Embed Session、Page Bridge、Guard 和 Control internal action catalog |
| 页面 Bridge/Catalog/Action 接入检查 | 页面地图的只读服务端观测诊断；不以客户端自报替代真实会话和结果 |
| 运行排障与调用统计 | Runtime Trace / RunOps 真实查询 |
| 浏览器验收 | `BROWSER_ACCEPTANCE` 任务及版本化 acceptance report；不拿普通 Run 冒充 |
