# MCP 互联中心产品设计

> 状态：M1_CODE_VERIFIED_DEPLOYMENT_E2E_PENDING（出向源码、SQL 基线、领域模型、协议面、管理 API、前端 `/mcp-hub` 与退役守卫已落地并通过源码回归；远程开发库已于 2026-08-29 完成定向升级和双遍幂等回读，但全新部署、浏览器业务流与真实外部客户端双源 E2E 尚未完成，因此不能称为 MCP Hub 已可用；M2 入向注册与 M3 编排接入未实现）
> 产品名：MCP 互联中心（MCP Hub）
> 适用协议基线：Model Context Protocol，JSON-RPC 2.0 over HTTP（protocol version 协商）
> 决策日期：2026-08-28

开发环境验证更新（2026-09-10）：出向 Workflow 已通过真实 HTTP 客户端的双协议执行、固定发布版本与 schema、ACL 和工具范围拒绝、发布暂停、凭证吊销，以及 RunOps/调用日志/MySQL 回读。能力分支已修复发布契约指纹的 HTTP 传递和可信租户丢失，实际 Spring Boot 2 SDK 样例通过注册、平台回调同步、只读自动接纳、契约漂移拒绝、接受新契约、旧发布拒绝及重新发布后的双协议调用。SDK 业务端签名追踪号现与平台生成的 MCP 根追踪号一致，调用参数不能覆盖；客户部署环境、双源业务与浏览器整体验收仍未完成。详见 [Workflow 验收](../../output/tasks/architecture-audit-20260905/mcp-contract-wire-notes.md)、[SDK 能力验收](../../output/tasks/architecture-audit-20260905/capability-tenant-scope-notes.md) 和 [SDK 追踪号验收](../../output/tasks/architecture-audit-20260905/sdk-trace-propagation-notes.md)。

BMAPI-6 更新（2026-10-05）：能力分支先读取已接纳业务方法 owner，再派生 MCP Tool 元数据；旧混合能力目录不再是选择入口。API 通过固定 API owner 的已发布 Workflow 暴露。本批受控发布/执行与限制见[专项进度](../plans/业务方法与API重构实施进度.md)，不将其扩写为 MCP Hub 入向或客户部署验收。

## 1. 产品决策

MCP Hub 是 ReachAI 的独立产品模块，不是"暴露白名单开关"，也不是现有 `ControlMcpAdminController`、三张表和四个平铺页面的增量升级。

本次采用 clean-slate replacement：

- 不兼容旧 `/api/mcp/visibility` 手工字符串白名单模型与 `control_mcp_visibility` 表。
- 不迁移 `control_mcp_visibility` 数据；`control_mcp_client`、`control_mcp_call_log` 按新模型改造后重建。
- 不保留"白名单页 + 凭证页 + 流水页 + 向导页"四页并存结构。
- 允许复用 ReachAI 已成熟的平台能力：业务方法目录（Capability Catalog）、Workflow-as-Tool 契约、Runtime tool 执行、Supervisor 策略链、Tool ACL、Guard、RunOps、Trace、服务间 HMAC 与管理端设计系统。
- `/mcp/manifest` 与 `/mcp/jsonrpc` 协议入口保留，但语义升级为修订驱动的发布模型（见第 9 节）。

MCP 是开放工具协议渠道，不是新的业务资产类型。出向的 Tool 投影来自已接纳业务方法与已发布 Workflow；入向的外部 MCP 工具是受治理的远程工具资产，不进入 `capability_tool_definition`。

## 2. 产品定义

MCP Hub 负责让企业能够把平台能力组成 MCP 服务对外发布，安全接入外部 MCP 服务，并把双向工具调用纳入统一身份、策略与可观测体系。

它交付的不是"协议通了"，而是四个企业结果：

1. **可发布**：平台管理员可以把一组能力（业务方法目录条目 + 已发布 Workflow）组成版本化、可回滚的 MCP 服务，外部 AI 客户端拿到的是真实 schema 与真实描述，可直接调用。
2. **可接入**：集成开发者可以注册外部 MCP 服务，验证连通性与身份，同步工具清单并在远端变化时受控审核。
3. **可编排**：外部 MCP 工具进入 Workflow Studio 节点目录与 Agent 工具白名单，Workflow 与 Supervisor 都能在明确授权范围内调用外部 MCP 工具。
4. **可治理**：安全和运维人员可以回答"谁在调用我们哪些能力、我们调用了谁家什么工具、凭什么调用、成功率和延迟如何、失败在哪一层"。

## 3. 产品边界

### 3.1 与其他开放能力的区别

| 场景 | 产品入口 | 调用主体 | 核心资源 |
| --- | --- | --- | --- |
| 用户在业务页面与 Agent 对话 | Embed | 业务用户 | Conversation / Interaction |
| 企业应用调用 Agent | Gateway | 应用客户端 | Request / Run |
| AI 客户端调用 Tool | MCP Hub（出向） | MCP Client | Publication / Tool |
| 平台调用外部 Tool | MCP Hub（入向） | ReachAI（Runtime） | Remote Server / Remote Tool |
| 一个 Agent 委派工作给另一个 Agent | A2A Hub | Remote Agent Principal | Context / Task / Message / Artifact |

MCP Hub 不取代 A2A Hub、Gateway、Embed、Workflow Studio 或 RunOps。MCP 面向 Tool 粒度的机器调用，A2A 面向 Agent 粒度的任务协作；两者共享 Principal、Trust 策略、Trace 与 RunOps 的治理语言，但不共享领域模型。

### 3.2 领域语言

- **MCP 发布（Publication）**：一个对外 MCP 服务单元，由名称、说明、状态和当前修订组成；是出向的聚合根。
- **发布修订（Publication Revision）**：发布内容在某一时刻的不可变快照，包含全部工具投影的名称、描述、inputSchema 与风险等级；`tools/list` 只从已发布修订出。
- **发布条目（Publication Item）**：草稿态的组成单元，引用一个已接纳业务方法或已发布 Workflow，可带名称别名与描述覆盖；发布时解析冻结为修订内容。
- **MCP Client**：挂接在单一 Publication 下的外部调用方身份，持有凭证、角色与工具范围。
- **外部 MCP 服务（Remote MCP Server）**：入向注册的外部 MCP endpoint，经连通性验证与信任审核后可被编排调用。
- **远程工具（Remote Tool）**：外部 MCP 服务 `tools/list` 同步下来的工具快照；绑定与执行都使用固定快照，不静默跟随远端变化。
- **工具投影（Tool Projection）**：MCP Tool 始终是投影——出向从能力资产投影，入向从远程快照投影；两侧都没有独立的"Tool 资产"。

## 4. 目标用户与核心任务

### 4.1 平台管理员（出向）

- 从业务方法目录与已发布 Workflow 中挑选条目组成发布，配置对外名称与描述。
- 查看每个条目解析出的真实 inputSchema 与风险等级，修正后再发布。
- 管理 Client 凭证：创建、限定工具范围、设置有效期、轮换、吊销。
- 暂停发布、创建下一修订、回滚到历史修订。

### 4.2 集成开发者（入向）

- 注册外部 MCP 服务 URL 与认证方式，执行连通性探测与 `tools/list` 同步。
- 审核工具清单与 schema 变化，确认工具的风险等级与权限键。
- 把远程工具绑定进 Workflow 节点或 Agent 工具白名单。
- 在诊断台构造参数试调用，拿到可复制的配置样例。

### 4.3 安全管理员

- 管理客户端凭证生命周期与工具范围；凭证只在创建时明文一次。
- 确认 Tool ACL 在 MCP 入口真实生效（当前为治理缺口，本设计收口）。
- 审核外部 MCP 服务的信任状态、远端变化与隔离处置。
- 控制出向风险等级：写操作与不可逆工具的发布必须显式放行。

### 4.4 运维 / SRE

- 查看双向调用成功率、P95 延迟、失败分层（协议 / 认证 / 策略 / Runtime / 远端）。
- 从一条流水一键跳转 Runtime Run 与 Trace。
- 对外部 MCP 服务执行只读健康检查与重新同步。

## 5. 信息架构

MCP Hub 在管理端提供一个一级产品入口，内部由五个任务型工作区组成。

| 工作区 | 用户问题 | 关键操作 |
| --- | --- | --- |
| 总览 | 双向互联是否健康，哪里需要处理？ | 指标、风险队列、最近调用、远端变化 |
| 对外发布 | 我们对外发布了什么，谁在用？ | 组包、预检、发布、修订管理、Client 凭证、接入指引 |
| 外部 MCP | 我们信任并接入了哪些外部服务？ | 注册、探测、同步、差异审核、健康检查、禁用 |
| 工具绑定 | 远程工具如何进入编排？ | 绑定进 Workflow 节点 / Agent 白名单（嵌入既有工作台） |
| 调用观测 | 一次双向调用发生了什么？ | 方向筛选、流水详情、Trace 跳转、错误分层 |

工具绑定不建独立页面：Workflow Studio 节点目录与 Agent Supervisor 工作台的工具选择器中，远程 MCP 工具与 Workflow-as-Tool 并列出现，带来源标识"外部 MCP"与所属服务名。

### 5.1 总览

- 24 小时出向 / 入向调用量、成功率、P95、活跃 Client 数、即将过期凭证。
- 已发布 Publication 数、当前修订分布、暂停中的发布。
- 已信任外部 MCP 服务数、健康异常数、待审核工具变化数。
- 待处理队列：连续健康失败、工具 schema 变化、策略拒绝、凭证过期。

### 5.2 对外发布

发布采用"组包 → 解析 → 预检 → 发布"向导，不提供原始 JSON 编辑界面：

1. **选择资产**：从业务方法目录（Capability Catalog）与已发布 Workflow 中搜索勾选；列表展示名称、描述、来源、风险等级。不提供手敲字符串。
2. **解析契约**：系统按 `source_kind` 实时解析每个条目的真实 `inputSchema` 与描述（Capability 走 Capability internal API 的 `parameters_json`/`ai_description`；Workflow 走 Runtime Workflow-as-Tool 契约的 schema），界面预览解析结果；解析失败的条目标红并阻断发布。
3. **配置对外身份**：对外名称别名、描述覆盖、条目启停。
4. **发布前检查**：所有条目 schema 解析成功；Workflow 条目仍有可解析的已发布版本；风险等级汇总确认——`IRREVERSIBLE` 条目必须二次显式放行；至少一个有效 Client 或说明暂不发放凭证。
5. **发布**：冻结为不可变修订并对外生效。

发布规则：

- 修订发布后不可变；修改必须创建下一修订。
- `tools/list` 与 `tools/call` 始终按 Client 所属 Publication 的当前生效修订执行。
- 暂停只停止新调用；既有调用按超时自然收敛。回滚是把 current revision 指针切回仍通过预检的历史修订。

Client 凭证管理位于发布详情内（"我把这个服务发给了谁"）：

- 一个凭证只属于一个 Publication，创建时生成 `Authorization: Bearer` API Key，仅明文一次。
- 工具范围（tool scope）只能引用该发布内的工具；留空表示整包。
- 支持角色（对接 `control_tool_acl`）、有效期、启用开关、轮换（新凭证生效后旧凭证进入 `ROTATED`）与吊销。
- 发布详情内嵌接入指引（Cursor / Claude Desktop / 通用 HTTP 配置片段与 curl 自检），吸收原 Onboarding 页能力，按当前发布与凭证动态生成。

### 5.3 外部 MCP

注册流程：

1. 输入服务名称与 endpoint URL（及认证方式：无 / Bearer / 自定义 Header，凭据只保存密文或 secret reference）。
2. 系统执行 SSRF 防护、DNS/IP 策略、HTTPS/TLS 校验、大小与超时限制。
3. 执行协议探测（manifest / initialize / tools/list），解析工具清单与 inputSchema。
4. 展示同步结果与风险提示，管理员确认信任并初始化工具的风险等级、权限键。
5. 创建不可变的服务修订与工具快照；状态进入 `TRUSTED`。

同步与变化治理：

- 手动或定时重新同步；新出现的工具进入 `PENDING_REVIEW`，不可被绑定调用。
- 既有工具 schema 变化标记 `CHANGE_REVIEW_REQUIRED`，绑定与调用继续使用旧快照，直到审核切换。
- 消失的工具标记 `REMOVED`：引用它的 Workflow 发布校验失败，运行时执行明确报错，不静默换用。
- 连续健康失败只改变 health status；endpoint 或身份变化优先进入 `QUARANTINED`。

### 5.4 调用观测

统一双向流水，字段含方向、Publication / 外部服务、Client、方法、工具、状态、延迟、traceId。详情展示脱敏请求/响应摘要与错误分层（协议 / 认证 / 策略 / Runtime / 远端），可一键跳转 Runtime Run 与 Trace Span（出向 `MCP_TOOLS_CALL`、入向 `REMOTE_MCP_CALL`）。

## 6. 关键用户旅程

### 6.1 首次对外发布

成功标准：不了解内部表结构的管理员从资产选择开始，在一个向导中完成组包、schema 预览、风险确认与发布；外部 AI 客户端仅凭 endpoint、API Key 和接入指引完成 `tools/list`（真实 schema）与第一次 `tools/call`。

### 6.2 首次接入外部 MCP

成功标准：开发者粘贴 URL 后能判断"连通吗、提供哪些工具、怎么认证、schema 长什么样"；信任后工具可绑定进 Workflow 节点与 Agent 白名单并完成第一次受治理调用；远端后续变化不未经审核进入生产。

### 6.3 Workflow 调用外部工具

成功标准：Workflow Studio 节点目录可搜索远程 MCP 工具；发布校验检查工具快照仍在且 schema 摘要一致；执行链路形成 Run + Trace + Tool Call 证据；远端失败时错误分层清晰、不伪装成功。

### 6.4 Supervisor 调用外部工具

成功标准：Agent 配置版本白名单可加入远程 MCP 工具；Supervisor 按既有策略链（白名单 → project → tenant → roles → permissionKey → risk）决策；READ 自动执行，WRITE 走既有确认交互，IRREVERSIBLE 默认拒绝；结果进入最终回答与 Trace。

### 6.5 故障调查

成功标准：运维从一条流水定位方向、主体、策略决策、协议调用、Runtime Run/Trace 或远端错误，不需要查原始日志或手工拼表。

## 7. 角色与权限

| 权限 | 用途 |
| --- | --- |
| `mcp-hub:read` | 查看总览、目录与脱敏流水 |
| `mcp-hub:publication:manage` | 组包、预检、发布、修订、暂停与回滚 |
| `mcp-hub:publication:irreversible` | 单独放行包含 `IRREVERSIBLE` 工具的发布修订；必须同时具备普通发布管理权限 |
| `mcp-hub:remote-server:manage` | 注册、审核、同步与禁用外部 MCP 服务 |
| `mcp-hub:credential:manage` | 创建、轮换、吊销凭证；接口永不回显秘密 |
| `mcp-hub:credential-role:manage` | 将既有 Tool ACL 角色授予 MCP 凭证；与普通凭证生命周期管理分离 |
| `mcp-hub:binding:manage` | 在 Workflow / Agent 白名单中绑定远程工具 |
| `mcp-hub:payload:read` | 查看保留期内经审计授权的请求/响应正文 |

生产默认职责分离：发布管理者不能自动放行 `IRREVERSIBLE` 条目；凭证管理者不能读取调用正文。

## 8. 数据模型与状态机

### 8.1 出向（Control owning）

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `control_mcp_publication` | `name`、`description`、`state`、`current_revision_id` | 发布聚合根 |
| `control_mcp_publication_item` | `publication_id`、`source_kind`（CAPABILITY/WORKFLOW）、`source_ref`、`alias`、`description_override`、`risk_level_override`、`enabled` | 草稿组成单元；Workflow 必须明确风险等级，发布时解析冻结 |
| `control_mcp_publication_revision` | `publication_id`、`revision_no`、`tools_snapshot_json`、`risk_summary`、`published_at` | 不可变工具投影快照，`tools/list` 事实源 |
| `control_mcp_client`（改造） | `publication_id`、`name`、`project_id`、`project_code`、`environment`、`tenant_id`、`api_key_prefix`、`api_key_hash`、`roles_json`、`tool_scope_json`、`state`、`enabled`、`expires_at`、`last_used_at` | 凭证归属单一发布和项目/环境/租户；`state` 含 ACTIVE/ROTATED/REVOKED/EXPIRED |
| `control_mcp_call_log`（改造） | `direction`、`publication_id`、`client_id`、`remote_server_id`、项目/环境/租户、`method`、`tool_name`、`success`、`latency_ms`、`error_category`、`trace_id`、`run_id`、可选脱敏正文 | 双向统一流水；正文默认不保留 |

退役：`control_mcp_visibility` 及其 `/api/mcp/visibility` 管理接口删除，不迁移数据。

### 8.2 入向（Control owning）

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `control_mcp_remote_server` | `name`、`endpoint_url`、`transport`、`protocol_version`、`auth_type`、`credential_cipher`、`state`、`health_status`、`last_sync_at` | 外部服务注册与信任状态 |
| `control_mcp_remote_tool` | `remote_server_id`、`tool_name`、`title`、`description`、`input_schema_json`、`schema_digest`、`status`、`risk_level`、`permission_key`、`last_seen_at` | 工具快照；`status` 含 PENDING_REVIEW/ACTIVE/CHANGE_REVIEW_REQUIRED/REMOVED |
| `control_mcp_remote_sync_log` | `remote_server_id`、`started_at`、`result`、`added`/`changed`/`removed` 计数、`error_message` | 同步审计 |

### 8.3 入向编排（Runtime owning）

| 表 | 关键列 | 说明 |
| --- | --- | --- |
| `runtime_agent_remote_tool` | `agent_config_version_id`、`remote_tool_key`、`description_override`、`risk_level`、`permission_key`、`read_only`、`enabled`、`priority` | Supervisor 白名单的远程工具条目，契约对齐 `runtime_agent_workflow_tool` |

Workflow 侧不新建表：GraphSpec 新增 `MCP_TOOL` 节点（`serverKey` + `toolName` + `schemaDigest`），发布校验与执行都按快照解析。

### 8.4 状态机

- **Publication**：`DRAFT → VALIDATING → READY → PUBLISHED → SUSPENDED → ARCHIVED`；`VALIDATING` 失败回 `DRAFT` 并保留检查结果；只有不可变修订可进入 `PUBLISHED`；`SUSPENDED` 可恢复同一修订。
- **Remote Server**：`DISCOVERED → VERIFYING → REVIEW_REQUIRED → TRUSTED → DISABLED / QUARANTINED → ARCHIVED`；身份或 endpoint 变化优先 `QUARANTINED`。
- **Remote Tool**：`PENDING_REVIEW → ACTIVE → CHANGE_REVIEW_REQUIRED → ACTIVE`；`REMOVED` 为待清理终态。
- **Client**：`ACTIVE → ROTATED / REVOKED / EXPIRED`，均为终态，凭证不可复活只能新建。

所有非法迁移由领域层拒绝并记录审计事件。

## 9. 协议与执行架构

### 9.1 出向协议面（Control owning `/mcp/**`）

- `GET /mcp/manifest`：返回 endpoint manifest；声明协议版本与 transport。
- `POST /mcp` 与兼容入口 `POST /mcp/jsonrpc`：同一 Streamable HTTP 适配器同时支持 legacy `2025-11-25` 与 modern `2026-07-28`。legacy 先 `initialize` 再发送 `notifications/initialized`；modern 直接 `server/discover`，并校验逐请求版本、方法与名称元数据。认证（Bearer API Key → Client → Publication）后：
  - `tools/list`：从 Client 所属 Publication 的当前生效修订快照计算，再按 Client `tool_scope` 收窄。
  - `tools/call`：按修订快照校验可见性 → 项目级 Tool ACL（Client `roles_json` 走 `control_tool_acl` 决策，DENY 优先、无命中默认拒绝）→ Control 以 HMAC `MCP_REMOTE_CLIENT` 身份调用 Runtime `POST /internal/runtime/mcp/tool-executions`。Runtime 按 `source_kind` 执行 Capability 或修订固定版本的 Workflow GraphSpec。
    能力调用的顶层 `capabilityContractHash` 必须取自冻结修订，并完整经过 Runtime HTTP 接收对象进入执行命令，最终成为 Capability 的 `constraints.expectedContractHash`。缺失指纹拒绝执行，工具参数中的同名字段不能代替发布指纹；Workflow 分支继续固定 `workflowVersionId`。
  - 协议交换尽力写入 `control_mcp_call_log`，保留 `success`、`error_category`、`trace_id`、`run_id` 等非敏感证据；原始请求/响应正文默认关闭，显式开启后也先递归脱敏并按保留期清理。
  - 每次外部 `tools/call` 在 Runtime 形成独立 `runType=MCP` 根 Run 与 Trace；阻塞式人工交互在 MCP 身份下 fail-closed。

关键修复：`tools/list` 的 `description` 与 `inputSchema` 来自发布修订快照的真实解析结果，替换当前硬编码空 schema 与 note 凑数描述（`ControlMcpEndpointController.toolView`）。schema 在发布时冻结进修订，运行时不二次解析，保证外部契约稳定且 `tools/list` O(1)。

### 9.2 入向执行链路（Runtime 编排，Control 持协议）

- Control 是 MCP 协议双向 owner：外部 MCP 客户端实现（连接、认证、重试）归 Control。
- Runtime 暴露协议中立端口：Supervisor 与 GraphSpec executor 通过既有工具执行边界调用远程工具；实际 MCP 出站请求由 Control internal API 承载（`/internal/control/mcp/remote-tools/{key}/execute`，HMAC 身份）。
- Runtime 负责编排证据：`runtime_run`、`runtime_tool_call_log`、`REMOTE_MCP_CALL` Trace Span、Guard 决策（复用 `SupervisorToolPolicyService` 策略链）。
- 远程工具的读模型（目录、schema、状态）由 Control internal API 提供，Workflow Studio 节点目录与 Agent 工作台合并展示，前端不直接读 Control 表。

### 9.3 服务边界与表所有权

- 所有 `control_mcp_*` 表 owner 为 `reachai-control-service`；`runtime_agent_remote_tool` owner 为 `reachai-runtime-service`。
- Capability schema 解析通过 Capability internal API；Workflow-as-Tool 契约通过 Runtime internal API；禁止跨服务直写对方表。
- `docs/architecture/service-table-ownership.md` 与 `sql/initV2.sql` 同步更新；已有库升级走 `sql/upgrade-YYYYMMDD-mcp-hub.sql`。

## 10. 企业级非功能要求

### 10.1 安全

- 凭据只保存 SHA-256 哈希（Client）或密文/secret reference（Remote Server）；创建后不回显。
- 出向 `tools/call` 强制走 Tool ACL 决策并记录 Guard 证据；收口当前"管理表存在但入口不执行"的缺口。
- 出向风险治理：发布修订汇总风险；`WRITE` 条目要求 Client 角色显式授权；`IRREVERSIBLE` 条目发布需二次放行且默认不进 `tools/list`。
- 入向注册与每次同步执行 SSRF 防护、DNS/IP 策略与 TLS 校验。
- 默认日志不保存原始 body 正文与认证头；正文查看需 `mcp-hub:payload:read` 权限且限保留期。
- 公共协议面随 Control 默认启动；目标环境部署前必须先完成新 schema 与 Control/Runtime HMAC 配置。维护或前置条件未满足时显式设置 `REACHAI_MCP_HUB_ENABLED=false`。带 `Origin` 的浏览器请求还必须命中显式允许列表。

### 10.2 可靠性

- 出向 `tools/call` 幂等语义跟随底层 Runtime tool 执行；修订回滚后新调用立即按新修订执行，进行中调用按旧修订收敛。
- 入向远程调用具备超时、有限重试与熔断；远端 5xx/网络错误不伪装成功，错误分层落库。
- 同步任务可重复执行且幂等（按 `remote_server_id` + `tool_name` + `schema_digest` 判定差异）。

### 10.3 可观测性

- 每条流水具备 direction、publication/remote 标识、principal（Client 或 ReachAI Runtime）、traceId、runId（入向）。
- 指标按方向、主体、工具、状态与错误类别聚合；高基数正文不进 label。
- Task→Run/Trace 关联率目标 100%。

### 10.4 性能与配额

- `tools/list` P95 < 50ms（快照直出）。
- `tools/call` 协议层开销（不含 Runtime 执行）P95 < 100ms。
- 当前 M1 已有全局请求体上限、连接/执行超时与 Runtime 取消收敛；每 Client QPS、并发和日配额仍是后续增量，未作为本轮已实现能力宣称。

## 11. 产品指标

北极星指标：**每周成功完成且可追溯的双向 MCP 工具调用数**。

配套指标：

- 发布漏斗：组包 → 预检通过 → 发布 → 首次外部 `tools/call` 成功。
- 接入漏斗：注册 → 探测通过 → 信任 → 首次绑定 → 首次编排调用成功。
- 双向调用成功率、P50/P95/P99、错误分层分布。
- Tool ACL 拦截数、策略拒绝数、越权尝试数。
- 远端变化审核时效（CHANGE_REVIEW_REQUIRED 平均滞留时间）。
- 流水 → Run/Trace 关联率，目标 100%。

## 12. 版本范围

### M1：出向重塑（本模块的地基）

- Publication / Item / Revision / Client 模型与状态机；`control_mcp_visibility` 退役。
- 发布向导（资产选择、schema 实时解析预览、风险确认、预检、发布、修订、回滚）。
- `tools/list`/`tools/call` 按修订快照执行；真实 description/inputSchema；`source_kind` 路由（Capability + Workflow）。
- Client 凭证挂发布单元、轮换、吊销；接入指引内嵌发布详情。
- Tool ACL 在 `tools/call` 强制执行；call log 增加 direction 与错误分层。
- 管理端：总览、对外发布（含凭证与指引）、双向流水初版。

### M2：入向注册与同步

- Remote Server / Remote Tool / Sync Log 模型与状态机；SSRF/TLS 防护。
- 注册、探测、同步、差异审核、健康检查、禁用、隔离。
- 管理端：外部 MCP 工作区、诊断试调用。

### M3：入向编排接入

- GraphSpec `MCP_TOOL` 节点：节点目录、发布校验（快照与 schema digest 一致性）、执行路由。
- `runtime_agent_remote_tool` 白名单与 Supervisor 策略链接入。
- `REMOTE_MCP_CALL` Trace Span、Guard 决策、Run/Trace 回跳闭环。

### 后续增量

- MCP Resources / Prompts 能力面。
- 可恢复 SSE 会话/通知、OAuth 2.0 客户端凭据、mTLS（基础 Streamable HTTP POST 已在 M1 支持）。
- 凭证自动轮换与密钥托管集成。
- 出向修订灰度（按 Client 分流新旧修订）。

后续增量只能扩展新领域模型，不得重新引入旧白名单结构。

### 发布验收门禁

以下证据全部成立才可称为 MCP Hub 可用：

1. 外部 AI 客户端从 endpoint + API Key 独立完成 `tools/list`（含真实 inputSchema）与首次 `tools/call`（Capability 与 Workflow 各一）。
2. 修订回滚后，`tools/list` 立即反映回滚结果。
3. 不同 Client 使用相同工具名无法越权（tool scope 与 Tool ACL 双层拦截均有证据）。
4. `IRREVERSIBLE` 条目默认不可见、不可调用，显式放行路径有审计。
5. 外部 MCP 注册 → 信任 → 绑定 → Workflow 节点调用 → Supervisor 调用各完成一次真实 E2E。
6. 远端工具 schema 变化后旧绑定继续按旧快照执行，审核切换前新 schema 不生效。
7. 每条双向流水可一键进入 Run/Trace，关联率 100%。
8. 源码、数据库与日志扫描证明无秘密与原始正文泄露。
9. 相关 Maven 模块测试、前端构建、浏览器工作流与真实双端 E2E 通过。

## 13. 已退役结构守卫

新模型落地后，下列旧结构必须持续不存在（以源码、路由、表结构与全文搜索为准，不以菜单隐藏为准）：

- `ControlMcpVisibilityEntity`、`ControlMcpVisibilityMapper`、`/api/mcp/visibility` 管理接口与 `control_mcp_visibility` 表。
- `McpVisibilityBoard.vue`、独立平铺的 `McpClientList.vue`、`McpOnboarding.vue`（能力被发布详情与 MCP Hub 工作区吸收）。
- `ControlMcpEndpointController.toolView` 的空 `inputSchema` 硬编码与 note 充当 description 的投影逻辑。
- Client 全局平铺、无 `publication_id` 归属的凭证模型。
