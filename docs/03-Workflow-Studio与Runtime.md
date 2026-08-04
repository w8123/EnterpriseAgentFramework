# Workflow Studio 与 Runtime

## 定位

ReachAI 将 Agent、Workflow 和 Runtime 分成三个清晰层次：

- **Agent**（`runtime_agent`）：稳定的智能体聚合根，承载身份、入口、项目范围和启用状态。
- **Agent 配置版本**（`runtime_agent_config_version`）：承载 Supervisor runtime、模型、提示词、规划/调用上限、超时和策略配置；只有已发布版本可执行。
- **Workflow**（`runtime_workflow`）：可复用的确定性业务能力，运行语义是 `GraphSpec`，画布布局是 `canvas_json`。
- **Workflow-as-Tool**（`runtime_agent_workflow_tool`）：按 Agent 配置版本维护可被 Supervisor 选择的已发布 Workflow 白名单及工具契约。

主执行链路是：

`Agent` → 已发布 Agent 配置版本 → AgentScope Supervisor → 规划/选工具 → 一个或多个已发布 Workflow `GraphSpec` → 汇总回答。

## Agent Supervisor

当前 Supervisor 运行时位于 `reachai-runtime-service`，实现为 `AgentScopeSupervisorRuntimeAdapter`，依赖 AgentScope Java `2.0.0` 正式版的 `ReActAgent`。`RuntimeAgentExecutionService` 解析 Agent、当前 ACTIVE 配置版本和该版本的 Workflow 工具目录。

Supervisor 的职责边界：

1. 理解用户真实意图并记录短计划。
2. 从版本化白名单选择零个、一个或多个 Workflow。
3. 将 Workflow 结果组合成最终回答。
4. Workflow 失败后先记录修订计划，再有限重规划。
5. 对事实查询优先选择 API/数据 Workflow；只有用户明确要求“打开、跳转、在页面上查询或操作”时，才允许选择页面动作 Workflow。

默认执行限制：

| 配置 | 默认值 |
| --- | ---: |
| 最大计划步骤 | 6 |
| 最大 Workflow 调用次数 | 4 |
| 最大重规划次数 | 2 |
| 总执行超时 | 300 秒 |
| 单 Workflow 超时 | 180 秒 |
| Page Bridge 超时 | 30 秒 |

只读 Workflow 可按 `parallel_read_only` 允许并行工具调用；写操作使用串行锁执行。每次调用都经过 `SupervisorToolPolicyService` 并写 Guard/Trace：READ 自动执行，PAGE_ACTION 要求用户原始问题中存在明确页面意图，WRITE 进入一次性确认，不可逆操作默认拒绝。`DEV_ALLOW_ALL` 仅跳过研发期 tenant allowlist 与 permission-role 映射，不绕过风险边界。

## Agent 配置与 Workflow-as-Tool

管理 API：

- `GET /api/agents/{agentId}/config-versions`：查看配置版本。
- `PUT /api/agents/{agentId}/config-versions/draft`：保存下一版草稿及其 Workflow 工具白名单。
- `POST /api/agents/{agentId}/config-versions/{configVersionId}/publish`：发布并激活配置版本。
- `POST /api/agents/{agentId}/config-versions/{configVersionId}/copy-to-draft`：把不可变历史快照复制为新草稿。
- `GET /api/workflows/search`：为 Agent 工具选择器提供服务端分页搜索；关键词覆盖 Workflow 名称、keySlug、描述、项目编码和类型。

`POST/PUT /api/agents` 只维护 Agent 身份、接入形态、项目范围、角色和启用状态；提示词、模型、Supervisor 限制与工具目录不得通过身份 API 写入。执行请求统一使用 `agentId`，不保留 `agentDefinitionId` 别名。

管理端 `AgentEdit.vue` 维护 Supervisor 配置和 Workflow 工具，并通过配置版本 API 发布新版本。Agent 主表保持稳定，提示词、模型、限制和工具集合通过配置版本演进，避免把每次配置调整写回身份主表。

每个 Workflow 工具可以覆盖名称、描述、输入/输出 schema，并声明 `risk_level`、`permission_key`、`read_only`、启用状态和优先级。Runtime 只装载 ACTIVE Workflow 的 ACTIVE 版本快照；草稿或没有可执行版本的 Workflow 不会被暴露给 Supervisor。

## Workflow Studio 与 GraphSpec

Workflow Studio 是唯一的画布编辑器：

- `runtime_workflow.graph_spec_json` 保存运行语义。
- `runtime_workflow.canvas_json` 只保存画布布局。
- `WorkflowReleaseValidationService` 校验节点、边、入口、变量映射、Capability 引用和可达性。
- 发布后的 `runtime_workflow_version.graph_spec_snapshot_json` 是 Supervisor 调用时的执行事实源。
- AI 生成 Proposal 使用 `POST /api/workflows/studio/proposals/generate`。
- AI 编辑 Proposal 使用 `POST /api/workflows/studio/proposals/edit`：AgentScope Authoring Adapter 通过受约束工具修改内存候选，确定性内核负责 mutation / validation；仅 `status=SUCCEEDED` 可应用到 Working Copy。旧 `generate-draft`、`edit-draft` 路由不再提供。

### 节点能力目录

`RuntimeWorkflowNodeCapabilityRegistry` 是 Workflow 节点产品开放策略的统一事实源；`RuntimeGraphSpecExecutor.handledNodeTypes()` 是 Runtime 真实可执行 Handler 集合。`GET /api/workflows/graph-node-types`、Studio Palette、发布校验和网页 AI 编排共同消费该目录，不再各自维护一份“可见/可生成/可发布”列表。

成熟度含义：

- `STABLE`：Runtime 可执行，且允许 Studio 新增、网页 AI 编排与发布。
- `BETA`：Runtime 可执行并允许开放使用，但可能带结构化 warning（例如 `PAGE_ACTION` 的 `GRAPH_NODE_BETA`）；个别 BETA 节点（如 `INTERACTION`）可因闭环未完成而禁止 Studio/AI/发布。
- `PLANNED`：协议身份已声明，但 Runtime Handler 尚未实现；禁止 Studio 新增、AI 编排和发布。

外部 AI Coding 本阶段不作为验收范围；共享 mutation 内核仍会拒绝不可编排节点。新增节点或 Runtime 行为必须把可执行语义写入 Workflow `GraphSpec`，不能只扩展前端画布。Workflow 仍可独立调试、发布、回滚和回放；被 Agent 使用时，它是 Supervisor 的受控工具，而不是静态入口路由。

当前受控开放节点约 **15** 个（`studioEnabled/publishable/aiAuthoring=true` 且 Runtime 有真实 Handler）：

| 成熟度 | 节点 |
| --- | --- |
| STABLE | `USER_INPUT`、`INTENT_CLASSIFIER`、`IF_ELSE`、`PARAMETER_EXTRACT`、`LLM`、`TOOL`、`CAPABILITY`、`ANSWER`、`VARIABLE_ASSIGN`、`TEMPLATE`、`VARIABLE_AGGREGATOR` |
| BETA（开放） | `PAGE_ACTION`、`KNOWLEDGE_RETRIEVAL`、`HTTP_REQUEST`、`LOOP`（FOREACH v1；Browser/Live E2E 仍 PENDING） |
| BETA（关闭） | `INTERACTION` — Runtime Handler 已具备（CODE_READY），待 Agent/Embed/RunOps Live E2E 后才开放（E2E_PENDING） |

第一阶段 5 类节点 + LOOP v1 状态：

- **SECURITY_SCOPE_CLOSED**：五项安全边界已收口；扩展项见 `docs/ai-memory/SECURITY-BACKLOG.md`
- **CODE_READY + AUTOMATED_TESTS_PASSED**：KR/HTTP/LOOP 自动化门槛已过
- **BROWSER/LIVE_E2E_PENDING / LOOP_E2E_PENDING**：浏览器 Studio、真实知识库、外部 HTTP、Agent/Embed、LOOP Live 未在本轮执行（本地服务端口未就绪时记 NOT RUN）
- **PRODUCTION_PENDING**：不得报告 Production Ready

`LOOP` v1 契约（平面 GraphSpec + 受控循环体）：

- 仅 `FOREACH` 有界串行；默认 `maxIterations=100`，硬上限 `1000`
- `collection` / `itemAlias` / `indexAlias` / `outputAlias` / `bodyOutput` / `bodyEntry` / `bodyExit` / `bodyNodeIds`
- 循环体边不进入主图任意环检测；禁止外部跳入、body 逃逸、嵌套 LOOP、body 内 INTERACTION/HUMAN_APPROVAL
- Trace 仅保留 collectionSize/completedIterations/index/status，禁止 item 原文

AI Draft 的 `nodeTypes` 与 system prompt 均以 `RuntimeWorkflowNodeCapabilityRegistry.aiAuthoringCatalog()` 为唯一事实源。

可信身份与内部调用：

- Control→Runtime 可信执行必须使用 HMAC-SHA256 + `BODY_SHA256`（`REACHAI_INTERNAL_SERVICE_SECRET`，K8s `reachai-internal-service-secret`）保护 sync/SSE；nonce 仅清理过期，满容 fail-closed。
- Agent：Bearer 合法 → `AGENT` + 平台 userId；无/无效 Bearer → `userTrusted=false`；RunOps 审计 userId 同理。
- Embed：仅已验签 claims userId → `EMBED_SESSION`；body attacker userId 永远无效。
- 公开 Runtime stream / body 中的 `source`/`userId`/`trustedIdentity` 全部不可信。

变量与输出契约：

- `input` / `message`：入口输入，运行中只读
- `params` / `sys` / `nodeOutput.<nodeId>`：保留命名空间
- `var.<alias>`：`outputAlias` 与 `VARIABLE_ASSIGN` 的规范业务命名空间（由 Runtime 统一写入）
- `lastOutput` / `previousOutput`：Runtime 便利字段，不作为设计期主入口
- 模板缺失变量渲染为空字符串（与 ANSWER/LLM 一致）
- `VARIABLE_ASSIGN` 保存原生 JSON 类型（number/boolean/object/array/null/表达式字符串），Save/reopen 不得全部 `String(value)`

节点失败策略（`GraphSpec.ErrorPolicy`）由执行器公共边界消费：`TERMINATE` / `CONTINUE`（使用 `defaultOutput`）/ `FALLBACK`（跳转 `fallbackNodeId`，禁止自环与不可控循环）。`RetryPolicy` 仅在 `AgentGraphNodeType.retryable()==true`、失败可分类为 retryable、且非 WAITING_USER/CANCELLED/配置类错误时生效；`HTTP_REQUEST` 默认仅幂等方法（GET/HEAD/OPTIONS）可重试，非幂等方法需显式 `retryAllowNonIdempotent=true`。

HTTP 契约：

- 传输成功：仅 HTTP 200–299；4xx/5xx → `success=false`，供 Retry/ErrorPolicy 消费
- 业务响应：若 JSON 顶层存在 `code`，仅字符串或数值 `200` 视为成功；`code != 200` 或显式 `success=false` → 节点失败、`retryableFailure=false`，用户提示以“查询失败”开头。该规则同时适用于 `HTTP_REQUEST` 与 Capability/Tool 返回，不能把 HTTP 200 误判为业务成功
- Canonical 凭据类型：`BEARER`、`BASIC`、`API_KEY_HEADER`、`API_KEY_QUERY`、`CUSTOM_HEADERS`（历史别名仅在单一 normalize 层转换）
- Scope fail-closed：PROJECT 凭据在缺少 `projectId/projectCode` 时拒绝；list 无项目身份时仅返回 GLOBAL
- Redirect：credentialed 请求禁止跨 origin；HTTPS→HTTP 降级拒绝；同 origin 仍重新做 egress 校验
- Trace：只保留无 query 的 safe URL、status、duration、bytes、contentType、redirectCount；不落 secret / bodyPreview

结构化回复展示契约：

- `INTERACTION/PRESENT_OUTPUT` 使用通用 `presentation.mode`，支持 `card_only`、`text_and_card`、`text_only`；Studio 新建展示节点默认 `card_only`
- `card_only` 只抑制可见正文，不删除完成结果中的 `answer`，以保证 API、历史记录和不支持卡片的客户端仍可降级
- 未携带 `presentation` 的历史 `uiRequest` 按 `text_and_card` 兼容；阻塞型交互禁止 `text_only`
- 业务节点失败时不产生结果卡片，失败正文必须保留，不能因展示策略隐藏“查询失败”

Knowledge 契约：

- Runtime 仅调用 `POST /internal/knowledge/retrieval/query`（hits only，不生成 LLM 答案）
- `searchMode`=`vector|keyword|hybrid` 与 `rerankEnabled` 作为 request override 进入生产 `retrievalTest` 路径，不修改 KnowledgeBase 持久化配置
- 用户文件 ACL 过滤生效；Workflow 节点不再暴露 `directReturnEnabled/directReturnThreshold`（KnowledgeBase 管理域同名配置不受影响）

Trace 边界：

- Studio Debug 可继续推送受控 node 事件
- Agent/Embed 公开 SSE **不**推送内部 node delta
- 内部始终收集 `workflowNodeTraces` 并持久化为 RunOps `WORKFLOW_NODE` Span（含真实 latency / attempt / errorPolicy / 安全 traceSummary）

## Page Bridge 跨路由协议

页面动作由 `reachai-control-service` 拥有会话和命令状态，Runtime 通过 internal API 调用，不跨服务直写 Control 表。跨路由动作遵循同一会话内的完整协议：

1. 校验 ACTIVE embed session、项目和 Agent 身份一致。
2. 如果目标页面不同，向当前 Page Bridge 投递 `NAVIGATE` 命令。
3. 等待导航完成，并取得目标页面返回的 `pageInstanceId`；未返回时，等待目标 Page Bridge 的新注册实例。
4. 将同一 session 更新到目标 `pageKey/pageInstanceId/route`。
5. 再向目标页面实例投递真实 Page Action，并等待成功、失败或超时。
6. `NAVIGATE`、`TARGET_READY`、`PAGE_ACTION` 分阶段进入 Page Action Event 与 Trace。

导航和动作共享一个截止时间，不会在目标页面未就绪时提前执行。业务页面必须在路由切换后重新注册 Page Bridge；导航结果若已明确提供新实例，Control 不会再无条件等待注册表查询。

## Trace 与会话

Supervisor 使用调用方 `sessionId` 保持多轮会话；未提供时生成运行时会话标识。Trace 至少记录：

- Agent 与激活配置版本；
- PLAN / REPLAN；
- Workflow、版本、参数、耗时、结果与失败码；
- Guard/Policy 决策；
- Page Bridge 分阶段事件；
- 最终答案和模型元数据。

RunOps 和 Trace 应以这些记录解释“为什么选择这个 Workflow、调用了哪些 Workflow、为何重规划”。

## 兼容面

- `/api/runtime/agents/execute`、`/api/runtime/agents/execute/detailed` 及既有兼容 alias 进入 Supervisor 主路径。
- Agent 历史画布入口不再恢复；GraphSpec、画布和 Workflow 版本始终归属 Workflow。
- `LangGraph4jRuntimeAdapter` / `RuntimeGraphSpecExecutor` 负责单个 Workflow 执行；AgentScope 负责上层理解、规划、选择和有限重规划。
