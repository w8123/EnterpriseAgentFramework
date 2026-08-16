# 数据库初始化脚本

## 当前基线

`sql/initV2.sql` 是 ReachAI 当前新库 SQL 基线入口，覆盖当前物理服务拆分主路径运行所需的表结构、补列、补索引和必要种子数据。当前阶段仍使用一个 MySQL 库，不拆库；表名按 owning service / domain 前缀收口。旧 `sql/init.sql` 已退场，不再保留为活跃或历史基线。

当前主路径服务：

- `reachai-control-service`
- `reachai-runtime-service`
- `reachai-capability-service`
- `reachai-knowledge-service`
- `reachai-model-service`

第一阶段保持同一个 MySQL 库，不拆库。旧 `ai-agent-service` module 已删除；当前基线面向新库重建，不兼容旧表名，也不提供旧数据迁移脚本。

## 执行方式

```bash
mysql -uroot -p < sql/initV2.sql
```

全新本地库直接执行 `initV2.sql`。已有开发库需要补 Agent Memory 增量结构时，可以使用仓库 runner；它读取各服务已经使用的 `AI_MYSQL_URL`、`AI_MYSQL_USER` 和 `AI_MYSQL_PASSWORD`，不会改写 JDBC 的 SSL 配置：

```powershell
# 查看顺序和 checksum，不连接数据库
node scripts/apply-memory-migrations.mjs

# 连接并只读检查目标库、版本、字符集和基础表
node scripts/apply-memory-migrations.mjs --preflight

# 本机开发库
node scripts/apply-memory-migrations.mjs --execute --confirm=reach_ai

# 已明确授权的远程开发库
node scripts/apply-memory-migrations.mjs --execute --confirm=reach_ai --allow-remote
```

执行器只允许目标库名为 `reach_ai`，不会输出连接地址和凭据；每份脚本执行后会做结构回查，并立即执行第二遍验证幂等性。需要留存 JSON 结果时，可额外传入 `--evidence=<absolute-new-file>`。证书链、CA 和 `VERIFY_IDENTITY` 不是本地运行或开发库迁移的前置条件，由部署者按自己的网络环境选择。

脚本按 `reach_ai` 数据库执行，并保持幂等：

1. 建库：`CREATE DATABASE IF NOT EXISTS reach_ai`。
2. 建表：统一使用 `CREATE TABLE IF NOT EXISTS`。
3. 补列 / 补索引：通过 `information_schema` 判空后执行。
4. 种子数据：`model_template` 平台模板在 `initV2.sql` 使用 `INSERT IGNORE`（全新空表）；已有库升级见 `upgrade-20260717-agent-supervisor-runops-model-center-v2.sql` 的 upsert。`model_instance` 不再提供目录型实例种子。Agent/Workflow 种子只在稳定键不存在时插入。

## 覆盖范围

- Agent 聚合：`runtime_agent`、`runtime_agent_config_version`、固定 Workflow 发布版本的 `runtime_agent_workflow_tool`。
- Workflow 编排：`runtime_workflow`、`runtime_workflow_version`、`runtime_workflow_resource_binding`。
- 注册中心、项目实例、能力快照、字段级 diff、review/apply。
- 扫描项目、扫描模块、项目接口、语义文档、API 图谱。
- Tool / Capability 资产、交互式能力挂起恢复。
- RunOps 根运行事实 `runtime_run`、通用子事件 `runtime_trace_span`、Tool 调用、Tool ACL、Guard、SlotExtractor、DomainClassifier。
- MCP、A2A、Gateway、市场资产、嵌入式对话。
- 业务页面工作台：`control_project_page`、页面资源/动作/分析结果，以及版本化 AI Coding 任务、问题、事件和报告。
- AI Coding 凭据策略：`control_ai_coding_project_policy`，未配置项目使用平台默认 72 小时。
- 模型中心 V2：`model_template`（平台目录模板）与 `model_instance`（可执行实例；全新库可不含实例种子）。
- 知识库、文件、chunk、权限、知识标签、问题、命中日志。
- 业务语义索引及附件。
- Context Governance 相关表。
- Runtime 企业会话状态目录、完整会话事件账本、租户保留策略和无正文生命周期审计：`runtime_conversation_session`、`runtime_conversation_event`、`runtime_session_retention_policy`、`runtime_session_retention_audit`；AgentScope 状态正文仍由 JSON（开发）或 Redis（生产）保存。
- Runtime 上下文工程短期工件：`runtime_tool_result_artifact` 只保存作用域摘要与 AES-GCM 密文，过期或清空会话后擦除，不作为个人长期记忆。
- 个人长期记忆使用 Control canonical context 表；`control_context_memory_outbox` 将变更可靠投影到 Knowledge-owned `knowledge_personal_memory_index` 和幂等事件账本。Knowledge 可异步生成版本化 float32 embedding，并在 owner 配额边界内执行精确向量/混合排序；该投影仍不是事实源。
- 企业擦除使用 Control-owned `control_memory_erasure_request` / `control_memory_erasure_domain` 持久协调九个责任域。五个自动域完成后擦除 raw Runtime user，四个人工域必须提交外部证据；Legal Hold 和 `RETAINED_LEGAL` 保持显式，不能把部分成功报告为全部删除。

## 升级规则

任何 schema、索引、种子数据或字段语义变化，都必须先：

1. 修改 `sql/initV2.sql`，保证全新环境直接可用。
2. 如果已有开发/测试库需要升级，再新增当次 `sql/upgrade-YYYYMMDD-short-name.sql`；当前新库重建场景不要求提供旧数据迁移。
3. 更新本文件或相关文档中的执行说明。

根目录 `sql/` 保留 `initV2.sql`、本说明和当前仍需应用的 `upgrade-*.sql`。后续真实数据库变更仍需新增当次 upgrade 脚本；该脚本在确认已合入基线且开发/测试库不再需要单独执行后，可以按同样规则清理。

不再执行或新增任何历史 service-level SQL 目录。不要在各服务目录下恢复独立迁移入口；当前唯一入口仍是根目录 `sql/`。

## Upgrade: 20260815 跨域 Agent 记忆擦除编排

已有开发、测试或生产库在启用跨域擦除 worker 前执行：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260815-cross-domain-memory-erasure.sql
```

该命令只表示使用受控 client 配置执行，实际必须由目标环境批准的远程迁移 runner 和密钥注入机制提供 `MYSQL_CLIENT_CONFIG`，不得回退到本机数据库。此前须依次完成个人记忆候选/outbox、outbox 脱敏、语义投影、Runtime session retention 与 Knowledge enterprise ingress 升级；跨域脚本最后执行。脚本为 `control_context_memory_outbox` 增加可空 `correlation_id` 及索引，创建 Control-owned 请求/逐域证据表，并给 `PLATFORM_ADMIN` 补充独立 `context:memory:erasure:manage` 权限。脚本不会删除用户数据、发起擦除、释放 Legal Hold、调用服务或启用 worker；大 outbox 表在 MySQL 5.7 增列/建索引时仍需按实际表量安排 DDL 窗口。部署和内部链路验收前保持 `REACHAI_MEMORY_ERASURE_WORKER_ENABLED=false`。当前远程开发库未执行该脚本。

## Upgrade: 20260813 Runtime 企业会话记忆

已有开发、测试或生产库在部署新版 Runtime 服务前执行：

```bash
mysql -uroot -p < sql/upgrade-20260813-runtime-session-memory.sql
```

影响：新增 Runtime-owned 的会话所有权/策略目录和完整消息事件账本，不修改既有对话或 RunOps 数据。生产 profile 启动时要求 `RUNTIME_SESSION_MEMORY_STATE_STORE=redis`，并配置 `RUNTIME_SESSION_MEMORY_REDIS_URI`；开发默认使用 JSON 文件。只有 Control HMAC 认证过的用户身份可写持久会话，直连 Runtime、匿名和 Debug 调用保持 turn-local。脚本不自动执行。

## Upgrade: 20260813 Runtime 上下文工程

已有开发、测试或生产库仅在准备启用大 Tool 结果卸载时执行：

```bash
mysql -uroot -p < sql/upgrade-20260813-runtime-context-engineering.sql
```

该脚本只新增 Runtime-owned 的 `runtime_tool_result_artifact`，不修改或回填会话、Workflow、Trace 与个人长期记忆数据，也不会自动打开功能。部署后先配置至少 32 字符的 `REACHAI_RUNTIME_CONTEXT_ARTIFACT_SECRET`，再按 canary 顺序启用 `RUNTIME_CONTEXT_ENGINEERING_ENABLED=true` 与 `RUNTIME_TOOL_RESULT_OFFLOAD_ENABLED=true`。表内不保存原始用户 ID 或明文 Tool 结果；读取同时校验当前 AgentScope 用户/会话状态槽，默认 24 小时后擦除密文。上下文压缩与一次性溢出恢复不依赖该表，可先单独灰度。

## Upgrade: 20260814 Runtime 会话事件 turn 幂等修复

如果 `runtime_conversation_event` 已由更早的开发脚本创建、但缺少 `turn_id`，请在 20260813 Runtime 会话记忆升级之后执行：

```bash
mysql -uroot -p < sql/upgrade-20260814-runtime-conversation-turn-id.sql
```

该脚本保留全部既有事件，为缺失值回填不可冲突的 `legacy-<event-id>`，再把 `turn_id` 收紧为非空并补齐 `(conversation_session_id, turn_id, role)` 唯一索引。脚本可重复执行，不删除或重写消息正文；如果目标表尚不存在或同名索引结构不兼容，会明确失败而不是猜测修复。

## Upgrade: 20260815 Runtime 会话保留、Legal Hold 与擦除

已有环境在部署包含会话保留代码的新版 Runtime / Control 服务前，通过目标环境既有的远程 MySQL 连接和凭据流程执行：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260815-runtime-session-retention.sql
```

前置顺序是 `upgrade-20260813-runtime-session-memory.sql`、`upgrade-20260814-runtime-conversation-turn-id.sql`，最后才是本脚本。本脚本幂等增加 Legal Hold、生命周期租约和保留查询索引，创建 Runtime-owned 的租户策略/无正文审计表，并补齐 `runtime:session:retention:manage` 权限；脚本自身不扫描、清除或删除任何会话内容，也不会自动执行。

自动保留任务默认关闭。上线顺序必须是：备份及恢复流程确认、执行并回读迁移、部署 Runtime/Control、配置每个目标租户策略和既有 Legal Hold、以只读查询核对预计命中范围，最后才在 canary Runtime 设置 `RUNTIME_SESSION_RETENTION_ENABLED=true`。启用后，任务会按有界批次物理删除到期的会话事件、AgentState 和 Tool artifact，并删除会话目录行；这一步不可通过关闭开关恢复。`runtime_session_retention_audit` 只保留哈希标识和机器元数据，不保存会话正文。

该能力只治理 Runtime 会话域。它不会删除 Control 个人长期记忆、候选/outbox，Knowledge 投影或业务索引，RunOps/Trace/Interaction 账本、业务系统主数据以及数据库/Redis/磁盘备份；这些数据域必须分别制定保留、Legal Hold、擦除和备份到期策略。

## Upgrade: 20260813 个人长期记忆候选与搜索投影

已有开发、测试或生产库在部署新版 Control / Knowledge 服务前执行：

```bash
mysql -uroot -p < sql/upgrade-20260813-personal-memory-candidates.sql
```

该升级会为 runtime-user mapping 增加“每个 tenant/platform user 仅一个 ACTIVE owner”的唯一槽位。如果已有重复 ACTIVE 映射，先保留正确的一条、将其余记录置为 `DELETED`，再执行或重试升级；脚本不会替你猜测哪个 owner 正确。

影响：为私有 RUNTIME_USER candidate 增加去重、语义冲突、观察次数和提取版本字段；新增 Control transactional outbox、Knowledge-owned 可重建搜索投影和消费幂等账本，并幂等补齐 runtime-user mapping 变更所需的非秘密平台审计表。变更是 additive，不自动执行，不回填历史 candidate，也不自动重建已有个人记忆投影。启用 `REACHAI_PERSONAL_MEMORY_KNOWLEDGE_QUERY_ENABLED=true` 前，必须配置稳定的 `REACHAI_PERSONAL_MEMORY_INDEX_IDENTITY_SECRET`、确认 outbox 无 backlog，并完成所需的全量投影重建；否则保持默认 false，Control 使用 canonical bounded retrieval。

## Upgrade: 20260815 个人长期记忆语义投影

已有环境在部署支持在线语义召回的 Knowledge 服务前，先执行 `upgrade-20260813-personal-memory-candidates.sql`，再执行：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260815-personal-memory-semantic-projection.sql
```

该脚本为 Knowledge-owned `knowledge_personal_memory_index` 幂等增加可重建 float32 embedding、模型/源版本、异步状态、重试和 CAS 租约字段及调度索引。首次补列时既有 ACTIVE 行使用 `DISABLED` 默认值；切换到语义模式后 worker 会把这些行纳入有界重建。非 ACTIVE tombstone 会清空全部向量与任务字段并标记 `DELETED`。重复执行不会把已经 `READY` 的向量重置，也不会调用模型、启动重建或修改 Control canonical 记忆。

## Upgrade: 20260815 个人记忆 outbox 错误脱敏

已有环境部署新版 Control 前，在 `upgrade-20260813-personal-memory-candidates.sql` 之后执行：

```bash
mysql --defaults-extra-file="$MYSQL_CLIENT_CONFIG" < sql/upgrade-20260815-personal-memory-outbox-redaction.sql
```

脚本将历史 personal-memory outbox 的异常文本擦除：DEAD 行仅保留 `PUBLISH_LEGACY_REDACTED`，其他状态清空旧错误；已 PUBLISHED 的正文 payload 替换为只含 sourceVersion/eventType 的投递 receipt。新版 Control 在成功投递的同一次条件更新中执行相同 payload 擦除，并且只写最长 64 字符机器码；canonical 删除/到期时，历史 payload 会被原子替换为无身份擦除收据，旧 PENDING/PUBLISHING/DEAD 转成不可重试的 `SUPERSEDED`，再写入更高版本 DELETE tombstone。数据库保留兼容列宽，避免 MySQL 5.7 缩列导致表重建、长锁和滚动发布竞态。脚本不修改 schema、投递状态、重试次数、canonical 记忆或 Knowledge 投影，可重复执行。

部署后默认仍为 `LEXICAL`。只有确认 Model Center embedding 实例、Model Gateway 连通性、静态加密和告警后，才在 Knowledge canary 配置 `REACHAI_PERSONAL_MEMORY_KNOWLEDGE_SEARCH_MODE=HYBRID` 与 `REACHAI_PERSONAL_MEMORY_EMBEDDING_MODEL_INSTANCE_ID`；Control 继续保持 `REACHAI_PERSONAL_MEMORY_KNOWLEDGE_QUERY_MODE=SHADOW`，直到真实脱敏样本准入通过。脚本不会自动执行，当前也未在远程开发库执行。

## Upgrade: 20260814 业务记忆引用契约

已有开发、测试或生产库在允许业务索引参与 Agent 记忆召回前执行：

```bash
mysql -uroot -p < sql/upgrade-20260814-business-memory-reference.sql
```

该升级为 `knowledge_business_index` 增加默认关闭的 `agent_memory_enabled` 和回源 `resolver_capability_key`，并为业务索引记录增加 `source_version` / `source_updated_at`。现有索引不会自动获得 Agent 记忆资格，也不会被当成权威业务事实。只有配置 tenant/project、注册 resolver Capability，并在每次 upsert 提供 source version 后，搜索结果才会生成 `reachai-business-memory-reference-v1`；Runtime 仍须在当前可信业务身份下调用 resolver 重新鉴权和回读。脚本 additive、幂等且不会自动执行。

## Upgrade: 20260813 Capability 评审真实回滚

已有开发、测试或生产库在部署新版 Capability 服务前执行：

```bash
mysql -uroot -p < sql/upgrade-20260813-capability-review-rollback.sql
```

影响：为 `capability_diff_item` 新增 `before_state_json`，此后评审 APPLY 会保存 SDK 扫描目录和全局可执行 Tool 的应用前状态；DELETED 应用会同步停用两类资产，回滚会恢复真实目录/执行状态。旧评审项没有应用前状态，不能直接回滚，需重新生成差异并应用。脚本不删除数据；本次代码改造不自动执行该脚本。

## Upgrade: 20260811 平台管理端会话 Token 加固

已有开发、测试或生产库在部署新 Control 服务前执行：

```bash
mysql -uroot -p < sql/upgrade-20260811-platform-session-token-hardening.sql
```

影响：旧实现曾把原始平台 access token 写入会话表；新实现只保存 SHA-256 摘要。升级脚本会撤销全部平台登录会话、将 `access_token_id` 用含随机 `session_id` 的 SHA-256 值不可逆覆盖，并清空 `refresh_token_id`，所有管理端用户需重新登录。该脚本不修改用户、角色或权限数据。升级后不得为了恢复旧会话而回写或恢复原始 Token。

## Upgrade: 20260811 平台管理端认证治理

在完成 Token 加固后，对已有开发、测试或生产库执行：

```bash
mysql -uroot -p < sql/upgrade-20260811-platform-auth-governance.sql
```

影响：

1. 新建 `control_platform_auth_audit_event`，记录认证提供方与用户角色等高危管理操作的非敏感审计元数据。
2. 补齐 `platform:admin` 及 `PLATFORM_ADMIN` 的权限绑定。
3. 强制把 HEADER、OIDC、SAML 置为 `INACTIVE`，并清空所有旧 `config_json`；当前版本没有 Provider 密文存储或这些 Provider 的登录适配器。
4. LOCAL 是否真正可登录仍由 `REACHAI_AUTH_PROVIDER=LOCAL`、`REACHAI_LOCAL_AUTH_ENABLED=true` 和数据库 LOCAL provider 的 `ACTIVE` 状态共同决定。升级不会创建、覆盖或重置管理员。

这两份 20260811 脚本必须在维护窗口、数据库备份完成且得到单独执行授权后运行；本次代码改造不自动执行它们。

## 破坏性变更

项目默认不为旧数据做复杂兼容迁移。当前基线按新库重建处理；如果后续升级脚本会清理、重建、重命名或丢弃历史字段/数据，必须在 SQL 注释和最终变更说明中写清影响。

## 建议验证

执行后至少抽样检查：

```sql
SHOW TABLES LIKE 'runtime_agent';
SHOW TABLES LIKE 'runtime_workflow';
DESC runtime_agent;
DESC runtime_workflow;
DESC runtime_run;
DESC runtime_trace_span;
DESC runtime_agent_workflow_tool;
DESC runtime_workflow_resource_binding;
DESC control_project_page;
DESC control_ai_coding_task;
DESC control_ai_coding_task_target;
DESC control_ai_coding_task_handoff;
DESC control_ai_coding_project_policy;
DESC capability_scan_project_tool;
SHOW INDEX FROM control_mcp_client;
```

文档/规则改动时可运行：

```powershell
node scripts/check-backend-boundary-naming.mjs
node scripts/check-model-center-v2-sql.mjs
git diff --check
```

## Upgrade: 20260717 Agent Supervisor / RunOps / Model Center V2（破坏性）

已有开发或测试库只执行下面这一份整合脚本。它替代 20260714 Agent/RunOps 与 20260716 Model Center V2 两份独立脚本，并按依赖顺序先升级 Agent/RunOps，再升级 Model Center V2。

### 前置检查

1. 确认目标库是可丢弃的开发/测试库，不是共享生产库。
2. 备份整个 `reach_ai`：`mysqldump -uroot -p reach_ai > reach_ai_backup_YYYYMMDD.sql`。
3. 确认 `model_instance` 处于哪一类状态：
   - 旧 V1：仍有 `endpoint_type` / `credential_json` / `workspace_id`
   - 最终 V2：`connection_config_json` 存在、无 `endpoint_type`，核心 7 列齐全，非核心必需 12 列（含 `remark`）齐全，`project_scope_key` 为 STORED generated `IFNULL(project_code,'')`，唯一索引完整（共 19 列）
   - 上一版不完整 V2：已有 `connection_config_json`、无 `endpoint_type`，但生成列/索引/字段不完整（常见：普通 `project_scope_key` + 仍 valid 的唯一索引）
4. 若存在名称在 `name + IFNULL(project_code,'')` 下冲突，先手工消重再执行。

### 执行

```bash
mysql -uroot -p < sql/upgrade-20260717-agent-supervisor-runops-model-center-v2.sql
```

### Agent Supervisor / RunOps 行为

1. 增加版本化 Agent Supervisor 配置和 Workflow-as-Tool 表，并删除旧的 Agent 内联配置列，不迁移旧数据。
2. 永久删除退役的静态 `runtime_agent_workflow_binding` 表及其数据。
3. 统一 Workflow Tool 风险语义、交互 Agent 身份、Supervisor 审批语义和 Agent Eval 表说明。
4. 将 Workflow Tool 固定到 ACTIVE Workflow 版本，删除没有可执行发布版本的记录，并重建 `runtime_run` 与通用 `runtime_trace_span`。
5. 删除退役的 `capability_project_instance.governance_policy_json`，保留 `capability_project_instance` 用于 SDK 心跳和 onboarding。

### Model Center V2 行为

- 对固定 31 个平台模板 id（`tpl-*`）执行 `INSERT ... ON DUPLICATE KEY UPDATE`，刷新目录字段；**保留已有 `enabled` 与 `created_at`**。用户自建非 `tpl-*` 模板不受影响。VALUES 与 `initV2.sql` 一致。
- 旧 V1：冲突预检后原子 `RENAME` 迁移；过程成功后自动删除临时旧表，不保留表级备份。
- 最终 V2 判定：核心列 = 7 **且** 非核心必需列 ≥ 12（含 `remark`）**且** 正确生成列 **且** 正确唯一索引。核心列缺失不会被误判为 final。
- 不完整 V2：核心列不全则在任何 DDL 前 `SIGNAL`（不猜测补齐）。冲突预检后按需 `ADD COLUMN`：`protocol`、`project_code`、`default_options_json`、`params_schema_json`、`status`、`last_test_*`、`remark`（`VARCHAR(512) DEFAULT NULL`）。生成列/索引修复顺序：若 `project_scope_key` 不是目标生成列 **或** 唯一索引不正确 → 先 `DROP INDEX uk_model_instance_name_scope`（即便当前索引形状 valid）→ 再 `DROP COLUMN` / `ADD` 正确生成列 → 最后按需重建唯一索引。禁止在同名索引仍引用该列时删列。
- 未知结构（有表但无 `connection_config_json` 且非 V1）：`SIGNAL`。
- 已是最终 V2：跳过实例迁移/修复，只做模板 upsert。
- 生成列检测使用 `EXTRA` + 规范化 `GENERATION_EXPRESSION`（不用 `IS_GENERATED`）。
- 脚本成功结束时自动删除旧脚本可能遗留的 `model_instance_v1_bak_20260716`。
- 静态防回归：`node scripts/check-model-center-v2-sql.mjs`。

### 验证

```sql
SHOW CREATE TABLE model_template;
SHOW CREATE TABLE model_instance;
SELECT COUNT(*) FROM model_template;
SELECT id, name, status, LEFT(connection_config_json, 16) FROM model_instance LIMIT 20;
```

脚本不保留表级回滚副本。如需回滚，恢复执行前创建的整库备份。

RunOps replay 使用来源运行记录的历史 Agent 配置和固定 Workflow 版本，不会静默解析当前 ACTIVE 版本。

该升级整体是破坏性的：不保留旧 Agent 内联配置、静态 Agent-Workflow 绑定、历史开发 Run/Span 数据、没有 ACTIVE Workflow 版本的 Tool 记录，也不保留旧模型表副本。执行前必须备份整个 `reach_ai`。MySQL DDL 会自动提交，脚本不提供全有或全无回滚。

如果已经执行过被替代的 20260713/20260714/20260716 脚本，不要仅为清理旧备份表而重复执行本脚本；除非明确接受在可丢弃开发/测试库上再次进行破坏性重建。

全新环境直接使用当前 `sql/initV2.sql`。

## Upgrade: 20260725 业务页面工作台（破坏性）

已有开发或测试库按顺序执行：

```bash
mysql -uroot -p < sql/upgrade-20260725-business-page-workbench.sql
mysql -uroot -p < sql/upgrade-20260725-ai-coding-task-protocol-v1.sql
```

影响：

1. 永久删除 `control_page_registry`、`control_page_action_registry` 及旧页面助手数据，不做迁移。
2. 建立唯一页面定义 `control_project_page`；运行中的 `pageInstanceId` 只由 `control_embed_session` 持有。
3. 新增页面资源、动作和分析结果；页面领域不再自建 AI Coding 任务协议。
4. 新增 Runtime 所有的 `runtime_workflow_resource_binding`，关键页面绑定不再依赖 `runtime_workflow.extra_json`。
5. 第二份脚本永久删除旧 SDK 接入 session/step 和旧页面专用 AI Coding 任务历史，不做迁移。
6. 第二份脚本建立 ReachAI 通用 Task、Target、Handoff、Event、Question、Artifact 表。

该脚本仅适用于可丢弃的开发/测试库。MySQL DDL 自动提交，执行前必须备份整个 `reach_ai`；脚本不提供旧页面数据兼容或回滚迁移。

验证：

```sql
SHOW TABLES LIKE 'control_project_page';
SHOW TABLES LIKE 'control_ai_coding_task';
SHOW TABLES LIKE 'control_ai_coding_task_target';
SHOW TABLES LIKE 'control_ai_coding_task_handoff';
SHOW TABLES LIKE 'runtime_workflow_resource_binding';
SHOW TABLES LIKE 'control_page_registry';
SHOW TABLES LIKE 'control_page_action_registry';
SHOW TABLES LIKE 'control_ai_access_session';
SHOW TABLES LIKE 'control_ai_access_step';
```

## Upgrade: 20260726 AI Coding 任务凭据策略

已有开发或测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260726-ai-coding-credential-policy.sql
```

影响：

1. 新增 Control-owned 的 `control_ai_coding_project_policy`，不修改或删除现有任务数据。
2. 未配置项目使用平台默认值：交接包激活有效期 72 小时、任务 Token 有效期 72 小时。
3. 项目级自定义值只影响新签发或新激活的交接包；已经签发、激活或丢失 Token 的任务不会被追溯修改。

验证：

```sql
DESC control_ai_coding_project_policy;
SELECT * FROM control_ai_coding_project_policy LIMIT 20;
```

## Upgrade: 20260719 Runtime internal auth nonce

已有开发/测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260719-runtime-internal-auth-nonce.sql
```

影响：

1. 新增 `runtime_internal_auth_nonce`：Control→Runtime HMAC 内部认证 nonce 防重放表（多实例共享）。
2. Control/Runtime 需配置同一 `REACHAI_INTERNAL_SERVICE_SECRET`；缺配置时内部 trusted execute fail-closed。

Windows 本地开发可生成并配置用户级共享 secret（脚本不会打印或写入仓库）：

```powershell
.\scripts\set-reachai-internal-service-secret.ps1
```

配置后必须重启 IntelliJ IDEA 以及 Control/Runtime 两个运行配置；已经启动的 JVM 不会读取到新环境变量。

验证：

```sql
DESC runtime_internal_auth_nonce;
SHOW INDEX FROM runtime_internal_auth_nonce;
```

## Upgrade: 20260722 Tool 用户可读名称

已有开发或测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260722-add-tool-title.sql
```

影响：

1. 为 `capability_scan_project_tool` 和 `capability_tool_definition` 增加必填 `title`，用于面向用户展示简短中文名称。
2. `name` 和 `qualified_name` 继续作为稳定机器标识，不参与本次重命名。
3. 已有数据先用 `name` 回填 `title`，后续 SDK 注册和界面编辑可写入真实业务名称；不删除任何数据。

验证：

```sql
SHOW FULL COLUMNS FROM capability_scan_project_tool LIKE 'title';
SHOW FULL COLUMNS FROM capability_tool_definition LIKE 'title';
SELECT name, title FROM capability_scan_project_tool LIMIT 20;
SELECT name, title FROM capability_tool_definition LIMIT 20;
```

## Upgrade: 20260722 能力开关与作用域字段清理（破坏性）

已有开发或测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260722-remove-redundant-capability-flags.sql
```

影响：

1. 永久删除能力目录、领域和能力内核资产表中的 `agent_visible`；`enabled=1` 即表示可被 Agent 发现与调用。
2. 永久删除扫描接口与全局 Tool 上没有运行时消费链路的 `lightweight_enabled`。
3. 永久删除 `capability_tool_definition.visibility`；项目作用域继续由 `capability_scan_project.visibility` 统一承载。
4. 同步删除 `scan_settings.defaultFlags` 和能力元数据 JSON 中遗留的对应开关，不保留隐藏配置。
5. 不做字段回填、别名兼容或数据保留，并按剩余字段重建相关索引。执行前如需保留旧值，必须先备份目标库。

验证：

```sql
DESC capability_scan_project_tool;
DESC capability_tool_definition;
DESC capability_domain_def;
DESC capability_tool_asset;
DESC capability_composition_definition;
DESC capability_interaction_definition;
SHOW INDEX FROM capability_tool_definition;
```

## 20260722 Workflow 语义硬切换

本次 Workflow Studio 升级不转换旧 Workflow 数据，也不读取旧字段、旧枚举值、旧 GraphSpec 或完整 Canvas 快照。已有开发或测试库不需要重跑 `initV2.sql`，执行以下破坏性升级脚本：

```bash
mysql -uroot -p < sql/upgrade-20260722-workflow-semantics.sql
```

脚本将当前契约直接应用到已有库：

1. Workflow 分类使用 `workflow_kind / execution_engine / definition_authority / creation_channel`。
2. Workflow 执行引擎只使用无产品代际后缀的 `GRAPH_SPEC`。
3. GraphSpec 只接受 `schemaVersion=2`、`entryNodeId`、`exitNodeIds`，且不包含 `START / END` 语义节点或边。
4. `canvas_json` 只保存 `schemaVersion=1 / layoutVersion=1` 的布局文档，不保存节点配置或拓扑语义。
5. Run、Interaction 和 Debug 分别使用 `suspension_reason / resume_checkpoint_json / working_copy_definition_json / state_snapshot_json`。

破坏性影响：

1. 删除全部 Workflow 工作副本、发布版本和 Agent Workflow-as-Tool 绑定；执行后需要按当前契约重新创建、发布并绑定 Workflow。
2. 删除全部 RunOps 根运行记录，并清空 Trace、Tool 调用、Guard 决策和运行时 Skill Interaction 历史。
3. 删除全部 Interaction 与 Debug 会话。
4. 清空评测执行记录与结果，但保留评测数据集和用例。
5. 保留 Agent、Agent 配置版本、Workflow Credential 以及 Capability、Knowledge、Model、Control 等非 Runtime 业务数据。
6. 不做旧列重命名、旧枚举映射、JSON 转换或兼容副本。脚本可重复执行，但每次都会再次清空上述数据；执行前必须备份目标库。

验证：

```sql
DESC runtime_workflow;
SHOW INDEX FROM runtime_workflow;
DESC runtime_run;
DESC runtime_interaction_session;
DESC runtime_executable_debug_session;

SELECT COUNT(*) FROM runtime_workflow;
SELECT COUNT(*) FROM runtime_workflow_version;
SELECT COUNT(*) FROM runtime_agent_workflow_tool;
SELECT COUNT(*) FROM runtime_run;
```

以上四项计数在刚执行完脚本时都应为 `0`。全新环境仍直接执行 `sql/initV2.sql`。

## Upgrade: 20260718 Workflow INTERACTION resume

已有开发/测试库执行：

```bash
mysql -uroot -p < sql/upgrade-20260718-workflow-interaction-resume.sql
```

影响：

1. 当时 `runtime_run` / `runtime_trace_span` 增加了 `WAITING_USER`；当前基线将 `runtime_run` 表达为 `status=SUSPENDED` 与 `suspension_reason=USER_INPUT / APPROVAL`，Trace/Interaction 自身状态仍按各自生命周期表达。
2. `runtime_interaction_session` 升级为 GraphSpec-native Workflow 交互会话：增加 `source_type`、`workflow_id`、`workflow_version_id`、`graph_spec_snapshot_json`、`revision`、`idempotency_key`、所有权字段与 continuation。
3. `composition_qualified_name` 改为可空；历史 composition 行保留，`source_type` 回填为 `COMPOSITION`。新 Workflow 暂停必须持久化 snapshot，恢复不得读取最新 Workflow。

验证：

```sql
DESC runtime_interaction_session;
SHOW INDEX FROM runtime_interaction_session;
SHOW FULL COLUMNS FROM runtime_run LIKE 'status';
```

## Upgrade: 20260709 AI Coding access default

Run `sql/upgrade-20260709-ai-coding-access-default.sql` on existing development or test databases that already have `capability_scan_project` rows. It adds missing AI Coding access columns and backfills generated `aic_...` keys with `ai_coding_access_enabled = 1` only for rows that previously had no key.

## Upgrade: 20260811 SDK zero-touch Enrollment

Apply this after the 20260811 platform session and auth-governance upgrades:

```bash
mysql -uroot -p < sql/upgrade-20260811-registry-enrollment-token.sql
```

It creates the Capability-owned 72-hour one-time Enrollment Token digest table
and Control-to-Capability HMAC replay store. Existing SDK credentials are not
rotated; a new Starter receives its generated credential only in the first
successful Enrollment response and persists it outside application YAML.

## Upgrade: 20260812 业务页面绝对地址

已有开发或测试库在部署新版 Control 服务前执行：

```bash
mysql -uroot -p < sql/upgrade-20260812-business-page-url.sql
```

该脚本给 `control_project_page` 增加可空的 `business_page_url`。它表示浏览器可直接打开的绝对 HTTP(S) 页面地址；项目 `base_url` 继续表示业务后端或网关 API 地址，两者不得互相兜底。已有页面不会自动猜测或回填，后续 SDK 页面登记会从真实 `origin + route` 同步，也可在页面工作台中手工维护。

## Upgrade: 20260815 Knowledge 企业入口鉴权

已有开发或测试库在启用业务索引项目凭证同步入口前执行：

```bash
mysql -uroot -p < sql/upgrade-20260815-knowledge-enterprise-ingress.sql
```

该脚本新增 Capability-owned `capability_registry_request_nonce`，用于
`REACHAI_PROJECT_REQUEST_V1` 的跨实例防重放；同时幂等补齐此前
Enrollment / Control-to-Capability HMAC 所需的两张 nonce/token 表。它不改
业务索引数据、不轮换项目凭证，也不执行远程数据库写入。

验证：

```sql
SHOW CREATE TABLE capability_registry_enrollment_token;
SHOW CREATE TABLE capability_internal_auth_nonce;
SHOW CREATE TABLE capability_registry_request_nonce;
```
