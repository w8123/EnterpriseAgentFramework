# MCP 互联中心实施清单

> 依据：[MCP 互联中心产品设计](../architecture/mcp-hub-product-design.md)
> 范围：M1 出向重塑 / M2 入向注册与同步 / M3 入向编排接入
> 约束：遵循 AGENTS.md —— SQL 双写（initV2 + upgrade）、服务表所有权、HMAC internal 契约、中文 UTF-8、不做旧数据兼容迁移
> 结构模板：镜像 `com.enterprise.ai.control.a2a` 的 clean-slate 分层（api/management、api/protocol、application、domain、infrastructure）

## 0. 里程碑与依赖总览

```
M1 出向重塑（可独立发布）
  W1 SQL 基线 ──► W2 领域模型 ──► W3 契约解析 ──► W4 协议面 ──► W5 管理 API ──► W6 前端 ──► W7 退役守卫
                    │                │
                    └── W8 测试（随各工作流同步写）──┘
M2 入向注册与同步（依赖 M1 的 call_log direction 字段与 mcp 包骨架）
M3 入向编排接入（依赖 M2 的 remote tool 快照）
```

每个里程碑完成定义（DoD）对应产品设计第 12 节验收门禁；所有 Maven / 前端 / `git diff --check` 验证命令见第 7 节。

---

## M1：出向重塑

### W1：SQL 与表所有权

| # | 任务 | 文件 | 内容 |
| --- | --- | --- | --- |
| W1-1 | 新增发布三表 | `sql/initV2.sql` | 在现有 MCP 段（约 L1979 起）前插入：`control_mcp_publication`（name 唯一、description、state、current_revision_id、created_at/updated_at）；`control_mcp_publication_item`（publication_id、source_kind CAPABILITY/WORKFLOW、source_ref、alias、description_override、enabled，uk(publication_id, source_kind, source_ref)）；`control_mcp_publication_revision`（publication_id、revision_no、tools_snapshot_json MEDIUMTEXT、risk_summary_json、published_at，uk(publication_id, revision_no)）。沿用现有 utf8mb4 + `CREATE TABLE IF NOT EXISTS` 幂等写法 |
| W1-2 | 改造 client 表 | `sql/initV2.sql` | `control_mcp_client` 增加 `publication_id`（NOT NULL，外联语义）、`state`（ACTIVE/ROTATED/REVOKED/EXPIRED，默认 ACTIVE）、`tool_scope_json`；`tool_whitelist_json` 删除（不保留兼容列）；`idx_publication` 索引 |
| W1-3 | 改造 call log 表 | `sql/initV2.sql` | `control_mcp_call_log` 增加 `direction`（OUTBOUND/INBOUND，默认 OUTBOUND）、`publication_id`、`remote_server_id`（M2 使用，列先建）、`error_category`、`run_id`；`idx_direction_created`、`idx_publication_created` 索引 |
| W1-4 | 退役旧表 | `sql/initV2.sql` | 删除 `control_mcp_visibility` 的 CREATE 段及关联 `add_*` 补丁调用 |
| W1-5 | 升级 SQL | `sql/upgrade-20260830-platform-consolidated.sql` Section 14 | DROP `control_mcp_visibility`；CREATE 三张新表；ALTER client/call_log 增列并 DROP 旧列（`tool_whitelist_json`）；写清影响：现有 Client 全部失效需按新模型重建，旧 visibility 数据不迁移 |
| W1-6 | SQL 文档 | `sql/README.md` | 登记升级脚本执行顺序与影响说明 |
| W1-7 | 表所有权 | `docs/architecture/service-table-ownership.md` | 5 张 `control_mcp_*` 表 owner=reachai-control-service；M3 预登记 `runtime_agent_remote_tool` owner=runtime |

**验收**：新库从 initV2 全量建库成功；upgrade 在开发库执行两遍幂等；`git diff --check` 通过。

### W2：Control 领域模型（新包 `com.enterprise.ai.control.mcp`）

| # | 任务 | 新文件（`reachai-control-service/src/main/java/com/enterprise/ai/control/mcp/` 下） |
| --- | --- | --- |
| W2-1 | 发布聚合 | `domain/publication/McpPublication.java`（状态机校验）、`McpPublicationStatus.java`（DRAFT/VALIDATING/READY/PUBLISHED/SUSPENDED/ARCHIVED 枚举 + `canTransitionTo`）、`McpPublicationItem.java`（source_kind/source_ref/alias）、`McpPublicationRevision.java`（不可变快照，`toolsSnapshotJson` 反序列化为 `List<McpToolProjection>`）、`McpToolProjection.java`（name/description/inputSchema/riskLevel/sourceKind/sourceRef，即 `tools/list` 单条投影）、`McpRiskSummary.java` |
| W2-2 | Client 域 | `domain/identity/McpClient.java`、`McpClientStatus.java`（ACTIVE/ROTATED/REVOKED/EXPIRED，均为终态不可逆）、`McpToolScope.java`（校验 scope 只引用所属发布内工具） |
| W2-3 | 持久化 | `infrastructure/persistence/`：Entity × 5（publication/item/revision/client 改造/call_log 改造）+ Mapper × 5 + `MybatisMcpPublicationRepository` 等仓储实现；application 侧定义 `application/port/McpPublicationRepository.java`、`McpClientRepository.java`、`McpCallLogRepository.java`（对齐 A2A 的 port/repository 模式，禁止 Controller 直用 Mapper） |
| W2-4 | 配置 | `infrastructure/McpHubConfiguration.java`、`McpHubProperties.java`（`services.*` 既有风格：API Key 前缀长度、快照大小上限、配额默认值） |
| W2-5 | 旧类处置 | 修改 `control/governance/ControlMcpClientEntity.java`（加 publication_id/state/tool_scope_json 字段，MyBatis 映射跟随表改造）、`ControlMcpCallLogEntity.java`（加 direction 等列）；**删除** `ControlMcpVisibilityEntity.java`、`ControlMcpVisibilityMapper.java` |

**验收**：`mvn -pl reachai-control-service -am compile` 通过；状态机非法迁移单测（W8-1）通过。

### W3：条目契约解析（真实 schema 来源）

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| W3-1 | 解析端口 | `mcp/application/port/McpItemContractResolver.java`（入参 source_kind + source_ref，出参 `McpToolProjection`，解析失败抛 `McpContractResolutionException`） | 两个实现 + 按 source_kind 路由的 `CompositeMcpItemContractResolver` |
| W3-2 | Capability 解析 | `mcp/infrastructure/runtime/CapabilityItemContractResolver.java` | 复用 `control/client/capability/CapabilityProxyClient.java`（必要时补一个取 tool 定义的 internal 调用）；schema 来自 `capability_tool_definition.parameters_json`，description 优先 `ai_description`，riskLevel 映射 `side_effect`（NONE/READ_ONLY→READ，IDEMPOTENT_WRITE/WRITE→WRITE，IRREVERSIBLE→IRREVERSIBLE）。**禁止** Control 直查 `capability_tool_definition` 表 |
| W3-3 | Workflow 解析 | `mcp/infrastructure/runtime/WorkflowItemContractResolver.java` | 走 `control/client/runtime/RuntimeProxyClient.java` 取已发布 Workflow 版本与 Workflow-as-Tool 契约（`RuntimeAgentConfigService.WorkflowToolRequest` 同构字段：toolName、descriptionOverride、inputSchemaOverrideJson、riskLevel、permissionKey、readOnly）；要求 source_ref 指向仍有可解析 ACTIVE 版本的 Workflow，否则解析失败 |
| W3-4 | 决策点（实现前确认） | — | **Runtime 是否已有"按 workflowId+固定版本执行"的独立入口**；若无，W4-4 在 Runtime 侧新增（见下）。此决策必须在写 W4 前落地，不得先按 capability 路由顶替 |

**验收**：mock 依赖的解析单测（W8-2）：真实 parameters_json → inputSchema 映射、ai_description 缺省回退 title、Workflow 版本缺失时报错且信息含 workflowId。

### W4：协议面重写（出向）

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| W4-1 | 删除旧协议实现 | 删除 `control/governance/ControlMcpEndpointController.java` | 整文件退场，空 inputSchema/note-description 逻辑随之消灭 |
| W4-2 | 新协议控制器 | `mcp/api/protocol/McpEndpointController.java` | 保留 `GET /mcp/manifest`、`POST /mcp/jsonrpc` 路由（路由兼容，语义全新）：Bearer 认证 → 定位 Client（校验 state/enabled/expires_at，更新 last_used_at）→ 定位所属 Publication 的 current_revision → `tools/list` 从修订快照直出 + tool_scope 收窄；`tools/call`：快照可见性 → Tool ACL 决策（W5-3）→ 按 source_kind 分流执行 → 写 call_log（direction=OUTBOUND、error_category 分层：PROTOCOL/AUTH/POLICY/RUNTIME/REMOTE） |
| W4-3 | Capability 执行 | `mcp/infrastructure/runtime/TrustedMcpRuntimeExecutionGateway.java` | **不沿用** `RuntimeProxyClient` 的无签名公共路径做可信执行（符合 AGENTS.md："涉及可信身份或写操作的链路必须遵守 HMAC"）。镜像 `control/managed/ControlManagedExecutionRuntimeClient.java` 的 exact-byte HMAC 模式（`InternalServiceAuthSigner` + `InternalServiceAuthHeaders`），新增 Runtime internal 路由见 W4-4 |
| W4-4 | Runtime internal 执行端点 | `reachai-runtime-service/.../runtime/mcp/api/McpToolExecutionInternalController.java`（新） | `POST /internal/runtime/mcp/tool-executions`：body 含 sourceKind/sourceRef/arguments/metadata（mcpClientId、publicationId、revisionNo）；CAPABILITY → 复用 `RuntimeCapabilityCatalogGateway.executeTool`；WORKFLOW → 按固定 workflowId+versionId 执行 GraphSpec（依赖 W3-4 决策的执行入口；无则在本控制器内调 `RuntimeGraphSpecExecutor` 门面实现）；HMAC 校验复用既有 internal filter；错误分类返回，不伪装成功 |
| W4-5 | 请求护栏 | `mcp/api/protocol/McpProtocolRequestGuard.java` | 镜像 `a2a/api/protocol/A2aProtocolRequestGuard`：请求体大小上限、方法白名单、JSON-RPC 2.0 结构校验、Authorization 脱敏（不进日志） |

**验收**：协议单测（W8-3）——tools/list 输出快照 schema；回滚后立即变化；ACL DENY 返回 -32000 段错误码且落 error_category=POLICY。

### W5：管理 API 与 Tool ACL 强制执行

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| W5-1 | 发布管理 | `mcp/api/management/McpPublicationController.java`（`/api/mcp/publications/**`：CRUD、items 增删、`POST /resolve-preview`（W3 解析结果预览，发布前 UI 用）、`POST /precheck`、`POST /publish`（冻结修订）、`POST /suspend`、`POST /resume`、`POST /revisions/{id}/rollback`、`GET /revisions`）+ `application/publication/McpPublicationApplicationService.java`（状态机唯一写入口）+ `McpPrecheckService.java`（全部条目解析成功、IRREVERSIBLE 二次放行标志校验、Workflow 版本可解析） |
| W5-2 | Client 管理 | `mcp/api/management/McpClientController.java`（`/api/mcp/publications/{id}/clients/**`：创建返回一次性明文 key、rotate（旧凭证置 ROTATED 并返回新 key）、revoke、启停、scope 校验拒绝发布外工具名）+ `application/identity/McpClientApplicationService.java`（key 生成规则沿用现有 createClient 的 prefix+sha256 实现，迁入新包） |
| W5-3 | Tool ACL 决策服务 | `control/governance/ControlToolAclDecisionService.java`（新，从 `ControlToolAclController` 抽出决策逻辑供复用） | 决策语义沿用 `control_tool_acl`：DENY 优先、无命中默认拒绝；MCP Client roles_json 作为角色输入；返回决策结果供 call_log 记录；**行为决策（需拍板）**：现状"roles 为空不拦截仅 warn"，MCP 入口按设计文档从严——无命中默认拒绝，需在升级 SQL 说明中写明 |
| W5-4 | 总览/流水 API | `mcp/api/management/McpHubOverviewController.java`（`/api/mcp/overview`：出向调用量/成功率/P95、活跃 Client、即将过期凭证、发布数）、`McpCallLogController.java`（`/api/mcp/call-logs`：加 direction/publication/错误类别筛选；正文默认脱敏摘要，`payload:read` 权限才回正文——权限挂平台会话，沿用 `A2aHubManagementAccess` 模式：`mcp/api/management/McpHubManagementAccess.java`） |
| W5-5 | 旧管理 API 退场 | 删除 `control/governance/ControlMcpAdminController.java` | `/api/mcp/visibility`、`/api/mcp/clients` 全路由退场（Client 管理迁至 publication 子资源） |

**验收**：`ControlGovernanceRoutesTest` 中 MCP 用例重写为对新路由的覆盖（W8-4）；权限守卫测试。

### W6：前端

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| W6-1 | 路由重构 | `ai-admin-front/src/router/index.ts`（L455-475 四条 mcp 路由替换） | 新结构：`/mcp-hub` + `McpHubLayout.vue` + children：`overview`、`publications`、`publications/:id`（详情页：条目/修订/凭证/接入指引四个 tab）、`call-logs`。镜像 a2a-hub 路由段写法 |
| W6-2 | 菜单 | `ai-admin-front/src/components/common/sidebarMenu.ts` | L143-146 四项替换为"MCP 互联"分组：总览/对外发布/调用流水（M2 增"外部 MCP"）；L270-273 activeMenu 映射与 L307-313 openGroups 同步改为 `/mcp-hub` 前缀匹配 |
| W6-3 | API 层 | `ai-admin-front/src/api/mcp.ts`（重写）、`types/mcp.ts`（重写） | 对齐 W5 路由：Publication/Item/Revision/ToolProjection/Client/CallLog 类型；删除 visibility 相关函数 |
| W6-4 | 页面 | `views/mcp-hub/` 新建：`McpHubLayout.vue`、`McpHubOverview.vue`、`McpPublicationList.vue`、`McpPublicationDetail.vue`（含发布向导：资产选择（capability/workflow 搜索复用现有选择器数据源）、resolve-preview schema 预览、风险确认、precheck 结果、发布/回滚）、`McpCallLogList.vue` | 复用 `WorkbenchPage`/`PageHeader`/`WorkbenchPanel`/`AppDialog`/`CodeSnippetBlock`（接入指引片段吸收原 McpOnboarding 的 cursor/claude/curl 样例，按当前发布+凭证动态生成） |
| W6-5 | 旧页面删除 | 删除 `views/mcp/McpVisibilityBoard.vue`、`McpClientList.vue`、`McpCallMonitor.vue`、`McpOnboarding.vue` 及 `views/mcp/` 目录 | — |

**验收**：`npx vue-tsc --noEmit` 无错；`npm run build` 通过；浏览器完成产品设计 6.1 旅程（组包→发布→外部 tools/list→tools/call）。

### W7：退役守卫与路由契约

| # | 任务 | 文件 |
| --- | --- | --- |
| W7-1 | 全文守卫 | 全仓搜索确认不存在：`ControlMcpVisibilityEntity`、`ControlMcpVisibilityMapper`、`/api/mcp/visibility`、`control_mcp_visibility`、`McpVisibilityBoard`、`toolWhitelistJson`（DB 列语义；代码内 json 字段名为 tool_scope_json） |
| W7-2 | 路由契约文档 | `docs/architecture/physical-split-route-ownership.md`（`/api/mcp/**`、`/mcp/**` 行更新为修订驱动模型 + internal 路由）、`docs/architecture/public-route-contracts.md`（若新增 `/internal/runtime/mcp/**`）、`docs/architecture/internal-api-contracts.md`（登记 W4-4 internal 契约：路径、调用方 Control、HMAC 身份） |
| W7-3 | 契约检查脚本 | 跑 `scripts/check-frontend-public-api-routes.mjs`、`scripts/check-internal-api-contracts.mjs`、`scripts/check-physical-service-route-contracts.mjs`，新路由未登记则补 |

### W8：测试（M1 汇总）

| # | 范围 | 文件 | 关键断言 |
| --- | --- | --- | --- |
| W8-1 | 状态机 | `mcp/domain/.../McpPublicationStatusTest.java` 等 | 非法迁移抛域异常；修订不可变（publish 后 snapshot 只读）；回滚只切指针 |
| W8-2 | 契约解析 | `mcp/infrastructure/runtime/...ResolverTest.java` | parameters_json→inputSchema 字段级映射；ai_description 回退；Workflow 无 ACTIVE 版本报错 |
| W8-3 | 协议面 | `mcp/api/protocol/McpEndpointControllerTest.java` | tools/list=快照∩scope；IRREVERSIBLE 默认不可见；ACL DENY→POLICY 错误码；CLIENT ROTATED→AUTH 错误码；call_log direction/error_category 落库 |
| W8-4 | 既有测试改造 | `control/governance/ControlGovernanceRoutesTest.java` | 删除 visibility 用例，新增 publication/client 管理路由用例 |
| W8-5 | Runtime internal | `runtime/mcp/api/McpToolExecutionInternalControllerTest.java` | HMAC 拒绝、source_kind 分流、Workflow 固定版本执行、错误分类 |
| W8-6 | 回归 | — | `mvn -pl reachai-control-service,reachai-runtime-service -am clean test` 全绿 |

**M1 DoD**（对应产品设计验收门禁 1-4、7、9）：外部客户端凭 endpoint+Key 完成 tools/list（真实 schema）与 tools/call（Capability 与 Workflow 各一条 E2E）；回滚立即生效；越权双层拦截有证据；IRREVERSIBLE 默认不可见。

### 2026-08-28—2026-08-29 W8 验证记录

当前判定：`M1_CODE_VERIFIED_DEPLOYMENT_E2E_PENDING`，不能标记为 `IMPLEMENTED_M1` 或“可用”。

- `PASS`：JDK 17 离线组合回归 `mvn -o -pl "reachai-control-service,reachai-runtime-service" -am test` 共 1597 项，0 失败、0 错误、3 项条件跳过（AI Common 61、Runtime Contract 5、Runtime 804、Control 727）。
- `PASS`：MCP 及关联治理定向用例 Control 79/79、Runtime 63/63；覆盖双版本协议、请求体上限与异常映射、状态机、不可变修订/回滚、契约解析、项目/租户 scope、ACL/不可逆风险、凭证生命周期、审计、固定 Workflow 修订、阻塞交互拒绝与错误分类。
- `PASS`：`ai-admin-front` 生产构建（2449 modules）；导航/侧边栏 happy-dom 用例 34/34；前端公共路由、internal API、物理服务路由三项契约检查；`scripts/check-mcp-hub-sql.mjs` 基线/升级一致性检查。
- `PASS`：MCP 相关路径 `git diff --check HEAD -- <MCP paths>`、未跟踪文件尾随空白、退役入口与代码占位守卫；后端命名守卫中的 MCP 双入口断言已同步通过。全仓后端命名守卫仍有 11 条 Dashboard 并发改动导致的既存断言缺失，全仓已暂存差异也仍有非 MCP 并发改动的既存空白错误，本次未越界修改。
- `BLOCKED`：W8-6 要求的 `clean test` 未满足；当前运行中的旧 Runtime JAR 锁定 `reachai-runtime-service/target`，本次未获授权中断进程。
- `PASS`：2026-08-29 经明确授权对远程开发库 `reach_ai` 执行合并前来源脚本 `upgrade-20260828-mcp-hub.sql`（现为合并升级 Section 14）。执行前确认 MySQL 5.7、库/连接字符集均为 `utf8mb4`，旧 visibility/client/call-log 三表均为 0 行；脚本每遍 16 条语句并连续执行两遍，两遍各 17 项结构回读全部通过：5 张 MCP Hub 表、Client 非空项目/环境/租户作用域、调用正文 MEDIUMTEXT、关键索引、6 项权限、旧表/旧列退役和中文无 `?` 乱码。`initV2.sql` 全新建库仍为 `NOT RUN`，且不属于已有开发库的升级入口。
- `NOT RUN`：新 Control/Runtime 包部署与健康、真实外部 HTTP 客户端对 Capability/Workflow 各一次 `tools/call`、回滚/越权/IRREVERSIBLE 业务流、真实浏览器验收。当前运行服务使用旧 JAR，不能作为本次源码验收证据。

---

## M2：入向注册与同步

### 后端（Control）

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| M2-1 | SQL | `sql/initV2.sql` + `sql/upgrade-YYYYMMDD-mcp-remote.sql` | `control_mcp_remote_server`（name、endpoint_url、transport、protocol_version、auth_type、credential_cipher（复用 A2A 的 AES-GCM 加密：`a2a/infrastructure/security/AesGcmA2aCredentialCipher` 模式新建 `mcp/infrastructure/security/AesGcmMcpCredentialCipher.java` 或抽公共）、state、health_status、last_sync_at）；`control_mcp_remote_tool`（remote_server_id、tool_name、title、description、input_schema_json、schema_digest、status、risk_level、permission_key、last_seen_at，uk(server_id, tool_name)）；`control_mcp_remote_sync_log` |
| M2-2 | 域与应用 | `mcp/domain/remoteserver/`（状态机 DISCOVERED→VERIFYING→REVIEW_REQUIRED→TRUSTED→DISABLED/QUARANTINED→ARCHIVED）+ `application/remoteserver/McpRemoteServerApplicationService.java`（注册、探测、信任、禁用、隔离）+ `McpRemoteSyncService.java`（同步 diff：新增→PENDING_REVIEW；schema_digest 变→CHANGE_REVIEW_REQUIRED；消失→REMOVED；幂等可重跑） |
| M2-3 | 出站传输 | `mcp/infrastructure/transport/SecureMcpRemoteTransport.java` | SSRF/DNS/大小/超时防护镜像 `a2a/infrastructure/transport/httpjson/SecureA2aRemoteCardFetcher.java` + `A2aOutboundTargetPolicy` 模式；JSON-RPC 2.0 客户端（initialize / tools/list / tools/call）；认证规划器（none/bearer/custom header） |
| M2-4 | 管理与协议 API | `mcp/api/management/McpRemoteServerController.java`（`/api/mcp/remote-servers/**`：注册、`POST /probe`、`POST /sync`、`POST /trust`、`POST /tools/{id}/review`（审核 schema 变化/新工具）、`POST /health-check`、禁用/隔离）+ 诊断试调用 `POST /tools/{id}/try-call`（写 call_log direction=INBOUND） |
| M2-5 | 定时健康 | `mcp/infrastructure/runtime/McpRemoteHealthWorker.java` | 定时探测；连续失败只降 health_status，endpoint/身份变化进 QUARANTINED |

### 前端

| # | 任务 | 文件 |
| --- | --- | --- |
| M2-6 | 外部 MCP 工作区 | `views/mcp-hub/remote/McpRemoteServerList.vue`（注册向导：URL+认证→探测结果→工具清单确认→信任）、`McpRemoteServerDetail.vue`（工具快照列表、status 徽标、同步 diff 审核队列、健康历史、诊断试调用）；router 与 sidebarMenu 增"外部 MCP"入口；`api/mcp.ts`/`types/mcp.ts` 扩展 |
| M2-7 | 总览扩展 | `McpHubOverview.vue` 增入向指标：已信任服务数、健康异常、待审核工具变化 |

**M2 DoD**（门禁 6 前半）：注册→探测→信任→快照落库→diff 审核（新工具 PENDING_REVIEW 不可调用）全链路有测试与页面证据。

---

## M3：入向编排接入

### Runtime（owner）

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| M3-1 | SQL | `sql/initV2.sql` + upgrade | `runtime_agent_remote_tool`（agent_config_version_id、remote_tool_key（serverKey:toolName）、description_override、risk_level、permission_key、read_only、enabled、priority，uk(config_version_id, remote_tool_key)）——契约对齐 `runtime_agent_workflow_tool` |
| M3-2 | Agent 白名单接入 | `reachai-runtime-service/.../runtime/agent/RuntimeAgentConfigService.java`（扩展）+ 新 Entity/Mapper | 配置版本草稿 API（`/api/agents/{agentId}/config-versions/draft`）accepts remote tools；`resolveActiveTools` 同构返回 remote tools；Supervisor 工具投影合并两源（Workflow-as-Tool + Remote MCP Tool，带来源标识） |
| M3-3 | Supervisor 策略链 | `SupervisorToolPolicyService` 消费侧扩展 | remote tool 的 riskLevel/permissionKey 走既有策略链：READ 自动、WRITE 一次性确认、IRREVERSIBLE 默认拒绝——不新建第二套策略 |
| M3-4 | GraphSpec 节点 | `com.enterprise.ai.agent.graph.GraphSpec`（ai-runtime-contract 或 runtime 内权威位置）新增 `MCP_TOOL` 节点类型（serverKey、toolName、schemaDigest）+ `runtime/execution/kernel/RemoteMcpNodeHandler.java`（镜像 `RuntimeActionNodeHandlers` 的 TOOL handler 结构）+ 节点目录注册 + `WorkflowReleaseValidationService` 校验快照存在且 schema_digest 一致 |
| M3-5 | Runtime→Control 客户端 | `runtime/client/control/RuntimeMcpRemoteToolClient.java` | 镜像 `RuntimeCapabilityInternalAuthSigner` + `ManagedExecutionControlEventClient` 的 HMAC 模式；调用 Control internal API（M3-6） |

### Control（协议 owner）

| # | 任务 | 文件 | 说明 |
| --- | --- | --- | --- |
| M3-6 | internal API | `mcp/api/internal/McpRemoteToolInternalController.java` | `GET /internal/control/mcp/remote-tools/catalog`（读模型：按项目/租户过滤的 ACTIVE 快照，供 Studio 节点目录与 Agent 工作台合并展示——前端不直接读 Control 表，目录数据经 Control 公共 API 委托）；`POST /internal/control/mcp/remote-tools/{key}/execute`（HMAC；执行走 M2-3 传输；超时/重试/熔断参数来自 server 配置；写 call_log direction=INBOUND + run 关联） |
| M3-7 | 证据链 | 与 M3-2/M3-5 联动 | `REMOTE_MCP_CALL` Trace Span、`runtime_tool_call_log`、run_id 回填 control_mcp_call_log（Runtime→Control 事件或调用响应回传，二选一在实现时定） |

### 前端

| # | 任务 | 文件 |
| --- | --- | --- |
| M3-8 | Studio 节点目录 | Workflow Studio 节点选择器合并展示远程 MCP 工具（数据源走 Control 委托的目录 API），带"外部 MCP"来源标识与服务名 |
| M3-9 | Agent 工作台 | Agent Supervisor 工作台可调用工具列表增加"远程 MCP 工具"分区（同 Workflow-as-Tool 的启停/排序/Schema 覆盖交互） |

**M3 DoD**（门禁 5、6、7）：注册→信任→Workflow 节点调用→Supervisor 调用各完成一次真实 E2E；schema 变化后旧绑定按旧快照执行；每条入向流水可跳 Run/Trace。

---

## 4. 文档同步清单（各里程碑内完成，不后置）

| 文档 | M1 | M2 | M3 |
| --- | --- | --- | --- |
| `docs/04-运行治理与开放协议.md` MCP 节 | 重写为 MCP Hub 模型 | 补外部 MCP | 补编排接入 |
| `docs/architecture/mcp-hub-product-design.md` | 源码回归通过后标记 `M1_CODE_VERIFIED_DEPLOYMENT_E2E_PENDING`；仅在数据库、部署与真实 E2E 门禁全过后再标记 M1 可用 | M2 | M3（含门禁逐项判定） |
| `docs/architecture/service-table-ownership.md` | W1-7 | remote 表 | runtime 表 |
| `docs/architecture/internal-api-contracts.md` | W4-4 | — | M3-6 |
| `docs/architecture/physical-split-route-ownership.md` | W7-2 | — | M3-6 |
| `docs/ai-memory/DECISIONS.md` | 登记 MCP Hub clean-slate 决策与 ACL 从严决策 | 同步 | 同步 |
| `reachai-control-service/README.md` / `reachai-runtime-service/README.md` | 入口表更新 | — | — |

## 5. 退役守卫总表（全部里程碑共用）

以下契约在任何后续变更中不得复活（以源码/路由/表结构/全文搜索为准）：

- `ControlMcpVisibilityEntity`、`ControlMcpVisibilityMapper`、`/api/mcp/visibility`、`control_mcp_visibility`
- `ControlMcpAdminController`、`ControlMcpEndpointController`（旧实现）、`/api/mcp/clients`（全局平铺语义）
- `McpVisibilityBoard.vue`、`McpClientList.vue`、`McpCallMonitor.vue`、`McpOnboarding.vue`
- `tool_whitelist_json` 列语义（Client scope 改为 publication 内 tool_scope_json）
- 空硬编码 `inputSchema`、note 充当 description 的投影逻辑

## 6. 需要拍板的决策点（阻塞项按出现顺序）

| # | 决策 | 影响 | 建议默认 |
| --- | --- | --- | --- |
| D1 | W3-4：Runtime 是否已有按 workflowId+固定版本执行的入口 | W4-4 实现路径 | 若无则新增 internal 端点内联调 `RuntimeGraphSpecExecutor` |
| D2 | W5-3：MCP 入口 Tool ACL 无命中默认拒绝（与现状 roles 空不拦截不同） | 外部调用行为变化，需在升级 SQL 说明 | 从严（按设计文档），写入升级影响 |
| D3 | M3-7：run_id 回填走事件（Runtime→Control）还是同步响应回传 | Control 是否需要 worker | 同步响应回传（简单，M3 无 outbox 必要） |

## 7. 验证矩阵

| 阶段 | 命令 / 动作 |
| --- | --- |
| 每次后端改动 | `mvn -pl reachai-control-service -am clean test`（M1/M2）；`mvn -pl reachai-runtime-service -am clean test`（W4-4/M3） |
| 每次前端改动 | `cd ai-admin-front && npx vue-tsc --noEmit && npm run build` |
| SQL | initV2 全量建库；upgrade 连续执行两遍幂等；有 MySQL 环境时回读中文无 `?` 乱码 |
| 文档/收尾 | `git diff --check`；守卫清单（第 5 节）全文搜索为空 |
| M1 门禁 | 真实 HTTP 客户端（curl/IDE MCP 配置）完成 tools/list + tools/call；回滚验证 |
| M3 门禁 | 真实双端 E2E（本平台 Workflow 节点 + Supervisor 各调一次外部 MCP） |

## 8. 风险与顺序纪律

- **先 SQL 后代码**：W1 未合入前 W2 起无法自测；upgrade 与 initV2 必须同一提交语义内完成。
- **W3-4（D1）是 M1 唯一硬依赖**：Capability 路由可先行，WORKFLOW 路由等决策落地，不得用 capability 路径顶替 Workflow-as-Tool 语义。
- **不做的**：stdio transport、OAuth/mTLS、Resources/Prompts 能力面、修订灰度（按设计文档"后续增量"）；M1 不引入 outbox（出向无长任务语义）。
- **旧数据**：visibility 与既有 Client 不迁移，升级 SQL 明写"需按新模型重建凭证"，符合仓库"不为旧数据做复杂兼容迁移"规则。
